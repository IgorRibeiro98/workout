import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type {
  FriendRelationship,
  FriendRequestDirection,
  FriendRequestStatus,
} from './friendship.contract';
import type { SocialProfileStatus } from './social.contract';

/**
 * O recorte de um perfil que o grafo precisa: identidade, status e as duas flags de privacidade.
 *
 * `ownerUid` existe aqui porque o banco relaciona por ele — e **nunca** sai em DTO. A fronteira
 * onde o Firebase UID para de existir é a projeção (`toPreview`/`toFriend` no serviço).
 */
export interface FriendProfileRow {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly displayName: string;
  readonly status: SocialProfileStatus;
  readonly discoverability: string;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly activityTimeZoneId: string | null;
  readonly friendRankingParticipationEnabled: boolean;
}

export interface StoredFriendRequest {
  readonly requestId: string;
  readonly requesterUid: string;
  readonly recipientUid: string;
  readonly status: FriendRequestStatus;
  readonly createdAt: number;
  readonly updatedAt: number;
}

/** Um pedido com o perfil da **outra** pessoa já resolvido — o que a listagem devolve. */
export interface FriendRequestWithProfile extends StoredFriendRequest {
  readonly direction: FriendRequestDirection;
  readonly otherSocialId: string;
  readonly otherDisplayName: string;
}

export interface StoredFriend {
  readonly socialId: string;
  readonly displayName: string;
  readonly friendsSince: number;
}

export interface PageRequest {
  readonly limit: number;
  readonly cursor: ListCursor | null;
}

/** A chave de continuação de uma listagem: sempre o par (chave de ordenação, desempate). */
export interface ListCursor {
  readonly primary: string | number;
  readonly secondary: string;
}

export interface Page<T> {
  readonly items: readonly T[];
  readonly nextCursor: ListCursor | null;
  readonly total: number;
}

/** O desfecho de um envio, decidido **dentro** da transação. */
export type SendRequestOutcome =
  | { readonly kind: 'CREATED'; readonly request: StoredFriendRequest }
  | { readonly kind: 'ALREADY_PENDING'; readonly request: StoredFriendRequest }
  | { readonly kind: 'FRIENDSHIP_CREATED'; readonly friendsSince: number }
  | { readonly kind: 'ALREADY_FRIENDS' };

/** O desfecho de um aceite, decidido **dentro** da transação. */
export type AcceptOutcome =
  | { readonly kind: 'ACCEPTED'; readonly friendsSince: number }
  | { readonly kind: 'ALREADY_FRIENDS'; readonly friendsSince: number }
  | { readonly kind: 'NOT_PENDING' };

/**
 * A persistência do grafo social (T17.1).
 *
 * ## As três garantias que moram no banco, e não na disciplina do serviço
 *
 * 1. **o par é único e simétrico** — `friendships` tem chave primária `(user_a_uid, user_b_uid)` e
 *    `CHECK (user_a_uid < user_b_uid)`. Amizade duplicada A-B, duplicada B-A e amizade consigo
 *    mesmo são **irrepresentáveis**, inclusive sob duas transações simultâneas;
 * 2. **um pendente por direção** — índice único parcial `WHERE status = 'PENDING'`. Reenviar não
 *    duplica, e pedir de novo depois de uma recusa continua possível, porque a linha terminal não
 *    ocupa a constraint;
 * 3. **transição é escrita condicional** — aceitar/recusar/cancelar é
 *    `UPDATE ... WHERE request_id = ? AND status = 'PENDING'`, e é o número de linhas afetadas que
 *    decide o desfecho. Uma verificação em memória antes do `UPDATE` perderia a corrida de §32
 *    (A cancela enquanto B aceita) e produziria os dois estados ao mesmo tempo.
 *
 * ## Sobre ler perfil por `friend_code` e por `social_id`
 *
 * A T17.0 registrou que não existia consulta de perfil que não fosse por `owner_uid`, porque
 * qualquer outra seria enumeração. As duas que nascem aqui **não são** enumeração, e a diferença é
 * estrutural: elas são igualdade sobre chave **única**, devolvem no máximo uma linha, exigem
 * conhecer o identificador inteiro e têm teto próprio de rate limit. Não existe — e continua não
 * podendo existir — `LIKE`, prefixo, `ORDER BY created_at`, busca por nome, busca por e-mail ou
 * qualquer consulta que devolva perfis que o chamador não nomeou.
 */
