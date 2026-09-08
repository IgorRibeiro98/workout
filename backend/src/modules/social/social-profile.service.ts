import { Injectable, Optional } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import type {
  SocialFriendProfileDto,
  SocialFriendProfileResponse,
  SocialProgressSettingsDto,
  SocialProgressSharingResponse,
} from './social-profile.contract';
import { SocialProfileErrors } from './social-profile.errors';
import type { UpdateProgressSharingRequest } from './social-profile.validator';
import { SocialProgressPrivacyFilter, SocialProgressProjector } from './social-progress.projector';
import {
  SocialProgressSettingsRepository,
  type StoredProgressSettings,
} from './social-progress.repository';
import { FriendshipErrors } from './friendship.errors';
import { FriendshipRepository, type FriendProfileRow } from './friendship.repository';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialErrors } from './social.errors';
import { BlockService } from './block.service';

/**
 * O caso de uso do perfil social enriquecido (T17.2).
 *
 * ## A ordem, e ela não é negociável (§116/§117)
 *
 * ```text
 * autenticar (BearerAuthGuard)
 *      ↓
 * resolver o perfil do visitante   →  existe? está ACTIVE?
 *      ↓
 * resolver o perfil do alvo        →  socialId → owner_uid, server-side
 *      ↓
 * verificar a amizade AGORA        →  uma consulta, aqui, e em nenhum controller (§115)
 *      ↓
 * projetar                         →  SocialProgressProjector
 *      ↓
 * filtrar                          →  SocialProgressPrivacyFilter
 *      ↓
 * responder
 * ```
 *
 * **Autorizar vem antes de projetar**, e não por elegância: carregar o progresso de alguém para
 * depois descobrir que quem perguntou não tem direito a ele é o desenho que, no dia de um bug,
 * responde o dado. Aqui, quando a autorização falha, nada foi lido do domínio de treino.
 *
 * ## Uma resposta para quatro situações
 *
 * `socialId` inexistente, alvo desativado, "não somos amigos" e "existe um pedido pendente" saem
 * todas como `404 FRIEND_PROFILE_NOT_FOUND` (§120). É a mesma decisão da T17.1 para o lookup, pelo
 * mesmo motivo: distinguir transformaria a rota num oráculo sobre a existência e o estado de
 * contas alheias.
 *
 * ## O preview do dono usa este mesmo pipeline (§41)
 *
 * [preview] chama exatamente [projectFor] — mesma projeção, mesmo filtro, mesma montagem de DTO.
 * A única diferença é o alvo, resolvido antes. Uma "lógica de preview" separada divergiria da real
 * no primeiro campo novo, e a divergência apareceria como "a prévia mostra o que meu amigo não vê".
 */
@Injectable()
export class SocialProfileService {
  constructor(
    private readonly friendships: FriendshipRepository,
    private readonly settings: SocialProgressSettingsRepository,
    private readonly projector: SocialProgressProjector,
    private readonly privacy: SocialProgressPrivacyFilter,
    private readonly policy: SocialAccessPolicy,
    private readonly logger: SparkLogger,
    @Optional() private readonly blockService?: BlockService,
  ) {}

  /**
   * `GET /v1/social/friends/:socialId/profile`.
   *
   * A amizade é verificada **nesta** requisição, contra a linha de `friendships` de agora. É isso
   * que faz `unfriend` revogar o acesso imediatamente (§47) e o que faz um perfil recém-desativado
   * sumir na próxima leitura (§48/§113): não há cache, não há token de acesso e não há estado que
   * o app possa apresentar no lugar da verificação.
   */
  friendProfile(
    principal: AuthenticatedPrincipal,
    requestId: string,
    socialId: string,
  ): SocialFriendProfileResponse {
    const viewer = this.requireActiveProfile(principal);
    const target = this.friendships.findProfileBySocialId(socialId);

    // Alvo inexistente e alvo desativado: a mesma resposta, e a mesma de "não somos amigos".
    if (!target) {
      throw SocialProfileErrors.friendProfileNotFound();
    }

    if (this.blockService?.isBlocked(viewer.ownerUid, target.ownerUid)) {
      throw SocialProfileErrors.friendProfileNotFound();
    }

    const areFriends = this.friendships.areFriends(viewer.ownerUid, target.ownerUid);
    if (!this.policy.canViewFriendProfile(target, viewer, areFriends)) {
      // Nada foi projetado até aqui: nenhuma leitura do domínio de treino aconteceu.
      throw SocialProfileErrors.friendProfileNotFound();
    }
    if (target.ownerUid === viewer.ownerUid) {
      // Irrepresentável — `friendships` tem `CHECK (user_a_uid < user_b_uid)`, então ninguém é
      // amigo de si mesmo. A guarda existe para que o dono use `/me/profile-preview` (§50) mesmo
      // que a constraint um dia mude.
      throw SocialProfileErrors.friendProfileNotFound();
    }

    const profile = this.projectFor(target);
    this.log(requestId, principal.uid, 'social.friend_profile.read', {
      fieldCount: Object.keys(profile.sharedProgress).length,
    });
    return { profile };
  }

