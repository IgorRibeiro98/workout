import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { join } from 'node:path';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import type {
  WorkoutProgramShareSnapshotV1,
  WorkoutTemplateShareSnapshotV1,
} from '../src/modules/social/workout-share.contract';

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
  C: { token: 'token-c', uid: 'uid-c', email: 'c@example.com', name: 'Charlie' },
} as const;

const exercise = (canonicalExerciseId: string, sortOrder: number) => ({
  canonicalExerciseId,
  sortOrder,
  targetSets: 4,
  minReps: 8,
  maxReps: 10,
  restDurationSeconds: 120,
});

const PROGRAM: WorkoutProgramShareSnapshotV1 = {
  snapshotVersion: 1,
  name: 'Push/Pull/Legs',
  description: 'Três dias, uma semana.',
  templates: [
    {
      name: 'Push',
      shortIdentifier: 'A',
      orderInProgram: 0,
      // T19.8: dois dias no mesmo treino, na forma canônica.
      scheduledDays: ['MONDAY', 'THURSDAY'],
      exercises: [
        exercise('canonical:supino-reto-barra', 0),
        exercise('canonical:desenvolvimento-halteres', 1),
      ],
    },
    {
      name: 'Pull',
      shortIdentifier: 'B',
      orderInProgram: 1,
      dayOfWeek: null,
      exercises: [exercise('canonical:remada-curvada-barra', 0)],
    },
    {
      name: 'Legs',
      shortIdentifier: 'C',
      orderInProgram: 2,
      exercises: [
        exercise('canonical:agachamento-livre', 0),
        exercise('canonical:leg-press', 1),
        exercise('canonical:cadeira-extensora', 2),
      ],
    },
  ],
};

const TEMPLATE: WorkoutTemplateShareSnapshotV1 = {
  snapshotVersion: 1,
  name: 'Upper A',
  shortIdentifier: 'A',
  exercises: [exercise('canonical:supino-reto-barra', 0)],
};

/**
 * T19.3 — compartilhar um **programa** inteiro é a mesma oferta da T17.7, com outro conteúdo.
 *
 * ```text
 * POST /workout-shares { programSnapshot }   →  share_type = WORKOUT_PROGRAM
 * GET  received / sent                       →  shareType, templateCount, exerciseCount
 * GET  :shareId                              →  programSnapshot (e NUNCA snapshot)
 * POST :shareId/accept                       →  o detalhe, idempotente em ACCEPTED e IMPORTED
 * ```
 *
 * O que estes testes provam sobre o servidor: o snapshot volta **verbatim** (imutável, em ordem),
 * o discriminador é o campo presente, a idempotência distingue tipo, e bloqueio, cancelamento,
 * recusa e exclusão de conta seguem exatamente a política do treino avulso. A cópia independente
 * é do aparelho — o servidor não sabe que ela existe, e é isto que a torna independente.
 */
