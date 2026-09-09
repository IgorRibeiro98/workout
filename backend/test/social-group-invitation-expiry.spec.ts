import request from 'supertest';
import {
  ACCOUNT_A,
  ACCOUNT_B,
  ACCOUNT_C,
  createSocialScenario,
  type AuditAccount,
  type SocialScenario,
} from './support/social-scenario';
import { uuid } from './support/sync-fixtures';
import { SOCIAL_GROUP_INVITATION_TTL_MS } from '../src/modules/social/social-group.limits';

/**
 * T17.13.1 §26–§32 — o convite de Squad expira **de verdade**.
 *
 * ## O defeito que estes testes fixam
 *
 * A T17.11 tratou `EXPIRED` como um estado **derivado na leitura**: a coluna `status` continuava
 * `PENDING` para sempre, e todo lugar que precisava saber se o convite tinha vencido comparava
 * `now >= expires_at` por conta própria. Isso funciona para *exibir* o convite e falha para tudo
 * o que depende do banco:
 *
 * ```text
 * idx_social_group_invitations_pending  UNIQUE (group_id, recipient_uid) WHERE status = 'PENDING'
 * ```
 *
 * O índice não sabe que horas são. Um convite vencido continua ocupando a vaga única daquele par
 * (Squad, destinatário) — e continua contando na quota de `MAX_PENDING_INVITATIONS`. O resultado é
 * um beco sem saída: quem recebeu não pode aceitar (a leitura recusa por vencimento) e quem
 * convidou não pode reconvidar (o serviço encontra o convite vencido e o devolve como se fosse
 * bom). O Squad perde a vaga para sempre.
 *
 * A correção persiste o estado (migration 0022) e varre antes de cada operação sensível a
 * `PENDING`. Estes testes cobrem os seis pontos que §68 exige: expiração, reconvite, quota, aceite
 * do antigo, listagem e ausência de push.
 */
