import { Injectable, Optional } from '@nestjs/common';
import type { PoolClient } from 'pg';
import { DbClient, PostgresService } from '../../database/postgres.service';
import type {
  ChallengeParticipantStatus,
  ChallengeRole,
  ChallengeType,
} from './challenge.contract';
import type { ListCursor, Page, PageRequest } from './friendship.repository';
import { NotificationService } from './notification.service';

/** Um desafio, como está gravado. `creatorUid` nunca sai em DTO. */
export interface StoredChallenge {
  readonly challengeId: string;
  readonly creatorUid: string;
  readonly name: string;
  readonly type: ChallengeType;
  readonly target: number;
  readonly startDate: string;
  readonly endDate: string;
  readonly timeZoneId: string;
  readonly startsAt: number;
  readonly endsAtExclusive: number;
  readonly lifecycle: 'OPEN' | 'CANCELLED';
  readonly cancelledAt: number | null;
  readonly createdAt: number;
  readonly updatedAt: number;
}

/** Um participante com o perfil social já resolvido — o que o placar precisa. */
export interface StoredChallengeParticipant {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly displayName: string;
  readonly role: ChallengeRole;
  readonly status: ChallengeParticipantStatus;
  readonly joinedAt: number;
}

export interface StoredChallengeInvitation {
  readonly invitationId: string;
  readonly challengeId: string;
  readonly inviterUid: string;
  readonly recipientUid: string;
  readonly status: 'PENDING' | 'ACCEPTED' | 'DECLINED';
  readonly createdAt: number;
}

/** Um convite com o desafio já carregado — a listagem de convites pendentes. */
export interface StoredInvitationWithChallenge extends StoredChallengeInvitation {
  readonly challenge: StoredChallenge;
}

/** Uma criação já validada e com os convidados **já resolvidos** para uid. */
export interface CreateChallengeInput {
  readonly challengeId: string;
  readonly creatorUid: string;
  readonly name: string;
  readonly type: ChallengeType;
  readonly target: number;
  readonly startDate: string;
  readonly endDate: string;
  readonly timeZoneId: string;
  readonly startsAt: number;
  readonly endsAtExclusive: number;
  /** `(invitationId, recipientUid)` — resolvidos e revalidados como amigos antes de chegar aqui. */
  readonly invitations: readonly { readonly invitationId: string; readonly recipientUid: string }[];
  readonly clientRequestId: string;
  readonly requestHash: string;
  readonly now: number;
}

/** O desfecho de uma criação, decidido **dentro** da transação. */
export type CreateChallengeOutcome =
  | { readonly kind: 'CREATED'; readonly challenge: StoredChallenge }
  /** Reenvio do mesmo pedido: o desafio que a primeira tentativa criou (§189). */
  | { readonly kind: 'ALREADY_CREATED'; readonly challenge: StoredChallenge }
  /** Mesmo `clientRequestId`, conteúdo diferente (§190). */
  | { readonly kind: 'IDEMPOTENCY_CONFLICT' };

/** O desfecho de um aceite, decidido **dentro** da transação. */
export type AcceptInvitationOutcome =
  | { readonly kind: 'ACCEPTED' }
  /** Aceitar de novo depois de uma resposta perdida, ou toque duplo (§191). É sucesso. */
  | { readonly kind: 'ALREADY_PARTICIPATING' }
  /** Outra escrita venceu: recusado no meio, ou o desafio foi cancelado. */
  | { readonly kind: 'NOT_PENDING' }
  /** O desafio encheu entre a verificação e a transação. */
  | { readonly kind: 'FULL' };