@Injectable()
export class FriendshipRepository {
  constructor(private readonly sqlite: SqliteService) {}

  // ------------------------------------------------------------------------------- perfis

  /**
   * O perfil de um `friendCode` **canônico**, com igualdade exata.
   *
   * A normalização acontece antes, no serviço, com a função da T17.0 — e o que está gravado é
   * sempre a forma canônica. Nada de `LIKE`, `COLLATE NOCASE`, prefixo ou distância de edição:
   * *fuzzy matching* aqui significaria devolver o perfil de outra pessoa para quem digitou errado.
   */
  findProfileByFriendCode(canonicalFriendCode: string): FriendProfileRow | null {
    return this.selectProfile('p.friend_code = ?', canonicalFriendCode);
  }

  /** O perfil de um `socialId`. Igualdade exata sobre índice único, como acima. */
  findProfileBySocialId(socialId: string): FriendProfileRow | null {
    return this.selectProfile('p.social_id = ?', socialId);
  }

  /** O perfil da própria conta autenticada. */
  findProfileByOwnerUid(ownerUid: string): FriendProfileRow | null {
    return this.selectProfile('p.owner_uid = ?', ownerUid);
  }

  private selectProfile(where: string, value: string): FriendProfileRow | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT p.owner_uid, p.social_id, p.display_name, p.status,
                s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
                s.activity_time_zone_id, s.friend_ranking_participation_enabled
         FROM social_profiles p
         JOIN social_privacy_settings s ON s.owner_uid = p.owner_uid
         WHERE ${where}`,
      )
      .get(value) as ProfileRow | undefined;

    return row ? toProfile(row) : null;
  }

  // ------------------------------------------------------------------------------- relação

  /** A relação que já existe entre duas contas, do ponto de vista de [viewerUid]. */
  relationship(viewerUid: string, otherUid: string): FriendRelationship {
    if (this.areFriends(viewerUid, otherUid)) {
      return 'FRIENDS';
    }
    if (this.findPendingRequest(viewerUid, otherUid)) {
      return 'OUTGOING_PENDING';
    }
    if (this.findPendingRequest(otherUid, viewerUid)) {
      return 'INCOMING_PENDING';
    }
    return 'NONE';
  }

  areFriends(uidA: string, uidB: string): boolean {
    const [a, b] = canonicalPair(uidA, uidB);
    const row = this.sqlite.connection
      .prepare(`SELECT 1 FROM friendships WHERE user_a_uid = ? AND user_b_uid = ?`)
      .get(a, b);
    return row !== undefined;
  }

  findPendingRequest(requesterUid: string, recipientUid: string): StoredFriendRequest | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT request_id, requester_uid, recipient_uid, status, created_at, updated_at
         FROM friend_requests
         WHERE requester_uid = ? AND recipient_uid = ? AND status = 'PENDING'`,
      )
      .get(requesterUid, recipientUid) as RequestRow | undefined;
    return row ? toRequest(row) : null;
  }

  findRequestById(requestId: string): StoredFriendRequest | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT request_id, requester_uid, recipient_uid, status, created_at, updated_at
         FROM friend_requests WHERE request_id = ?`,
      )
      .get(requestId) as RequestRow | undefined;
    return row ? toRequest(row) : null;
  }

  // ------------------------------------------------------------------------------- envio

  /**
   * Envia um pedido — ou resolve o cruzamento, na **mesma** transação.
   *
   * ```text
   * já são amigos            ──▶ ALREADY_FRIENDS       (nada é escrito)
   * já existe A → B PENDING  ──▶ ALREADY_PENDING       (nada é escrito; idempotente)
   * existe B → A PENDING     ──▶ FRIENDSHIP_CREATED    (pedido de B vira ACCEPTED + amizade)
   * nada                     ──▶ CREATED
   * ```
   *
   * O caso do cruzamento é o que **exige** a transação: marcar o pedido inverso como aceito e
   * criar a amizade são uma decisão só. Fora de uma transação, uma falha entre as duas escritas
   * deixaria uma amizade cujo pedido continua pendente na tela do outro — ou, pior, um pedido
   * aceito sem amizade nenhuma.
   */
  sendRequest(input: {
    readonly requestId: string;
    readonly requesterUid: string;
    readonly recipientUid: string;
    readonly now: number;
  }): SendRequestOutcome {
    const db = this.sqlite.connection;

    return db.transaction((): SendRequestOutcome => {
      if (this.areFriends(input.requesterUid, input.recipientUid)) {
        return { kind: 'ALREADY_FRIENDS' };
      }

      const existing = this.findPendingRequest(input.requesterUid, input.recipientUid);
      if (existing) {
        // Reenvio depois de resposta perdida, ou toque duplo. A situação lógica é a que o
        // cliente queria: devolver o mesmo pedido é a resposta correta, e não um erro.
        return { kind: 'ALREADY_PENDING', request: existing };
      }

      const inverse = this.findPendingRequest(input.recipientUid, input.requesterUid);
      if (inverse) {
        // Consentimento bilateral já expresso pelos dois: a amizade nasce agora (§26/§27).
        db.prepare(
          `UPDATE friend_requests SET status = 'ACCEPTED', updated_at = ?
           WHERE request_id = ? AND status = 'PENDING'`,
        ).run(input.now, inverse.requestId);
        this.insertFriendship(input.requesterUid, input.recipientUid, input.now);
        return { kind: 'FRIENDSHIP_CREATED', friendsSince: input.now };
      }

      db.prepare(
        `INSERT INTO friend_requests
           (request_id, requester_uid, recipient_uid, status, created_at, updated_at)
         VALUES (?, ?, ?, 'PENDING', ?, ?)`,
      ).run(input.requestId, input.requesterUid, input.recipientUid, input.now, input.now);

      return {
        kind: 'CREATED',
        request: {
          requestId: input.requestId,
          requesterUid: input.requesterUid,
          recipientUid: input.recipientUid,
          status: 'PENDING',
          createdAt: input.now,
          updatedAt: input.now,
        },
      };
    })();
  }

  // ------------------------------------------------------------------------------- respostas

  /**
   * Aceita um pedido: marca `ACCEPTED` **e** cria a amizade, ou não faz nenhuma das duas.
   *
   * A ordem importa menos que o fato de as duas estarem na mesma transação. O que decide o
   * desfecho é o `changes` do `UPDATE` condicional: se ele afetou zero linhas, outra escrita
   * venceu — e a resposta depende de qual. Aceito de novo (resposta perdida, toque duplo) devolve
   * `ALREADY_FRIENDS`, que é sucesso; cancelado/recusado no meio devolve `NOT_PENDING`.
   */
  acceptRequest(requestId: string, now: number): AcceptOutcome {
    const db = this.sqlite.connection;

    return db.transaction((): AcceptOutcome => {
      const request = this.findRequestById(requestId);
      if (!request) {
        return { kind: 'NOT_PENDING' };
      }

      const changed = db
        .prepare(
          `UPDATE friend_requests SET status = 'ACCEPTED', updated_at = ?
           WHERE request_id = ? AND status = 'PENDING'`,
        )
        .run(now, requestId).changes;

      if (changed === 0) {
        // Ninguém aceitou agora. Se a amizade existe, este aceite é a repetição de um que já
        // funcionou — e repetir uma operação bem-sucedida não é erro.
        const since = this.friendshipCreatedAt(request.requesterUid, request.recipientUid);
        if (request.status === 'ACCEPTED' && since !== null) {
          return { kind: 'ALREADY_FRIENDS', friendsSince: since };
        }
        return { kind: 'NOT_PENDING' };
      }

      this.insertFriendship(request.requesterUid, request.recipientUid, now);
      const since = this.friendshipCreatedAt(request.requesterUid, request.recipientUid) ?? now;
      return { kind: 'ACCEPTED', friendsSince: since };
    })();
  }

  /**
   * Muda o status de um pedido pendente. `true` quando **esta** chamada foi a que mudou.
   *
   * `false` não é falha: é "outra escrita chegou antes". Quem chamou decide o que isso significa
   * — recusar duas vezes é idempotente, recusar algo que foi cancelado não é.
   */
  resolveRequest(requestId: string, status: FriendRequestStatus, now: number): boolean {
    return (
      this.sqlite.connection
        .prepare(
          `UPDATE friend_requests SET status = ?, updated_at = ?
           WHERE request_id = ? AND status = 'PENDING'`,
        )
        .run(status, now, requestId).changes > 0
    );
  }

  // ------------------------------------------------------------------------------- amizade

  /**
   * Cria a amizade na forma canônica.
   *
   * `ON CONFLICT DO NOTHING` porque a chave primária já garante o par único: quando duas
   * transações tentam criar a mesma amizade, a segunda não deve explodir — ela deve concluir que
   * o estado desejado já existe. `created_at` da primeira é preservado.
   */
  private insertFriendship(uidA: string, uidB: string, now: number): void {
    const [a, b] = canonicalPair(uidA, uidB);
    this.sqlite.connection
      .prepare(
        `INSERT INTO friendships (user_a_uid, user_b_uid, created_at) VALUES (?, ?, ?)
         ON CONFLICT (user_a_uid, user_b_uid) DO NOTHING`,
      )
      .run(a, b, now);
  }

  friendshipCreatedAt(uidA: string, uidB: string): number | null {
    const [a, b] = canonicalPair(uidA, uidB);
    const row = this.sqlite.connection
      .prepare(`SELECT created_at FROM friendships WHERE user_a_uid = ? AND user_b_uid = ?`)
      .get(a, b) as { created_at: number } | undefined;
    return row ? row.created_at : null;
  }

  /**
   * Desfaz a amizade. `true` quando havia uma.
   *
   * Uma linha, e só ela: nenhum treino, backup, sync, sessão, medida, gamificação ou perfil é
   * tocado — este módulo não alcança nada disso. O pedido histórico que originou a amizade
   * também fica como está: ele registra que um dia alguém pediu e alguém aceitou, e reescrevê-lo
   * seria apagar um fato para simplificar uma consulta.
   */
  removeFriendship(uidA: string, uidB: string): boolean {
    const [a, b] = canonicalPair(uidA, uidB);
    return (
      this.sqlite.connection
        .prepare(`DELETE FROM friendships WHERE user_a_uid = ? AND user_b_uid = ?`)
        .run(a, b).changes > 0
    );
  }

  // ------------------------------------------------------------------------------- listagens

  /**
   * Os amigos de uma conta, em ordem estável e paginada.
   *
   * **Perfil desativado não aparece** (§49): a amizade continua gravada e volta inteira quando a
   * pessoa reativar, mas enquanto ela estiver desativada não há perfil social para mostrar.
   * Exibi-lo como um amigo normal contradiria o que desativar significa; exibir "usuário
   * indisponível" contaria a quem olha uma coisa que é do outro.
   *
   * A ordenação é `display_name`, `social_id` — alfabética e determinística. A comparação é
   * binária (SQLite), então maiúsculas ordenam antes de minúsculas; isso é cosmético e estável.
   * Uma ordenação sensível a locale exigiria uma coluna de chave de ordenação normalizada, e ela
   * não existe porque nenhuma tela precisa disso hoje.
   */
  listFriends(ownerUid: string, page: PageRequest): Page<StoredFriend> {
    const cursorClause = page.cursor
      ? `AND (p.display_name > ? OR (p.display_name = ? AND p.social_id > ?))`
      : '';
    const cursorValues = page.cursor
      ? [String(page.cursor.primary), String(page.cursor.primary), page.cursor.secondary]
      : [];

    const rows = this.sqlite.connection
      .prepare(
        `${FRIENDS_SELECT}
         ${cursorClause}
         ORDER BY p.display_name ASC, p.social_id ASC
         LIMIT ?`,
      )
      .all(ownerUid, ownerUid, ownerUid, ...cursorValues, page.limit + 1) as FriendRow[];

    const total = (
      this.sqlite.connection
        .prepare(`SELECT COUNT(*) AS total FROM (${FRIENDS_SELECT})`)
        .get(ownerUid, ownerUid, ownerUid) as { total: number }
    ).total;

    return paginate(
      rows.map((row) => ({
        socialId: row.social_id,
        displayName: row.display_name,
        friendsSince: row.created_at,
      })),
      page.limit,
      total,
      (friend) => ({ primary: friend.displayName, secondary: friend.socialId }),
    );
  }

  /**
   * Os pedidos pendentes de uma conta, mais recentes primeiro.
   *
   * **Pedido cuja outra ponta está desativada não aparece** (§50): ele fica suspenso, não
   * cancelado. Cancelar por conta própria decidiria, no lugar das duas pessoas, que aquele convite
   * não vale mais — e reativar não teria como desfazer isso.
   */
  listRequests(
    ownerUid: string,
    direction: FriendRequestDirection,
    page: PageRequest,
  ): Page<FriendRequestWithProfile> {
    const [own, other] =
      direction === 'INCOMING'
        ? ['fr.recipient_uid', 'fr.requester_uid']
        : ['fr.requester_uid', 'fr.recipient_uid'];

    const select = requestsSelect(own, other);
    const cursorClause = page.cursor
      ? `AND (fr.created_at < ? OR (fr.created_at = ? AND fr.request_id < ?))`
      : '';
    const cursorValues = page.cursor
      ? [Number(page.cursor.primary), Number(page.cursor.primary), page.cursor.secondary]
      : [];

    const rows = this.sqlite.connection
      .prepare(
        `${select}
         ${cursorClause}
         ORDER BY fr.created_at DESC, fr.request_id DESC
         LIMIT ?`,
      )
      .all(ownerUid, ...cursorValues, page.limit + 1) as RequestWithProfileRow[];

    const total = (
      this.sqlite.connection.prepare(`SELECT COUNT(*) AS total FROM (${select})`).get(ownerUid) as {
        total: number;
      }
    ).total;

    return paginate(
      rows.map((row) => ({
        requestId: row.request_id,
        requesterUid: row.requester_uid,
        recipientUid: row.recipient_uid,
        status: row.status as FriendRequestStatus,
        createdAt: row.created_at,
        updatedAt: row.updated_at,
        direction,
        otherSocialId: row.other_social_id,
        otherDisplayName: row.other_display_name,
      })),
      page.limit,
      total,
      (request) => ({ primary: request.createdAt, secondary: request.requestId }),
    );
  }

  /**
   * Retorna todos os amigos diretos cujo perfil está ACTIVE, acompanhados de suas preferências
   * de privacidade (T17.4). Usado nas projeções de atividade e ranking contextual.
   */
  findActiveFriends(ownerUid: string): readonly FriendProfileRow[] {
    const rows = this.sqlite.connection
      .prepare(
        `SELECT p.owner_uid, p.social_id, p.display_name, p.status,
                s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
                s.activity_time_zone_id, s.friend_ranking_participation_enabled
         FROM friendships f
         JOIN social_profiles p
           ON p.owner_uid = CASE WHEN f.user_a_uid = ? THEN f.user_b_uid ELSE f.user_a_uid END
         JOIN social_privacy_settings s
           ON s.owner_uid = p.owner_uid
         WHERE (f.user_a_uid = ? OR f.user_b_uid = ?)
           AND p.status = 'ACTIVE'
         ORDER BY p.display_name ASC, p.social_id ASC`,
      )
      .all(ownerUid, ownerUid, ownerUid) as ProfileRow[];

    return rows.map(toProfile);
  }
}

/**
 * O par canônico: menor uid primeiro.
 *
 * É a **única** forma de escrever um par, e é o que faz `A-B` e `B-A` serem literalmente a mesma
 * linha. Sem ela, "são amigos?" precisaria de duas consultas e "criar amizade" poderia produzir
 * duas linhas para a mesma relação — o estado que o produto não tem como representar.
 */
export function canonicalPair(uidA: string, uidB: string): [string, string] {
  return uidA < uidB ? [uidA, uidB] : [uidB, uidA];
}

/**
 * Os amigos **ativos** de uma conta, dos dois lados do par.
 *
 * O `?` aparece três vezes: duas para descobrir qual coluna é "o outro" e uma para excluir a
 * própria conta do resultado.
 */
const FRIENDS_SELECT = `
  SELECT p.social_id, p.display_name, f.created_at
  FROM friendships f
  JOIN social_profiles p
    ON p.owner_uid = CASE WHEN f.user_a_uid = ? THEN f.user_b_uid ELSE f.user_a_uid END
  WHERE (f.user_a_uid = ? OR f.user_b_uid = ?)
    AND p.status = 'ACTIVE'
