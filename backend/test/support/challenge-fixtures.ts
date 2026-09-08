import { createHash, randomUUID } from 'node:crypto';
import type { INestApplication } from '@nestjs/common';
import request from 'supertest';

/**
 * Os apoios dos testes de desafio (T17.3).
 *
 * Eles montam **contas reais** pelas rotas reais — ativar Social, virar amigo, criar desafio —, e
 * não linhas inseridas à mão no banco. É o que faz o teste exercitar a mesma validação, a mesma
 * política e a mesma transação que a produção usa: um `INSERT` direto em `challenge_participants`
 * provaria que a consulta funciona, e não que alguém consegue entrar num desafio.
 */

export const SAO_PAULO = 'America/Sao_Paulo';

/** Uma conta de teste: token, uid e o `socialId` que o servidor gerou. */
export interface TestAccount {
  readonly token: string;
  readonly uid: string;
  socialId: string;
  displayName: string;
}

export function account(name: string, displayName: string): TestAccount {
  return {
    token: `token-${name}`,
    uid: `uid-${name}`,
    socialId: '',
    displayName,
  };
}

/** Ativa o Social e guarda o `socialId` que o servidor devolveu. */
export async function enableSocial(app: INestApplication, who: TestAccount): Promise<void> {
  const response = await request(app.getHttpServer())
    .post('/v1/social/me/activate')
    .set('Authorization', `Bearer ${who.token}`)
    .send({ displayName: who.displayName })
    .expect(200);
  who.socialId = response.body.profile.socialId;
}

/**
 * Faz duas contas virarem amigas, pelo caminho real: pedido e aceite.
 *
 * Não há atalho por `INSERT` em `friendships` — o desafio exige amizade **ativa**, e a única forma
 * de provar que ele exige é criar a amizade do jeito que uma pessoa criaria.
 */
export async function befriend(
  app: INestApplication,
  a: TestAccount,
  b: TestAccount,
): Promise<void> {
  const sent = await request(app.getHttpServer())
    .post('/v1/social/friend-requests')
    .set('Authorization', `Bearer ${a.token}`)
    .send({ socialId: b.socialId })
    .expect(200);

  await request(app.getHttpServer())
    .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
    .set('Authorization', `Bearer ${b.token}`)
    .expect(200);
}

export interface CreateChallengeOverrides {
  clientRequestId?: string;
  name?: string;
  type?: string;
  target?: number;
  startDate?: string;
  endDate?: string;
  timeZoneId?: string;
  invitedSocialIds?: string[];
}

/** O corpo de uma criação, no formato que o Android envia. */
export function createChallengeBody(
  invited: TestAccount[],
  overrides: CreateChallengeOverrides = {},
): Record<string, unknown> {
  return {
    clientRequestId: randomUUID(),
    name: '12 treinos',
    type: 'WORKOUTS_COMPLETED',
    target: 12,
    startDate: '2026-09-10',
    endDate: '2026-10-09',
    timeZoneId: SAO_PAULO,
    invitedSocialIds: invited.map((who) => who.socialId),
    ...overrides,
  };
}

/** Cria um desafio e devolve o corpo da resposta. */
export async function createChallenge(
  app: INestApplication,
  creator: TestAccount,
  invited: TestAccount[],
  overrides: CreateChallengeOverrides = {},
): Promise<{ challengeId: string; body: Record<string, never> }> {
  const response = await request(app.getHttpServer())
    .post('/v1/social/challenges')
    .set('Authorization', `Bearer ${creator.token}`)
    .send(createChallengeBody(invited, overrides))
    .expect(200);
  return { challengeId: response.body.challenge.challengeId, body: response.body };
}

/** O convite pendente daquela conta para aquele desafio. */
export async function pendingInvitationId(
  app: INestApplication,
  who: TestAccount,
  challengeId: string,
): Promise<string> {
  const response = await request(app.getHttpServer())
    .get('/v1/social/challenge-invitations')
    .set('Authorization', `Bearer ${who.token}`)
    .expect(200);

  const invitation = response.body.invitations.find(
    (item: { challenge: { challengeId: string } }) => item.challenge.challengeId === challengeId,
  );
  if (!invitation) {
    throw new Error(`nenhum convite pendente para ${challengeId}`);
  }
  return invitation.invitationId;
}