/**
 * A persistência dos desafios (T17.3).
 *
 * ## As garantias que moram no banco, e não na disciplina do serviço
 *
 * 1. **um participante por pessoa, por desafio** — `PRIMARY KEY (challenge_id, participant_uid)`.
 *    Aceitar duas vezes não cria dois participantes, nem sob duas transações simultâneas (§40);
 * 2. **um criador por desafio** — índice único parcial `WHERE role = 'CREATOR'` (§41);
 * 3. **um convite por pessoa, por desafio** — `UNIQUE (challenge_id, recipient_uid)`. É isto que
 *    torna `[B, B]` incapaz de produzir dois convites mesmo se a normalização em código falhasse
 *    (§43);
 * 4. **uma criação por `clientRequestId`** — `PRIMARY KEY (owner_uid, client_request_id)` em
 *    `challenge_creation_requests`. O toque duplo não cria dois desafios (§187/§188);
 * 5. **transição é escrita condicional** — aceitar/recusar/sair/cancelar é
 *    `UPDATE ... WHERE ... AND <estado atual>`, e é o `changes` que decide o desfecho. Uma
 *    verificação em memória antes do `UPDATE` perderia a corrida "criador cancela enquanto
 *    convidado aceita" e produziria os dois estados ao mesmo tempo.
 *
 * ## Nenhuma coluna de pontuação é lida ou escrita aqui
 *
 * Este repositório não conhece `score`. Ele devolve **quem** participa; quanto cada um fez é
 * pergunta do `ChallengeScoringService`, sobre a fonte canônica. A separação é o que impede a
 * tentação de guardar um contador ao lado do participante.
 */
@Injectable()
export class ChallengeRepository {
  constructor(
    private readonly db: PostgresService,
    @Optional() private readonly notificationService?: NotificationService,
  ) {}

  // ------------------------------------------------------------------------------- criação

  /**
   * Cria o desafio, o participante-criador e todos os convites — **em uma transação** (§44).
   */
  async create(input: CreateChallengeInput): Promise<CreateChallengeOutcome> {
    return this.db.transaction(async (client): Promise<CreateChallengeOutcome> => {
      const previous = await this.findCreationRequest(
        input.creatorUid,
        input.clientRequestId,
        client,
      );
      if (previous) {
        // Mesma tentativa. Conteúdo igual devolve o mesmo desafio; conteúdo diferente é conflito —
        // e nunca uma segunda criação silenciosa (§190).
        if (previous.requestHash !== input.requestHash) {
          return { kind: 'IDEMPOTENCY_CONFLICT' };
        }
        const existing = await this.findById(previous.challengeId, client);
        if (existing) {
          return { kind: 'ALREADY_CREATED', challenge: existing };
        }
        // Ledger apontando para um desafio que não existe mais é estado impossível (a FK é
        // `ON DELETE CASCADE`, e nenhuma rota apaga desafio). Tratado como conflito em vez de
        // criar um segundo: o cliente relê e vê o que existe.
        return { kind: 'IDEMPOTENCY_CONFLICT' };
      }

      await client.query(
        `INSERT INTO challenges
           (challenge_id, creator_uid, name, type, target, start_date, end_date, time_zone_id,
            starts_at, ends_at_exclusive, lifecycle, cancelled_at, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, 'OPEN', NULL, $11, $12)`,
        [
          input.challengeId,
          input.creatorUid,
          input.name,
          input.type,
          input.target,
          input.startDate,
          input.endDate,
          input.timeZoneId,
          input.startsAt,
          input.endsAtExclusive,
          input.now,
          input.now,
        ],
      );

      // O criador entra automaticamente, já aceito (§31/§42). Ele não recebe convite: convidá-lo
      // seria pedir que ele aceite o que acabou de propor.
      await client.query(
        `INSERT INTO challenge_participants
           (challenge_id, participant_uid, role, status, joined_at, left_at)
         VALUES ($1, $2, 'CREATOR', 'JOINED', $3, NULL)`,
        [input.challengeId, input.creatorUid, input.now],
      );

      for (const invitation of input.invitations) {
        await client.query(
          `INSERT INTO challenge_invitations
             (invitation_id, challenge_id, inviter_uid, recipient_uid, status, created_at, updated_at)
           VALUES ($1, $2, $3, $4, 'PENDING', $5, $6)`,
          [
            invitation.invitationId,
            input.challengeId,
            input.creatorUid,
            invitation.recipientUid,
            input.now,
            input.now,
          ],
        );

        // Notifica convidados sobre o novo convite recebido (T17.5 §50)
        await this.notificationService?.enqueueChallengeInvitationReceived(client, {
          invitationId: invitation.invitationId,
          challengeId: input.challengeId,
          recipientUid: invitation.recipientUid,
          startsAt: input.startsAt,
          now: input.now,
        });
      }

      // Notificações programadas do criador (T17.5 §52/§57)
      await this.notificationService?.enqueueChallengeStartingSoon(client, {
        challengeId: input.challengeId,
        participantUid: input.creatorUid,
        startsAt: input.startsAt,
        now: input.now,
      });
      await this.notificationService?.enqueueChallengeEnded(client, {
        challengeId: input.challengeId,
        participantUid: input.creatorUid,
        endsAtExclusive: input.endsAtExclusive,
        now: input.now,
      });

      await client.query(
        `INSERT INTO challenge_creation_requests
           (owner_uid, client_request_id, request_hash, challenge_id, created_at)
         VALUES ($1, $2, $3, $4, $5)`,
        [input.creatorUid, input.clientRequestId, input.requestHash, input.challengeId, input.now],
      );

      const challenge = await this.findById(input.challengeId, client);
      if (!challenge) {
        // Irrepresentável: o `INSERT` acima acabou de acontecer nesta transação.
        throw new Error('challenge desapareceu dentro da própria transação de criação');
      }
      return { kind: 'CREATED', challenge };
    });
  }