`;

function requestsSelect(ownColumn: string, otherColumn: string): string {
  return `
  SELECT fr.request_id, fr.requester_uid, fr.recipient_uid, fr.status, fr.created_at,
         fr.updated_at, p.social_id AS other_social_id, p.display_name AS other_display_name
  FROM friend_requests fr
  JOIN social_profiles p ON p.owner_uid = ${otherColumn}
  WHERE ${ownColumn} = ?
    AND fr.status = 'PENDING'
    AND p.status = 'ACTIVE'
`;
}

/**
 * Corta a página no limite pedido e decide se há continuação.
 *
 * A consulta pede `limit + 1` de propósito: é o que permite responder "há mais" sem uma segunda
 * consulta e sem depender de comparar `total` com o que já foi entregue.
 */
function paginate<T>(
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

interface ProfileRow {
  owner_uid: string;
  social_id: string;
  display_name: string;
  status: string;
  discoverability: string;
  friend_requests_enabled: number;
  activity_sharing_enabled: number;
  activity_time_zone_id: string | null;
  friend_ranking_participation_enabled: number;
}

interface RequestRow {
  request_id: string;
  requester_uid: string;
  recipient_uid: string;
  status: string;
  created_at: number;
  updated_at: number;
}

interface RequestWithProfileRow extends RequestRow {
  other_social_id: string;
  other_display_name: string;
}

interface FriendRow {
  social_id: string;
  display_name: string;
  created_at: number;
}

function toProfile(row: ProfileRow): FriendProfileRow {
  return {
    ownerUid: row.owner_uid,
    socialId: row.social_id,
    displayName: row.display_name,
    status: row.status as SocialProfileStatus,
    discoverability: row.discoverability,
    friendRequestsEnabled: row.friend_requests_enabled === 1,
    activitySharingEnabled: row.activity_sharing_enabled === 1,
    activityTimeZoneId: row.activity_time_zone_id,
    friendRankingParticipationEnabled: row.friend_ranking_participation_enabled === 1,
  };
}

function toRequest(row: RequestRow): StoredFriendRequest {
  return {
    requestId: row.request_id,
    requesterUid: row.requester_uid,
    recipientUid: row.recipient_uid,
    status: row.status as FriendRequestStatus,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}
