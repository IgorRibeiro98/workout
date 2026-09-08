import { Injectable } from '@nestjs/common';
import type { StoredSocialProfile } from './social.repository';
import { isValidTimeZone } from './social-time';

/**
 * O **único** lugar que responde "quem pode o quê" no domínio social (T17.0 §81).
 *
 * ## Por que centralizada desde agora, com tão poucas regras
 *
 * Porque a alternativa é o que sempre acontece: `if (privacy.discoverability === ...)` espalhado
 * por três controllers, cada cópia envelhecendo no seu ritmo. No dia em que a T17.1 acrescentar
 * "não descubro quem me bloqueou", uma das cópias vai ficar para trás — e a cópia esquecida é
 * justamente a que vaza. Uma política que já existe é barata de estender; três `if` espalhados
 * são caros de reunir.
 *
 * ## O que ela decide, e o que ela nunca decide
 *
 * Ela decide **visibilidade**: dado um perfil e as configurações dele, o que outra conta pode ver
 * ou pedir. Ela **não** decide autenticação nem propriedade: quem é o dono sai sempre do
 * `AuthenticatedPrincipal.uid` verificado, e nenhuma resposta desta classe substitui isso. Uma
 * política que respondesse `true` não daria acesso a nada por si só — o `owner_uid` continua na
 * cláusula `WHERE` de toda consulta.
 *
 * ## O estado da T17.0
 *
 * Nenhuma das rotas desta fase consulta perfil alheio: não há lookup, busca nem listagem. Os
 * métodos abaixo existem para que a T17.1 os **use**, e as regras que já podem ser verdadeiras
 * — perfil desativado não é descobrível, `friendRequestsEnabled` respeitado, atividade off por
 * padrão — já estão implementadas e testadas aqui, e não adiadas para o dia em que a rota nascer.
 */
@Injectable()
export class SocialAccessPolicy {
  /**
   * O perfil pode ser encontrado por alguém que digitou o `friendCode` correto?
   *
   * `DISABLED` responde `false`, e é isso que faz o futuro lookup responder "não encontrado" — a
   * mesma resposta de um código que nunca existiu. Distinguir os dois transformaria a rota num
   * oráculo: bastaria comparar as respostas para descobrir que um código existe mas está
   * desligado, que é exatamente a informação que desativar deveria esconder.
   */
  canDiscoverByFriendCode(profile: SocialProfileAccessView): boolean {
    return profile.status === 'ACTIVE' && profile.discoverability === 'FRIEND_CODE_ONLY';
  }

  /**
   * O `viewer` pode ver o preview deste perfil?
   *
   * Na T17.0 a única relação que existe é "eu comigo mesmo": não há amizade, e um perfil só é
   * visível para o próprio dono. A T17.1 estende isto com amizade aceita — e é aqui que ela
   * estende, não em um controller.
   */
  canViewProfile(profile: SocialProfileAccessView, viewer: SocialViewer): boolean {
    if (viewer.isOwner) {
      return true;
    }
    return this.canDiscoverByFriendCode(profile);
  }

  /**
   * O `viewer` pode ver atividade (treinou hoje, sequência, ...) deste perfil?
   *
   * `activitySharingEnabled` é `false` por padrão, então a resposta padrão é **não** — inclusive
   * no dia em que a T17.4 existir. Ninguém passa a publicar por uma feature ter nascido.
   *
   * O dono vê a própria atividade sem passar por aqui: ela é dado dele, no aparelho dele, e não
   * uma projeção social.
   */
  canViewActivity(profile: SocialProfileAccessView, viewer: SocialViewer): boolean {
    if (viewer.isOwner) {
      return true;
    }
    return profile.activitySharingEnabled && this.canViewProfile(profile, viewer);
  }