describe('T17.13.1 — expiração persistida de convites de Squad', () => {
  let s: SocialScenario;

  const auth = (a: AuditAccount) => ({ Authorization: `Bearer ${a.token}` });

  /** O `status` **como está no banco**, e não como a API o projeta. É a distinção em teste aqui. */
  const storedStatus = (invitationId: string): string | undefined =>
    s.inDatabase(
      (db) =>
        (
          db
            .prepare(`SELECT status FROM social_group_invitations WHERE id = ?`)
            .get(invitationId) as { status: string } | undefined
        )?.status,
    );

  beforeEach(async () => {
    s = await createSocialScenario();
    await s.activate(ACCOUNT_A);
    await s.activate(ACCOUNT_B);
    await s.activate(ACCOUNT_C);
    await s.makeFriends(ACCOUNT_A, ACCOUNT_B);
    await s.makeFriends(ACCOUNT_A, ACCOUNT_C);
  });

  afterEach(async () => {
    await s.close();
  });

  it('o convite vencido vira EXPIRED no banco, e não só na leitura (§26/§27)', async () => {
    const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    const invitationId = await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);
    expect(storedStatus(invitationId)).toBe('PENDING');

    s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);

    // Uma operação sensível a `PENDING` — listar o que chegou — é o que dispara a varredura.
    await request(s.server()).get('/v1/social/groups/invitations').set(auth(ACCOUNT_B)).expect(200);

    expect(storedStatus(invitationId)).toBe('EXPIRED');
  });

  it('depois de expirar, o mesmo par (Squad, pessoa) pode ser convidado de novo (§29)', async () => {
    const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    const first = await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

    s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);

    const socialId = await s.socialIdOf(ACCOUNT_B);
    const res = await request(s.server())
      .post(`/v1/social/groups/${groupId}/invitations`)
      .set(auth(ACCOUNT_A))
      .send({ socialId, clientRequestId: uuid() })
      .expect(201);

    // O convite novo é **outro**, e nasce pendente. Antes desta correção, a rota devolvia o
    // convite vencido — que ninguém consegue aceitar — e o Squad ficava sem saída.
    expect(res.body.invitationId).not.toBe(first);
    expect(res.body.status).toBe('PENDING');
    expect(storedStatus(first)).toBe('EXPIRED');
    expect(storedStatus(res.body.invitationId as string)).toBe('PENDING');

    // E o convite novo funciona de verdade.
    await request(s.server())
      .post(`/v1/social/group-invitations/${res.body.invitationId}/accept`)
      .set(auth(ACCOUNT_B))
      .expect(200);
  });

  it('convite expirado não ocupa a quota de convites pendentes (§30)', async () => {
    const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);
    await s.invite(ACCOUNT_A, groupId, ACCOUNT_C);

    const pendingCount = () =>
      s.inDatabase(
        (db) =>
          (
            db
              .prepare(
                `SELECT COUNT(*) AS n FROM social_group_invitations
                 WHERE group_id = ? AND status = 'PENDING'`,
              )
              .get(groupId) as { n: number }
          ).n,
      );

    expect(pendingCount()).toBe(2);

    s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);

    const extra = s.extraAccount(1);
    await s.activate(extra);
    await s.makeFriends(ACCOUNT_A, extra);
    await s.invite(ACCOUNT_A, groupId, extra);

    // Os dois antigos saíram da contagem de pendentes; só o novo conta.
    expect(pendingCount()).toBe(1);
  });

  it('um convite expirado não pode ser aceito (§31)', async () => {
    const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    const invitationId = await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

    s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);

    await request(s.server())
      .post(`/v1/social/group-invitations/${invitationId}/accept`)
      .set(auth(ACCOUNT_B))
      .expect(404);

    expect(storedStatus(invitationId)).toBe('EXPIRED');
    // E ninguém entrou no Squad por causa disso.
    expect(
      s.inDatabase(
        (db) =>
          (
            db
              .prepare(
                `SELECT COUNT(*) AS n FROM social_group_memberships
                 WHERE group_id = ? AND member_uid = ?`,
              )
              .get(groupId, ACCOUNT_B.uid) as { n: number }
          ).n,
      ),
    ).toBe(0);
  });

  it('o convite expirado some da lista de quem recebeu (§68)', async () => {
    const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

    const before = await request(s.server())
      .get('/v1/social/groups/invitations')
      .set(auth(ACCOUNT_B))
      .expect(200);
    expect(before.body.items).toHaveLength(1);

    s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);

    const after = await request(s.server())
      .get('/v1/social/groups/invitations')
      .set(auth(ACCOUNT_B))
      .expect(200);
    expect(after.body.items).toHaveLength(0);
  });

  it('expirar não gera push: nenhum evento de notificação novo (§32)', async () => {
    const groupId = await s.createGroup(ACCOUNT_A, 'Os Monstros');
    await s.invite(ACCOUNT_A, groupId, ACCOUNT_B);

    const eventCount = () =>
      s.inDatabase(
        (db) =>
          (
            db.prepare(`SELECT COUNT(*) AS n FROM social_notification_events`).get() as {
              n: number;
            }
          ).n,
      );

    const afterInvite = eventCount();
    // O convite em si notifica (T17.11 §90); a expiração é o que não pode notificar.
    expect(afterInvite).toBeGreaterThan(0);

    s.clock.advance(SOCIAL_GROUP_INVITATION_TTL_MS + 1);
    await request(s.server()).get('/v1/social/groups/invitations').set(auth(ACCOUNT_B)).expect(200);

    expect(storedStatus(firstInvitationId())).toBe('EXPIRED');
    // §32 — nenhum tipo de notificação novo, e nenhum evento a mais. A expiração é silenciosa por
    // decisão: ela não é um acontecimento que alguém precise ver, e um push "seu convite venceu"
    // seria ruído para todo convite ignorado.
    expect(eventCount()).toBe(afterInvite);
  });

  function firstInvitationId(): string {
    return s.inDatabase(
      (db) =>
        (db.prepare(`SELECT id FROM social_group_invitations LIMIT 1`).get() as { id: string }).id,
    );
  }
});