/** Aceita o convite daquela conta para aquele desafio, pelo caminho real. */
export async function acceptChallenge(
  app: INestApplication,
  who: TestAccount,
  challengeId: string,
): Promise<void> {
  const invitationId = await pendingInvitationId(app, who, challengeId);
  await request(app.getHttpServer())
    .post(`/v1/social/challenge-invitations/${invitationId}/accept`)
    .set('Authorization', `Bearer ${who.token}`)
    .expect(200);
}

/**
 * Uma sessão de treino **concluída**, no formato canônico do sync.
 *
 * `startedAt` é o instante que decide a que dia e a que janela a sessão pertence — a regra
 * canônica do Spark (`ConsistencyCalculator`, e a fixture `weekly-window.json`). `finishedAt` é
 * nulável no schema e **não** participa da elegibilidade: ver
 * `challenge-progress.source.ts` para por que usar `finishedAt` seria uma segunda regra.
 */
export function completedSessionPayload(
  syncId: string,
  startedAt: number,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    syncId,
    templateSyncId: null,
    templateNameSnapshot: 'Treino A',
    status: 'COMPLETED',
    startedAt,
    finishedAt: startedAt + 45 * 60 * 1000,
    notes: null,
    exercises: [],
    ...overrides,
  };
}

/**
 * Um `syncId` **válido e estável** a partir de um rótulo legível.
 *
 * O sync recusa qualquer coisa que não seja UUID (`IDENTITY_MISMATCH`), e é assim que tem de ser:
 * `syncId` é identidade global, e um rótulo livre não é identidade. Mas um teste que dissesse
 * `pushCompletedSession(app, igor, '3f2a...', ...)` seria ilegível, e "a mesma sessão reenviada"
 * precisa que as duas chamadas produzam o **mesmo** id.
 *
 * Derivar de um SHA-256 do rótulo resolve os dois: a forma é UUID, o valor é estável, e o teste
 * continua dizendo `'sess-repetida'`.
 */
export function syncIdFor(label: string): string {
  const hex = createHash('sha256').update(label, 'utf8').digest('hex');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    `4${hex.slice(13, 16)}`,
    `8${hex.slice(17, 20)}`,
    hex.slice(20, 32),
  ].join('-');
}

/**
 * Empurra uma sessão concluída para o servidor **pelo sync real** (§234).
 *
 * Pelo endpoint, e não por `INSERT` em `sync_entities`: é o que garante que a pontuação está
 * lendo o mesmo estado que um aparelho de verdade produz — inclusive a política que só aceita
 * `COMPLETED`, e a constraint de identidade que faz um reenvio não contar duas vezes.
 *
 * O resultado é **verificado**. Um `push` recusado que passasse em silêncio faria todo teste de
 * pontuação virar "zero é igual a zero" — verde, e provando nada. `ALREADY_APPLIED` é sucesso: é
 * exatamente o reenvio que o teste de idempotência exercita.
 */
export async function pushCompletedSession(
  app: INestApplication,
  who: TestAccount,
  label: string,
  startedAt: number,
  deviceId = 'device-a',
): Promise<request.Response> {
  const syncId = syncIdFor(label);
  const response = await request(app.getHttpServer())
    .post('/v1/sync/push')
    .set('Authorization', `Bearer ${who.token}`)
    .set('Content-Type', 'application/json')
    .send(
      JSON.stringify({
        deviceId,
        mutations: [
          {
            clientMutationId: randomUUID(),
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            entitySchemaVersion: 1,
            operation: 'UPSERT',
            baseRevision: null,
            payload: completedSessionPayload(syncId, startedAt),
          },
        ],
      }),
    );

  if (response.status !== 200) {
    throw new Error(`push de sessão falhou: ${response.status} ${JSON.stringify(response.body)}`);
  }
  const result = response.body.results?.[0];
  if (result?.status !== 'APPLIED' && result?.status !== 'ALREADY_APPLIED') {
    throw new Error(`push de sessão não foi aplicado: ${JSON.stringify(result)}`);
  }
  return response;
}

/**
 * O instante de uma hora local em São Paulo, em epoch millis.
 *
 * Escrito como offset explícito para que o teste não dependa do fuso da máquina que o roda — e
 * para que a data que aparece no teste seja a data que uma pessoa em São Paulo veria.
 */
export function saoPauloInstant(iso: string): number {
  // São Paulo está em UTC-3 desde 2019 (sem horário de verão). Os casos de DST usam outros fusos,
  // com o offset explícito no próprio teste.
  return Date.parse(`${iso}-03:00`);
}
