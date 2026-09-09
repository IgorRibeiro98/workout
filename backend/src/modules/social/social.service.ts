import { Inject, Injectable, Optional } from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { SqliteService } from '../../database/sqlite.service';
import { ChallengeRepository } from './challenge.repository';
import { SocialGroupService } from './social-group.service';
import { NotificationService } from './notification.service';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import {
  type SocialMeResponse,
  type SocialOwnerProfileDto,
  type SocialProfileResponse,
} from './social.contract';
import { SocialErrors } from './social.errors';
import { generateFriendCode, generateSocialId } from './social.identity';
import { FRIEND_CODE_MAX_GENERATION_ATTEMPTS } from './social.limits';
import {
  FriendCodeCollisionError,
  SocialRepository,
  type StoredSocialAccount,
} from './social.repository';
import type {
  ActivateSocialRequest,
  UpdateSocialPrivacyRequest,
  UpdateSocialProfileRequest,
} from './social.validator';

/**
 * Os defaults de privacidade de um perfil recém-ativado (T17.0 §24).
 *
 * Conservadores por decisão: descoberta **só** por código de amigo, atividade **não**
 * compartilhada. Pedidos de amizade nascem habilitados porque receber convite é o caminho
 * pretendido do produto — e quem não quiser desliga, antes de a T17.1 existir.
 *
 * Ficam aqui, e não numa coluna `DEFAULT` do SQLite, porque um default de schema é invisível para
 * quem lê o código e silencioso quando muda: uma migration futura poderia trocá-lo sem que
 * nenhum teste de comportamento notasse.
 */
export const SOCIAL_PRIVACY_DEFAULTS = {
  discoverability: 'FRIEND_CODE_ONLY',
  friendRequestsEnabled: true,
  activitySharingEnabled: false,
} as const;

/**
 * O caso de uso do domínio social (T17.0).
 *
 * ## Nada é criado por login
 *
 * Autenticar-se produz uma Conta Spark, e mais nada. `GET /v1/social/me` de uma conta que nunca
 * ativou responde `{ enabled: false }` **sem escrever uma linha** — não há criação preguiçosa, não
 * há "cria se não existir" escondido em uma leitura. Só `activate` cria, e ele só é chamado por
 * toque explícito.
 *
 * ## Ownership
 *
 * O dono é sempre `principal.uid`, saído de um Firebase ID Token verificado. Não existe parâmetro
 * de usuário em nenhuma rota, o corpo não tem campo de dono (o validador recusa a requisição
 * inteira se tiver), e toda consulta filtra por `owner_uid`. A conta A não tem como pedir o perfil
 * da conta B: não existe a pergunta.
 *
 * ## Identidade é do servidor
 *
 * `socialId` e `friendCode` nascem aqui, de CSPRNG, e são imutáveis. Nenhum caminho os altera:
 * `PATCH` só toca `display_name`, e desativar/reativar só toca `status`. Reativar devolve a mesma
 * identidade — gerar uma nova a cada toque destruiria qualquer relação futura.
 */
@Injectable()
export class SocialService {
  constructor(
    private readonly repository: SocialRepository,
    /**
     * Os desafios, alcançados **só** por [disable] (T17.3 §115–§120).
     *
     * A dependência tem uma direção só, e é esta: o social sabe que desativar precisa encerrar a
     * participação em desafios. O contrário não existe — nada em `ChallengeService` altera perfil,
     * privacidade ou identidade.
     */
    private readonly challenges: ChallengeRepository,
    /**
     * Os Squads, alcançados **só** por [disable] (T17.11 §96–§99).
     *
     * A dependência tem uma direção só, como a dos desafios: o social sabe que desativar precisa
     * resolver os grupos. O contrário não existe — nada em `SocialGroupService` altera perfil,
     * privacidade ou identidade; ele **lê** o perfil pelo `SocialRepository`, que é o mesmo dado
     * sem o caso de uso em volta.
     */
    private readonly groups: SocialGroupService,
    /** A conexão, para que desativar e sair dos desafios sejam uma transação só (§119). */
    private readonly sqlite: SqliteService,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
    @Optional() private readonly notificationService?: NotificationService,
  ) {}

