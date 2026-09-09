import BetterSqlite3 from 'better-sqlite3';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import type { WorkoutTemplateShareSnapshotV1 } from '../src/modules/social/workout-share.contract';
import { SqliteService } from '../src/database/sqlite.service';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
} as const;

const SNAPSHOT: WorkoutTemplateShareSnapshotV1 = {
  snapshotVersion: 1,
  name: 'Upper A',
  shortIdentifier: 'A',
  exercises: [
    {
      canonicalExerciseId: 'canonical:supino-reto-barra',
      sortOrder: 0,
      targetSets: 4,
      minReps: 8,
      maxReps: 10,
      restDurationSeconds: 120,
    },
  ],
};

/**
 * T17.13.1 §45–§53, §71 e §72 — o compartilhamento de treino é atômico e não perde corridas.
 *
 * ## Duas correções, duas garantias
 *
 * ```text
 * §45–§49   INSERT workout_shares + INSERT social_notification_events  =  UMA transação
 * §50–§53   toda transição de estado é condicional ao estado esperado  =  CAS
 * ```
 *
 * O evento de notificação é o **outbox** do push: é ele que faz o destinatário saber que a oferta
 * existe. As duas escritas eram sequenciais e independentes, e uma falha entre elas deixava uma
 * oferta no banco que ninguém seria avisado de ter recebido — em silêncio, expirando em trinta
 * dias. O FCM continua fora da transação: o que ela grava é a **intenção** de notificar.
 *
 * As transições eram `UPDATE ... WHERE id = ?`, decididas a partir de uma leitura anterior. Entre
 * a leitura e a escrita cabe outra requisição inteira, e duas transições incompatíveis simultâneas
 * ambas passavam — a última a escrever ganhava. Agora o `WHERE` carrega o estado esperado, e o
 * SQLite decide uma vez só.
 */
