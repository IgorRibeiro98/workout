import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';
const TOKEN_C = 'token-da-conta-c';
const UID_C = 'uid-da-conta-c';

/** Terça-feira, 15h em São Paulo. A mesma semana da fixture `contracts/social/v1/weekly-window.json`. */
const NOW = Date.parse('2026-09-08T18:00:00Z');
const TZ = 'America/Sao_Paulo';

/**
 * O perfil social enriquecido (T17.2): autorização, privacidade e o que nunca sai na resposta.
 *
 * ```text
 * Friendship(A,B) ──▶ SocialAccessPolicy ──▶ projeção ──▶ privacidade ──▶ SocialFriendProfileDto
 * ```
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **amizade é a única porta.** Pedido pendente não abre, terceiro não abre, `unfriend` fecha na
 *    hora, e desativar — dos dois lados — também fecha;
 * 2. **a privacidade é do servidor.** Um campo desligado **não está** no JSON; ele não é escondido
 *    depois. Um cliente modificado receberia exatamente a mesma resposta;
 * 3. **nada de identidade privada ou de treino bruto atravessa.** Nem uid, nem e-mail, nem
 *    `friendCode`, nem sessão, série, carga, medida, nota ou payload.
 *
 * Tudo offline: verificador de token dublê, SQLite em arquivo temporário, sem Firebase e sem VPS.
 */
