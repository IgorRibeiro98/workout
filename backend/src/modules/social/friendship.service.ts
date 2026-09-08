import { Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type {
  AcceptFriendRequestResponseDto,
  CancelFriendRequestResponseDto,
  FriendDto,
  FriendListResponseDto,
  FriendLookupResponseDto,
  FriendRequestDto,
  FriendRequestListResponseDto,
  RejectFriendRequestResponseDto,
  RemoveFriendResponseDto,
  SendFriendRequestResponseDto,
} from './friendship.contract';
import { FriendshipAccessPolicy } from './friendship.access-policy';
import { FriendshipErrors } from './friendship.errors';
import { FriendshipRateLimiter } from './friendship.rate-limit';
import {
  type FriendProfileRow,
  type FriendRequestWithProfile,
  FriendshipRepository,
  type StoredFriend,
  type StoredFriendRequest,
} from './friendship.repository';
import { encodeCursor, type ListQuery } from './friendship.validator';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialErrors } from './social.errors';
import { generateFriendRequestId, normalizeFriendCode } from './social.identity';
import type { SocialProfilePreviewDto } from './social.contract';

/**
 * O caso de uso do grafo social (T17.1).
 *
 * ```text
 * friendCode ─normalizar─▶ match exato ─▶ preview ─▶ FriendRequest ─aceitar─▶ Friendship(A,B)
 * ```
 *
 * ## Três invariantes que este arquivo existe para sustentar
 *
 * 1. **descoberta é só por código exato.** Não há busca por nome, por e-mail, listagem global,
 *    sugestão, prefixo ou *fuzzy matching* — nem aqui, nem no repositório, nem em rota nenhuma. A
 *    única pergunta que o servidor responde sobre um perfil alheio é "quem é o dono deste código
 *    exato", e ela tem teto próprio de requisições;
 * 2. **o Firebase UID nunca sai.** Ele é a chave de tudo aqui dentro e não aparece em nenhum DTO:
 *    [toPreview] e [toFriend] são a fronteira onde ele para de existir. O que circula é
 *    `socialId`;
 * 3. **o servidor decide sempre de novo.** O preview que o app guardou não autoriza nada: entre o
 *    lookup e o envio, o outro lado pode ter desativado o social, desligado os pedidos ou virado
 *    amigo por outro caminho. Cada mutação revalida do zero (§114–§116).
 */
@Injectable()
export class FriendshipService {
  constructor(
    private readonly repository: FriendshipRepository,
    private readonly socialPolicy: SocialAccessPolicy,
    private readonly policy: FriendshipAccessPolicy,
    private readonly limiter: FriendshipRateLimiter,
    private readonly logger: SparkLogger,
  ) {}

  // --------------------------------------------------------------------------------- lookup

  /**
   * `POST /v1/social/friends/lookup`.
   *
   * ```text
   * entrada ─normalizar(T17.0)─▶ null ────────────────────────▶ NOT_FOUND
   *                            │
   *                            └─ canônico ─▶ match exato ─▶ ausente ──▶ NOT_FOUND
   *                                                       ├─ eu ───────▶ SELF
   *                                                       ├─ DISABLED ─▶ NOT_FOUND
   *                                                       └─ ACTIVE ───▶ FOUND + preview
   * ```
   *
   * Código malformado, código inexistente e código de perfil desativado respondem **a mesma
   * coisa**, e nenhum dos três toca a mesma quantidade de trabalho a mais — a resposta não conta
   * qual dos três aconteceu. É isso que impede a rota de virar oráculo de existência.
   *
   * Consultar não envia pedido, não cria relação e não escreve linha nenhuma.
   */
  lookup(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawFriendCode: string,
  ): FriendLookupResponseDto {
    const viewer = this.requireActiveProfile(principal);

    if (!this.limiter.tryAcquireLookup(principal.uid)) {
      this.logger.warn('social.friends.lookup.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw FriendshipErrors.rateLimited('muitas buscas por código de amigo nesta conta');
    }

    // A normalização é a da T17.0, e é a única do Spark. Um código malformado não chega ao banco.
    const canonical = normalizeFriendCode(rawFriendCode);
    const target = canonical ? this.repository.findProfileByFriendCode(canonical) : null;

    if (!target) {
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'NOT_FOUND' });
      return { result: 'NOT_FOUND' };
    }