describe('T17.13.1 — WorkoutShare: outbox transacional e transições CAS', () => {
  let temp: TempDb;
  let app: INestApplication;

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  const inDatabase = <T>(read: (db: BetterSqlite3.Database) => T): T => {
    const db = new BetterSqlite3(temp.path);
    try {
      return read(db);
    } finally {
      db.close();
    }
  };

  const countOf = (sql: string): number =>
    inDatabase((db) => (db.prepare(sql).get() as { n: number }).n);

  const shareCount = () => countOf(`SELECT COUNT(*) AS n FROM workout_shares`);
  const eventCount = () =>
    countOf(
      `SELECT COUNT(*) AS n FROM social_notification_events WHERE type = 'WORKOUT_SHARE_RECEIVED'`,
    );
  const statusOf = (shareId: string): string | undefined =>
    inDatabase(
      (db) =>
        (
          db.prepare(`SELECT status FROM workout_shares WHERE id = ?`).get(shareId) as
            | { status: string }
            | undefined
        )?.status,
    );

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(configFor(temp.path), verifier);
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  async function setup(): Promise<{ socialB: string }> {
    const activate = async (token: string, name: string) =>
      (
        await request(server())
          .post('/v1/social/me/activate')
          .set('Authorization', auth(token))
          .send({ displayName: name })
          .expect(200)
      ).body.profile.socialId as string;

    await activate(ACCOUNTS.A.token, 'Alice');
    const socialB = await activate(ACCOUNTS.B.token, 'Bob');

    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ socialId: socialB })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    return { socialB };
  }

  const share = (socialB: string, clientRequestId: string) =>
    request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId, snapshot: SNAPSHOT });

  // ============================================================ §45–§49 outbox transacional

  it('o share e o evento de notificação nascem juntos (§45/§46)', async () => {
    const { socialB } = await setup();
    await share(socialB, 'req-1').expect(201);

    expect(shareCount()).toBe(1);
    expect(eventCount()).toBe(1);
  });

  it('falha ao gravar o evento faz ROLLBACK do share (§47/§49/§71)', async () => {
    const { socialB } = await setup();

    // Injeção de falha **no banco**, e não no FCM: um gatilho que aborta a inserção do evento. Ele
    // dispara dentro da transação, depois de o share já ter sido inserido — que é exatamente o
    // ponto onde o par ficava inconsistente.
    //
    // §49 é explícito em que testar uma falha do FCM falso não cobre isto: aquilo é outra camada,
    // e a entrega acontece muito depois, fora de qualquer transação.
    app.get(SqliteService).connection.exec(`
      CREATE TRIGGER falha_no_evento BEFORE INSERT ON social_notification_events
      BEGIN SELECT RAISE(ABORT, 'falha injetada no outbox'); END;
    `);

    await share(socialB, 'req-falha').expect(500);

    // Nenhum dos dois: não pode existir oferta sem o aviso que a torna visível.
    expect(shareCount()).toBe(0);
    expect(eventCount()).toBe(0);

    // E o `clientRequestId` não ficou queimado: a tentativa seguinte, depois de o problema
    // passar, cria a oferta normalmente.
    app.get(SqliteService).connection.exec(`DROP TRIGGER falha_no_evento`);
    await share(socialB, 'req-falha').expect(201);
    expect(shareCount()).toBe(1);
    expect(eventCount()).toBe(1);
  });

  it('falha ao gravar o share não deixa evento órfão (§47)', async () => {
    const { socialB } = await setup();
    app.get(SqliteService).connection.exec(`
      CREATE TRIGGER falha_no_share BEFORE INSERT ON workout_shares
      BEGIN SELECT RAISE(ABORT, 'falha injetada no share'); END;
    `);

    await share(socialB, 'req-falha-share').expect(500);
    expect(shareCount()).toBe(0);
    expect(eventCount()).toBe(0);
  });

  it('replay idempotente não cria um segundo evento de notificação (§48/§71)', async () => {
    const { socialB } = await setup();
    const first = await share(socialB, 'req-replay').expect(201);
    const second = await share(socialB, 'req-replay').expect(201);

    expect(second.body.shareId).toBe(first.body.shareId);
    expect(shareCount()).toBe(1);
    // O destinatário não é avisado duas vezes por uma oferta só.
    expect(eventCount()).toBe(1);
  });

  // ============================================================ §50–§53/§72 corridas

  it('aceitar e cancelar ao mesmo tempo: só uma transição vence (§52/§72)', async () => {
    const { socialB } = await setup();
    const created = await share(socialB, 'req-corrida-1').expect(201);
    const shareId = created.body.shareId as string;

    const [accept, cancel] = await Promise.all([
      request(server())
        .post(`/v1/social/workout-shares/${shareId}/accept`)
        .set('Authorization', auth(ACCOUNTS.B.token)),
      request(server())
        .post(`/v1/social/workout-shares/${shareId}/cancel`)
        .set('Authorization', auth(ACCOUNTS.A.token)),
    ]);

    const final = statusOf(shareId);
    expect(['ACCEPTED', 'CANCELLED']).toContain(final);

    // O desfecho é **determinístico** no sentido que importa: exatamente uma das duas teve
    // sucesso, e o estado do banco concorda com ela. Antes, as duas podiam responder sucesso — e o
    // destinatário levava para casa um treino que o remetente tinha acabado de retirar.
    const succeeded = [accept, cancel].filter((r) => r.status === 200);
    expect(succeeded).toHaveLength(1);
    if (final === 'ACCEPTED') {
      expect(accept.status).toBe(200);
      expect(cancel.status).toBe(400);
    } else {
      expect(cancel.status).toBe(200);
      expect(accept.status).toBe(400);
    }
  });

  it('recusar e aceitar ao mesmo tempo: só uma transição vence (§52/§72)', async () => {
    const { socialB } = await setup();
    const created = await share(socialB, 'req-corrida-2').expect(201);
    const shareId = created.body.shareId as string;

    const [accept, decline] = await Promise.all([
      request(server())
        .post(`/v1/social/workout-shares/${shareId}/accept`)
        .set('Authorization', auth(ACCOUNTS.B.token)),
      request(server())
        .post(`/v1/social/workout-shares/${shareId}/decline`)
        .set('Authorization', auth(ACCOUNTS.B.token)),
    ]);

    expect([accept.status, decline.status].filter((s) => s === 200)).toHaveLength(1);
    expect(['ACCEPTED', 'DECLINED']).toContain(statusOf(shareId));
  });

  it('expirar não sobrescreve um aceite já gravado (§52/§72)', async () => {
    const { socialB } = await setup();
    const created = await share(socialB, 'req-corrida-3').expect(201);
    const shareId = created.body.shareId as string;

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(statusOf(shareId)).toBe('ACCEPTED');

    // A auto-expiração de uma listagem concorrente é condicional em `PENDING`: ela não alcança
    // uma oferta já aceita, por mais que o prazo tenha passado.
    inDatabase((db) =>
      db.prepare(`UPDATE workout_shares SET expires_at = 1 WHERE id = ?`).run(shareId),
    );
    await request(server())
      .get('/v1/social/workout-shares/received')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    expect(statusOf(shareId)).toBe('ACCEPTED');
  });

  it('concluir a importação duas vezes converge, com um carimbo só (§52/§72)', async () => {
    const { socialB } = await setup();
    const created = await share(socialB, 'req-corrida-4').expect(201);
    const shareId = created.body.shareId as string;

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    const [one, two] = await Promise.all([
      request(server())
        .post(`/v1/social/workout-shares/${shareId}/complete-import`)
        .set('Authorization', auth(ACCOUNTS.B.token)),
      request(server())
        .post(`/v1/social/workout-shares/${shareId}/complete-import`)
        .set('Authorization', auth(ACCOUNTS.B.token)),
    ]);

    // As duas convergem — o desfecho pretendido é o mesmo —, mas o carimbo é o da vencedora.
    expect(one.status).toBe(200);
    expect(two.status).toBe(200);
    expect(statusOf(shareId)).toBe('IMPORTED');
    expect(
      inDatabase(
        (db) =>
          (
            db
              .prepare(`SELECT imported_at AS at FROM workout_shares WHERE id = ?`)
              .get(shareId) as {
              at: number | null;
            }
          ).at,
      ),
    ).not.toBeNull();
  });

  it('concluir importação sem aceitar é recusado, e o estado não muda (§50)', async () => {
    const { socialB } = await setup();
    const created = await share(socialB, 'req-corrida-5').expect(201);
    const shareId = created.body.shareId as string;

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/complete-import`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(400);

    expect(statusOf(shareId)).toBe('PENDING');
  });
});
