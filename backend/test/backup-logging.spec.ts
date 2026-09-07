import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { fixture, fixtureText, withClientBackupId } from './support/backup-fixtures';

const TOKEN = 'token-secreto-da-conta-xyz789';
const UID = 'uid-da-conta-a';

/**
 * O que o backup registra — e o que ele nunca registra.
 *
 * O log precisa responder "qual conta, qual tentativa, quantos itens, quantos bytes, quanto
 * demorou e como terminou". Ele não pode responder "o que a pessoa treinou", "quanto ela pesa" ou
 * "que anotação ela escreveu".
 *
 * O teste captura a saída real do `pino` e procura o que não pode estar lá — inclusive o corpo,
 * que a partir da T16.4 fica disponível cru na requisição para o hash canônico e por isso merece
 * uma prova explícita de que não vaza.
 */
describe('Observabilidade do backup: metadata sim, conteúdo não', () => {
  let temp: TempDb;
  let app: INestApplication;
  let written: string[];
  let restore: () => void;

  beforeEach(() => {
    temp = createTempDb();
    written = [];
    const original = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]): boolean => {
      written.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
      return original(chunk as never, ...(rest as []));
    }) as typeof process.stdout.write;
    restore = () => {
      process.stdout.write = original;
    };
  });

  afterEach(async () => {
    restore();
    await app?.close();
    temp.cleanup();
  });

  const logs = () => written.join('\n');

  const start = async (): Promise<void> => {
    app = await createTestApp(
      configFor(temp.path, { LOG_LEVEL: 'debug' }),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
    );
  };

  const post = (body: unknown) =>
    request(app.getHttpServer())
      .post('/v1/backups')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(typeof body === 'string' ? body : JSON.stringify(body));

  it('um backup completo registra correlação e volume, e nada de conteúdo', async () => {
    await start();

    const response = await post(fixture('backup-v1-complete'));
    expect(response.status).toBe(201);

    const output = logs();

    // Metadata técnica: existe.
    expect(output).toContain('backup.created');
    expect(output).toContain('"itemCount":9');
    expect(output).toContain('"sizeBytes"');
    expect(output).toContain('"uidPrefix":"uid-da"');

    // Conteúdo do usuário: não existe. Estes valores estão todos na fixture enviada.
    for (const secret of [
      'Treino A',
      'Rosca martelo no banco inclinado',
      'Academia do bairro',
      'Pegada média, pés firmes.',
      'Supino reto (barra olímpica)',
      'Programa Hipertrofia',
      '79.4',
      '18.5',
    ]) {
      expect(output).not.toContain(secret);
    }

    // O corpo inteiro, o header e o token também não.
    expect(output).not.toContain(TOKEN);
    expect(output).not.toContain('Bearer');
    expect(output).not.toContain('"payload"');
    expect(output).not.toContain('supino-reto-barra');
    // O uid inteiro não é registrado — só o prefixo que correlaciona sem identificar.
    expect(output).not.toContain(UID);
  });

  it('um backup recusado não registra o que havia dentro dele', async () => {
    await start();

    const response = await post(fixture('backup-v1-duplicate-item'));
    expect(response.status).toBe(400);

    const output = logs();
    expect(output).not.toContain('Treino A');
    expect(output).not.toContain('Academia do bairro');
    expect(output).not.toContain('"payload"');
  });

  it('o conflito de idempotência registra a tentativa, não o conteúdo', async () => {
    await start();

    const clientBackupId = '66666666-6666-4666-8666-666666666666';
    await post(withClientBackupId('backup-v1-complete', clientBackupId));
    const conflict = await post(withClientBackupId('backup-v1-minimal', clientBackupId));

    expect(conflict.status).toBe(409);
    const output = logs();
    expect(output).toContain('backup.idempotency.conflict');
    expect(output).toContain(clientBackupId);
    expect(output).not.toContain('Treino A');
  });

  it('baixar um backup registra correlação e volume, nunca o snapshot (T16.5)', async () => {
    await start();

    const created = await post(fixture('backup-v1-complete'));
    const backupId = created.body.backupId as string;

    written.length = 0;
    const list = await request(app.getHttpServer())
      .get('/v1/backups')
      .set('Authorization', `Bearer ${TOKEN}`);
    const content = await request(app.getHttpServer())
      .get(`/v1/backups/${backupId}/content`)
      .set('Authorization', `Bearer ${TOKEN}`);

    expect(list.status).toBe(200);
    expect(content.status).toBe(200);

    const output = logs();

    // Metadata técnica do download: existe.
    expect(output).toContain('backup.content.served');
    expect(output).toContain('"uidPrefix":"uid-da"');

    // O snapshot atravessou a resposta; ele não pode ter atravessado o log.
    for (const secret of [
      'Treino A',
      'Rosca martelo no banco inclinado',
      'Academia do bairro',
      'Pegada média, pés firmes.',
      'Programa Hipertrofia',
      '79.4',
      '18.5',
      'supino-reto-barra',
    ]) {
      expect(output).not.toContain(secret);
    }
    expect(output).not.toContain('"payload"');
    expect(output).not.toContain(TOKEN);
    expect(output).not.toContain('Bearer');
    expect(output).not.toContain(UID);
  });

  it('a resposta de erro não devolve conteúdo do snapshot', async () => {
    await start();

    const body = fixture('backup-v1-complete') as {
      items: Array<{ entityType: string; payload: { name?: string } }>;
    };
    const template = body.items.find((item) => item.entityType === 'WORKOUT_TEMPLATE')!;
    template.payload.name = 'x'.repeat(5_000);

    const response = await post(body);

    expect(response.status).toBe(400);
    expect(JSON.stringify(response.body)).not.toContain('xxxxx');
    expect(response.body.error.requestId).toEqual(expect.any(String));
  });

  it('o corpo cru fica disponível para o hash sem virar log', async () => {
    await start();

    // A fixture tem uma marca única; se o corpo cru estivesse sendo registrado em qualquer
    // caminho — inclusive no log de acesso — ela apareceria.
    expect(fixtureText('backup-v1-complete')).toContain('Rosca martelo no banco inclinado');
    await post(fixture('backup-v1-complete'));

    expect(logs()).not.toContain('Rosca martelo');
  });
});
