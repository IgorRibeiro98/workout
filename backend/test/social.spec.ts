import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { SOCIAL_DISPLAY_NAME } from '../src/modules/social/social.limits';
import { SOCIAL_PRIVACY_DEFAULTS } from '../src/modules/social/social.service';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';

/**
 * A API do domínio social (T17.0).
 *
 * O que estes testes protegem, acima de tudo:
 *
 * 1. **login não ativa Social.** Autenticar produz uma Conta Spark e nada mais — nenhuma linha é
 *    criada por ler `/v1/social/me`;
 * 2. **a identidade é do servidor.** `socialId` e `friendCode` nunca vêm do cliente, e nunca
 *    mudam depois de criados;
 * 3. **a conta A não alcança a conta B.** Nem por parâmetro, nem por corpo, nem por acidente.
 *
 * Auth é dublê (`FakeAuthTokenVerifier`): nenhum teste toca Firebase, rede ou VPS.
 */
describe('Domínio social: identidade, ativação e privacidade', () => {
  let temp: TempDb;
  let app: INestApplication;

  beforeEach(async () => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' }),
    );
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();

  const me = (token: string) =>
    request(server()).get('/v1/social/me').set('Authorization', `Bearer ${token}`);

  const activate = (token: string, body: object = { displayName: 'Igor' }) =>
    request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  const patchProfile = (token: string, body: object) =>
    request(server()).patch('/v1/social/me').set('Authorization', `Bearer ${token}`).send(body);

  const patchPrivacy = (token: string, body: object) =>
    request(server())
      .patch('/v1/social/me/privacy')
      .set('Authorization', `Bearer ${token}`)
      .send(body);

  const disable = (token: string) =>
    request(server()).post('/v1/social/me/disable').set('Authorization', `Bearer ${token}`);

  const enable = (token: string) =>
    request(server()).post('/v1/social/me/enable').set('Authorization', `Bearer ${token}`);

  // ------------------------------------------------------------------ autenticação obrigatória

  describe('nenhuma rota social é pública', () => {
    const routes: Array<[string, () => request.Test]> = [
      ['GET /v1/social/me', () => request(server()).get('/v1/social/me')],
      [
        'POST /v1/social/me/activate',
        () => request(server()).post('/v1/social/me/activate').send({ displayName: 'Igor' }),
      ],
      ['PATCH /v1/social/me', () => request(server()).patch('/v1/social/me').send({})],
      [
        'PATCH /v1/social/me/privacy',
        () => request(server()).patch('/v1/social/me/privacy').send({}),
      ],
      ['POST /v1/social/me/disable', () => request(server()).post('/v1/social/me/disable')],
      ['POST /v1/social/me/enable', () => request(server()).post('/v1/social/me/enable')],
    ];

    it.each(routes)('%s exige Firebase ID Token', async (_name, call) => {
      const response = await call();
      expect(response.status).toBe(401);
      expect(response.body.error.code).toBe('UNAUTHENTICATED');
    });

    it('token desconhecido não vira identidade', async () => {
      const response = await me('token-que-nao-existe');
      expect(response.status).toBe(401);
    });
  });

  // ------------------------------------------------------------------ social não é automático

  describe('login não cria perfil social', () => {
    it('conta autenticada sem Social responde enabled:false, e não 404', async () => {
      const response = await me(TOKEN_A);

      expect(response.status).toBe(200);
      expect(response.body).toEqual({ enabled: false });
    });

    it('ler /me repetidamente não cria linha nenhuma', async () => {
      await me(TOKEN_A);
      await me(TOKEN_A);
      await me(TOKEN_A);

      // A prova é a ausência: se `me` criasse preguiçosamente, a leitura seguinte veria o perfil.
      expect((await me(TOKEN_A)).body).toEqual({ enabled: false });
    });

    it('alterar sem ter ativado é SOCIAL_NOT_ENABLED', async () => {
      const responses = [
        await patchProfile(TOKEN_A, { displayName: 'Igor' }),
        await patchPrivacy(TOKEN_A, { activitySharingEnabled: true }),
        await disable(TOKEN_A),
        await enable(TOKEN_A),
      ];

      for (const response of responses) {
        expect(response.status).toBe(404);
        expect(response.body.error.code).toBe('SOCIAL_NOT_ENABLED');
      }
    });
  });

  // ------------------------------------------------------------------ ativação

  describe('ativação explícita', () => {
    it('cria socialId, friendCode e privacidade padrão', async () => {
      const response = await activate(TOKEN_A, { displayName: 'Igor' });

      expect(response.status).toBe(200);
      const profile = response.body.profile;
      expect(profile.displayName).toBe('Igor');
      expect(profile.status).toBe('ACTIVE');
      expect(profile.socialId).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
      expect(profile.friendCode).toMatch(/^SPK-[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/);
      expect(profile.privacy).toEqual({
        discoverability: 'FRIEND_CODE_ONLY',
        friendRequestsEnabled: true,
        activitySharingEnabled: false,
        updatedAt: expect.any(Number),
      });
      expect(profile.privacy.discoverability).toBe(SOCIAL_PRIVACY_DEFAULTS.discoverability);
      expect(profile.privacy.activitySharingEnabled).toBe(false);
    });

    it('os timestamps são do servidor', async () => {
      const before = Date.now();
      const { body } = await activate(TOKEN_A);
      const after = Date.now();

      expect(body.profile.createdAt).toBeGreaterThanOrEqual(before);
      expect(body.profile.createdAt).toBeLessThanOrEqual(after);
      expect(body.profile.updatedAt).toBe(body.profile.createdAt);
    });

    it('o perfil ativado aparece em /me', async () => {
      const created = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;
      const read = await me(TOKEN_A);

      expect(read.status).toBe(200);
      expect(read.body).toEqual({ enabled: true, profile: created });
    });

    it('ativar duas vezes não cria dois perfis', async () => {
      const first = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;
      const second = await activate(TOKEN_A, { displayName: 'Outro Nome' });

      expect(second.status).toBe(200);
      // Identidade e nome preservados: ativar é "garanta que eu tenho identidade", não "renomeie".
      expect(second.body.profile).toEqual(first);
    });

    it('duas ativações simultâneas convergem para um perfil', async () => {
      // Uma porta de verdade: sem `listen`, o supertest sobe um servidor efêmero **por
      // requisição**, e duas requisições paralelas deixariam de ser paralelas contra o mesmo
      // processo — que é justamente o que este teste precisa exercitar.
      await app.listen(0);

      const [first, second] = await Promise.all([
        activate(TOKEN_A, { displayName: 'Igor' }),
        activate(TOKEN_A, { displayName: 'Igor' }),
      ]);

      expect(first.status).toBe(200);
      expect(second.status).toBe(200);
      expect(first.body.profile.socialId).toBe(second.body.profile.socialId);
      expect(first.body.profile.friendCode).toBe(second.body.profile.friendCode);
    });

    it('duas contas recebem identidades diferentes', async () => {
      const a = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;
      const b = (await activate(TOKEN_B, { displayName: 'Igor' })).body.profile;

      expect(a.socialId).not.toBe(b.socialId);
      expect(a.friendCode).not.toBe(b.friendCode);
      // Nome igual é permitido: a identidade única é o socialId, não o nome exibido.
      expect(a.displayName).toBe(b.displayName);
    });
  });

  // ------------------------------------------------------------------ o cliente não escolhe ids

  describe('identidade não vem do cliente', () => {
    const forbidden: Array<[string, Record<string, unknown>]> = [
      ['ownerUid', { displayName: 'Igor', ownerUid: UID_B }],
      ['uid', { displayName: 'Igor', uid: UID_B }],
      ['socialId', { displayName: 'Igor', socialId: '00000000-0000-4000-8000-000000000000' }],
      ['friendCode', { displayName: 'Igor', friendCode: 'SPK-AAAAAAAA' }],
      ['status', { displayName: 'Igor', status: 'ACTIVE' }],
      ['createdAt', { displayName: 'Igor', createdAt: 0 }],
      ['email', { displayName: 'Igor', email: 'a@example.com' }],
    ];

    it.each(forbidden)('activate recusa %s no corpo', async (_field, body) => {
      const response = await activate(TOKEN_A, body);

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
      // E não criou nada pela metade.
      expect((await me(TOKEN_A)).body).toEqual({ enabled: false });
    });

    it('PATCH não altera socialId nem friendCode', async () => {
      const created = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;

      const attempt = await patchProfile(TOKEN_A, {
        displayName: 'Igor',
        socialId: '00000000-0000-4000-8000-000000000000',
      });
      expect(attempt.status).toBe(400);

      const after = (await me(TOKEN_A)).body.profile;
      expect(after.socialId).toBe(created.socialId);
      expect(after.friendCode).toBe(created.friendCode);
    });

    it('campo desconhecido recusa a requisição em vez de ser ignorado', async () => {
      const response = await activate(TOKEN_A, { displayName: 'Igor', isAdmin: true });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
    });
  });

  // ------------------------------------------------------------------ nome social

  describe('validação do nome social', () => {
    const invalid: Array<[string, unknown]> = [
      ['vazio', ''],
      ['só espaços', '   '],
      ['curto demais', 'I'],
      ['longo demais', 'x'.repeat(SOCIAL_DISPLAY_NAME.maxLength + 1)],
      ['com quebra de linha', 'Igor\nAdmin'],
      ['com tab', 'Igor\tAdmin'],
      ['com caractere de controle', 'Igor\u0007Admin'],
      ['com marca de direção', 'Igor\u202EAdmin'],
      ['número', 42],
      ['ausente', undefined],
      ['nulo', null],
    ];

    it.each(invalid)('recusa nome %s', async (_name, displayName) => {
      const response = await activate(TOKEN_A, displayName === undefined ? {} : { displayName });

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_DISPLAY_NAME');
    });

    const valid: Array<[string, string, string]> = [
      ['acento', 'João Neto', 'João Neto'],
      ['espaços nas pontas', '  Igor  ', 'Igor'],
      ['emoji', '💪 Igor', '💪 Igor'],
      ['não latino', 'イゴール', 'イゴール'],
      [
        'no limite',
        'x'.repeat(SOCIAL_DISPLAY_NAME.maxLength),
        'x'.repeat(SOCIAL_DISPLAY_NAME.maxLength),
      ],
    ];

    it.each(valid)('aceita nome com %s', async (_name, input, expected) => {
      const response = await activate(TOKEN_A, { displayName: input });

      expect(response.status).toBe(200);
      expect(response.body.profile.displayName).toBe(expected);
    });

    it('emoji conta como um caractere, e não como duas unidades UTF-16', async () => {
      // 40 emojis = 40 code points = 80 unidades UTF-16. Contar unidades recusaria este nome.
      const response = await activate(TOKEN_A, {
        displayName: '💪'.repeat(SOCIAL_DISPLAY_NAME.maxLength),
      });

      expect(response.status).toBe(200);
    });

    it('renomear preserva a identidade', async () => {
      const created = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;

      const renamed = await patchProfile(TOKEN_A, { displayName: 'Igor Ribeiro' });

      expect(renamed.status).toBe(200);
      expect(renamed.body.profile.displayName).toBe('Igor Ribeiro');
      expect(renamed.body.profile.socialId).toBe(created.socialId);
      expect(renamed.body.profile.friendCode).toBe(created.friendCode);
      expect(renamed.body.profile.createdAt).toBe(created.createdAt);
      expect((await me(TOKEN_A)).body.profile.displayName).toBe('Igor Ribeiro');
    });
  });

  // ------------------------------------------------------------------ privacidade

  describe('privacidade', () => {
    beforeEach(async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });
    });

    it('atualiza só o que foi enviado', async () => {
      const response = await patchPrivacy(TOKEN_A, { friendRequestsEnabled: false });

      expect(response.status).toBe(200);
      expect(response.body.profile.privacy).toMatchObject({
        discoverability: 'FRIEND_CODE_ONLY',
        friendRequestsEnabled: false,
        activitySharingEnabled: false,
      });
    });

    it('activity sharing pode ser ligado explicitamente e persiste', async () => {
      await patchPrivacy(TOKEN_A, { activitySharingEnabled: true });

      expect((await me(TOKEN_A)).body.profile.privacy.activitySharingEnabled).toBe(true);
    });

    it('discoverability aceita apenas FRIEND_CODE_ONLY', async () => {
      const accepted = await patchPrivacy(TOKEN_A, { discoverability: 'FRIEND_CODE_ONLY' });
      expect(accepted.status).toBe(200);

      for (const value of ['PUBLIC_SEARCH', 'GLOBAL_PROFILE', 'EVERYONE', '']) {
        const rejected = await patchPrivacy(TOKEN_A, { discoverability: value });
        expect(rejected.status).toBe(400);
        expect(rejected.body.error.code).toBe('INVALID_SOCIAL_REQUEST');
      }

      expect((await me(TOKEN_A)).body.profile.privacy.discoverability).toBe('FRIEND_CODE_ONLY');
    });

    it('recusa corpo vazio e valor de tipo errado', async () => {
      expect((await patchPrivacy(TOKEN_A, {})).status).toBe(400);
      expect((await patchPrivacy(TOKEN_A, { friendRequestsEnabled: 'sim' })).status).toBe(400);
    });

    it('alterar privacidade não mexe na identidade', async () => {
      const before = (await me(TOKEN_A)).body.profile;

      await patchPrivacy(TOKEN_A, { activitySharingEnabled: true });

      const after = (await me(TOKEN_A)).body.profile;
      expect(after.socialId).toBe(before.socialId);
      expect(after.friendCode).toBe(before.friendCode);
    });
  });

  // ------------------------------------------------------------------ desativar / reativar

  describe('desativação e reativação', () => {
    it('desativar preserva o perfil e a identidade', async () => {
      const created = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;

      const disabled = await disable(TOKEN_A);

      expect(disabled.status).toBe(200);
      expect(disabled.body.profile.status).toBe('DISABLED');
      expect(disabled.body.profile.socialId).toBe(created.socialId);
      expect(disabled.body.profile.friendCode).toBe(created.friendCode);

      // Continua legível pelo dono: desativado não é apagado.
      const read = await me(TOKEN_A);
      expect(read.body.enabled).toBe(true);
      expect(read.body.profile.status).toBe('DISABLED');
    });

    it('reativar devolve o mesmo socialId e o mesmo friendCode', async () => {
      const created = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;
      await disable(TOKEN_A);

      const enabled = await enable(TOKEN_A);

      expect(enabled.status).toBe(200);
      expect(enabled.body.profile.status).toBe('ACTIVE');
      expect(enabled.body.profile.socialId).toBe(created.socialId);
      expect(enabled.body.profile.friendCode).toBe(created.friendCode);
      expect(enabled.body.profile.displayName).toBe(created.displayName);
      expect(enabled.body.profile.createdAt).toBe(created.createdAt);
    });

    it('vários ciclos de desativar/reativar não geram identidade nova', async () => {
      const created = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;

      for (let i = 0; i < 3; i += 1) {
        await disable(TOKEN_A);
        await enable(TOKEN_A);
      }

      const final = (await me(TOKEN_A)).body.profile;
      expect(final.socialId).toBe(created.socialId);
      expect(final.friendCode).toBe(created.friendCode);
    });

    it('ativar de novo não religa em silêncio um perfil desativado', async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });
      await disable(TOKEN_A);

      const reactivated = await activate(TOKEN_A, { displayName: 'Igor' });

      expect(reactivated.status).toBe(200);
      expect(reactivated.body.profile.status).toBe('DISABLED');
    });

    it('enable sobre perfil ativo é conflito explícito', async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });

      const response = await enable(TOKEN_A);

      expect(response.status).toBe(409);
      expect(response.body.error.code).toBe('SOCIAL_ALREADY_ENABLED');
    });

    it('disable sobre perfil já desativado é conflito explícito', async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });
      await disable(TOKEN_A);

      const response = await disable(TOKEN_A);

      expect(response.status).toBe(409);
      expect(response.body.error.code).toBe('SOCIAL_ALREADY_DISABLED');
    });

    it('perfil desativado continua editável pelo dono', async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });
      await disable(TOKEN_A);

      const renamed = await patchProfile(TOKEN_A, { displayName: 'Igor Ribeiro' });

      expect(renamed.status).toBe(200);
      expect(renamed.body.profile.status).toBe('DISABLED');
    });
  });

  // ------------------------------------------------------------------ isolamento entre contas

  describe('a conta A nunca lê a conta B', () => {
    it('cada conta recebe o próprio perfil', async () => {
      const a = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;
      const b = (await activate(TOKEN_B, { displayName: 'Jonathas' })).body.profile;

      const readA = (await me(TOKEN_A)).body.profile;
      const readB = (await me(TOKEN_B)).body.profile;

      expect(readA.socialId).toBe(a.socialId);
      expect(readB.socialId).toBe(b.socialId);
      expect(readA.socialId).not.toBe(readB.socialId);
      expect(readA.displayName).toBe('Igor');
      expect(readB.displayName).toBe('Jonathas');
    });

    it('parâmetro de uid na query é ignorado — a identidade é a do token', async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });
      await activate(TOKEN_B, { displayName: 'Jonathas' });

      const response = await request(server())
        .get(`/v1/social/me?uid=${UID_B}&ownerUid=${UID_B}&socialId=qualquer`)
        .set('Authorization', `Bearer ${TOKEN_A}`);

      expect(response.status).toBe(200);
      expect(response.body.profile.displayName).toBe('Igor');
    });

    it('B alterar o próprio perfil não toca em A', async () => {
      const a = (await activate(TOKEN_A, { displayName: 'Igor' })).body.profile;
      await activate(TOKEN_B, { displayName: 'Jonathas' });

      await patchProfile(TOKEN_B, { displayName: 'Outro' });
      await patchPrivacy(TOKEN_B, { activitySharingEnabled: true });
      await disable(TOKEN_B);

      const readA = (await me(TOKEN_A)).body.profile;
      expect(readA).toEqual(a);
    });

    it('A desativar não desativa B', async () => {
      await activate(TOKEN_A, { displayName: 'Igor' });
      await activate(TOKEN_B, { displayName: 'Jonathas' });

      await disable(TOKEN_A);

      expect((await me(TOKEN_B)).body.profile.status).toBe('ACTIVE');
    });
  });

  // ------------------------------------------------------------------ o que o DTO não carrega

  describe('o DTO do dono não expõe identidade privada', () => {
    it('nenhuma resposta social carrega uid, ownerUid ou email', async () => {
      const bodies = [
        (await activate(TOKEN_A, { displayName: 'Igor' })).body,
        (await me(TOKEN_A)).body,
        (await patchProfile(TOKEN_A, { displayName: 'Igor R' })).body,
        (await patchPrivacy(TOKEN_A, { friendRequestsEnabled: false })).body,
        (await disable(TOKEN_A)).body,
        (await enable(TOKEN_A)).body,
      ];

      for (const body of bodies) {
        const text = JSON.stringify(body);
        expect(text).not.toContain(UID_A);
        expect(text).not.toContain('a@example.com');
        for (const forbidden of ['ownerUid', 'owner_uid', 'firebaseUid', 'email', '"uid"']) {
          expect(text).not.toContain(forbidden);
        }
      }
    });

    it('o perfil do dono tem exatamente os campos do contrato', async () => {
      const { body } = await activate(TOKEN_A, { displayName: 'Igor' });

      expect(Object.keys(body.profile).sort()).toEqual([
        'createdAt',
        'displayName',
        'friendCode',
        'privacy',
        'socialId',
        'status',
        'updatedAt',
      ]);
      expect(Object.keys(body.profile.privacy).sort()).toEqual([
        'activitySharingEnabled',
        'discoverability',
        'friendRequestsEnabled',
        'updatedAt',
      ]);
    });
  });

  // ------------------------------------------------------------------ o que ainda não existe

  /**
   * A T17.0 não abria descoberta nenhuma. A T17.1 abriu **uma**: lookup por `friendCode` exato,
   * em `POST /v1/social/friends/lookup`, com teto próprio de rate limit — e o comportamento dela
   * é testado em `friendship.spec.ts`.
   *
   * O que continua não existindo é o que este bloco protege, e ele é a parte que **nunca** pode
   * nascer: busca por nome, busca por e-mail, listagem global de perfis e qualquer rota que
   * devolva gente que o chamador não nomeou. `GET /v1/social/friends` agora existe e devolve
   * **os amigos de quem perguntou** — nunca perfis alheios —, então ele saiu desta lista e ganhou
   * cobertura própria.
   */
  describe('a descoberta continua sendo só por código exato', () => {
    const absent = [
      '/v1/social/users',
      '/v1/social/profiles',
      '/v1/social/lookup?friendCode=SPK-AAAAAAAA',
      '/v1/social/users?friendCode=SPK-AAAAAAAA',
      '/v1/social/users?q=igor',
      '/v1/social/users?email=a@example.com',
      '/v1/social/friends/search?q=igor',
      '/v1/social/friends/suggestions',
    ];

    it.each(absent)('%s não existe', async (path) => {
      await activate(TOKEN_A, { displayName: 'Igor' });

      const response = await request(server()).get(path).set('Authorization', `Bearer ${TOKEN_A}`);

      expect(response.status).toBe(404);
      // E a resposta é o envelope de erro, sem nenhum campo de perfil. (A mensagem do Nest ecoa o
      // caminho pedido, então o que se verifica é a forma do corpo — não a ausência do texto.)
      expect(Object.keys(response.body)).toEqual(['error']);
      for (const field of ['profile', 'socialId', 'friendCode', 'displayName']) {
        expect(response.body.error).not.toHaveProperty(field);
      }
    });
  });
});