    if (target.ownerUid === viewer.ownerUid) {
      // O próprio código. A tela diz "este é o seu próprio código" em vez de oferecer um convite
      // que o servidor recusaria — e nenhum preview é devolvido, porque não há o que prever.
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'SELF' });
      return { result: 'SELF' };
    }

    if (!this.socialPolicy.canDiscoverByFriendCode(target)) {
      // Perfil desativado: **a mesma** resposta de um código que nunca existiu.
      this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'NOT_FOUND' });
      return { result: 'NOT_FOUND' };
    }

    const relationship = this.repository.relationship(viewer.ownerUid, target.ownerUid);
    this.log(requestId, principal.uid, 'social.friends.lookup', { result: 'FOUND', relationship });

    return {
      result: 'FOUND',
      profile: toPreview(target),
      relationship,
      // Dica de UI, não permissão: `OUTGOING_PENDING` já pediu e `FRIENDS` não tem o que pedir.
      // `INCOMING_PENDING` continua podendo enviar — é o cruzamento, e ele cria a amizade.
      canSendFriendRequest:
        this.socialPolicy.canReceiveFriendRequest(target) &&
        relationship !== 'FRIENDS' &&
        relationship !== 'OUTGOING_PENDING',
    };
  }

  // --------------------------------------------------------------------------------- envio

  /**
   * `POST /v1/social/friend-requests`.
   *
   * Tudo é revalidado aqui, do zero: o preview que o app guardou não vale como autorização. O
   * destinatário pode ter desativado o social, desligado os pedidos ou virado amigo por outro
   * caminho entre a consulta e o toque — e é o estado de **agora** que decide.
   */
  send(
    principal: AuthenticatedPrincipal,
    requestId: string,
    socialId: string,
  ): SendFriendRequestResponseDto {
    const requester = this.requireActiveProfile(principal);

    if (!this.limiter.tryAcquireSend(principal.uid)) {
      this.logger.warn('social.friends.request.rate_limited', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
      });
      throw FriendshipErrors.rateLimited('muitos pedidos de amizade enviados por esta conta');
    }

    const target = this.repository.findProfileBySocialId(socialId);
    // Inexistente e desativado dão a mesma resposta, como no lookup.
    if (!target || target.status !== 'ACTIVE') {
      throw FriendshipErrors.profileNotFound();
    }
    if (target.ownerUid === requester.ownerUid) {
      // A UI já impede, o banco também (`CHECK`), e ainda assim a regra é declarada aqui: as três
      // camadas existem porque a UI pode estar desatualizada e o `CHECK` só produziria um 500.
      throw FriendshipErrors.selfRequest();
    }
    if (this.repository.areFriends(requester.ownerUid, target.ownerUid)) {
      throw FriendshipErrors.alreadyFriends();
    }
    if (!this.socialPolicy.canReceiveFriendRequest(target)) {
      // `friendRequestsEnabled = false` recusa aqui, e não na tela: o servidor é a autoridade,
      // inclusive para um app desatualizado que nem sabe que a preferência existe.
      throw FriendshipErrors.requestsDisabled();
    }

    const outcome = this.repository.sendRequest({
      requestId: generateFriendRequestId(),
      requesterUid: requester.ownerUid,
      recipientUid: target.ownerUid,
      now: Date.now(),
    });

    this.log(requestId, principal.uid, 'social.friends.request.sent', { result: outcome.kind });

    switch (outcome.kind) {
      case 'CREATED':
        return {
          result: 'REQUEST_CREATED',
          request: toRequestDto(outcome.request, target, 'OUTGOING'),
        };
      case 'ALREADY_PENDING':
        return {
          result: 'REQUEST_ALREADY_PENDING',
          request: toRequestDto(outcome.request, target, 'OUTGOING'),
        };
      case 'FRIENDSHIP_CREATED':
        return { result: 'FRIENDSHIP_CREATED', friend: toFriend(target, outcome.friendsSince) };
      case 'ALREADY_FRIENDS':
        // A corrida de §116: a amizade nasceu por outro caminho entre a verificação e a transação.
        throw FriendshipErrors.alreadyFriends();
    }
  }

  // --------------------------------------------------------------------------------- respostas

  /** `POST /v1/social/friend-requests/:requestId/accept` — só o destinatário. */
  accept(
    principal: AuthenticatedPrincipal,
    requestId: string,
    friendRequestId: string,
  ): AcceptFriendRequestResponseDto {
    const recipient = this.requireActiveProfile(principal);
    const { request, other } = this.requireParticipation(recipient, friendRequestId);

    if (!this.policy.canRespond(request, recipient.ownerUid)) {
      // Quem enviou não aceita o próprio pedido: sem isso, "pedido" não significaria nada.
      throw FriendshipErrors.notRecipient();
    }

    const outcome = this.repository.acceptRequest(friendRequestId, Date.now());
    this.log(requestId, principal.uid, 'social.friends.request.accepted', {
      friendRequestId,
      result: outcome.kind,
    });

    if (outcome.kind === 'NOT_PENDING') {
      // A corrida de §32: quem enviou cancelou primeiro. Um estado válido venceu, e a tela relê.
      throw FriendshipErrors.requestNotPending();
    }
    return {
      result: outcome.kind === 'ACCEPTED' ? 'ACCEPTED' : 'ALREADY_FRIENDS',
      friend: toFriend(other, outcome.friendsSince),
    };
  }

  /** `POST /v1/social/friend-requests/:requestId/reject` — só o destinatário. */
  reject(
    principal: AuthenticatedPrincipal,
    requestId: string,
    friendRequestId: string,
  ): RejectFriendRequestResponseDto {
    const recipient = this.requireActiveProfile(principal);
    const { request } = this.requireParticipation(recipient, friendRequestId);

    if (!this.policy.canRespond(request, recipient.ownerUid)) {
      throw FriendshipErrors.notRecipient();
    }

    const result = this.resolve(friendRequestId, 'REJECTED');
    this.log(requestId, principal.uid, 'social.friends.request.rejected', {
      friendRequestId,
      result,
    });
    return { result: result === 'RESOLVED' ? 'REJECTED' : 'ALREADY_REJECTED' };
  }

  /** `POST /v1/social/friend-requests/:requestId/cancel` — só quem enviou. */
  cancel(
    principal: AuthenticatedPrincipal,
    requestId: string,
    friendRequestId: string,
  ): CancelFriendRequestResponseDto {
    const requester = this.requireActiveProfile(principal);
    const { request } = this.requireParticipation(requester, friendRequestId);

    if (!this.policy.canCancel(request, requester.ownerUid)) {
      throw FriendshipErrors.notSender();
    }

    const result = this.resolve(friendRequestId, 'CANCELLED');
    this.log(requestId, principal.uid, 'social.friends.request.cancelled', {
      friendRequestId,
      result,
    });
    return { result: result === 'RESOLVED' ? 'CANCELLED' : 'ALREADY_CANCELLED' };
  }

  /**
   * A transição condicional, e o que fazer quando ela não acontece.
   *
   * `RESOLVED` quando **esta** chamada mudou o estado. `ALREADY` quando o pedido já estava no
   * estado pedido — que é o toque duplo e o reenvio depois de resposta perdida, e é sucesso.
   * Qualquer outro estado terminal é a corrida de §32, e aí a resposta é um conflito honesto.
   */
  private resolve(
    friendRequestId: string,
    status: 'REJECTED' | 'CANCELLED',
  ): 'RESOLVED' | 'ALREADY' {
    if (this.repository.resolveRequest(friendRequestId, status, Date.now())) {
      return 'RESOLVED';
    }
    const current = this.repository.findRequestById(friendRequestId);
    if (current?.status === status) {
      return 'ALREADY';
    }
    throw FriendshipErrors.requestNotPending();
  }

  // --------------------------------------------------------------------------------- listagens

  /** `GET /v1/social/friend-requests/incoming` — quem quer me adicionar. */
  incoming(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ListQuery,
  ): FriendRequestListResponseDto {
    return this.requests(principal, requestId, 'INCOMING', query);
  }

  /** `GET /v1/social/friend-requests/outgoing` — o que eu pedi e ainda não foi respondido. */
  outgoing(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ListQuery,
  ): FriendRequestListResponseDto {
    return this.requests(principal, requestId, 'OUTGOING', query);
  }

  private requests(
    principal: AuthenticatedPrincipal,
    requestId: string,
    direction: 'INCOMING' | 'OUTGOING',
    query: ListQuery,
  ): FriendRequestListResponseDto {
    const owner = this.requireActiveProfile(principal);
    const page = this.repository.listRequests(owner.ownerUid, direction, query);

    this.log(requestId, principal.uid, 'social.friends.requests.listed', {
      direction,
      count: page.items.length,
    });

    return {
      requests: page.items.map(toListedRequestDto),
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeCursor(page.nextCursor) } : {}),
    };
  }

  /** `GET /v1/social/friends`. */
  friends(
    principal: AuthenticatedPrincipal,
    requestId: string,
    query: ListQuery,
  ): FriendListResponseDto {
    const owner = this.requireActiveProfile(principal);
    const page = this.repository.listFriends(owner.ownerUid, query);

    this.log(requestId, principal.uid, 'social.friends.listed', { count: page.items.length });

    return {
      friends: page.items.map(toFriendDto),
      total: page.total,
      ...(page.nextCursor ? { nextCursor: encodeCursor(page.nextCursor) } : {}),
    };
  }

  /**
   * `POST /v1/social/friends/remove`.
   *
   * Remove **uma linha** de `friendships`, e nada mais. Não apaga perfil, treino, histórico,
   * backup, sync, medida nem gamificação — este módulo não alcança nenhum deles. Não bloqueia: os
   * dois podem se adicionar de novo depois (§45/§46). E não reescreve o pedido histórico que
   * originou a amizade.
   *
   * Qualquer um dos dois pode remover. Não há hierarquia entre quem convidou e quem aceitou.
   */
  remove(
    principal: AuthenticatedPrincipal,
    requestId: string,
    socialId: string,
  ): RemoveFriendResponseDto {
    const owner = this.requireActiveProfile(principal);
    const target = this.repository.findProfileBySocialId(socialId);

    // Perfil inexistente e "não somos amigos" respondem a mesma coisa: quem pergunta só precisa
    // saber que não há amizade, e distinguir os dois contaria que aquele `socialId` existe.
    if (!target || !this.repository.areFriends(owner.ownerUid, target.ownerUid)) {
      throw FriendshipErrors.friendshipNotFound();
    }
    // A política é consultada mesmo sendo, aqui, uma tautologia: é ela que responde "quem pode
    // desfazer este par", e um dia a resposta pode deixar de ser óbvia.
    if (!this.policy.canRemoveFriendship(owner.ownerUid, target.ownerUid, owner.ownerUid)) {
      throw FriendshipErrors.friendshipNotFound();
    }

    this.repository.removeFriendship(owner.ownerUid, target.ownerUid);
    this.log(requestId, principal.uid, 'social.friends.removed', {});
    return { result: 'REMOVED' };
  }

  // --------------------------------------------------------------------------------- comum

  /**
   * O perfil da conta autenticada, exigindo que ele exista e esteja ativo.
   *
   * Sem perfil: `SOCIAL_NOT_ENABLED` — o mesmo erro da T17.0, porque é o mesmo fato.
   * Perfil desativado: `SOCIAL_PROFILE_DISABLED`, e **nenhuma** rota do grafo responde — nem as de
   * leitura. É o que "suspender as relações" significa: elas continuam gravadas, param de ser
   * acionáveis, e voltam inteiras ao reativar. O oposto de transformar desativar em desfazer.
   */
  private requireActiveProfile(principal: AuthenticatedPrincipal): FriendProfileRow {
    const profile = this.repository.findProfileByOwnerUid(principal.uid);
    if (!profile) {
      throw SocialErrors.notEnabled();
    }
    if (profile.status !== 'ACTIVE') {
      throw FriendshipErrors.profileDisabled();
    }
    return profile;
  }

  /**
   * O pedido, quando ele existe e **envolve quem perguntou**.
   *
   * Uma conta que não participa recebe `FRIEND_REQUEST_NOT_FOUND` — a mesma resposta de um
   * `requestId` inventado. Dizer "existe, mas não é seu" ensinaria a uma conta C que aquele id
   * acertou um pedido entre A e B, que é justamente o que ela não tem o direito de aprender.
   *
   * Um pedido cuja **outra ponta** está desativada também responde "não encontrado": ele está
   * suspenso, não cancelado (§50). Ele reaparece inteiro quando a outra pessoa reativar.
   *
   * **O status não é verificado aqui**, e isso é deliberado: recusar um pedido já recusado é
   * idempotente (sucesso), enquanto aceitar um pedido já cancelado é conflito. Quem sabe a
   * diferença é cada operação, e ela é decidida por escrita condicional no banco — não por uma
   * leitura anterior, que perderia a corrida de §32.
   */
  private requireParticipation(
    viewer: FriendProfileRow,
    friendRequestId: string,
  ): { request: StoredFriendRequest; other: FriendProfileRow } {
    const request = this.repository.findRequestById(friendRequestId);
    if (!request || !this.policy.isParticipant(request, viewer.ownerUid)) {
      throw FriendshipErrors.requestNotFound();
    }

    const otherUid =
      request.requesterUid === viewer.ownerUid ? request.recipientUid : request.requesterUid;
    const other = this.repository.findProfileByOwnerUid(otherUid);
    if (!other || other.status !== 'ACTIVE') {
      throw FriendshipErrors.requestNotFound();
    }
    return { request, other };
  }

  /**
   * O log do grafo social.
   *
   * O que entra: `requestId` da requisição HTTP, prefixo do uid (6 caracteres), o evento, o
   * desfecho e contagens. O que **nunca** entra: `friendCode`, `displayName`, `socialId`, e-mail,
   * uid completo e corpo da requisição. Um log que carregasse `friendCode` transformaria qualquer
   * cópia de log numa lista de convites válidos.
   */
  private log(
    requestId: string,
    uid: string,
    event: string,
    fields: Record<string, unknown>,
  ): void {
    this.logger.info(event, { requestId, uidPrefix: uidPrefix(uid), ...fields });
  }
}

