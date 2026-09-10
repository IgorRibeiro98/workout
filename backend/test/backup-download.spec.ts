import { createHash } from 'node:crypto';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixtureText, withClientBackupId } from './support/backup-fixtures';

const TOKEN_A = 'token-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/**
 * A leitura do backup para o restore (T16.5): lista, metadata e conteúdo.
 *
 * ```text
 * GET /v1/backups                     lista da conta autenticada, ordem do servidor
 * GET /v1/backups/{id}                metadata de um
 * GET /v1/backups/{id}/content        o snapshot canônico, verbatim
 * ```
 *
 * Três coisas são provadas aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **ownership sai do token.** Não existe `?uid=`, e o backup de A é invisível para B;
 * 2. **o snapshot é imutável.** Baixar não altera nada — nem metadata, nem itens, nem retenção;
 * 3. **metadata e conteúdo concordam.** O SHA-256 do corpo devolvido é `payloadHash`, que é o que
 *    permite ao Android recusar um download corrompido antes de tocar no banco dele.
 *
 * Tudo offline: verificador de token dublê, SQLite temporário, sem Firebase, sem VPS, sem rede.
 */
describe('Download de backup (/v1/backups)', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
      uid: UID_B,
    });
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  const post = (body: unknown, token = TOKEN_A) =>
    request(app.getHttpServer())
      .post('/v1/backups')
      .set('Authorization', `Bearer ${token}`)
      .set('Content-Type', 'application/json')
      .send(typeof body === 'string' ? body : JSON.stringify(body));

  const list = (token = TOKEN_A) =>
    request(app.getHttpServer()).get('/v1/backups').set('Authorization', `Bearer ${token}`);

  const metadata = (backupId: string, token = TOKEN_A) =>
    request(app.getHttpServer())
      .get(`/v1/backups/${backupId}`)
      .set('Authorization', `Bearer ${token}`);

  const content = (backupId: string, token = TOKEN_A) =>
    request(app.getHttpServer())
      .get(`/v1/backups/${backupId}/content`)
      .set('Authorization', `Bearer ${token}`);

  const sha256 = (text: string): string => createHash('sha256').update(text, 'utf8').digest('hex');

  // ---------------------------------------------------------------------- autenticação

  it('sem token não lista backups', async () => {
    const response = await request(app.getHttpServer()).get('/v1/backups');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('UNAUTHENTICATED');
  });

  it('sem token não baixa conteúdo', async () => {
    const created = await post(fixtureText('backup-v1-complete'));
    const backupId = created.body.backupId as string;

    const response = await request(app.getHttpServer()).get(`/v1/backups/${backupId}/content`);

    expect(response.status).toBe(401);
  });

  // ---------------------------------------------------------------------- lista

  it('a conta sem backup recebe lista vazia, não erro', async () => {
    const response = await list();

    expect(response.status).toBe(200);
    expect(response.body.items).toEqual([]);
  });

  it('a lista traz só os backups da conta autenticada', async () => {
    await post(withClientBackupId('backup-v1-complete', 'tentativa-de-a'), TOKEN_A);
    await post(withClientBackupId('backup-v1-minimal', 'tentativa-de-b'), TOKEN_B);

    const ofA = await list(TOKEN_A);
    const ofB = await list(TOKEN_B);

    expect(ofA.body.items).toHaveLength(1);
    expect(ofA.body.items[0].clientBackupId).toBe('tentativa-de-a');
    expect(ofB.body.items).toHaveLength(1);
    expect(ofB.body.items[0].clientBackupId).toBe('tentativa-de-b');
  });

  it('a ordem vem do servidor: mais recente primeiro', async () => {
    await post(withClientBackupId('backup-v1-minimal', 'primeira'));
    await post(withClientBackupId('backup-v1-minimal', 'segunda'));
    await post(withClientBackupId('backup-v1-minimal', 'terceira'));

    const response = await list();

    expect(
      response.body.items.map((item: { clientBackupId: string }) => item.clientBackupId),
    ).toEqual(['terceira', 'segunda', 'primeira']);
  });

  it('a lista respeita a retenção: só o que ainda existe', async () => {
    // A retenção padrão é 5; a sexta tentativa empurra a primeira para fora.
    for (let index = 1; index <= 6; index += 1) {
      await post(withClientBackupId('backup-v1-minimal', `tentativa-${index}`));
    }

    const response = await list();

    expect(response.body.items).toHaveLength(5);
    expect(
      response.body.items.map((item: { clientBackupId: string }) => item.clientBackupId),
    ).not.toContain('tentativa-1');
  });

  it('a lista é metadata: nenhum payload atravessa', async () => {
    await post(fixtureText('backup-v1-complete'));

    const response = await list();

    const serialized = JSON.stringify(response.body);
    expect(response.body.items[0]).toEqual({
      backupId: expect.any(String),
      clientBackupId: expect.any(String),
      backupSchemaVersion: 1,
      createdAt: expect.any(Number),
      itemCount: expect.any(Number),
      sizeBytes: expect.any(Number),
      payloadHash: expect.any(String),
    });
    // Nada do conteúdo do snapshot pode aparecer numa listagem.
    expect(serialized).not.toContain('Supino reto');
    expect(serialized).not.toContain('Academia do bairro');
    expect(serialized).not.toContain('entityType');
    // `payloadHash` é metadata e pode aparecer; o campo `payload` (o snapshot) não.
    expect(serialized).not.toContain('"payload"');
  });

  // ---------------------------------------------------------------------- metadata de um

  it('a metadata de um backup é a mesma da criação', async () => {
    const created = await post(fixtureText('backup-v1-complete'));

    const response = await metadata(created.body.backupId as string);

    expect(response.status).toBe(200);
    expect(response.body).toEqual(created.body);
  });

  it('backup inexistente responde 404', async () => {
    const response = await metadata('00000000-0000-4000-8000-000000000000');

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('BACKUP_NOT_FOUND');
  });

  // ---------------------------------------------------------------------- conteúdo

  it('o dono baixa o snapshot e o hash fecha com a metadata', async () => {
    const created = await post(fixtureText('backup-v1-complete'));

    const response = await content(created.body.backupId as string);

    expect(response.status).toBe(200);
    expect(sha256(response.text)).toBe(created.body.payloadHash);
    expect(Buffer.byteLength(response.text, 'utf8')).toBe(created.body.sizeBytes);
  });

  it('o conteúdo devolvido é o snapshot, e ele descreve a mesma tentativa', async () => {
    const created = await post(fixtureText('backup-v1-complete'));

    const response = await content(created.body.backupId as string);
    const snapshot = JSON.parse(response.text) as {
      clientBackupId: string;
      backupSchemaVersion: number;
      items: unknown[];
    };

    expect(snapshot.clientBackupId).toBe(created.body.clientBackupId);
    expect(snapshot.backupSchemaVersion).toBe(created.body.backupSchemaVersion);
    expect(snapshot.items).toHaveLength(created.body.itemCount);
    // O dono não viaja no corpo: ele é do token, e nunca virou campo do contrato.
    expect(response.text).not.toContain('ownerUid');
  });

  it('o corpo devolvido é byte a byte o texto canônico, não uma reserialização', async () => {
    const created = await post(fixtureText('backup-v1-complete'));

    const first = await content(created.body.backupId as string);
    const second = await content(created.body.backupId as string);

    expect(second.text).toBe(first.text);
    // Forma canônica: sem espaço insignificante e com as chaves do envelope ordenadas.
    expect(first.text.startsWith('{"backupSchemaVersion":1,"capturedAt":')).toBe(true);
  });

  // ---------------------------------------------------------------------- ownership

  it('a conta B não baixa o backup da conta A', async () => {
    const created = await post(fixtureText('backup-v1-complete'), TOKEN_A);
    const backupId = created.body.backupId as string;

    const response = await content(backupId, TOKEN_B);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('BACKUP_NOT_FOUND');
    // A resposta para "existe e não é seu" é idêntica à de "não existe": nada aqui confirma que o
    // recurso de outra conta existe.
    const inexistent = await content('00000000-0000-4000-8000-000000000000', TOKEN_B);
    expect(inexistent.status).toBe(response.status);
    // `requestId` é o único campo que difere entre duas respostas — de propósito, ele identifica a
    // requisição. Código e mensagem precisam ser indistinguíveis.
    expect(inexistent.body.error.code).toBe(response.body.error.code);
    expect(inexistent.body.error.message).toBe(response.body.error.message);
  });

  it('a conta B não lê a metadata do backup da conta A', async () => {
    const created = await post(fixtureText('backup-v1-complete'), TOKEN_A);

    const response = await metadata(created.body.backupId as string, TOKEN_B);

    expect(response.status).toBe(404);
  });

  // ---------------------------------------------------------------------- imutabilidade

  it('baixar não altera o snapshot, a metadata nem a retenção', async () => {
    const created = await post(fixtureText('backup-v1-complete'));
    const backupId = created.body.backupId as string;

    for (let index = 0; index < 3; index += 1) {
      expect((await content(backupId)).status).toBe(200);
    }

    const after = await metadata(backupId);
    expect(after.body).toEqual(created.body);

    const listed = await list();
    expect(listed.body.items).toHaveLength(1);
    expect(listed.body.items[0]).toEqual(created.body);
  });

  it('restaurar não gasta o backup: ele continua o mais recente depois do download', async () => {
    const older = await post(withClientBackupId('backup-v1-minimal', 'antiga'));
    await post(withClientBackupId('backup-v1-complete', 'nova'));

    await content(older.body.backupId as string);

    const latest = await request(app.getHttpServer())
      .get('/v1/backups/latest')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    expect(latest.body.clientBackupId).toBe('nova');
    expect((await list()).body.items).toHaveLength(2);
  });

  it('o download não escreve dado de domínio: nenhuma tabela nova, nenhuma linha nova', async () => {
    const created = await post(fixtureText('backup-v1-complete'));
    const backupId = created.body.backupId as string;

    const before = snapshotOfDatabase(temp.path);
    await content(backupId);
    await list();
    await metadata(backupId);
    const after = snapshotOfDatabase(temp.path);

    expect(after).toEqual(before);
  });

  // ---------------------------------------------------------------------- backup da T16.4

  it('snapshot antigo, sem documento guardado, recusa o download com erro próprio', async () => {
    const created = await post(fixtureText('backup-v1-complete'));
    const backupId = created.body.backupId as string;

    // Simula exatamente o que existe numa VPS que rodou a T16.4: metadata sim, documento não —
    // nem no banco (T16.5), nem no Object Storage (T18.1).
    withOpenDatabase(temp.path, (db) => {
      db.prepare(
        'UPDATE backup_snapshots SET payload = NULL, storage_key = NULL WHERE backup_id = ?',
      ).run(backupId);
    });

    const response = await content(backupId);

    expect(response.status).toBe(410);
    expect(response.body.error.code).toBe('BACKUP_CONTENT_UNAVAILABLE');
    // Ele continua existindo como backup — só não é restaurável.
    expect((await metadata(backupId)).status).toBe(200);
  });
});

/** Uma leitura direta do arquivo SQLite, para provar que uma requisição não escreveu nada. */
function snapshotOfDatabase(path: string): unknown {
  return withOpenDatabase(path, (db) => {
    const tables = db
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
      .all() as { name: string }[];
    return tables.map((table) => ({
      table: table.name,
      rows: db.prepare(`SELECT * FROM "${table.name}"`).all(),
    }));
  });
}

function withOpenDatabase<T>(path: string, block: (db: BetterSqlite3Database) => T): T {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const Database = require('better-sqlite3') as new (file: string) => BetterSqlite3Database;
  const db = new Database(path);
  try {
    return block(db);
  } finally {
    db.close();
  }
}

interface BetterSqlite3Database {
  prepare(sql: string): {
    all(...params: unknown[]): unknown[];
    run(...params: unknown[]): unknown;
  };
  close(): void;
}
