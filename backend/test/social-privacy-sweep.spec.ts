import request from 'supertest';
import { jpeg } from './support/image-fixtures';
import { uuid } from './support/sync-fixtures';
import {
  ACCOUNT_A,
  ACCOUNT_B,
  ACCOUNT_C,
  createSocialScenario,
  type AuditAccount,
  type SocialScenario,
} from './support/social-scenario';

/**
 * T17.10 §7–§11 — a varredura de privacidade sobre **todas** as superfícies sociais de uma vez.
 *
 * ## Por que uma varredura a mais
 *
 * Cada fase da T17 trouxe a sua: a T17.0 varre o perfil, a T17.1 o grafo, a T17.8 o Feed. O que
 * não existia era a pergunta transversal — *nenhum* endpoint social, em *nenhuma* combinação de
 * relação, devolve identidade privada. É essa a que pega o caso real: a rota que nasceu na fase
 * seguinte e herdou a atenção de ninguém.
 *
 * ## Duas listas, e não uma
 *
 * §11 é explícito: uma blacklist global mente nos dois sentidos. `friendCode` **pertence** a
 * `GET /v1/social/me` e é vazamento em qualquer resposta voltada a amigo; `caption` pertence ao
 * Feed e não pertence a lugar nenhum do grafo. Por isso a varredura é:
 *
 * 1. **proibido em toda parte** — uid do Firebase, e-mail, `sessionSyncId`, `deviceId`;
 * 2. **proibido fora do dono** — `friendCode`, e o valor concreto dele.
 *
 * A busca é por **valor**, e não só por nome de campo: um `ownerUid` renomeado para `ref` continua
 * carregando o uid, e é o valor que vaza.
 */

/** Colhe todas as strings de um JSON, em qualquer profundidade. */
function stringsIn(value: unknown, out: string[] = []): string[] {
  if (typeof value === 'string') {
    out.push(value);
  } else if (Array.isArray(value)) {
    for (const item of value) stringsIn(item, out);
  } else if (value !== null && typeof value === 'object') {
    for (const item of Object.values(value)) stringsIn(item, out);
  }
  return out;
}

/** Colhe todos os nomes de chave de um JSON, em qualquer profundidade. */
function keysIn(value: unknown, out: string[] = []): string[] {
  if (Array.isArray(value)) {
    for (const item of value) keysIn(item, out);
  } else if (value !== null && typeof value === 'object') {
    for (const [key, item] of Object.entries(value)) {
      out.push(key);
      keysIn(item, out);
    }
  }
  return out;
}

/** Nomes de campo que nenhuma resposta social pode carregar (§7/§8/§10). */
const FORBIDDEN_KEYS = [
  'uid',
  'ownerUid',
  'firebaseUid',
  'authorUid',
  'recipientUid',
  'senderUid',
  'reporterUid',
  'reportedUid',
  'reactorUid',
  'creatorUid',
  'participantUid',
  'blockerUid',
  'blockedUid',
  'email',
  'deviceId',
  'localId',
  'syncId',
  'sessionSyncId',
  'sourceSessionSyncId',
  'storageKey',
  'fcmToken',
  'cloudDataBindingId',
];

