import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixture, fixtureText, withClientBackupId } from './support/backup-fixtures';

const TOKEN_A = 'token-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_A = 'uid-da-conta-a';
const UID_B = 'uid-da-conta-b';

/**
 * `POST /v1/backups` e `GET /v1/backups/latest` (T16.4).
 *
 * Tudo offline: verificador de token dublê, SQLite em arquivo temporário, sem Firebase, sem VPS e
 * sem rede. A aplicação é a real — mesmo bootstrap, mesmo versionamento, mesmo filtro de erro.
 */
describe('Backup (/v1/backups)', () => {
  let temp: TempDb;
  let app: INestApplication;
  let verifier: FakeAuthTokenVerifier;

  beforeEach(async () => {
    temp = createTempDb();
    verifier = FakeAuthTokenVerifier.withPrincipal(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, {
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

  const latest = (token = TOKEN_A) =>
    request(app.getHttpServer()).get('/v1/backups/latest').set('Authorization', `Bearer ${token}`);

  // ---------------------------------------------------------------------- autenticação

  it('sem token não cria backup', async () => {
    const response = await request(app.getHttpServer())
      .post('/v1/backups')
      .set('Content-Type', 'application/json')
      .send(fixtureText('backup-v1-complete'));

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('UNAUTHENTICATED');
  });

  it('sem token não lê metadata', async () => {
    expect((await request(app.getHttpServer()).get('/v1/backups/latest')).status).toBe(401);
  });

  it('com token válido o backup é aceito', async () => {
    const response = await post(fixture('backup-v1-complete'));

    expect(response.status).toBe(201);
    expect(response.body.backupId).toEqual(expect.any(String));
    expect(response.body.clientBackupId).toBe(fixture('backup-v1-complete').clientBackupId);
    expect(response.body.itemCount).toBe(9);
    expect(response.body.payloadHash).toMatch(/^[0-9a-f]{64}$/);
    // Metadata, nunca o snapshot: o corpo da resposta não devolve item nenhum.
    expect(response.body.items).toBeUndefined();
  });

  it('o snapshot mínimo — sem item nenhum — é válido', async () => {
    const response = await post(fixture('backup-v1-minimal'));

    expect(response.status).toBe(201);
    expect(response.body.itemCount).toBe(0);
  });

  // ------------------------------------------------------------------------- ownership

  it('ownerUid no corpo não substitui o principal', async () => {
    // O contrato não tem campo de dono. Mandar um é campo desconhecido: recusado, e não
    // "aceito e ignorado" — o que já seria um caminho a mais para errar.
    const response = await post({ ...fixture('backup-v1-minimal'), ownerUid: UID_B });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_BACKUP');
  });

  it('query string com uid não troca a dona do backup', async () => {
    await post(fixture('backup-v1-complete'));

    const response = await request(app.getHttpServer())
      .get(`/v1/backups/latest?uid=${UID_A}`)
      .set('Authorization', `Bearer ${TOKEN_B}`);

    // A conta B não tem backup, e o `uid` da query não é identidade nenhuma.
    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('BACKUP_NOT_FOUND');
  });

  it('a conta B não enxerga o backup da conta A', async () => {
    await post(fixture('backup-v1-complete'), TOKEN_A);

    expect((await latest(TOKEN_B)).status).toBe(404);
    expect((await latest(TOKEN_A)).status).toBe(200);
  });

  it('duas contas podem usar o mesmo clientBackupId sem colidir', async () => {
    const body = fixture('backup-v1-minimal');

    expect((await post(body, TOKEN_A)).status).toBe(201);
    // A chave de idempotência é (dono, clientBackupId) — não `clientBackupId` sozinho.
    expect((await post(body, TOKEN_B)).status).toBe(201);

    expect((await latest(TOKEN_A)).body.clientBackupId).toBe(body.clientBackupId);
    expect((await latest(TOKEN_B)).body.clientBackupId).toBe(body.clientBackupId);
  });

  // ---------------------------------------------------------------------- idempotência

  it('reenviar a mesma tentativa devolve o mesmo backup, sem criar outro', async () => {
    const body = fixture('backup-v1-complete');

    const first = await post(body);
    const second = await post(body);

    expect(first.status).toBe(201);
    expect(second.status).toBe(200);
    expect(second.body.backupId).toBe(first.body.backupId);
    expect(second.body.createdAt).toBe(first.body.createdAt);
    expect(second.body.payloadHash).toBe(first.body.payloadHash);
  });

  it('reenvio com espaçamento e ordem de chaves diferentes ainda é a mesma tentativa', async () => {
    // A forma canônica ignora espaço e ordem de chave: um cliente que reserializasse o mesmo
    // snapshot não pode ser tratado como se tivesse mudado o conteúdo.
    const body = fixture('backup-v1-minimal');
    const first = await post(JSON.stringify(body, null, 4));
    const reordered = JSON.stringify({
      items: body.items,
      source: body.source,
      capturedAt: body.capturedAt,
      deviceId: body.deviceId,
      clientBackupId: body.clientBackupId,
      backupSchemaVersion: body.backupSchemaVersion,
    });
    const second = await post(reordered);

    expect(first.status).toBe(201);
    expect(second.status).toBe(200);
    expect(second.body.backupId).toBe(first.body.backupId);
  });

  it('mesmo clientBackupId com conteúdo diferente é conflito', async () => {
    const body = fixture('backup-v1-complete');
    await post(body);

    const changed = { ...fixture('backup-v1-minimal'), clientBackupId: body.clientBackupId };
    const response = await post(changed);

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('BACKUP_IDEMPOTENCY_CONFLICT');
    // O conflito não substitui o que já estava guardado.
    expect((await latest()).body.itemCount).toBe(9);
  });

  // -------------------------------------------------------------------------- validação

  it('backupSchemaVersion desconhecida é recusada', async () => {
    const response = await post(fixture('backup-v1-unsupported-version'));

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('UNSUPPORTED_BACKUP_SCHEMA_VERSION');
  });

  it('entitySchemaVersion desconhecida é recusada', async () => {
    const body = fixture('backup-v1-complete') as { items: Array<{ entitySchemaVersion: number }> };
    body.items[0].entitySchemaVersion = 99;

    const response = await post(body);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('UNSUPPORTED_ENTITY_SCHEMA_VERSION');
  });

  it('entityType fora do registry é recusado', async () => {
    const body = fixture('backup-v1-minimal') as { items: unknown[] };
    body.items = [
      { entityType: 'QUALQUER_COISA', entitySchemaVersion: 1, syncId: 'x', payload: {} },
    ];

    const response = await post(body);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_BACKUP');
  });

  it('payload arbitrário para um tipo conhecido é recusado', async () => {
    const body = fixture('backup-v1-minimal') as { items: unknown[] };
    body.items = [
      {
        entityType: 'BODY_MEASUREMENT',
        entitySchemaVersion: 1,
        syncId: '2a5f8c31-4b6d-4e19-9c02-7d1a3e6b8f40',
        payload: { qualquerCoisa: true },
      },
    ];

    expect((await post(body)).status).toBe(400);
  });

  it('identidade que não é portátil é recusada', async () => {
    const response = await post(fixture('backup-v1-invalid-id'));

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_BACKUP');
  });

  it('item duplicado no mesmo snapshot é recusado', async () => {
    const response = await post(fixture('backup-v1-duplicate-item'));

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_BACKUP');
  });

  it('syncId do item que não bate com o payload é recusado', async () => {
    const body = fixture('backup-v1-complete') as {
      items: Array<{ entityType: string; syncId: string }>;
    };
    const measurement = body.items.find((item) => item.entityType === 'BODY_MEASUREMENT')!;
    measurement.syncId = '00000000-0000-4000-8000-000000000000';

    expect((await post(body)).status).toBe(400);
  });

  it('exercício personalizado referenciado por um treino precisa estar no snapshot', async () => {
    const body = fixture('backup-v1-complete') as {
      items: Array<{ entityType: string }>;
    };
    body.items = body.items.filter((item) => item.entityType !== 'CUSTOM_EXERCISE');

    const response = await post(body);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_BACKUP');
  });

  it('sessão concluída sobrevive ao treino que a originou', async () => {
    // Histórico não depende do template: exigir que ele exista recusaria o backup de quem apagou
    // um treino antigo. A sessão carrega `exerciseNameSnapshot` e se sustenta sozinha.
    const body = fixture('backup-v1-complete') as { items: Array<{ entityType: string }> };
    body.items = body.items.filter(
      (item) => item.entityType !== 'WORKOUT_TEMPLATE' && item.entityType !== 'EXERCISE_OVERRIDE',
    );

    expect((await post(body)).status).toBe(201);
  });

  it('sessão não concluída não entra no backup', async () => {
    const body = fixture('backup-v1-complete') as {
      items: Array<{ entityType: string; payload: { status?: string } }>;
    };
    const session = body.items.find((item) => item.entityType === 'WORKOUT_SESSION')!;
    session.payload.status = 'IN_PROGRESS';

    expect((await post(body)).status).toBe(400);
  });

  // ------------------------------------------------------------------------------ tetos

  it('corpo acima do teto responde 413', async () => {
    const body = fixture('backup-v1-minimal') as { items: unknown[] };
    body.items = Array.from({ length: 60 }, (_, index) => ({
      entityType: 'CUSTOM_EXERCISE',
      entitySchemaVersion: 1,
      syncId: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
      payload: {
        syncId: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
        name: 'x'.repeat(100_000),
        primaryMuscle: null,
        equipment: null,
        description: null,
        isBodyweight: false,
        rirEnabled: false,
        active: true,
      },
    }));

    const response = await post(body);

    expect(response.status).toBe(413);
  });

  it('item acima do teto de bytes é recusado', async () => {
    const syncId = '00000000-0000-4000-8000-000000000001';
    const body = fixture('backup-v1-minimal') as { items: unknown[] };
    body.items = [
      {
        entityType: 'CUSTOM_EXERCISE',
        entitySchemaVersion: 1,
        syncId,
        payload: {
          syncId,
          name: 'x'.repeat(3_900),
          primaryMuscle: 'x'.repeat(3_900),
          equipment: 'x'.repeat(3_900),
          description: 'x'.repeat(3_900),
          isBodyweight: false,
          rirEnabled: false,
          active: true,
        },
      },
    ];
    // 4 campos de 3 900 caracteres ainda cabem no teto do item; o teto de string é o que barra
    // primeiro se algum passar de 4 000.
    expect((await post(body)).status).toBe(201);

    const oversized = fixture('backup-v1-minimal') as { items: unknown[] };
    oversized.items = [
      {
        entityType: 'CUSTOM_EXERCISE',
        entitySchemaVersion: 1,
        syncId,
        payload: {
          syncId,
          name: 'x'.repeat(4_001),
          primaryMuscle: null,
          equipment: null,
          description: null,
          isBodyweight: false,
          rirEnabled: false,
          active: true,
        },
      },
    ];
    expect((await post(oversized)).status).toBe(400);
  });

  it('mais itens que o teto é recusado', async () => {
    const body = fixture('backup-v1-minimal') as { items: unknown[] };
    body.items = Array.from({ length: 5_001 }, (_, index) => ({
      entityType: 'WEEKLY_GOAL',
      entitySchemaVersion: 1,
      syncId: `week:${index}`,
      payload: { effectiveFromWeekStartEpochDay: index, goal: 3, createdAt: 1 },
    }));

    expect((await post(body)).status).toBe(400);
  });

  // ----------------------------------------------------------------------------- latest

  it('sem backup, latest responde 404', async () => {
    const response = await latest();

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('BACKUP_NOT_FOUND');
  });

  it('latest devolve a metadata do mais recente, sem payload de domínio', async () => {
    await post(withClientBackupId('backup-v1-minimal', '11111111-1111-4111-8111-111111111111'));
    const second = await post(
      withClientBackupId('backup-v1-complete', '22222222-2222-4222-8222-222222222222'),
    );

    const response = await latest();

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      backupId: second.body.backupId,
      clientBackupId: '22222222-2222-4222-8222-222222222222',
      backupSchemaVersion: 1,
      createdAt: second.body.createdAt,
      itemCount: 9,
      sizeBytes: second.body.sizeBytes,
      payloadHash: second.body.payloadHash,
    });
    // Nenhuma chave de domínio atravessa a metadata.
    expect(JSON.stringify(response.body)).not.toContain('Treino A');
  });

  it('o mais recente é decidido pela sequência do servidor, não pelo relógio do aparelho', async () => {
    const older = {
      ...withClientBackupId('backup-v1-minimal', '33333333-3333-4333-8333-333333333333'),
      capturedAt: 4_000_000_000_000,
    };
    const newer = {
      ...withClientBackupId('backup-v1-complete', '44444444-4444-4444-8444-444444444444'),
      capturedAt: 1_000,
    };

    await post(older);
    const last = await post(newer);

    // O segundo backup tem `capturedAt` muito menor — um aparelho com o relógio adiantado não
    // pode esconder para sempre os backups que vierem depois dele.
    expect((await latest()).body.backupId).toBe(last.body.backupId);
  });
});