  /**
   * `GET /v1/social/me`.
   *
   * Leitura pura. Uma conta sem perfil recebe `{ enabled: false }` — que é um estado normal do
   * produto, não um erro, e por isso não é `404`.
   */
  me(principal: AuthenticatedPrincipal, requestId: string): SocialMeResponse {
    const account = this.repository.find(principal.uid);
    this.logger.info('social.me', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      enabled: account !== null,
      status: account?.profile.status,
    });

    if (!account) {
      return { enabled: false };
    }
    return { enabled: true, profile: toOwnerProfile(account) };
  }

  /**
   * `POST /v1/social/me/activate` — a **única** porta que cria identidade social.
   *
   * Idempotente por desenho: uma conta que já tem perfil recebe o perfil que já tem, sem mutação
   * e sem erro. Isso cobre os dois casos que importam — o reenvio depois de uma resposta perdida e
   * dois aparelhos ativando ao mesmo tempo —, e os dois convergem para **um** perfil, um
   * `socialId` e um `friendCode`.
   *
   * Consequência deliberada: o `displayName` da segunda ativação é ignorado. Ativar é "garanta que
   * eu tenho identidade social"; renomear é `PATCH`. Aplicar o nome da segunda faria a ordem de
   * chegada de duas requisições concorrentes decidir o nome do usuário.
   *
   * Um perfil `DISABLED` também é devolvido como está: reativar é `POST /me/enable`, e fazer a
   * ativação religar em silêncio esconderia do usuário que ele tinha desativado.
   */
  activate(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: ActivateSocialRequest,
  ): SocialProfileResponse {
    const existing = this.repository.find(principal.uid);
    if (existing) {
      this.logger.info('social.activate.already', {
        requestId,
        uidPrefix: uidPrefix(principal.uid),
        status: existing.profile.status,
      });
      return { profile: toOwnerProfile(existing) };
    }

    const now = Date.now();
    // Retry limitado sobre a `UNIQUE` do banco. O `socialId` é sorteado uma vez só: a chance de
    // colisão de um UUID v4 é desprezível e, se acontecesse, o erro subiria como 500 — que é a
    // resposta honesta para algo que não deveria ser possível. O `friendCode` tem espaço menor
    // (31^8) e por isso é o que tem tratamento explícito.
    const socialId = generateSocialId();

    for (let attempt = 1; attempt <= FRIEND_CODE_MAX_GENERATION_ATTEMPTS; attempt += 1) {
      try {
        const created = this.repository.create({
          ownerUid: principal.uid,
          socialId,
          friendCode: generateFriendCode(),
          displayName: request.displayName,
          discoverability: SOCIAL_PRIVACY_DEFAULTS.discoverability,
          friendRequestsEnabled: SOCIAL_PRIVACY_DEFAULTS.friendRequestsEnabled,
          activitySharingEnabled: SOCIAL_PRIVACY_DEFAULTS.activitySharingEnabled,
          now,
        });

        // Nem `socialId`, nem `friendCode`, nem `displayName` no log. O que se registra é que uma
        // ativação aconteceu e quantas tentativas de código foram necessárias.
        this.logger.info('social.activated', {
          requestId,
          uidPrefix: uidPrefix(principal.uid),
          friendCodeAttempts: attempt,
        });
        return { profile: toOwnerProfile(created) };
      } catch (error) {
        if (!(error instanceof FriendCodeCollisionError)) {
          throw error;
        }
        this.logger.warn('social.friend_code.collision', { requestId, attempt });
      }
    }

    // Estatisticamente impossível com 31^8 códigos; ainda assim, uma resposta explícita em vez de
    // um 500 aleatório — e **nunca** um perfil criado sem código.
    this.logger.error('social.friend_code.exhausted', {
      requestId,
      attempts: FRIEND_CODE_MAX_GENERATION_ATTEMPTS,
    });
    throw SocialErrors.unavailable('não foi possível gerar um código de amigo agora');
  }

  /** `PATCH /v1/social/me` — só o nome social. */
  updateProfile(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: UpdateSocialProfileRequest,
  ): SocialProfileResponse {
    const account = this.require(principal);

    const now = Date.now();
    this.repository.updateDisplayName(principal.uid, request.displayName, now);

    // O nome não vai para o log — nem o antigo, nem o novo.
    this.logger.info('social.profile.updated', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
    });
    return { profile: toOwnerProfile(this.reload(principal, account)) };
  }

  /** `PATCH /v1/social/me/privacy` — parcial; o que não veio no corpo não é tocado. */
  updatePrivacy(
    principal: AuthenticatedPrincipal,
    requestId: string,
    request: UpdateSocialPrivacyRequest,
  ): SocialProfileResponse {
    const account = this.require(principal);

    if (request.activitySharingEnabled === true) {
      const effectiveTimeZone = request.activityTimeZoneId ?? account.privacy.activityTimeZoneId;
      if (!effectiveTimeZone) {
        throw SocialErrors.invalidActivityTimeZone(
          'activityTimeZoneId é obrigatório ao ativar o compartilhamento de atividade',
        );
      }
    }

    const now = this.clock.now();
    this.repository.updatePrivacy(principal.uid, { ...request, now });

    this.logger.info('social.privacy.updated', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      // Quais chaves mudaram, nunca o perfil inteiro: é o suficiente para investigar uma
      // reclamação de "eu não mudei isso" sem registrar a configuração da pessoa.
      fields: Object.keys(request).sort().join(','),
    });
    return { profile: toOwnerProfile(this.reload(principal, account)) };
  }

  /**
   * `POST /v1/social/me/disable`.
   *
   * Desativa **os recursos sociais**, e nada mais. Não apaga a Conta Spark, a conta Firebase,
   * backups, sync, treinos, histórico, medidas nem gamificação — nenhum deles é sequer alcançável
   * a partir deste módulo. Exclusão completa de conta continua sendo outro assunto (pré-release).
   *
   * A linha permanece, com `status = DISABLED`: é ela que preserva `socialId` e `friendCode` para
   * uma reativação futura.
   */
  disable(principal: AuthenticatedPrincipal, requestId: string): SocialProfileResponse {
    const account = this.require(principal);
    if (account.profile.status === 'DISABLED') {
      throw SocialErrors.alreadyDisabled();
    }

    const now = this.clock.now();

    // T17.11 §98/§99 — a recusa vem **antes** da transação, e é deliberada.
    //
    // Ser dono de um Squad com outras pessoas é o único caso em que desativar o Social não pode
    // ser resolvido pelo servidor sozinho. A alternativa seria escolher um novo dono, e qualquer
    // ordenação que fizesse isso entregaria um grupo de gente real a alguém que não pediu. Recusar
    // é mais explícito e mais seguro: a pessoa transfere a posse para quem ela escolher, ou exclui
    // o Squad, e só então desativa.
    //
    // A resposta carrega uma **contagem**, e nunca dados de membro (§99).
    const pendingGroups = this.groups.countGroupsRequiringOwnerAction(principal.uid);
    if (pendingGroups > 0) {
      throw SocialErrors.groupOwnershipRequiresAction(pendingGroups);
    }

    // T17.3 §119 — desativar o Social e sair dos desafios acontecem **juntos**, ou não acontecem.
    //
    // Fora de uma transação, uma falha entre as duas escritas deixaria o pior estado possível:
    // perfil desativado com a pontuação da pessoa ainda atualizando no placar dos amigos, ou um
    // desafio cancelado para todos com o criador ainda ativo. As duas são visíveis para **outras**
    // pessoas, e nenhuma delas se corrige sozinha.
    //
    // A amizade continua intocada (T17.1): desativar suspende, não desfaz.
    // T17.11 §96/§97 — os Squads entram na **mesma** transação, pelo mesmo motivo dos desafios:
    // fora dela, uma falha entre as escritas deixaria um perfil desativado com a pessoa ainda
    // aparecendo no feed de um grupo, ou um grupo resolvido com o perfil ainda ativo. As duas são
    // visíveis para **outras** pessoas, e nenhuma se corrige sozinha.
    const effect = this.sqlite.connection.transaction(() => {
      this.repository.updateStatus(principal.uid, 'DISABLED', now);
      this.notificationService?.onSocialDisable(principal.uid);
      const challengeEffect = this.challenges.applySocialDisable(principal.uid, now);
      const groupEffect = this.groups.applySocialDisable(principal.uid, now);
      return { ...challengeEffect, ...groupEffect };
    })();

    this.logger.info('social.disabled', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      // Contagens, e nunca identificadores (§128/§129; T17.11 §124). É o suficiente para
      // investigar "sumi de um desafio" ou "sumi de um squad" sem registrar de quais a pessoa
      // participava.
      ...effect,
    });
    return { profile: toOwnerProfile(this.reload(principal, account)) };
  }

  /**
   * `POST /v1/social/me/enable` — reativa preservando a identidade.
   *
   * `socialId` e `friendCode` são os mesmos de antes: o código que o usuário já compartilhou
   * continua sendo o dele, e as relações que a T17.1 vier a criar não precisam ser reconstruídas
   * a cada toque no interruptor.
   */
  enable(principal: AuthenticatedPrincipal, requestId: string): SocialProfileResponse {
    const account = this.require(principal);
    if (account.profile.status === 'ACTIVE') {
      throw SocialErrors.alreadyEnabled();
    }

    this.repository.updateStatus(principal.uid, 'ACTIVE', Date.now());
    this.logger.info('social.enabled', { requestId, uidPrefix: uidPrefix(principal.uid) });
    return { profile: toOwnerProfile(this.reload(principal, account)) };
  }

  /** O perfil da conta autenticada, ou `SOCIAL_NOT_ENABLED`. Toda escrita passa por aqui. */
  private require(principal: AuthenticatedPrincipal): StoredSocialAccount {
    const account = this.repository.find(principal.uid);
    if (!account) {
      throw SocialErrors.notEnabled();
    }
    return account;
  }

  /**
   * O estado depois da escrita, relido do banco.
   *
   * Devolver o objeto anterior com os campos alterados "na mão" faria a resposta descrever o que o
   * servidor **pretendia** gravar. O que a API afirma é o que ficou gravado.
   */
  private reload(
    principal: AuthenticatedPrincipal,
    fallback: StoredSocialAccount,
  ): StoredSocialAccount {
    return this.repository.find(principal.uid) ?? fallback;
  }
}

/**
 * O perfil do dono, na forma que sai na resposta.
 *
 * `ownerUid` **não** é copiado. Esta função é a fronteira onde o Firebase UID para de existir: o
 * repositório o carrega porque o banco precisa dele, e nada além daqui o vê.
 */
export function toOwnerProfile(account: StoredSocialAccount): SocialOwnerProfileDto {
  return {
    socialId: account.profile.socialId,
    friendCode: account.profile.friendCode,
    displayName: account.profile.displayName,
    status: account.profile.status,
    privacy: {
      discoverability: account.privacy.discoverability,
      friendRequestsEnabled: account.privacy.friendRequestsEnabled,
      activitySharingEnabled: account.privacy.activitySharingEnabled,
      activityTimeZoneId: account.privacy.activityTimeZoneId,
      friendRankingParticipationEnabled: account.privacy.friendRankingParticipationEnabled,
      updatedAt: account.privacy.updatedAt,
    },
    createdAt: account.profile.createdAt,
    updatedAt: account.profile.updatedAt,
  };
}