  private getRunner(client?: PoolClient): DbClient {
    return (client ?? this.db) as DbClient;
  }

  async findCreationRequest(
    ownerUid: string,
    clientRequestId: string,
    client?: PoolClient,
  ): Promise<{ requestHash: string; challengeId: string } | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ request_hash: string; challenge_id: string }>(
      `SELECT request_hash, challenge_id FROM challenge_creation_requests
        WHERE owner_uid = $1 AND client_request_id = $2`,
      [ownerUid, clientRequestId],
    );
    const row = res.rows[0];
    return row ? { requestHash: row.request_hash, challengeId: row.challenge_id } : null;
  }

  /**
   * Quantos desafios **abertos** esta conta criou (§111).
   */
  async countOpenChallengesBy(
    creatorUid: string,
    nowMs: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM challenges
        WHERE creator_uid = $1 AND lifecycle = 'OPEN' AND ends_at_exclusive > $2`,
      [creatorUid, nowMs],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  // ------------------------------------------------------------------------------- leitura

  async findById(challengeId: string, client?: PoolClient): Promise<StoredChallenge | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<ChallengeRow>(
      `${CHALLENGE_COLUMNS} FROM challenges WHERE challenge_id = $1`,
      [challengeId],
    );
    const row = res.rows[0];
    return row ? toChallenge(row) : null;
  }

  /**
   * A participação de uma conta num desafio, ou `null`.
   */
  async findParticipation(
    challengeId: string,
    participantUid: string,
    client?: PoolClient,
  ): Promise<{ role: ChallengeRole; status: ChallengeParticipantStatus } | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ role: string; status: string }>(
      `SELECT role, status FROM challenge_participants
        WHERE challenge_id = $1 AND participant_uid = $2`,
      [challengeId, participantUid],
    );
    const row = res.rows[0];
    return row
      ? { role: row.role as ChallengeRole, status: row.status as ChallengeParticipantStatus }
      : null;
  }

  /**
   * Os participantes de um desafio, com o perfil social resolvido.
   */
  async listParticipants(
    challengeId: string,
    client?: PoolClient,
  ): Promise<readonly StoredChallengeParticipant[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<ParticipantRow>(
      `SELECT cp.participant_uid, cp.role, cp.status, cp.joined_at,
              p.social_id, p.display_name
         FROM challenge_participants cp
         JOIN social_profiles p ON p.owner_uid = cp.participant_uid
        WHERE cp.challenge_id = $1
        ORDER BY cp.joined_at ASC, cp.participant_uid ASC`,
      [challengeId],
    );

    return res.rows.map((row) => ({
      ownerUid: row.participant_uid,
      socialId: row.social_id,
      displayName: row.display_name,
      role: row.role as ChallengeRole,
      status: row.status as ChallengeParticipantStatus,
      joinedAt: Number(row.joined_at),
    }));
  }

  /** Quantos participantes ativos. Usado pela política para decidir `VOID` (§28). */
  async countActiveParticipants(challengeId: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM challenge_participants
        WHERE challenge_id = $1 AND status = 'JOINED'`,
      [challengeId],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  /** Quantos convites ainda estão pendentes. Só o criador vê o número (§172), sem nomes (§173). */
  async countPendingInvitations(challengeId: string, client?: PoolClient): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM challenge_invitations
        WHERE challenge_id = $1 AND status = 'PENDING'`,
      [challengeId],
    );
    return Number(res.rows[0]?.total ?? 0);
  }

  /**
   * Os desafios de que esta conta participa, paginados.
   */
  async listForParticipant(
    participantUid: string,
    page: PageRequest,
  ): Promise<Page<StoredChallenge>> {
    let query: string;
    let params: unknown[];

    if (page.cursor) {
      query = `
        ${PARTICIPANT_CHALLENGES_SELECT}
        AND (c.starts_at < $2 OR (c.starts_at = $2 AND c.challenge_id < $3))
        ORDER BY c.starts_at DESC, c.challenge_id DESC
        LIMIT $4
      `;
      params = [participantUid, Number(page.cursor.primary), page.cursor.secondary, page.limit + 1];
    } else {
      query = `
        ${PARTICIPANT_CHALLENGES_SELECT}
        ORDER BY c.starts_at DESC, c.challenge_id DESC
        LIMIT $2
      `;
      params = [participantUid, page.limit + 1];
    }

    const res = await this.db.query<ChallengeRow>(query, params);

    const totalRes = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM (${PARTICIPANT_CHALLENGES_SELECT}) AS count_subquery`,
      [participantUid],
    );
    const total = Number(totalRes.rows[0]?.total ?? 0);

    return paginateChallenges(res.rows.map(toChallenge), page.limit, total, (challenge) => ({
      primary: challenge.startsAt,
      secondary: challenge.challengeId,
    }));
  }

  // ------------------------------------------------------------------------------- convites

  async findInvitationById(
    invitationId: string,
    client?: PoolClient,
  ): Promise<StoredChallengeInvitation | null> {
    const runner = this.getRunner(client);
    const res = await runner.query<InvitationRow>(
      `SELECT invitation_id, challenge_id, inviter_uid, recipient_uid, status, created_at
         FROM challenge_invitations WHERE invitation_id = $1`,
      [invitationId],
    );
    const row = res.rows[0];
    return row ? toInvitation(row) : null;
  }

  /**
   * Os convites **pendentes** desta conta, com o desafio carregado.
   */
  async listPendingInvitations(
    recipientUid: string,
    page: PageRequest,
  ): Promise<Page<StoredInvitationWithChallenge>> {
    let query: string;
    let params: unknown[];

    if (page.cursor) {
      query = `
        ${PENDING_INVITATIONS_SELECT}
        AND (i.created_at < $2 OR (i.created_at = $2 AND i.invitation_id < $3))
        ORDER BY i.created_at DESC, i.invitation_id DESC
        LIMIT $4
      `;
      params = [recipientUid, Number(page.cursor.primary), page.cursor.secondary, page.limit + 1];
    } else {
      query = `
        ${PENDING_INVITATIONS_SELECT}
        ORDER BY i.created_at DESC, i.invitation_id DESC
        LIMIT $2
      `;
      params = [recipientUid, page.limit + 1];
    }

    const res = await this.db.query<InvitationWithChallengeRow>(query, params);

    const totalRes = await this.db.query<{ total: string | number }>(
      `SELECT COUNT(*) AS total FROM (${PENDING_INVITATIONS_SELECT}) AS count_subquery`,
      [recipientUid],
    );
    const total = Number(totalRes.rows[0]?.total ?? 0);

    const items = res.rows.map((row) => ({
      ...toInvitation(row),
      challenge: toChallenge(row),
    }));

    return paginateChallenges(items, page.limit, total, (item) => ({
      primary: item.createdAt,
      secondary: item.invitationId,
    }));
  }

  /**
   * Aceita um convite: marca `ACCEPTED` **e** cria a participação, ou não faz nenhuma das duas.
   */
  async acceptInvitation(
    invitationId: string,
    challengeId: string,
    recipientUid: string,
    maxParticipants: number,
    now: number,
  ): Promise<AcceptInvitationOutcome> {
    return this.db.transaction(async (client): Promise<AcceptInvitationOutcome> => {
      const changedRes = await client.query(
        `UPDATE challenge_invitations SET status = 'ACCEPTED', updated_at = $1
          WHERE invitation_id = $2 AND status = 'PENDING'`,
        [now, invitationId],
      );

      if ((changedRes.rowCount ?? 0) === 0) {
        // Ninguém aceitou agora. Se a participação existe, este aceite é a repetição de um que já
        // funcionou — e repetir uma operação bem-sucedida não é erro (§191).
        const participation = await this.findParticipation(challengeId, recipientUid, client);
        if (participation) {
          return { kind: 'ALREADY_PARTICIPATING' };
        }
        return { kind: 'NOT_PENDING' };
      }

      if ((await this.countActiveParticipants(challengeId, client)) >= maxParticipants) {
        // A transação inteira é desfeita, inclusive o `UPDATE` acima: o convite volta a
        // `PENDING`, e não fica aceito num desafio de que a pessoa não participa.
        throw new ChallengeFullError();
      }

      await client.query(
        `INSERT INTO challenge_participants
           (challenge_id, participant_uid, role, status, joined_at, left_at)
         VALUES ($1, $2, 'MEMBER', 'JOINED', $3, NULL)
         ON CONFLICT (challenge_id, participant_uid) DO NOTHING`,
        [challengeId, recipientUid, now],
      );

      const chRes = await client.query<{
        starts_at: string | number;
        ends_at_exclusive: string | number;
      }>(`SELECT starts_at, ends_at_exclusive FROM challenges WHERE challenge_id = $1`, [
        challengeId,
      ]);
      const ch = chRes.rows[0];

      if (ch) {
        // Notificações programadas do participante aceito (T17.5 §55/§57)
        await this.notificationService?.enqueueChallengeStartingSoon(client, {
          challengeId,
          participantUid: recipientUid,
          startsAt: Number(ch.starts_at),
          now,
        });
        await this.notificationService?.enqueueChallengeEnded(client, {
          challengeId,
          participantUid: recipientUid,
          endsAtExclusive: Number(ch.ends_at_exclusive),
          now,
        });
      }

      return { kind: 'ACCEPTED' };
    });
  }

  /**
   * Recusa um convite pendente. `true` quando **esta** chamada foi a que mudou.
   */
  async declineInvitation(invitationId: string, now: number): Promise<boolean> {
    const res = await this.db.query(
      `UPDATE challenge_invitations SET status = 'DECLINED', updated_at = $1
        WHERE invitation_id = $2 AND status = 'PENDING'`,
      [now, invitationId],
    );
    return (res.rowCount ?? 0) > 0;
  }

  // ------------------------------------------------------------------------------- saída

  /**
   * Sai do desafio. `true` quando **esta** chamada foi a que mudou (§193).
   */
  async leave(challengeId: string, participantUid: string, now: number): Promise<boolean> {
    const res = await this.db.query(
      `UPDATE challenge_participants SET status = 'WITHDRAWN', left_at = $1
        WHERE challenge_id = $2 AND participant_uid = $3 AND status = 'JOINED'
          AND role = 'MEMBER'`,
      [now, challengeId, participantUid],
    );
    const success = (res.rowCount ?? 0) > 0;

    if (success) {
      await this.notificationService?.cancelParticipantEvents(challengeId, participantUid);
    }

    return success;
  }

  /**
   * Cancela o desafio. `true` quando **esta** chamada foi a que mudou (§194).
   */
  async cancel(challengeId: string, now: number): Promise<boolean> {
    const res = await this.db.query(
      `UPDATE challenges SET lifecycle = 'CANCELLED', cancelled_at = $1, updated_at = $2
        WHERE challenge_id = $3 AND lifecycle = 'OPEN'`,
      [now, now, challengeId],
    );
    const success = (res.rowCount ?? 0) > 0;

    if (success) {
      await this.notificationService?.cancelChallengeEvents(challengeId);
    }

    return success;
  }

  // ------------------------------------------------------------------------------- desativação

  /**
   * Os desafios **ainda não encerrados** que esta conta criou (§118).
   */
  async openChallengesCreatedBy(
    creatorUid: string,
    nowMs: number,
    client?: PoolClient,
  ): Promise<readonly string[]> {
    const runner = this.getRunner(client);
    const res = await runner.query<{ challenge_id: string }>(
      `SELECT challenge_id FROM challenges
        WHERE creator_uid = $1 AND lifecycle = 'OPEN' AND ends_at_exclusive > $2`,
      [creatorUid, nowMs],
    );
    return res.rows.map((row) => row.challenge_id);
  }

  /**
   * Tira esta conta de todos os desafios ainda não encerrados em que ela participa como membro
   * (§117), e recusa os convites pendentes dela (§116).
   */
  async withdrawFromOpenChallenges(
    participantUid: string,
    nowMs: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE challenge_participants SET status = 'WITHDRAWN', left_at = $1
        WHERE participant_uid = $2 AND status = 'JOINED' AND role = 'MEMBER'
          AND challenge_id IN (
              SELECT challenge_id FROM challenges
               WHERE lifecycle = 'OPEN' AND ends_at_exclusive > $3
          )`,
      [nowMs, participantUid, nowMs],
    );
    return res.rowCount ?? 0;
  }

  /** Recusa todos os convites pendentes desta conta (§116). Parte da mesma transação. */
  async declinePendingInvitationsOf(
    recipientUid: string,
    nowMs: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE challenge_invitations SET status = 'DECLINED', updated_at = $1
        WHERE recipient_uid = $2 AND status = 'PENDING'`,
      [nowMs, recipientUid],
    );
    return res.rowCount ?? 0;
  }

  /** Cancela os desafios abertos criados por esta conta (§118). Parte da mesma transação. */
  async cancelOpenChallengesCreatedBy(
    creatorUid: string,
    nowMs: number,
    client?: PoolClient,
  ): Promise<number> {
    const runner = this.getRunner(client);
    const res = await runner.query(
      `UPDATE challenges SET lifecycle = 'CANCELLED', cancelled_at = $1, updated_at = $2
        WHERE creator_uid = $3 AND lifecycle = 'OPEN' AND ends_at_exclusive > $4`,
      [nowMs, nowMs, creatorUid, nowMs],
    );
    return res.rowCount ?? 0;
  }

  /**
   * O efeito de **desativar o Social** sobre os desafios (T17.3 §115–§120).
   */
  async applySocialDisable(
    ownerUid: string,
    nowMs: number,
    client?: PoolClient,
  ): Promise<{ declinedInvitations: number; withdrawnFrom: number; cancelledChallenges: number }> {
    return {
      declinedInvitations: await this.declinePendingInvitationsOf(ownerUid, nowMs, client),
      withdrawnFrom: await this.withdrawFromOpenChallenges(ownerUid, nowMs, client),
      cancelledChallenges: await this.cancelOpenChallengesCreatedBy(ownerUid, nowMs, client),
    };
  }
}