describe('Perfil social enriquecido', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
        .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' }),
    );
  });

  afterEach(async () => {
    jest.restoreAllMocks();
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();

  const activate = (token: string, displayName: string) =>
    request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', `Bearer ${token}`)
      .send({ displayName });

  const friendProfile = (token: string, socialId: string) =>
    request(server())
      .get(`/v1/social/friends/${socialId}/profile`)
      .set('Authorization', `Bearer ${token}`);

  const preview = (token: string) =>
    request(server()).get('/v1/social/me/profile-preview').set('Authorization', `Bearer ${token}`);

  const sharing = (token: string) =>
    request(server()).get('/v1/social/me/progress-sharing').set('Authorization', `Bearer ${token}`);

  const patchSharing = (token: string, body: object) =>
    request(server())
      .patch('/v1/social/me/progress-sharing')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  /** Ativa A e B e cria a amizade pelo caminho real: pedido + aceite. */
  const becomeFriends = async (): Promise<{ socialIdA: string; socialIdB: string }> => {
    const a = await activate(TOKEN_A, 'Ana');
    const b = await activate(TOKEN_B, 'Igor');

    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', `Bearer ${TOKEN_A}`)
      .send({ socialId: b.body.profile.socialId });

    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', `Bearer ${TOKEN_B}`)
      .expect(200);

    return { socialIdA: a.body.profile.socialId, socialIdB: b.body.profile.socialId };
  };

  /** O push com um payload de sessão em um instante escolhido. */
  const pushSession = (token: string, startedAt: number) => {
    const syncId = uuid();
    return request(server())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${token}`)
      .set('Content-Type', 'application/json')
      .send(
        pushBody([
          {
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            payload: sessionPayload(syncId, { startedAt, finishedAt: startedAt + 3_600_000 }),
          },
        ]),
      );
  };

  const freezeClock = () => jest.spyOn(Date, 'now').mockReturnValue(NOW);

  // ------------------------------------------------------------------ autenticação

  describe('nenhuma rota do perfil é pública', () => {
    const routes: Array<[string, () => request.Test]> = [
      [
        'GET /v1/social/friends/:socialId/profile',
        () => request(server()).get('/v1/social/friends/qualquer/profile'),
      ],
      [
        'GET /v1/social/me/profile-preview',
        () => request(server()).get('/v1/social/me/profile-preview'),
      ],
      [
        'GET /v1/social/me/progress-sharing',
        () => request(server()).get('/v1/social/me/progress-sharing'),
      ],
      [
        'PATCH /v1/social/me/progress-sharing',
        () => request(server()).patch('/v1/social/me/progress-sharing').send({ shareLevel: true }),
      ],
    ];

    it.each(routes)('%s exige Bearer', async (_name, call) => {
      const response = await call();
      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('UNAUTHENTICATED');
    });
  });

  // ------------------------------------------------------------------ autorização A/B/C

  describe('amizade é a única porta', () => {
    it('amigo vê o perfil', async () => {
      const { socialIdB } = await becomeFriends();
      const response = await friendProfile(TOKEN_A, socialIdB);

      expect(response.status).toBe(200);
      expect(response.body.profile.socialId).toBe(socialIdB);
      expect(response.body.profile.displayName).toBe('Igor');
    });

    it('pedido apenas PENDENTE não concede acesso', async () => {
      await activate(TOKEN_A, 'Ana');
      const b = await activate(TOKEN_B, 'Igor');

      await request(server())
        .post('/v1/social/friend-requests')
        .set('Authorization', `Bearer ${TOKEN_A}`)
        .send({ socialId: b.body.profile.socialId })
        .expect(200);

      const response = await friendProfile(TOKEN_A, b.body.profile.socialId);
      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');
    });

    it('conta C, que só conhece o socialId de B, não vê nada', async () => {
      const { socialIdB } = await becomeFriends();
      await activate(TOKEN_C, 'Carla');

      const response = await friendProfile(TOKEN_C, socialIdB);
      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');
    });

    it('desfazer a amizade revoga o acesso na requisição seguinte', async () => {
      const { socialIdA, socialIdB } = await becomeFriends();
      expect((await friendProfile(TOKEN_A, socialIdB)).status).toBe(200);

      await request(server())
        .post('/v1/social/friends/remove')
        .set('Authorization', `Bearer ${TOKEN_B}`)
        .send({ socialId: socialIdA })
        .expect(200);

      const response = await friendProfile(TOKEN_A, socialIdB);
      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');
    });

    it('alvo com Social desativado responde como se não existisse — e volta ao reativar', async () => {
      const { socialIdB } = await becomeFriends();

      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', `Bearer ${TOKEN_B}`)
        .expect(200);

      expect((await friendProfile(TOKEN_A, socialIdB)).body.error.code).toBe(
        'FRIEND_PROFILE_NOT_FOUND',
      );

      await request(server())
        .post('/v1/social/me/enable')
        .set('Authorization', `Bearer ${TOKEN_B}`)
        .expect(200);

      // A amizade continuava gravada: desativar suspende, não desfaz (política da T17.1).
      expect((await friendProfile(TOKEN_A, socialIdB)).status).toBe(200);
    });

    it('visitante com Social desativado não consome perfil nenhum', async () => {
      const { socialIdB } = await becomeFriends();

      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', `Bearer ${TOKEN_A}`)
        .expect(200);

      const response = await friendProfile(TOKEN_A, socialIdB);
      expect(response.status).toBe(409);
      expect(response.body.error.code).toBe('SOCIAL_PROFILE_DISABLED');
      expect((await preview(TOKEN_A)).status).toBe(409);
    });

    it('conta sem perfil social recebe SOCIAL_NOT_ENABLED', async () => {
      const { socialIdB } = await becomeFriends();
      const response = await friendProfile(TOKEN_C, socialIdB);

      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });

    it('socialId inexistente e socialId malformado dão a mesma resposta', async () => {
      await activate(TOKEN_A, 'Ana');

      const inexistente = await friendProfile(TOKEN_A, '11111111-2222-3333-4444-555555555555');
      const malformado = await friendProfile(TOKEN_A, '%20');

      expect(inexistente.status).toBe(404);
      expect(inexistente.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');
      expect(malformado.status).toBe(404);
      expect(malformado.body.error.code).toBe('FRIEND_PROFILE_NOT_FOUND');
    });

    it('o próprio perfil sai por /me/profile-preview, e não fingindo amizade consigo mesmo', async () => {
      const a = await activate(TOKEN_A, 'Ana');
      const socialIdA = a.body.profile.socialId;

      expect((await friendProfile(TOKEN_A, socialIdA)).status).toBe(404);
      expect((await preview(TOKEN_A)).status).toBe(200);
      expect((await preview(TOKEN_A)).body.profile.socialId).toBe(socialIdA);
    });
  });

  // ------------------------------------------------------------------ privacidade

  describe('privacidade: defaults, efeito imediato e ausência de vestígio', () => {
    it('os quatro interruptores nascem desligados', async () => {
      await activate(TOKEN_A, 'Ana');
      const response = await sharing(TOKEN_A);

      expect(response.status).toBe(200);
      expect(response.body.settings).toMatchObject({
        shareLevel: false,
        shareConsistencyStreak: false,
        shareWeeklyWorkoutCount: false,
        shareHighlightedAchievements: false,
        weekTimeZone: null,
      });
    });

    it('com tudo desligado, o amigo recebe um objeto vazio — e nenhuma flag de privacidade', async () => {
      const { socialIdB } = await becomeFriends();
      const response = await friendProfile(TOKEN_A, socialIdB);

      expect(response.body.profile.sharedProgress).toEqual({});
      // Nenhum vestígio da configuração: `sharedProgress` é o contêiner, e ele está vazio.
      for (const flag of [
        'shareLevel',
        'shareConsistencyStreak',
        'shareWeeklyWorkoutCount',
        'shareHighlightedAchievements',
      ]) {
        expect(JSON.stringify(response.body)).not.toContain(flag);
      }
      expect(response.body.profile).not.toHaveProperty('privacy');
    });

    it('ligado com dado disponível aparece; desligado desaparece na leitura seguinte', async () => {
      const { socialIdB } = await becomeFriends();
      await pushSession(TOKEN_B, Date.parse('2026-09-07T09:00:00Z'));
      await pushSession(TOKEN_B, Date.parse('2026-09-08T22:00:00Z'));

      freezeClock();
      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);

      expect((await friendProfile(TOKEN_A, socialIdB)).body.profile.sharedProgress).toEqual({
        weeklyWorkoutCount: 2,
      });

      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: false }).expect(200);

      // Efeito imediato: não há cache de perfil, então não há cache a invalidar.
      expect((await friendProfile(TOKEN_A, socialIdB)).body.profile.sharedProgress).toEqual({});
    });

    it('ligado sem dado não publica zero', async () => {
      const { socialIdB } = await becomeFriends();
      freezeClock();
      // B nunca sincronizou nada: o servidor não sabe se são zero treinos ou zero sincronizações.
      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);

      const response = await friendProfile(TOKEN_A, socialIdB);
      expect(response.body.profile.sharedProgress).toEqual({});
      expect(JSON.stringify(response.body)).not.toContain('weeklyWorkoutCount');
    });

    it('nível e sequência ligados não aparecem — não há autoridade remota nesta versão', async () => {
      const { socialIdB } = await becomeFriends();
      await pushSession(TOKEN_B, Date.parse('2026-09-08T12:00:00Z'));
      freezeClock();

      await patchSharing(TOKEN_B, {
        shareLevel: true,
        shareConsistencyStreak: true,
        shareHighlightedAchievements: true,
        weekTimeZone: TZ,
      }).expect(200);

      const response = await friendProfile(TOKEN_A, socialIdB);
      expect(response.body.profile.sharedProgress).toEqual({});

      // E o dono sabe **por quê**: a disponibilidade distingue "ligado sem dado" de "esta versão
      // não sabe", enquanto o amigo não distingue nada (§37/§38).
      const owner = await sharing(TOKEN_B);
      expect(owner.body.availability).toEqual({
        level: 'UNSUPPORTED',
        consistencyStreak: 'UNSUPPORTED',
        weeklyWorkoutCount: 'AVAILABLE',
        highlightedAchievements: 'UNSUPPORTED',
      });
      expect(owner.body.settings.shareLevel).toBe(true);
    });

    it('sem fuso declarado a contagem semanal fica indisponível para o dono', async () => {
      await activate(TOKEN_B, 'Igor');
      await pushSession(TOKEN_B, Date.parse('2026-09-08T12:00:00Z'));
      freezeClock();

      expect((await sharing(TOKEN_B)).body.availability.weeklyWorkoutCount).toBe('UNAVAILABLE');

      await patchSharing(TOKEN_B, { weekTimeZone: TZ }).expect(200);
      expect((await sharing(TOKEN_B)).body.availability.weeklyWorkoutCount).toBe('AVAILABLE');
    });

    it('a prévia do dono é exatamente o que o amigo vê', async () => {
      const { socialIdB } = await becomeFriends();
      await pushSession(TOKEN_B, Date.parse('2026-09-08T12:00:00Z'));
      freezeClock();
      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);

      const asFriend = await friendProfile(TOKEN_A, socialIdB);
      const asOwner = await preview(TOKEN_B);

      expect(asOwner.body).toEqual(asFriend.body);
    });
  });

  // ------------------------------------------------------------------ escrita de preferências

  describe('o cliente envia preferência, nunca progresso', () => {
    beforeEach(async () => {
      await activate(TOKEN_A, 'Ana');
    });

    it.each([
      ['level', { level: 14 }],
      ['streak', { streak: 4 }],
      ['consistencyStreak', { consistencyStreak: 4 }],
      ['weeklyWorkoutCount', { weeklyWorkoutCount: 3 }],
      ['totalXp', { totalXp: 9000 }],
      ['earnedAchievementIds', { earnedAchievementIds: ['100_workouts'] }],
      ['highlightedAchievementIds', { highlightedAchievementIds: ['100_workouts'] }],
    ])('%s no corpo recusa a requisição inteira', async (_name, body) => {
      const response = await patchSharing(TOKEN_A, { shareLevel: true, ...body });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
      // E nada foi gravado: a recusa é da requisição inteira, não do campo.
      expect((await sharing(TOKEN_A)).body.settings.shareLevel).toBe(false);
    });

    it.each(['ownerUid', 'uid', 'firebaseUid', 'socialId', 'friendCode', 'email'])(
      '%s no corpo recusa a requisição inteira',
      async (field) => {
        const response = await patchSharing(TOKEN_A, { shareLevel: true, [field]: 'x' });
        expect(response.status).toBe(400);
        expect(response.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
      },
    );

    it('o PATCH é parcial: o que não veio não muda', async () => {
      await patchSharing(TOKEN_A, {
        shareLevel: true,
        shareWeeklyWorkoutCount: true,
        weekTimeZone: TZ,
      }).expect(200);

      const response = await patchSharing(TOKEN_A, { shareLevel: false });

      expect(response.body.settings).toMatchObject({
        shareLevel: false,
        shareWeeklyWorkoutCount: true,
        weekTimeZone: TZ,
      });
    });

    it('corpo vazio é recusado', async () => {
      const response = await patchSharing(TOKEN_A, {});
      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
    });

    it('fuso inválido é recusado, e não vira UTC', async () => {
      const response = await patchSharing(TOKEN_A, { weekTimeZone: 'Terra/Media' });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
      expect((await sharing(TOKEN_A)).body.settings.weekTimeZone).toBeNull();
    });

    it('campo desconhecido é recusado', async () => {
      const response = await patchSharing(TOKEN_A, { shareEverything: true });
      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_PROGRESS_SETTINGS');
    });

    it('updatedAt vem do relógio do servidor', async () => {
      freezeClock();
      const response = await patchSharing(TOKEN_A, { shareLevel: true });
      expect(response.body.settings.updatedAt).toBe(NOW);
    });

    it('conta sem perfil social não configura compartilhamento', async () => {
      const response = await patchSharing(TOKEN_C, { shareLevel: true });
      expect(response.status).toBe(404);
      expect(response.body.error.code).toBe('SOCIAL_NOT_ENABLED');
    });
  });

  // ------------------------------------------------------------------ o que nunca sai

  describe('o que a resposta do amigo nunca carrega', () => {
    it('nenhuma identidade privada, nenhum dado de treino bruto', async () => {
      const { socialIdB } = await becomeFriends();
      await pushSession(TOKEN_B, Date.parse('2026-09-08T12:00:00Z'));
      freezeClock();
      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);

      const response = await friendProfile(TOKEN_A, socialIdB);
      const body = JSON.stringify(response.body);

      for (const forbidden of [
        UID_A,
        UID_B,
        'ownerUid',
        'owner_uid',
        'firebaseUid',
        'b@example.com',
        'email',
        'friendCode',
        'SPK-',
        // treino bruto: a sessão empurrada acima carrega tudo isto, e nada disso atravessa
        'Treino A',
        'Supino reto',
        'supino-reto-barra',
        'sets',
        'setNumber',
        'weight',
        'repetitions',
        'startedAt',
        'finishedAt',
        'notes',
        'payload',
        'syncId',
        'entitySyncId',
        'serverRevision',
        'backup',
        'weightKg',
        'measurement',
        'lastSyncAt',
        // e nenhuma pista da configuração de privacidade
        'shareLevel',
        'availability',
        'UNAVAILABLE',
        'UNSUPPORTED',
      ]) {
        expect({ forbidden, present: body.includes(forbidden) }).toEqual({
          forbidden,
          present: false,
        });
      }

      // O que ele carrega, e nada mais.
      expect(Object.keys(response.body)).toEqual(['profile']);
      expect(Object.keys(response.body.profile).sort()).toEqual([
        'displayName',
        'sharedProgress',
        'socialId',
      ]);
    });

    it('o lookup por friendCode continua mínimo — sem progresso', async () => {
      const b = await activate(TOKEN_B, 'Igor');
      await activate(TOKEN_A, 'Ana');
      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);

      const response = await request(server())
        .post('/v1/social/friends/lookup')
        .set('Authorization', `Bearer ${TOKEN_A}`)
        .send({ friendCode: b.body.profile.friendCode });

      expect(response.status).toBe(200);
      expect(Object.keys(response.body.profile).sort()).toEqual(['displayName', 'socialId']);
      expect(JSON.stringify(response.body)).not.toContain('sharedProgress');
      expect(JSON.stringify(response.body)).not.toContain('weeklyWorkoutCount');
    });

    it('a lista de amigos continua leve — nenhum progresso nela', async () => {
      const { socialIdB } = await becomeFriends();
      await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);

      const response = await request(server())
        .get('/v1/social/friends')
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(response.body.friends).toHaveLength(1);
      expect(Object.keys(response.body.friends[0]).sort()).toEqual([
        'displayName',
        'friendsSince',
        'socialId',
      ]);
      expect(socialIdB).toBe(response.body.friends[0].socialId);
    });

    it('não existe rota de perfil em lote', async () => {
      const { socialIdB } = await becomeFriends();
      await friendProfile(TOKEN_A, socialIdB).expect(200);

      const batch = await request(server())
        .get('/v1/social/friends/profiles')
        .set('Authorization', `Bearer ${TOKEN_A}`);
      // A rota não existe: `profiles` cai no `:socialId` e responde "não encontrado".
      expect(batch.status).toBe(404);
      expect(batch.body.error.code).not.toBe('FRIEND_PROFILE_NOT_FOUND');
    });
  });

  // ------------------------------------------------------------------ durabilidade

  it('preferências sobrevivem a reiniciar o servidor', async () => {
    await activate(TOKEN_A, 'Ana');
    await patchSharing(TOKEN_A, { shareLevel: true, weekTimeZone: TZ }).expect(200);
    const before = await request(server())
      .get('/v1/social/me')
      .set('Authorization', `Bearer ${TOKEN_A}`);

    await app.close();
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier().accept(TOKEN_A, { uid: UID_A }),
    );

    const after = await sharing(TOKEN_A);
    expect(after.body.settings).toMatchObject({ shareLevel: true, weekTimeZone: TZ });
    // E a identidade continua a mesma: nada da T17.2 a recria.
    const profile = await request(server())
      .get('/v1/social/me')
      .set('Authorization', `Bearer ${TOKEN_A}`);
    expect(profile.body.profile.socialId).toBe(before.body.profile.socialId);
    expect(profile.body.profile.friendCode).toBe(before.body.profile.friendCode);
  });

  it('ver perfil e alterar privacidade não geram gamificação nem escrita de treino', async () => {
    const { socialIdB } = await becomeFriends();
    await pushSession(TOKEN_B, Date.parse('2026-09-08T12:00:00Z'));

    const stateBefore = await request(server())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_B}`);

    freezeClock();
    await patchSharing(TOKEN_B, { shareWeeklyWorkoutCount: true, weekTimeZone: TZ }).expect(200);
    await friendProfile(TOKEN_A, socialIdB).expect(200);
    await preview(TOKEN_B).expect(200);

    const stateAfter = await request(server())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN_B}`);

    // Nenhuma mudança nova no change log: o Social não escreve no domínio de treino, e não tem
    // como — ele não alcança `SyncRepository`. Ler um perfil também não move nada.
    expect(stateAfter.body).toEqual(stateBefore.body);
  });
});
