import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { programPayload, pushBody, templatePayload, uuid } from './support/sync-fixtures';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';

/**
 * Convergência entre aparelhos da mesma conta, contra o servidor **real** (T16.6).
 *
 * Cada `Device` aqui guarda o que um celular guardaria: o próprio `deviceId`, o próprio cursor e a
 * própria revision conhecida por entidade. Nenhum deles enxerga o estado do outro — só o que o
 * servidor entrega.
 *
 * O espelho deste arquivo do lado Android é `SyncMultiDeviceTest`, com bancos Room independentes.
 * Os dois cobrem o mesmo cenário de pontas diferentes: aqui o protocolo, lá a aplicação local.
 */
describe('Sync multi-device', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
    );
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  /** Um aparelho: cursor próprio, revisions próprias, nada compartilhado. */
  class Device {
    readonly revisions = new Map<string, number>();
    cursor = 0;
    readonly applied: Array<{ entitySyncId: string; name: string; revision: number }> = [];

    constructor(readonly deviceId: string) {}

    async push(entitySyncId: string, payload: Record<string, unknown>) {
      const response = await request(app.getHttpServer())
        .post('/v1/sync/push')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(
          pushBody(
            [
              {
                entityType: 'WORKOUT_TEMPLATE',
                entitySyncId,
                baseRevision: this.revisions.get(entitySyncId) ?? null,
                payload,
              },
            ],
            this.deviceId,
          ),
        );

      const result = response.body.results[0];
      if (result.status === 'APPLIED' || result.status === 'ALREADY_APPLIED') {
        this.revisions.set(entitySyncId, result.serverRevision);
      }
      return result;
    }

    async pull() {
      const response = await request(app.getHttpServer())
        .get(`/v1/sync/pull?cursor=${this.cursor}&limit=100`)
        .set('Authorization', `Bearer ${TOKEN}`);

      for (const change of response.body.changes) {
        this.revisions.set(change.entitySyncId, change.serverRevision);
        this.applied.push({
          entitySyncId: change.entitySyncId,
          name: (change.payload as { name: string }).name,
          revision: change.serverRevision,
        });
      }
      this.cursor = response.body.nextCursor;
      return response.body;
    }

    nameOf(entitySyncId: string): string | undefined {
      return [...this.applied].reverse().find((c) => c.entitySyncId === entitySyncId)?.name;
    }
  }

  it('A altera e B recebe', async () => {
    const a = new Device('device-a');
    const b = new Device('device-b');
    const syncId = uuid();

    await a.push(syncId, templatePayload(syncId, 'Treino do A'));
    await b.pull();

    expect(b.nameOf(syncId)).toBe('Treino do A');
    expect(b.revisions.get(syncId)).toBe(1);
  });

  it('B altera e A recebe', async () => {
    const a = new Device('device-a');
    const b = new Device('device-b');
    const syncId = uuid();

    await a.push(syncId, templatePayload(syncId, 'Treino do A'));
    await b.pull();

    const result = await b.push(syncId, templatePayload(syncId, 'Treino do B'));
    expect(result.status).toBe('APPLIED');
    expect(result.serverRevision).toBe(2);

    await a.pull();
    expect(a.nameOf(syncId)).toBe('Treino do B');
    expect(a.revisions.get(syncId)).toBe(2);
  });

  it('ida e volta várias vezes converge sem conflito', async () => {
    const a = new Device('device-a');
    const b = new Device('device-b');
    const syncId = uuid();

    await a.push(syncId, templatePayload(syncId, 'v1'));
    await b.pull();
    await b.push(syncId, templatePayload(syncId, 'v2'));
    await a.pull();
    await a.push(syncId, templatePayload(syncId, 'v3'));
    await b.pull();

    expect(a.revisions.get(syncId)).toBe(3);
    expect(b.revisions.get(syncId)).toBe(3);
    expect(b.nameOf(syncId)).toBe('v3');
  });

  it('três aparelhos convergem', async () => {
    const a = new Device('device-a');
    const b = new Device('device-b');
    const c = new Device('device-c');
    const syncId = uuid();

    await a.push(syncId, templatePayload(syncId, 'comum'));
    await b.pull();
    await c.pull();

    await b.push(syncId, templatePayload(syncId, 'editado por B'));
    await c.pull();
    await a.pull();

    expect(a.nameOf(syncId)).toBe('editado por B');
    expect(c.nameOf(syncId)).toBe('editado por B');
    expect(a.revisions.get(syncId)).toBe(2);
    expect(c.revisions.get(syncId)).toBe(2);
  });

  it('edição concorrente é detectada e nada é perdido', async () => {
    const a = new Device('device-a');
    const b = new Device('device-b');
    const syncId = uuid();

    await a.push(syncId, templatePayload(syncId, 'base'));
    await b.pull();
    // Os dois partem da revision 1.
    expect(a.revisions.get(syncId)).toBe(1);
    expect(b.revisions.get(syncId)).toBe(1);

    const fromA = await a.push(syncId, templatePayload(syncId, 'versão do A'));
    const fromB = await b.push(syncId, templatePayload(syncId, 'versão do B'));

    expect(fromA.status).toBe('APPLIED');
    expect(fromA.serverRevision).toBe(2);
    // B não sobrescreve: o servidor devolve a revision atual e não aplica nada.
    expect(fromB.status).toBe('STALE');
    expect(fromB.currentRevision).toBe(2);

    // O que está no servidor é o de A, e o de B continua existindo no aparelho dele — o servidor
    // não escolheu vencedor, ele reportou o desencontro.
    await b.pull();
    expect(b.nameOf(syncId)).toBe('versão do A');
  });

  it('o mesmo aparelho recebe de volta a própria mudança sem duplicar nada', async () => {
    const a = new Device('device-a');
    const syncId = uuid();

    await a.push(syncId, templatePayload(syncId, 'do próprio A'));
    const first = await a.pull();

    expect(first.changes).toHaveLength(1);
    // A origem vem junto, para diagnóstico e supressão de eco. Ela não autoriza nada.
    expect(first.changes[0].originDeviceId).toBe('device-a');

    const second = await a.pull();
    expect(second.changes).toHaveLength(0);
    expect(second.nextCursor).toBe(first.nextCursor);
  });

  it('um agregado em conflito não impede os outros de convergirem', async () => {
    const a = new Device('device-a');
    const b = new Device('device-b');
    const conflitante = uuid();
    const tranquilo = uuid();
    const programa = uuid();

    await a.push(conflitante, templatePayload(conflitante, 'base'));
    await b.pull();
    await a.push(conflitante, templatePayload(conflitante, 'versão do A'));

    // B envia um lote com a mutação stale **e** duas mutações independentes.
    const response = await request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(
        pushBody(
          [
            {
              entityType: 'WORKOUT_TEMPLATE',
              entitySyncId: conflitante,
              baseRevision: 1,
              payload: templatePayload(conflitante, 'versão do B'),
            },
            {
              entityType: 'WORKOUT_TEMPLATE',
              entitySyncId: tranquilo,
              payload: templatePayload(tranquilo, 'treino novo do B'),
            },
            {
              entityType: 'WORKOUT_PROGRAM',
              entitySyncId: programa,
              payload: programPayload(programa, 'programa novo do B'),
            },
          ],
          'device-b',
        ),
      );

    expect(response.body.results.map((r: { status: string }) => r.status)).toEqual([
      'STALE',
      'APPLIED',
      'APPLIED',
    ]);

    await a.pull();
    expect(a.nameOf(tranquilo)).toBe('treino novo do B');
  });
});