/**
 * O desafio encheu entre a verificação e o `INSERT`.
 */
export class ChallengeFullError extends Error {
  constructor() {
    super('challenge is full');
    this.name = 'ChallengeFullError';
  }
}

const CHALLENGE_COLUMNS = `
  SELECT challenge_id, creator_uid, name, type, target, start_date, end_date, time_zone_id,
         starts_at, ends_at_exclusive, lifecycle, cancelled_at, created_at, updated_at
`;

/** Os desafios de que uma conta participa — em qualquer papel, em qualquer status. */
const PARTICIPANT_CHALLENGES_SELECT = `
  SELECT c.challenge_id, c.creator_uid, c.name, c.type, c.target, c.start_date, c.end_date,
         c.time_zone_id, c.starts_at, c.ends_at_exclusive, c.lifecycle, c.cancelled_at,
         c.created_at, c.updated_at
    FROM challenges c
    JOIN challenge_participants cp ON cp.challenge_id = c.challenge_id
   WHERE cp.participant_uid = $1
`;

/** Convites pendentes, com o desafio e o filtro de criador ativo. */
const PENDING_INVITATIONS_SELECT = `
  SELECT i.invitation_id, i.challenge_id, i.inviter_uid, i.recipient_uid, i.status, i.created_at,
         c.creator_uid, c.name, c.type, c.target, c.start_date, c.end_date, c.time_zone_id,
         c.starts_at, c.ends_at_exclusive, c.lifecycle, c.cancelled_at,
         c.created_at AS challenge_created_at, c.updated_at
    FROM challenge_invitations i
    JOIN challenges c ON c.challenge_id = i.challenge_id
    JOIN social_profiles p ON p.owner_uid = i.inviter_uid
   WHERE i.recipient_uid = $1
     AND i.status = 'PENDING'
     AND p.status = 'ACTIVE'
`;