  /**
   * O `viewer` pode ver o **perfil enriquecido** do `target`? (T17.2 §114/§116)
   *
   * ```text
   * viewer ACTIVE?  ──não──▶ false      quem desativou o Social não consome perfil social
   * target ACTIVE?  ──não──▶ false      perfil desativado não existe para ninguém
   * amigos agora?   ──não──▶ false      pedido pendente, recusado, cancelado e nada: todos não
   * ```
   *
   * ## As três condições, e por que nenhuma delas é dispensável
   *
   * - **amizade ativa é a única porta** (§44/§45). Um `FriendRequest` `PENDING` não concede nada:
   *   pedir para ver não é ser autorizado a ver, e tratá-lo como acesso faria de "mandar pedido"
   *   um jeito de olhar o progresso de quem ainda não respondeu. Depois de um `unfriend`, a
   *   próxima leitura falha na hora (§47) — porque a pergunta é feita **agora**, contra a linha de
   *   `friendships`, e não contra um estado que o app guardou;
   * - **o alvo precisa estar `ACTIVE`** (§48). Desativado, ele não tem perfil social para mostrar,
   *   e a resposta é indistinguível de "não existe";
   * - **o visitante precisa estar `ACTIVE`** (§49). Quem desligou os próprios recursos sociais
   *   parou de participar do domínio social — inclusive como leitor. É a mesma regra que a T17.1
   *   já aplica em todas as rotas do grafo, e não uma nova.
   *
   * Ela decide **visibilidade**, e não propriedade: `areFriends` chega como fato já apurado no
   * servidor, a partir do `uid` do token e do `owner_uid` resolvido do `socialId`. Uma política
   * que consultasse o banco sozinha acabaria sendo a segunda dona da regra de ownership.
   */
  canViewFriendProfile(
    target: SocialProfileAccessView,
    viewer: SocialProfileAccessView,
    areFriends: boolean,
  ): boolean {
    return viewer.status === 'ACTIVE' && target.status === 'ACTIVE' && areFriends;
  }

  /**
   * Alguém pode mandar um pedido de amizade para este perfil?
   *
   * A flag existe desde a T17.0 mesmo sem pedidos de amizade, para que a T17.1 nasça
   * respeitando-a. Uma preferência criada junto com a feature que a consome sempre atrasa um
   * release em relação à feature.
   */
  canReceiveFriendRequest(profile: SocialProfileAccessView): boolean {
    return profile.status === 'ACTIVE' && profile.friendRequestsEnabled;
  }

  /**
   * O viewer pode ver a atividade recente de um amigo? (T17.4)
   *
   * Exige amizade direta ativa, friend com activitySharingEnabled = true e timezone IANA válido.
   */
  canViewFriendActivity(
    friend: SocialProfileAccessView,
    viewer: SocialProfileAccessView,
    areFriends: boolean,
  ): boolean {
    if (!this.canViewFriendProfile(friend, viewer, areFriends)) {
      return false;
    }
    return (
      friend.activitySharingEnabled === true &&
      typeof friend.activityTimeZoneId === 'string' &&
      isValidTimeZone(friend.activityTimeZoneId)
    );
  }

  /**
   * O participante pode figurar no ranking contextual? (T17.4)
   *
   * Exige perfil ACTIVE e consentimento explícito (reciprocidade).
   */
  canParticipateInRanking(participant: SocialProfileAccessView): boolean {
    return (
      participant.status === 'ACTIVE' && participant.friendRankingParticipationEnabled === true
    );
  }
}

/**
 * O recorte de um perfil que a política precisa — status e privacidade, nada mais.
 *
 * Deliberadamente **sem** `ownerUid`, `socialId`, `friendCode` e `displayName`: uma política de
 * acesso que recebe o uid tende, com o tempo, a compará-lo com alguma coisa, e ownership não é
 * decisão dela. Ela responde sobre visibilidade; propriedade continua saindo do token verificado.
 */
export interface SocialProfileAccessView {
  readonly status: StoredSocialProfile['status'];
  readonly discoverability: string;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly activityTimeZoneId?: string | null;
  readonly friendRankingParticipationEnabled?: boolean;
}

/** Quem está olhando. Na T17.0 só existe uma pergunta: é o próprio dono? */
export interface SocialViewer {
  readonly isOwner: boolean;
}