/**
 * O perfil de outra pessoa, como ela é vista.
 *
 * Esta função e [toFriend] são a fronteira onde o Firebase UID para de existir: o repositório o
 * carrega porque o banco relaciona por ele, e nada além daqui o vê. Nenhuma delas copia
 * `ownerUid`, `friendCode`, e-mail, privacidade ou qualquer projeção de treino.
 */
function toPreview(profile: FriendProfileRow): SocialProfilePreviewDto {
  return { socialId: profile.socialId, displayName: profile.displayName };
}

function toFriend(profile: FriendProfileRow, friendsSince: number): FriendDto {
  return {
    socialId: profile.socialId,
    displayName: profile.displayName,
    friendsSince,
  };
}

function toFriendDto(friend: StoredFriend): FriendDto {
  return {
    socialId: friend.socialId,
    displayName: friend.displayName,
    friendsSince: friend.friendsSince,
  };
}

function toRequestDto(
  request: StoredFriendRequest,
  other: FriendProfileRow,
  direction: 'INCOMING' | 'OUTGOING',
): FriendRequestDto {
  return {
    requestId: request.requestId,
    profile: toPreview(other),
    direction,
    createdAt: request.createdAt,
  };
}

function toListedRequestDto(request: FriendRequestWithProfile): FriendRequestDto {
  return {
    requestId: request.requestId,
    profile: { socialId: request.otherSocialId, displayName: request.otherDisplayName },
    direction: request.direction,
    createdAt: request.createdAt,
  };
}