interface ChallengeRow {
  challenge_id: string;
  creator_uid: string;
  name: string;
  type: string;
  target: string | number;
  start_date: string;
  end_date: string;
  time_zone_id: string;
  starts_at: string | number;
  ends_at_exclusive: string | number;
  lifecycle: string;
  cancelled_at: string | number | null;
  created_at: string | number;
  challenge_created_at?: string | number;
  updated_at: string | number;
}

interface ParticipantRow {
  participant_uid: string;
  social_id: string;
  display_name: string;
  role: string;
  status: string;
  joined_at: string | number;
}

interface InvitationRow {
  invitation_id: string;
  challenge_id: string;
  inviter_uid: string;
  recipient_uid: string;
  status: string;
  created_at: string | number;
}

interface InvitationWithChallengeRow extends InvitationRow, ChallengeRow {}

function toChallenge(row: ChallengeRow): StoredChallenge {
  return {
    challengeId: row.challenge_id,
    creatorUid: row.creator_uid,
    name: row.name,
    type: row.type as ChallengeType,
    target: Number(row.target),
    startDate: row.start_date,
    endDate: row.end_date,
    timeZoneId: row.time_zone_id,
    startsAt: Number(row.starts_at),
    endsAtExclusive: Number(row.ends_at_exclusive),
    lifecycle: row.lifecycle as 'OPEN' | 'CANCELLED',
    cancelledAt: row.cancelled_at != null ? Number(row.cancelled_at) : null,
    createdAt: Number(row.challenge_created_at ?? row.created_at),
    updatedAt: Number(row.updated_at),
  };
}

function toInvitation(row: InvitationRow): StoredChallengeInvitation {
  return {
    invitationId: row.invitation_id,
    challengeId: row.challenge_id,
    inviterUid: row.inviter_uid,
    recipientUid: row.recipient_uid,
    status: row.status as 'PENDING' | 'ACCEPTED' | 'DECLINED',
    createdAt: Number(row.created_at),
  };
}

/** Mesma paginação de `friendship.repository.ts`: pede `limit + 1` para saber se há continuação. */
function paginateChallenges<T>(
  rows: T[],
  limit: number,
  total: number,
  cursorOf: (item: T) => ListCursor,
): Page<T> {
  const hasMore = rows.length > limit;
  const items = hasMore ? rows.slice(0, limit) : rows;
  return {
    items,
    total,
    nextCursor: hasMore && items.length > 0 ? cursorOf(items[items.length - 1]) : null,
  };
}
