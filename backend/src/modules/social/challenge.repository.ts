import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type {
  ChallengeParticipantStatus,
  ChallengeRole,
  ChallengeType,
} from './challenge.contract';
import type { ListCursor, Page, PageRequest } from './friendship.repository';

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
  constructor(private readonly sqlite: SqliteService) {}

  // ------------------------------------------------------------------------------- criação

  /**
   * Cria o desafio, o participante-criador e todos os convites — **em uma transação** (§44).
   *
   * ```text
   * challenges (1)  +  challenge_participants (o criador)  +  challenge_invitations (N)
   *                        tudo, ou nada
   * ```
   *
   * A atomicidade não é zelo: fora de uma transação, uma falha no terceiro convite deixaria um
   * desafio existindo com dois dos três amigos convidados — e o criador não teria como perceber,
   * porque a tela mostraria um desafio criado. §45 é explícito: erro em um convidado significa
   * desafio nenhum.
   *
   * O ledger de idempotência é escrito na **mesma** transação: um desafio criado sem a linha do
   * ledger faria o retry criar um segundo.
   */
  create(input: CreateChallengeInput): CreateChallengeOutcome {
    const db = this.sqlite.connection;

    return db.transaction((): CreateChallengeOutcome => {
      const previous = this.findCreationRequest(input.creatorUid, input.clientRequestId);
      if (previous) {
        // Mesma tentativa. Conteúdo igual devolve o mesmo desafio; conteúdo diferente é conflito —
        // e nunca uma segunda criação silenciosa (§190).
        if (previous.requestHash !== input.requestHash) {
          return { kind: 'IDEMPOTENCY_CONFLICT' };
        }
        const existing = this.findById(previous.challengeId);
        if (existing) {
          return { kind: 'ALREADY_CREATED', challenge: existing };
        }
        // Ledger apontando para um desafio que não existe mais é estado impossível (a FK é
        // `ON DELETE CASCADE`, e nenhuma rota apaga desafio). Tratado como conflito em vez de
        // criar um segundo: o cliente relê e vê o que existe.
        return { kind: 'IDEMPOTENCY_CONFLICT' };
      }

      db.prepare(
        `INSERT INTO challenges
           (challenge_id, creator_uid, name, type, target, start_date, end_date, time_zone_id,
            starts_at, ends_at_exclusive, lifecycle, cancelled_at, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', NULL, ?, ?)`,
      ).run(
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
      );

      // O criador entra automaticamente, já aceito (§31/§42). Ele não recebe convite: convidá-lo
      // seria pedir que ele aceite o que acabou de propor.
      db.prepare(
        `INSERT INTO challenge_participants
           (challenge_id, participant_uid, role, status, joined_at, left_at)
         VALUES (?, ?, 'CREATOR', 'JOINED', ?, NULL)`,
      ).run(input.challengeId, input.creatorUid, input.now);

      const insertInvitation = db.prepare(
        `INSERT INTO challenge_invitations
           (invitation_id, challenge_id, inviter_uid, recipient_uid, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'PENDING', ?, ?)`,
      );
      for (const invitation of input.invitations) {
        insertInvitation.run(
          invitation.invitationId,
          input.challengeId,
          input.creatorUid,
          invitation.recipientUid,
          input.now,
          input.now,
        );
      }

      db.prepare(
        `INSERT INTO challenge_creation_requests
           (owner_uid, client_request_id, request_hash, challenge_id, created_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).run(
        input.creatorUid,
        input.clientRequestId,
        input.requestHash,
        input.challengeId,
        input.now,
      );

      const challenge = this.findById(input.challengeId);
      if (!challenge) {
        // Irrepresentável: o `INSERT` acima acabou de acontecer nesta transação.
        throw new Error('challenge desapareceu dentro da própria transação de criação');
      }
      return { kind: 'CREATED', challenge };
    })();
  }

  private findCreationRequest(
    ownerUid: string,
    clientRequestId: string,
  ): { requestHash: string; challengeId: string } | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT request_hash, challenge_id FROM challenge_creation_requests
          WHERE owner_uid = ? AND client_request_id = ?`,
      )
      .get(ownerUid, clientRequestId) as { request_hash: string; challenge_id: string } | undefined;
    return row ? { requestHash: row.request_hash, challengeId: row.challenge_id } : null;
  }

  /**
   * Quantos desafios **abertos** esta conta criou (§111).
   *
   * "Aberto" é `lifecycle = 'OPEN'` e a janela ainda não fechou: um desafio encerrado não ocupa
   * vaga, porque ele não é mais um compromisso — é histórico. Cancelados também não ocupam.
   */
  countOpenChallengesBy(creatorUid: string, nowMs: number): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS total FROM challenges
          WHERE creator_uid = ? AND lifecycle = 'OPEN' AND ends_at_exclusive > ?`,
      )
      .get(creatorUid, nowMs) as { total: number };
    return row.total;
  }

  // ------------------------------------------------------------------------------- leitura

  findById(challengeId: string): StoredChallenge | null {
    const row = this.sqlite.connection
      .prepare(`${CHALLENGE_COLUMNS} FROM challenges WHERE challenge_id = ?`)
      .get(challengeId) as ChallengeRow | undefined;
    return row ? toChallenge(row) : null;
  }

  /**
   * A participação de uma conta num desafio, ou `null`.
   *
   * É esta consulta que decide **todo** acesso de leitura: quem não tem linha aqui recebe
   * `CHALLENGE_NOT_FOUND`, indistinguível de um desafio inexistente (§100/§182). Conhecer o
   * `challengeId` não concede nada (§101).
   */
  findParticipation(
    challengeId: string,
    participantUid: string,
  ): { role: ChallengeRole; status: ChallengeParticipantStatus } | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT role, status FROM challenge_participants
          WHERE challenge_id = ? AND participant_uid = ?`,
      )
      .get(challengeId, participantUid) as
      | { role: ChallengeRole; status: ChallengeParticipantStatus }
      | undefined;
    return row ?? null;
  }

  /**
   * Os participantes de um desafio, com o perfil social resolvido.
   *
   * `JOINED` e `WITHDRAWN` vêm juntos: o serviço decide o que fazer com cada um (o placar
   * competitivo só mostra `JOINED`, §96, e a contagem de quem saiu é metadado). Duas consultas
   * separadas fariam a soma "ativos + saídos" precisar de duas leituras que podem discordar.
   *
   * O perfil vem por `JOIN`, e um participante cujo perfil social foi desativado **continua**
   * aparecendo com o nome que tem: a política de desativação (§117) o transforma em `WITHDRAWN`
   * na mesma operação, então ele já sai do placar competitivo por esse caminho — e não por
   * desaparecer da consulta, o que faria a contagem de participantes mudar sem que ninguém tivesse
   * saído.
   */
  listParticipants(challengeId: string): readonly StoredChallengeParticipant[] {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT cp.participant_uid, cp.role, cp.status, cp.joined_at,
                p.social_id, p.display_name
           FROM challenge_participants cp
           JOIN social_profiles p ON p.owner_uid = cp.participant_uid
          WHERE cp.challenge_id = ?
          ORDER BY cp.joined_at ASC, cp.participant_uid ASC`,
      )
      .all(challengeId) as ParticipantRow[];

    return rows.map((row) => ({
      ownerUid: row.participant_uid,
      socialId: row.social_id,
      displayName: row.display_name,
      role: row.role as ChallengeRole,
      status: row.status as ChallengeParticipantStatus,
      joinedAt: row.joined_at,
    }));
  }

  /** Quantos participantes ativos. Usado pela política para decidir `VOID` (§28). */
  countActiveParticipants(challengeId: string): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS total FROM challenge_participants
          WHERE challenge_id = ? AND status = 'JOINED'`,
      )
      .get(challengeId) as { total: number };
    return row.total;
  }

  /** Quantos convites ainda estão pendentes. Só o criador vê o número (§172), sem nomes (§173). */
  countPendingInvitations(challengeId: string): number {
    const row = this.sqlite.connection
      .prepare(
        `SELECT COUNT(*) AS total FROM challenge_invitations
          WHERE challenge_id = ? AND status = 'PENDING'`,
      )
      .get(challengeId) as { total: number };
    return row.total;
  }

  /**
   * Os desafios de que esta conta participa, paginados.
   *
   * A ordenação é `starts_at` **decrescente**, com `challenge_id` de desempate: o que está
   * acontecendo e o que vem a seguir ficam perto do topo, e o histórico desce. A categorização
   * final — ativos, próximos, encerrados (§103/§107) — é derivada do status no serviço, porque ela
   * depende do relógio e a ordenação do banco não pode depender de um valor que muda.
   *
   * Participação `WITHDRAWN` continua listada: quem saiu ainda tem direito de ver que participou,
   * e sumir com o desafio da lista dele pareceria perda de dado.
   */
  listForParticipant(participantUid: string, page: PageRequest): Page<StoredChallenge> {
    const cursorClause = page.cursor
      ? `AND (c.starts_at < ? OR (c.starts_at = ? AND c.challenge_id < ?))`
      : '';
    const cursorValues = page.cursor
      ? [Number(page.cursor.primary), Number(page.cursor.primary), page.cursor.secondary]
      : [];

    const rows = this.sqlite.connection
      .prepare(
        `${PARTICIPANT_CHALLENGES_SELECT}
         ${cursorClause}
         ORDER BY c.starts_at DESC, c.challenge_id DESC
         LIMIT ?`,
      )
      .all(participantUid, ...cursorValues, page.limit + 1) as ChallengeRow[];

    const total = (
      this.sqlite.connection
        .prepare(`SELECT COUNT(*) AS total FROM (${PARTICIPANT_CHALLENGES_SELECT})`)
        .get(participantUid) as { total: number }
    ).total;

    return paginateChallenges(rows.map(toChallenge), page.limit, total, (challenge) => ({
      primary: challenge.startsAt,
      secondary: challenge.challengeId,
    }));
  }

  // ------------------------------------------------------------------------------- convites

  findInvitationById(invitationId: string): StoredChallengeInvitation | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT invitation_id, challenge_id, inviter_uid, recipient_uid, status, created_at
           FROM challenge_invitations WHERE invitation_id = ?`,
      )
      .get(invitationId) as InvitationRow | undefined;
    return row ? toInvitation(row) : null;
  }

  /**
   * Os convites **pendentes** desta conta, com o desafio carregado.
   *
   * Só `PENDING` gravado: recusados e aceitos não voltam para a tela de convites. Os pendentes de
   * desafios cancelados ou já iniciados **vêm** — o serviço os classifica como `CANCELLED`/
   * `EXPIRED` (§36) e a tela os mostra assim, em vez de eles sumirem sem explicação depois de a
   * pessoa ter visto a notificação.
   *
   * Convite cujo **criador** desativou o Social não aparece: o `JOIN` com `social_profiles` exige
   * `ACTIVE`, a mesma regra que a T17.1 aplica às listas do grafo (§50).
   */
  listPendingInvitations(
    recipientUid: string,
    page: PageRequest,
  ): Page<StoredInvitationWithChallenge> {
    const cursorClause = page.cursor
      ? `AND (i.created_at < ? OR (i.created_at = ? AND i.invitation_id < ?))`
      : '';
    const cursorValues = page.cursor
      ? [Number(page.cursor.primary), Number(page.cursor.primary), page.cursor.secondary]
      : [];

    const rows = this.sqlite.connection
      .prepare(
        `${PENDING_INVITATIONS_SELECT}
         ${cursorClause}
         ORDER BY i.created_at DESC, i.invitation_id DESC
         LIMIT ?`,
      )
      .all(recipientUid, ...cursorValues, page.limit + 1) as InvitationWithChallengeRow[];

    const total = (
      this.sqlite.connection
        .prepare(`SELECT COUNT(*) AS total FROM (${PENDING_INVITATIONS_SELECT})`)
        .get(recipientUid) as { total: number }
    ).total;

    const items = rows.map((row) => ({
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
   *
   * O desfecho é decidido pelo `changes` do `UPDATE` condicional, e não por uma leitura anterior.
   * A corrida real: o criador cancela o desafio enquanto o convidado aceita. Uma das duas escritas
   * vence atomicamente, e a outra recebe um desfecho honesto.
   *
   * O teto de participantes é verificado **dentro** da transação (§30/§166): entre uma verificação
   * externa e o `INSERT` cabe outro `INSERT`, e o resultado seria o décimo primeiro participante.
   */
  acceptInvitation(
    invitationId: string,
    challengeId: string,
    recipientUid: string,
    maxParticipants: number,
    now: number,
  ): AcceptInvitationOutcome {
    const db = this.sqlite.connection;

    return db.transaction((): AcceptInvitationOutcome => {
      const changed = db
        .prepare(
          `UPDATE challenge_invitations SET status = 'ACCEPTED', updated_at = ?
            WHERE invitation_id = ? AND status = 'PENDING'`,
        )
        .run(now, invitationId).changes;

      if (changed === 0) {
        // Ninguém aceitou agora. Se a participação existe, este aceite é a repetição de um que já
        // funcionou — e repetir uma operação bem-sucedida não é erro (§191).
        const participation = this.findParticipation(challengeId, recipientUid);
        if (participation) {
          return { kind: 'ALREADY_PARTICIPATING' };
        }
        return { kind: 'NOT_PENDING' };
      }

      // `JOINED` **e** `WITHDRAWN` ocupam vaga na contagem? Não: quem saiu liberou o lugar, e não
      // há rejoin (§66), então a vaga não pode ser retomada por ele. Contar só os ativos é o que
      // permite a um desafio de 10 continuar recebendo aceites depois de alguém sair.
      if (this.countActiveParticipants(challengeId) >= maxParticipants) {
        // A transação inteira é desfeita, inclusive o `UPDATE` acima: o convite volta a
        // `PENDING`, e não fica aceito num desafio de que a pessoa não participa.
        throw new ChallengeFullError();
      }

      db.prepare(
        `INSERT INTO challenge_participants
           (challenge_id, participant_uid, role, status, joined_at, left_at)
         VALUES (?, ?, 'MEMBER', 'JOINED', ?, NULL)
         ON CONFLICT (challenge_id, participant_uid) DO NOTHING`,
      ).run(challengeId, recipientUid, now);

      return { kind: 'ACCEPTED' };
    })();
  }

  /**
   * Recusa um convite pendente. `true` quando **esta** chamada foi a que mudou.
   *
   * `false` não é falha: é "outra escrita chegou antes". Quem chamou decide o que isso significa —
   * recusar duas vezes é idempotente (§192), recusar algo já aceito não é.
   */
  declineInvitation(invitationId: string, now: number): boolean {
    return (
      this.sqlite.connection
        .prepare(
          `UPDATE challenge_invitations SET status = 'DECLINED', updated_at = ?
            WHERE invitation_id = ? AND status = 'PENDING'`,
        )
        .run(now, invitationId).changes > 0
    );
  }

  // ------------------------------------------------------------------------------- saída

  /**
   * Sai do desafio. `true` quando **esta** chamada foi a que mudou (§193).
   *
   * A linha **não** é apagada (§65): ela vira `WITHDRAWN` com `left_at`, e continua registrando
   * que aquela pessoa participou. Apagar tornaria "saiu" indistinguível de "nunca entrou", e a
   * integridade histórica do desafio dependeria de todo mundo ter ficado até o fim.
   *
   * `WITHDRAWN` é terminal: não há rejoin nesta fase (§66), e o `WHERE status = 'JOINED'` é o que
   * garante isso mesmo sob duas requisições simultâneas.
   */
  leave(challengeId: string, participantUid: string, now: number): boolean {
    return (
      this.sqlite.connection
        .prepare(
          `UPDATE challenge_participants SET status = 'WITHDRAWN', left_at = ?
            WHERE challenge_id = ? AND participant_uid = ? AND status = 'JOINED'
              AND role = 'MEMBER'`,
        )
        .run(now, challengeId, participantUid).changes > 0
    );
  }

  /**
   * Cancela o desafio. `true` quando **esta** chamada foi a que mudou (§194).
   *
   * Uma escrita, sobre uma linha. Os convites pendentes **não** são reescritos: eles passam a ser
   * lidos como `CANCELLED` (§36), derivado do desafio. Reescrevê-los custaria N escritas para
   * gravar uma informação que já é dedutível — e a dedução não pode divergir da fonte.
   *
   * Nada é apagado (§138/§139): o desafio continua existindo, com resultado nenhum (§69/§94), e os
   * participantes continuam podendo abri-lo para ver que ele foi cancelado.
   */
  cancel(challengeId: string, now: number): boolean {
    return (
      this.sqlite.connection
        .prepare(
          `UPDATE challenges SET lifecycle = 'CANCELLED', cancelled_at = ?, updated_at = ?
            WHERE challenge_id = ? AND lifecycle = 'OPEN'`,
        )
        .run(now, now, challengeId).changes > 0
    );
  }

  // ------------------------------------------------------------------------------- desativação

  /**
   * Os desafios **ainda não encerrados** que esta conta criou (§118).
   *
   * Usado quando alguém desativa o Social: o criador não pode deixar para trás um desafio que
   * ninguém mais consegue encerrar.
   */
  openChallengesCreatedBy(creatorUid: string, nowMs: number): readonly string[] {
    return (
      this.sqlite.connection
        .prepare(
          `SELECT challenge_id FROM challenges
            WHERE creator_uid = ? AND lifecycle = 'OPEN' AND ends_at_exclusive > ?`,
        )
        .all(creatorUid, nowMs) as { challenge_id: string }[]
    ).map((row) => row.challenge_id);
  }

  /**
   * Tira esta conta de todos os desafios ainda não encerrados em que ela participa como membro
   * (§117), e recusa os convites pendentes dela (§116).
   *
   * Chamado dentro da transação de desativar o Social. Devolve quantos participações e convites
   * foram alterados — para o log, que registra contagem e nunca identificadores.
   *
   * A razão é o consentimento: participar de um desafio é consentir em compartilhar a pontuação
   * daquele desafio (§123). Desligar os recursos sociais retira esse consentimento, e continuar
   * publicando a pontuação de alguém que desligou o Social seria manter um compartilhamento que a
   * pessoa acabou de encerrar.
   *
   * Desafios **encerrados** não são tocados (§120): o resultado deles é histórico, e reescrevê-lo
   * apagaria um fato de que outras pessoas participaram.
   */
  withdrawFromOpenChallenges(participantUid: string, nowMs: number): number {
    return this.sqlite.connection
      .prepare(
        `UPDATE challenge_participants SET status = 'WITHDRAWN', left_at = ?
          WHERE participant_uid = ? AND status = 'JOINED' AND role = 'MEMBER'
            AND challenge_id IN (
                SELECT challenge_id FROM challenges
                 WHERE lifecycle = 'OPEN' AND ends_at_exclusive > ?
            )`,
      )
      .run(nowMs, participantUid, nowMs).changes;
  }

  /** Recusa todos os convites pendentes desta conta (§116). Parte da mesma transação. */
  declinePendingInvitationsOf(recipientUid: string, nowMs: number): number {
    return this.sqlite.connection
      .prepare(
        `UPDATE challenge_invitations SET status = 'DECLINED', updated_at = ?
          WHERE recipient_uid = ? AND status = 'PENDING'`,
      )
      .run(nowMs, recipientUid).changes;
  }

  /** Cancela os desafios abertos criados por esta conta (§118). Parte da mesma transação. */
  cancelOpenChallengesCreatedBy(creatorUid: string, nowMs: number): number {
    return this.sqlite.connection
      .prepare(
        `UPDATE challenges SET lifecycle = 'CANCELLED', cancelled_at = ?, updated_at = ?
          WHERE creator_uid = ? AND lifecycle = 'OPEN' AND ends_at_exclusive > ?`,
      )
      .run(nowMs, nowMs, creatorUid, nowMs).changes;
  }

  /**
   * O efeito de **desativar o Social** sobre os desafios (T17.3 §115–§120).
   *
   * ```text
   * convites pendentes recebidos          ──▶ DECLINED    (§116)
   * participações como MEMBER, em aberto  ──▶ WITHDRAWN   (§117)
   * desafios abertos que eu criei         ──▶ CANCELLED   (§118)
   * desafios já encerrados                ──▶ intocados   (§120)
   * ```
   *
   * ## Por que desativar precisa alcançar os desafios
   *
   * Porque participar é **consentir em compartilhar a pontuação daquele desafio** (§123), e
   * desligar os recursos sociais retira esse consentimento. Sem isto, alguém que desativasse o
   * Social continuaria com a própria pontuação atualizando no placar dos outros — que é o
   * bloqueante "disable Social continua compartilhando progresso ativo sem política".
   *
   * ## Por que o criador tem de cancelar, e não só sair
   *
   * Um desafio sem criador ativo é um desafio que ninguém mais pode encerrar (§62/§118). Cancelar
   * é honesto com os outros participantes: ele acaba para todos, sem resultado, em vez de ficar
   * correndo com regras que perderam o dono.
   *
   * ## Encerrados não são tocados
   *
   * O resultado de um desafio que já acabou é histórico do qual **outras** pessoas participaram, e
   * reescrevê-lo apagaria um fato delas para atender a decisão de uma. Desativar suspende
   * participação futura; ele não reescreve o passado — a mesma regra que a T17.1 aplica à amizade.
   *
   * A ordem importa: os desafios criados são cancelados **por último**, porque `withdraw` filtra
   * por `lifecycle = 'OPEN'` e cancelar antes deixaria as participações de membro do próprio
   * criador (que não existem, mas a ordem não deve depender disso) fora do alcance.
   *
   * Chamado **dentro** da transação que muda o status do perfil (§119). Devolve contagens, e
   * apenas contagens: o log registra quantos, nunca quais.
   */
  applySocialDisable(
    ownerUid: string,
    nowMs: number,
  ): { declinedInvitations: number; withdrawnFrom: number; cancelledChallenges: number } {
    return {
      declinedInvitations: this.declinePendingInvitationsOf(ownerUid, nowMs),
      withdrawnFrom: this.withdrawFromOpenChallenges(ownerUid, nowMs),
      cancelledChallenges: this.cancelOpenChallengesCreatedBy(ownerUid, nowMs),
    };
  }
}

/**
 * O desafio encheu entre a verificação e o `INSERT`.
 *
 * Erro, e não valor de retorno, porque ele precisa **desfazer a transação**: o `UPDATE` que marcou
 * o convite como aceito já aconteceu, e deixá-lo valendo produziria um convite aceito sem
 * participação — a pessoa acreditaria estar no desafio.
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
   WHERE cp.participant_uid = ?
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
   WHERE i.recipient_uid = ?
     AND i.status = 'PENDING'
     AND p.status = 'ACTIVE'
`;