  /**
   * `GET /v1/social/me/profile-preview` — exatamente o que um amigo veria agora (§40).
   *
   * O dono chega aqui, e não por uma amizade fingida consigo mesmo (§50). Ele **não** vê mais do
   * que um amigo veria: o filtro de privacidade é o mesmo, com as mesmas preferências. Uma prévia
   * que mostrasse os campos desligados seria uma prévia de outra coisa.
   */
  preview(principal: AuthenticatedPrincipal, requestId: string): SocialFriendProfileResponse {
    const owner = this.requireActiveProfile(principal);
    const profile = this.projectFor(owner);

    this.log(requestId, principal.uid, 'social.profile_preview.read', {
      fieldCount: Object.keys(profile.sharedProgress).length,
    });
    return { profile };
  }

  /** `GET /v1/social/me/progress-sharing` — o que eu compartilho e o que está disponível. */
  progressSharing(
    principal: AuthenticatedPrincipal,
    requestId: string,
  ): SocialProgressSharingResponse {
    const owner = this.requireActiveProfile(principal);
    this.log(requestId, principal.uid, 'social.progress_settings.read', {});
    return this.sharingResponseFor(owner.ownerUid);
  }

  /**
   * `PATCH /v1/social/me/progress-sharing`.
   *
   * Semântica parcial (§57): só o que veio muda. O efeito é **imediato** (§59) — não há cache de
   * perfil no servidor, então a próxima leitura de qualquer amigo já passa pelas preferências
   * novas. Não existe invalidação a fazer porque não existe cache a invalidar (§60/§61).
   *
   * Nada aqui concede XP, conquista, missão ou evento de gamificação (§134): este módulo não
   * alcança nenhum deles, e a ausência é estrutural — o `SocialModule` não importa nada disso.
   */
  updateProgressSharing(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: UpdateProgressSharingRequest,
  ): SocialProgressSharingResponse {
    const owner = this.requireActiveProfile(principal);

    this.settings.update(owner.ownerUid, { ...request, now: Date.now() });

    this.log(requestId, principal.uid, 'social.progress_settings.updated', {
      // Quantos campos mudaram — nunca quais valores. Um log que dissesse `shareLevel=true`
      // registraria a configuração de privacidade de alguém em texto claro.
      fieldCount: Object.keys(request).length,
    });
    return this.sharingResponseFor(owner.ownerUid);
  }

  // --------------------------------------------------------------------------------- comum

  /**
   * O pipeline, e o único lugar onde ele existe.
   *
   * ```text
   * perfil alvo ──▶ preferências ──▶ projeção ──▶ filtro ──▶ SocialFriendProfileDto
   * ```
   *
   * `displayName` entra sem interruptor (§15): sem nome social, uma amizade não teria
   * representação útil na tela, e o nome já é o que a T17.1 mostra na lista de amigos. `friendCode`
   * **não** entra (§16), e `ownerUid` para de existir exatamente aqui.
   */
  private projectFor(target: FriendProfileRow): SocialFriendProfileDto {
    const settings = this.settings.find(target.ownerUid);
    const projection = this.projector.project(target.ownerUid, settings.weekTimeZone, Date.now());

    return {
      socialId: target.socialId,
      displayName: target.displayName,
      sharedProgress: this.privacy.apply(projection, settings),
    };
  }

  /** Preferências + disponibilidade, a resposta das duas rotas de configuração. */
  private sharingResponseFor(ownerUid: string): SocialProgressSharingResponse {
    const settings = this.settings.find(ownerUid);
    const projection = this.projector.project(ownerUid, settings.weekTimeZone, Date.now());

    return {
      settings: toSettingsDto(settings),
      availability: this.privacy.availabilityOf(projection),
    };
  }

  /**
   * O perfil da conta autenticada, exigindo que ele exista e esteja ativo.
   *
   * Os mesmos dois erros da T17.1, porque são os mesmos dois fatos: `SOCIAL_NOT_ENABLED` para quem
   * nunca ativou, `SOCIAL_PROFILE_DISABLED` para quem desativou. Um visitante desativado não
   * consome perfil social nenhum (§49) — nem o próprio preview, porque enquanto ele estiver assim
   * não existe perfil social dele para prever.
   */
  private requireActiveProfile(principal: AuthenticatedPrincipal): FriendProfileRow {
    const profile = this.friendships.findProfileByOwnerUid(principal.uid);
    if (!profile) {
      throw SocialErrors.notEnabled();
    }
    if (profile.status !== 'ACTIVE') {
      throw FriendshipErrors.profileDisabled();
    }
    return profile;
  }

  /**
   * O log do perfil social.
   *
   * O que entra: `requestId`, prefixo do uid (6 caracteres), evento e **quantidade** de campos. O
   * que nunca entra (§122): `displayName`, `socialId`, `friendCode`, e-mail, uid completo, nível,
   * sequência, contagem de treinos, lista de conquistas e o corpo da requisição. Um log com o
   * número de treinos de alguém é o dado social vazando por uma porta que ninguém audita.
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

function toSettingsDto(settings: StoredProgressSettings): SocialProgressSettingsDto {
  return {
    shareLevel: settings.shareLevel,
    shareConsistencyStreak: settings.shareConsistencyStreak,
    shareWeeklyWorkoutCount: settings.shareWeeklyWorkoutCount,
    shareHighlightedAchievements: settings.shareHighlightedAchievements,
    weekTimeZone: settings.weekTimeZone,
    updatedAt: settings.updatedAt,
  };
}