describe('Program Shares: compartilhamento de programa completo (T19.3)', () => {
  let temp: TempDb;
  let app: INestApplication;
  let verifier: FakeAuthTokenVerifier;

  beforeEach(async () => {
    temp = createTempDb();
    verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(
      configFor(temp.path, {
        DELETION_TOMBSTONES_FILE_PATH: join(temp.directory, 'deletion_tombstones.tsv'),
        ACCOUNT_DELETION_HMAC_KEY: 'test-hmac-key-for-account-deletion-very-secret',
      }),
      verifier,
    );
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  async function setupProfile(token: string, displayName: string): Promise<string> {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId;
  }

  async function establishFriendship(tokenA: string, tokenB: string, socialIdB: string) {
    const sendRes = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenA))
      .send({ socialId: socialIdB })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sendRes.body.request.requestId}/accept`)
      .set('Authorization', auth(tokenB))
      .send()
      .expect(200);
  }

  async function friends(): Promise<{ socialA: string; socialB: string }> {
    const socialA = await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);
    return { socialA, socialB };
  }

  const shareProgram = (
    socialB: string,
    clientRequestId: string,
    programSnapshot: unknown = PROGRAM,
  ) =>
    request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId, programSnapshot });

  // ------------------------------------------------------------------ criação e leitura

  it('cria a oferta de programa, e as listas dizem o tipo, os treinos e os exercícios', async () => {
    const { socialA, socialB } = await friends();

    const created = await shareProgram(socialB, 'req-program-1').expect(201);
    expect(created.body.shareType).toBe('WORKOUT_PROGRAM');
    expect(created.body.status).toBe('PENDING');
    expect(created.body.sender.socialId).toBe(socialA);
    expect(created.body.recipient.socialId).toBe(socialB);
    expect(created.body.programSnapshot).toEqual(PROGRAM);
    expect(created.body).not.toHaveProperty('snapshot');

    const received = await request(server())
      .get('/v1/social/workout-shares/received')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(received.body).toHaveLength(1);
    expect(received.body[0]).toMatchObject({
      shareId: created.body.shareId,
      shareType: 'WORKOUT_PROGRAM',
      status: 'PENDING',
      templateName: 'Push/Pull/Legs',
      templateCount: 3,
      exerciseCount: 6,
      otherUser: { socialId: socialA, displayName: 'Alice' },
    });

    const sent = await request(server())
      .get('/v1/social/workout-shares/sent')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(sent.body).toHaveLength(1);
    expect(sent.body[0]).toMatchObject({
      shareType: 'WORKOUT_PROGRAM',
      templateName: 'Push/Pull/Legs',
      templateCount: 3,
      exerciseCount: 6,
    });
  });

  it('a agenda de cada treino volta como foi enviada: vários dias, nenhum dia, ou a forma anterior à T19.8', async () => {
    const { socialB } = await friends();
    const legacy = {
      ...PROGRAM,
      templates: [
        PROGRAM.templates[0],
        { ...PROGRAM.templates[1], dayOfWeek: 'Ter' },
        { ...PROGRAM.templates[2], scheduledDays: [] },
      ],
    };

    const created = await shareProgram(socialB, 'req-days', legacy).expect(201);

    const templates = created.body.programSnapshot.templates as Array<Record<string, unknown>>;
    expect(templates[0].scheduledDays).toEqual(['MONDAY', 'THURSDAY']);
    expect(templates[0]).not.toHaveProperty('dayOfWeek');
    // Um app anterior à T19.8 ainda manda um dia como rótulo; o servidor guarda verbatim.
    expect(templates[1].dayOfWeek).toBe('Ter');
    expect(templates[1]).not.toHaveProperty('scheduledDays');
    expect(templates[2].scheduledDays).toEqual([]);
  });

  it('um treino avulso continua listado como WORKOUT_TEMPLATE, com templateCount 1 (T17.7)', async () => {
    const { socialB } = await friends();
    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId: 'req-template', snapshot: TEMPLATE })
      .expect(201);

    const received = await request(server())
      .get('/v1/social/workout-shares/received')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(received.body[0]).toMatchObject({
      shareType: 'WORKOUT_TEMPLATE',
      templateName: 'Upper A',
      templateCount: 1,
      exerciseCount: 1,
    });

    const detail = await request(server())
      .get(`/v1/social/workout-shares/${received.body[0].shareId}`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(detail.body.shareType).toBe('WORKOUT_TEMPLATE');
    expect(detail.body.snapshot).toEqual(TEMPLATE);
    expect(detail.body).not.toHaveProperty('programSnapshot');
  });

  it('o snapshot é imutável e volta na ordem em que foi enviado', async () => {
    const { socialB } = await friends();
    const created = await shareProgram(socialB, 'req-immutable').expect(201);
    const shareId = created.body.shareId as string;

    // "Editar o programa depois" é, do ponto de vista do servidor, uma **outra** oferta: a
    // primeira continua devolvendo exatamente o que foi enviado.
    const edited = {
      ...PROGRAM,
      name: 'Push/Pull/Legs v2',
      templates: [PROGRAM.templates[2], PROGRAM.templates[0]],
    };
    const second = await shareProgram(socialB, 'req-immutable-2', edited).expect(201);
    expect(second.body.shareId).not.toBe(shareId);

    const detail = await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(detail.body.programSnapshot).toEqual(PROGRAM);
    expect(
      (detail.body.programSnapshot as WorkoutProgramShareSnapshotV1).templates.map((t) => t.name),
    ).toEqual(['Push', 'Pull', 'Legs']);
    expect(
      (detail.body.programSnapshot as WorkoutProgramShareSnapshotV1).templates[2].exercises.map(
        (e) => e.canonicalExerciseId,
      ),
    ).toEqual([
      'canonical:agachamento-livre',
      'canonical:leg-press',
      'canonical:cadeira-extensora',
    ]);
  });

  // ------------------------------------------------------------------ aceite

  it('aceitar devolve o detalhe com o programa, e é idempotente em ACCEPTED e em IMPORTED', async () => {
    const { socialB } = await friends();
    const created = await shareProgram(socialB, 'req-accept').expect(201);
    const shareId = created.body.shareId as string;

    const accepted = await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(accepted.body).toMatchObject({
      shareId,
      shareType: 'WORKOUT_PROGRAM',
      status: 'ACCEPTED',
    });
    expect(accepted.body.programSnapshot).toEqual(PROGRAM);
    expect(accepted.body).not.toHaveProperty('snapshot');

    // Toque duplo / retry depois de resposta perdida: o mesmo conteúdo, sem erro.
    const again = await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(again.body.status).toBe('ACCEPTED');
    expect(again.body.programSnapshot).toEqual(PROGRAM);

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/complete-import`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    // Depois de IMPORTED o replay continua respondendo o conteúdo — é o recibo local do aparelho
    // que impede a segunda cópia, e recusar aqui deixaria sem saída quem perdeu o recibo.
    const afterImport = await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(afterImport.body.status).toBe('IMPORTED');
    expect(afterImport.body.programSnapshot).toEqual(PROGRAM);

    const detail = await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(detail.body.status).toBe('IMPORTED');
  });

  it('aceitar um treino avulso também devolve o detalhe (T17.7 pelo mesmo caminho)', async () => {
    const { socialB } = await friends();
    const created = await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId: 'req-t', snapshot: TEMPLATE })
      .expect(201);

    const accepted = await request(server())
      .post(`/v1/social/workout-shares/${created.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(accepted.body).toMatchObject({ shareType: 'WORKOUT_TEMPLATE', status: 'ACCEPTED' });
    expect(accepted.body.snapshot).toEqual(TEMPLATE);
    expect(accepted.body).not.toHaveProperty('programSnapshot');
  });

  // ------------------------------------------------------------------ idempotência de criação

  it('idempotência: replay idêntico devolve a mesma oferta; conteúdo ou tipo diferente é 409', async () => {
    const { socialB } = await friends();
    const first = await shareProgram(socialB, 'req-idem').expect(201);
    const replay = await shareProgram(socialB, 'req-idem').expect(201);
    expect(replay.body.shareId).toBe(first.body.shareId);

    await shareProgram(socialB, 'req-idem', { ...PROGRAM, name: 'Outro nome' }).expect(409);

    // A mesma chave com um **treino** no lugar do programa: é outro conteúdo, nunca o antigo.
    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId: 'req-idem', snapshot: TEMPLATE })
      .expect(409);

    const sent = await request(server())
      .get('/v1/social/workout-shares/sent')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(sent.body).toHaveLength(1);
  });

  // ------------------------------------------------------------------ validação

  it('o discriminador é o campo presente: os dois, nenhum, ou shareType no corpo recusam', async () => {
    const { socialB } = await friends();

    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-both',
        snapshot: TEMPLATE,
        programSnapshot: PROGRAM,
      })
      .expect(400);

    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId: 'req-none' })
      .expect(400);

    await request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: 'req-type',
        shareType: 'WORKOUT_PROGRAM',
        programSnapshot: PROGRAM,
      })
      .expect(400);
  });

  it.each<[string, unknown]>([
    ['sem treinos', { ...PROGRAM, templates: [] }],
    [
      'treino sem exercícios',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[0], exercises: [] }] },
    ],
    ['nome vazio', { ...PROGRAM, name: '   ' }],
    ['descrição longa', { ...PROGRAM, description: 'x'.repeat(501) }],
    [
      'dia da semana longo (forma anterior à T19.8)',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[1], dayOfWeek: 'x'.repeat(33) }] },
    ],
    [
      'scheduledDays com rótulo em vez do nome canônico',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[0], scheduledDays: ['Seg'] }] },
    ],
    [
      'scheduledDays com dia repetido',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[0], scheduledDays: ['MONDAY', 'MONDAY'] }] },
    ],
    [
      'scheduledDays que não é lista',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[0], scheduledDays: 'MONDAY' }] },
    ],
    [
      'dayOfWeek e scheduledDays no mesmo treino',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[0], dayOfWeek: 'Seg' }] },
    ],
    [
      'ordem negativa',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[0], orderInProgram: -1 }] },
    ],
    ['versão desconhecida', { ...PROGRAM, snapshotVersion: 2 }],
    [
      'carga pessoal num exercício',
      {
        ...PROGRAM,
        templates: [
          {
            ...PROGRAM.templates[1],
            exercises: [{ ...exercise('canonical:x', 0), plannedWeight: 80 }],
          },
        ],
      },
    ],
    [
      'nota pessoal num treino',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[1], notes: 'segredo' }] },
    ],
    [
      'identidade do remetente no treino',
      { ...PROGRAM, templates: [{ ...PROGRAM.templates[1], syncId: 'abc' }] },
    ],
    ['programId no treino', { ...PROGRAM, templates: [{ ...PROGRAM.templates[1], programId: 7 }] }],
    ['isCurrent no programa', { ...PROGRAM, isCurrent: true }],
    ['syncId no programa', { ...PROGRAM, syncId: 'abc' }],
    ['campo desconhecido no programa', { ...PROGRAM, history: [] }],
    [
      'campo desconhecido no exercício',
      {
        ...PROGRAM,
        templates: [
          { ...PROGRAM.templates[1], exercises: [{ ...exercise('canonical:x', 0), weight: 80 }] },
        ],
      },
    ],
  ])('recusa por nome: %s', async (_label, programSnapshot) => {
    const { socialB } = await friends();
    const res = await shareProgram(socialB, 'req-invalid', programSnapshot).expect(400);
    expect(res.body.error.code).toBe('INVALID_SNAPSHOT');

    const sent = await request(server())
      .get('/v1/social/workout-shares/sent')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(sent.body).toHaveLength(0);
  });

  it('recusa um programa acima do teto de treinos', async () => {
    const { socialB } = await friends();
    const templates = Array.from({ length: 31 }, (_, i) => ({
      ...PROGRAM.templates[0],
      name: `Treino ${i}`,
      orderInProgram: i,
    }));
    await shareProgram(socialB, 'req-too-many', { ...PROGRAM, templates }).expect(400);
  });

  // ------------------------------------------------------------------ autorização e ciclo de vida

  it('exige amizade, recusa consigo mesmo e esconde de terceiros', async () => {
    const socialA = await setupProfile(ACCOUNTS.A.token, 'Alice');
    const socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await setupProfile(ACCOUNTS.C.token, 'Charlie');

    await shareProgram(socialA, 'req-self').expect(400);
    await shareProgram(socialB, 'req-non-friend').expect(403);

    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);
    const created = await shareProgram(socialB, 'req-third').expect(201);

    await request(server())
      .get(`/v1/social/workout-shares/${created.body.shareId}`)
      .set('Authorization', auth(ACCOUNTS.C.token))
      .expect(404);
    await request(server())
      .post(`/v1/social/workout-shares/${created.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.C.token))
      .expect(404);
  });

  it('recusar não importa nada; cancelar impede o aceite posterior', async () => {
    const { socialB } = await friends();

    const declined = await shareProgram(socialB, 'req-decline').expect(201);
    await request(server())
      .post(`/v1/social/workout-shares/${declined.body.shareId}/decline`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    await request(server())
      .post(`/v1/social/workout-shares/${declined.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(400);

    const cancelled = await shareProgram(socialB, 'req-cancel').expect(201);
    await request(server())
      .post(`/v1/social/workout-shares/${cancelled.body.shareId}/cancel`)
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    const res = await request(server())
      .post(`/v1/social/workout-shares/${cancelled.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(400);
    expect(res.body.error.code).toBe('SHARE_NOT_AVAILABLE');
  });

  it('bloqueio antes do aceite: a oferta some e o aceite é 404', async () => {
    const { socialA, socialB } = await friends();
    const created = await shareProgram(socialB, 'req-block').expect(201);

    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .send({ blockedSocialId: socialA })
      .expect(200);

    await request(server())
      .post(`/v1/social/workout-shares/${created.body.shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(404);
    const received = await request(server())
      .get('/v1/social/workout-shares/received')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(received.body).toHaveLength(0);
  });

  it('bloqueio depois da importação não reabre nem cancela a oferta já importada', async () => {
    const { socialB } = await friends();
    const created = await shareProgram(socialB, 'req-block-after').expect(201);
    const shareId = created.body.shareId as string;

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/complete-import`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    const socialB2 = (
      await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(ACCOUNTS.B.token))
        .expect(200)
    ).body.profile.socialId as string;
    await request(server())
      .post('/v1/social/blocks')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ blockedSocialId: socialB2 })
      .expect(200);

    // O servidor só cancela PENDING/ACCEPTED (T17.6): a oferta importada permanece IMPORTED. A
    // cópia, essa, está no Room do destinatário — e o servidor não tem como alcançá-la.
    const detail = await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.B.token));
    expect(detail.status).toBe(404); // bloqueio esconde a oferta dos dois lados
    const sent = await request(server())
      .get('/v1/social/workout-shares/sent')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(sent.body).toHaveLength(0);
  });

  it('exclusão da conta do remetente depois do aceite apaga a oferta, e só ela', async () => {
    const { socialB } = await friends();
    const created = await shareProgram(socialB, 'req-deletion').expect(201);
    const shareId = created.body.shareId as string;

    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/accept`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    await request(server())
      .post(`/v1/social/workout-shares/${shareId}/complete-import`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);

    const deleted = await request(server())
      .delete('/v1/account')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .expect(200);
    expect(deleted.body.status).toBe('DELETED');

    // A oferta foi purgada com a conta; o destinatário continua com o perfil dele intacto. A cópia
    // importada não existe no servidor — logo, não há o que apagar dela.
    await request(server())
      .get(`/v1/social/workout-shares/${shareId}`)
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(404);
    const received = await request(server())
      .get('/v1/social/workout-shares/received')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(received.body).toHaveLength(0);
    const me = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(ACCOUNTS.B.token))
      .expect(200);
    expect(me.body.enabled).toBe(true);
  });
});