describe('T17.10 — varredura de privacidade em todas as superfícies sociais', () => {
  let s: SocialScenario;
  let ids: {
    socialIdA: string;
    socialIdB: string;
    friendCodeA: string;
    friendCodeB: string;
    sessionSyncIdA: string;
    checkInId: string;
    mediaId: string;
    commentId: string;
    shareId: string;
    challengeId: string;
  };

  beforeAll(async () => {
    s = await createSocialScenario();

    const socialIdA = await s.activate(ACCOUNT_A);
    const socialIdB = await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
    await s.makeFriends(ACCOUNT_A, ACCOUNT_B);

    // Atividade e ranking exigem consentimento — e o ranking exige reciprocidade. Sem ligá-los, as
    // duas rotas respondem 403/vazio e **não seriam varridas**: a varredura passaria dizendo que
    // nada vaza por superfícies que ela nunca chegou a ler.
    for (const account of [ACCOUNT_A, ACCOUNT_B]) {
      await request(s.server())
        .patch('/v1/social/me/privacy')
        .set('Authorization', s.auth(account.token))
        .send({
          activitySharingEnabled: true,
          activityTimeZoneId: 'America/Sao_Paulo',
          friendRankingParticipationEnabled: true,
        })
        .expect(200);
    }

    const me = (account: AuditAccount) =>
      request(s.server()).get('/v1/social/me').set('Authorization', s.auth(account.token));
    const friendCodeA = (await me(ACCOUNT_A).expect(200)).body.profile.friendCode as string;
    const friendCodeB = (await me(ACCOUNT_B).expect(200)).body.profile.friendCode as string;

    const sessionSyncIdA = await s.pushSession(ACCOUNT_A);
    const mediaId = await s.uploadPhoto(ACCOUNT_A, sessionSyncIdA, await jpeg());
    const checkInId = await s.publishCheckIn(ACCOUNT_A, sessionSyncIdA, {
      mediaId,
      caption: 'Semana fechada',
    });

    const comment = await request(s.server())
      .post(`/v1/social/workout-checkins/${checkInId}/comments`)
      .set('Authorization', s.auth(ACCOUNT_B.token))
      .send({ body: 'Boa!' })
      .expect(201);

    await request(s.server())
      .put(`/v1/social/workout-checkins/${checkInId}/reaction`)
      .set('Authorization', s.auth(ACCOUNT_B.token))
      .send({ type: 'FIRE' })
      .expect(200);

    const share = await request(s.server())
      .post('/v1/social/workout-shares')
      .set('Authorization', s.auth(ACCOUNT_A.token))
      .send({
        recipientSocialId: socialIdB,
        clientRequestId: uuid(),
        snapshot: {
          snapshotVersion: 1,
          name: 'Treino compartilhado',
          exercises: [
            {
              canonicalExerciseId: 'supino-reto-barra',
              sortOrder: 0,
              targetSets: 3,
              minReps: 8,
              maxReps: 12,
              restDurationSeconds: 90,
            },
          ],
        },
      })
      .expect(201);

    const challenge = await request(s.server())
      .post('/v1/social/challenges')
      .set('Authorization', s.auth(ACCOUNT_A.token))
      .send({
        clientRequestId: uuid(),
        name: 'Semana forte',
        type: 'WORKOUTS_COMPLETED',
        target: 5,
        startDate: '2026-09-14',
        endDate: '2026-09-21',
        timeZoneId: 'America/Sao_Paulo',
        invitedSocialIds: [socialIdB],
      })
      .expect(200);

    ids = {
      socialIdA,
      socialIdB,
      friendCodeA,
      friendCodeB,
      sessionSyncIdA,
      checkInId,
      mediaId,
      commentId: comment.body.commentId as string,
      shareId: share.body.shareId as string,
      challengeId: challenge.body.challenge.challengeId as string,
    };
  }, 60_000);

  afterAll(async () => {
    await s?.close();
  });

  /** Toda superfície de leitura social, vista pelo amigo B — o caso `friend-facing`. */
  const friendFacingSurfaces = (): Array<[string, string]> => [
    ['friends', '/v1/social/friends'],
    ['friend-requests/incoming', '/v1/social/friend-requests/incoming'],
    ['friend-requests/outgoing', '/v1/social/friend-requests/outgoing'],
    ['friend profile', `/v1/social/friends/${ids.socialIdA}/profile`],
    ['challenges', '/v1/social/challenges'],
    ['challenge detail', `/v1/social/challenges/${ids.challengeId}`],
    ['challenge-invitations', '/v1/social/challenge-invitations'],
    ['activity', '/v1/social/activity'],
    ['ranking', '/v1/social/rankings/last-7-days'],
    ['notification preferences', '/v1/social/notifications/preferences'],
    ['workout-shares received', '/v1/social/workout-shares/received'],
    ['workout-shares sent', '/v1/social/workout-shares/sent'],
    ['workout-share detail', `/v1/social/workout-shares/${ids.shareId}`],
    ['feed', '/v1/social/feed'],
    ['check-in detail', `/v1/social/workout-checkins/${ids.checkInId}`],
    ['comments', `/v1/social/workout-checkins/${ids.checkInId}/comments`],
    ['blocks', '/v1/social/blocks'],
  ];

  it('nenhuma superfície social devolve uid, e-mail, syncId de treino ou deviceId (§7/§8/§10)', async () => {
    const offenders: string[] = [];
    // Uma superfície que nunca respondeu 200 é uma superfície que **não foi varrida** — e uma
    // varredura que não varre passa vazia, dizendo o contrário. Um caminho errado nesta lista
    // custou exatamente isso durante esta auditoria: o ranking ficou de fora sem nada falhar.
    const swept = new Set<string>();

    for (const [label, path] of [
      ...friendFacingSurfaces(),
      ['me', '/v1/social/me'] as [string, string],
      ['progress sharing', '/v1/social/me/progress-sharing'] as [string, string],
    ]) {
      for (const account of [ACCOUNT_A, ACCOUNT_B]) {
        const res = await request(s.server()).get(path).set('Authorization', s.auth(account.token));
        if (res.status !== 200) continue;
        swept.add(label);

        const keys = keysIn(res.body);
        for (const forbidden of FORBIDDEN_KEYS) {
          if (keys.some((key) => key.toLowerCase() === forbidden.toLowerCase())) {
            offenders.push(`${label} (${account.name}): campo "${forbidden}"`);
          }
        }

        // Os **valores** privados, procurados em qualquer profundidade.
        const values = stringsIn(res.body);
        const privateValues: Array<[string, string]> = [
          ['uid de A', ACCOUNT_A.uid],
          ['uid de B', ACCOUNT_B.uid],
          ['e-mail de A', ACCOUNT_A.email],
          ['e-mail de B', ACCOUNT_B.email],
          ['sessionSyncId de A', ids.sessionSyncIdA],
        ];
        for (const [what, value] of privateValues) {
          if (values.some((item) => item.includes(value))) {
            offenders.push(`${label} (${account.name}): valor de ${what}`);
          }
        }
      }
    }

    expect(offenders).toEqual([]);

    const neverSwept = [
      ...friendFacingSurfaces().map(([label]) => label),
      'me',
      'progress sharing',
    ].filter((label) => !swept.has(label));
    expect(neverSwept).toEqual([]);
  });

  it('o friendCode só aparece para o dono, e nunca em superfície voltada a amigo (§9)', async () => {
    // O dono vê o próprio, e é a única resposta em que ele aparece.
    const mine = await request(s.server())
      .get('/v1/social/me')
      .set('Authorization', s.auth(ACCOUNT_A.token))
      .expect(200);
    expect(mine.body.profile.friendCode).toBe(ids.friendCodeA);

    const offenders: string[] = [];
    for (const [label, path] of friendFacingSurfaces()) {
      for (const account of [ACCOUNT_A, ACCOUNT_B]) {
        const res = await request(s.server()).get(path).set('Authorization', s.auth(account.token));
        if (res.status !== 200) continue;

        if (keysIn(res.body).some((key) => key.toLowerCase() === 'friendcode')) {
          offenders.push(`${label} (${account.name}): campo friendCode`);
        }
        const values = stringsIn(res.body);
        for (const [owner, code] of [
          ['A', ids.friendCodeA],
          ['B', ids.friendCodeB],
        ] as const) {
          if (values.some((item) => item.includes(code))) {
            offenders.push(`${label} (${account.name}): friendCode de ${owner}`);
          }
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('nada de treino privado atravessa a fronteira social (§39/§160)', async () => {
    const forbiddenWorkoutKeys = [
      'exercises',
      'sets',
      'reps',
      'load',
      'weight',
      'volume',
      'notes',
      'templateId',
      'templateName',
      'workoutName',
      'startedAt',
      'finishedAt',
      'duration',
      'calories',
      'personalRecord',
      'bodyMeasurement',
    ];

    const offenders: string[] = [];
    for (const [label, path] of [
      ['feed', '/v1/social/feed'] as [string, string],
      ['check-in detail', `/v1/social/workout-checkins/${ids.checkInId}`] as [string, string],
      ['friend profile', `/v1/social/friends/${ids.socialIdA}/profile`] as [string, string],
      ['activity', '/v1/social/activity'] as [string, string],
      ['ranking', '/v1/social/rankings/last-7-days'] as [string, string],
      ['challenge detail', `/v1/social/challenges/${ids.challengeId}`] as [string, string],
    ]) {
      const res = await request(s.server()).get(path).set('Authorization', s.auth(ACCOUNT_B.token));
      if (res.status !== 200) continue;
      const keys = keysIn(res.body).map((key) => key.toLowerCase());
      for (const forbidden of forbiddenWorkoutKeys) {
        if (keys.includes(forbidden.toLowerCase())) {
          offenders.push(`${label}: campo "${forbidden}"`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('o snapshot compartilhado só carrega o que é portável (§58/§166)', async () => {
    const detail = await request(s.server())
      .get(`/v1/social/workout-shares/${ids.shareId}`)
      .set('Authorization', s.auth(ACCOUNT_B.token))
      .expect(200);

    // Allowlist, e não blacklist (§11): o que **não** está aqui é vazamento, mesmo com nome novo.
    const snapshot = detail.body.snapshot as Record<string, unknown>;
    const allowedSnapshotKeys = ['exercises', 'name', 'shortIdentifier', 'snapshotVersion'];
    expect(Object.keys(snapshot).filter((key) => !allowedSnapshotKeys.includes(key))).toEqual([]);
    const exercise = (snapshot.exercises as Array<Record<string, unknown>>)[0];
    const allowedExerciseKeys = [
      'canonicalExerciseId',
      'maxReps',
      'minReps',
      'restDurationSeconds',
      'sortOrder',
      'targetSets',
    ];
    expect(Object.keys(exercise).filter((key) => !allowedExerciseKeys.includes(key))).toEqual([]);
    expect(Object.keys(exercise).sort()).toEqual(allowedExerciseKeys.sort());
  });

  it('o DTO do Feed é o contrato inteiro, e nada além dele (§39)', async () => {
    const feed = await request(s.server())
      .get('/v1/social/feed')
      .set('Authorization', s.auth(ACCOUNT_B.token))
      .expect(200);

    const item = feed.body.items[0] as Record<string, unknown>;
    expect(Object.keys(item).sort()).toEqual(
      [
        'author',
        // T17.11 §70 — se **este** viewer pode reagir e comentar. Booleano derivado da política de
        // acesso; não carrega identidade nem dado de treino.
        'canInteract',
        'caption',
        'checkInId',
        'commentCount',
        'currentUserReaction',
        'isCurrentUser',
        'media',
        'publishedAt',
        'reactions',
        'type',
      ].sort(),
    );
    expect(Object.keys(item.author as object).sort()).toEqual(['displayName', 'socialId'].sort());
    // A mídia sai como identificador opaco: nunca bytes, nunca URL, nunca chave de armazenamento.
    expect(Object.keys(item.media as object).sort()).toEqual(['height', 'mediaId', 'width'].sort());
  });

  it('o não-amigo C não obtém nada de A nem de B em nenhuma superfície (§12/§13)', async () => {
    const denied: Array<[string, string]> = [
      ['friend profile', `/v1/social/friends/${ids.socialIdA}/profile`],
      ['check-in detail', `/v1/social/workout-checkins/${ids.checkInId}`],
      ['comments', `/v1/social/workout-checkins/${ids.checkInId}/comments`],
      ['media', `/v1/social/media/${ids.mediaId}`],
      ['workout-share detail', `/v1/social/workout-shares/${ids.shareId}`],
      ['challenge detail', `/v1/social/challenges/${ids.challengeId}`],
    ];

    for (const [label, path] of denied) {
      const res = await request(s.server()).get(path).set('Authorization', s.auth(ACCOUNT_C.token));
      expect([label, res.status]).toEqual([label, 404]);
    }

    // As listagens de C existem, e vêm vazias — sem revelar que A e B existem.
    for (const path of [
      '/v1/social/feed',
      '/v1/social/friends',
      '/v1/social/activity',
      '/v1/social/workout-shares/received',
    ]) {
      const res = await request(s.server())
        .get(path)
        .set('Authorization', s.auth(ACCOUNT_C.token))
        .expect(200);
      const values = stringsIn(res.body);
      expect(values).not.toContain(ids.socialIdA);
      expect(values).not.toContain(ACCOUNT_A.name);
    }
  });
});