interface ChallengeRow {
  challenge_id: string;
  creator_uid: string;
  name: string;
  type: string;
  target: number;
  start_date: string;
  end_date: string;
  time_zone_id: string;
  starts_at: number;
  ends_at_exclusive: number;
  lifecycle: string;
  cancelled_at: number | null;
  created_at: number;
  challenge_created_at?: number;
  updated_at: number;
}

interface ParticipantRow {
  participant_uid: string;
  social_id: string;
  display_name: string;
  role: string;
  status: string;
  joined_at: number;
}

interface InvitationRow {
  invitation_id: string;
  challenge_id: string;
  inviter_uid: string;
  recipient_uid: string;
  status: string;
  created_at: number;
}

interface InvitationWithChallengeRow extends InvitationRow, ChallengeRow {}

function toChallenge(row: ChallengeRow): StoredChallenge {
  return {
    challengeId: row.challenge_id,
    creatorUid: row.creator_uid,
    name: row.name,
    type: row.type as ChallengeType,
    target: row.target,
    startDate: row.start_date,
    endDate: row.end_date,
    timeZoneId: row.time_zone_id,
    startsAt: row.starts_at,
    endsAtExclusive: row.ends_at_exclusive,
    lifecycle: row.lifecycle as 'OPEN' | 'CANCELLED',
    cancelledAt: row.cancelled_at,
    // Na listagem de convites as duas tabelas têm `created_at`; o do desafio vem com alias para
    // que o do convite não o sobrescreva. Sem isto, a data de criação do desafio seria a do
    // convite — e um desafio pareceria criado depois de ter sido convidado para ele.
    createdAt: row.challenge_created_at ?? row.created_at,
    updatedAt: row.updated_at,
  };
}

function toInvitation(row: InvitationRow): StoredChallengeInvitation {
  return {
    invitationId: row.invitation_id,
    challengeId: row.challenge_id,
    inviterUid: row.inviter_uid,
    recipientUid: row.recipient_uid,
    status: row.status as 'PENDING' | 'ACCEPTED' | 'DECLINED',
    createdAt: row.created_at,
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
