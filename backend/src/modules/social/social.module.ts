import { Inject, MiddlewareConsumer, Module, NestModule, RequestMethod } from '@nestjs/common';
import { raw } from 'express';
import { AuthModule } from '../auth/auth.module';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { ChallengeAccessPolicy } from './challenge.access-policy';
import { ChallengeController } from './challenge.controller';
import { ChallengeRateLimiter } from './challenge.rate-limit';
import { ChallengeRepository } from './challenge.repository';
import { ChallengeScoringService } from './challenge.scoring';
import { ChallengeService } from './challenge.service';
import {
  CHALLENGE_PROGRESS_SOURCE,
  SyncedChallengeProgressSource,
} from './challenge-progress.source';
import { FriendshipAccessPolicy } from './friendship.access-policy';
import { FriendshipController } from './friendship.controller';
import { FriendshipRateLimiter } from './friendship.rate-limit';
import { FriendshipRepository } from './friendship.repository';
import { FriendshipService } from './friendship.service';
import { SocialProfileController } from './social-profile.controller';
import { SocialProfileService } from './social-profile.service';
import { SocialProgressPrivacyFilter, SocialProgressProjector } from './social-progress.projector';
import { SocialProgressSettingsRepository } from './social-progress.repository';
import { SOCIAL_PROGRESS_SOURCE, SyncedSocialProgressSource } from './social-progress.source';
import { SocialAccessPolicy } from './social.access-policy';
import { SocialController } from './social.controller';
import { SocialRepository } from './social.repository';
import { SocialService } from './social.service';
import { SocialActivityController } from './social-activity.controller';
import { SocialActivityService } from './social-activity.service';
import { FriendRankingService } from './friend-ranking.service';
import {
  CANONICAL_TRAINING_SOURCE,
  SyncedCanonicalTrainingSource,
} from './canonical-training.source';
import { NotificationController } from './notification.controller';
import { NotificationService } from './notification.service';
import { NotificationRepository } from './notification.repository';
import { NotificationDispatcher } from './notification.dispatcher';
import { PUSH_GATEWAY } from './push-gateway';
import { FirebasePushGateway } from './firebase-push-gateway';
import { BlockController } from './block.controller';
import { BlockService } from './block.service';
import { BlockRepository } from './block.repository';
import { ReportController } from './report.controller';
import { ReportService } from './report.service';
import { ReportRepository } from './report.repository';
import { WorkoutShareController } from './workout-share.controller';
import { WorkoutShareService } from './workout-share.service';
import { WorkoutShareRepository } from './workout-share.repository';
import { WorkoutCheckInController } from './workout-checkin.controller';
import { WorkoutCheckInService } from './workout-checkin.service';
import { WorkoutCheckInRepository } from './workout-checkin.repository';
import { WorkoutCheckInRateLimiter } from './workout-checkin.rate-limit';
import { WorkoutCheckInAccessPolicy } from './workout-checkin.access-policy';
import { CheckInInteractionRepository } from './checkin-interaction.repository';
import { SocialContentRateLimiter } from './social-content.rate-limit';
import { SocialMediaController } from './social-media.controller';
import { SocialMediaService } from './social-media.service';
import { SocialMediaRepository } from './social-media.repository';
import { SocialMediaProcessor } from './social-media.processor';
import { SocialMediaCleaner } from './social-media.cleaner';
import { LocalSocialMediaStore, SOCIAL_MEDIA_STORE } from './social-media.store';
import { ACCEPTED_IMAGE_FORMATS } from './social-media.limits';
import { SocialGroupController } from './social-group.controller';
import { SocialGroupService } from './social-group.service';
import { SocialGroupRepository } from './social-group.repository';
import { SocialGroupRateLimiter } from './social-group.rate-limit';
import { CheckInProjector } from './checkin.projector';

/**
 * Módulo do domínio social (T17.0).
 *
 * ## O que ele importa — e o que ele não pode importar
 *
 * Importa `AuthModule`, porque **não existe rota social pública**: identidade e privacidade só se
 * movem com um Firebase ID Token verificado, e o dono sai dele.
 *
 * Não importa `BackupModule`, `SyncModule` nem `AiModule`, e isso é a fronteira arquitetural da
 * T17.0 — não uma coincidência de quem ainda não precisou. O domínio social não pode alcançar
 * `BackupRepository`, `SyncRepository`, payload de backup, `sync_entities`, histórico bruto,
 * medidas corporais nem notas. O dia em que o feed precisar dizer "treinou hoje", o caminho é uma
 * `SocialProjection` explícita (`social.projection.ts`), e não um import daqui para lá. Há teste
 * estrutural sobre esses imports.
 *
 * ## O grafo mora aqui dentro, e não em um módulo à parte
 *
 * `FriendshipController`/`Service`/`Repository` (T17.1) entraram neste módulo, e não em um
 * `FriendshipModule` vizinho, porque eles precisam das mesmas duas coisas que a identidade: a
 * `SocialAccessPolicy` — que responde descoberta e `friendRequestsEnabled` — e as tabelas de
 * perfil. Um módulo separado teria de importar este para não duplicar a política, e o resultado
 * seria a mesma fronteira com um arquivo a mais. O que **não** pode acontecer é o inverso: o grafo
 * continua sem alcançar backup, sync e IA, e há teste sobre os imports.
 *
 * ## O perfil enriquecido (T17.2), e a fronteira que ele NÃO cruzou
 *
 * `SocialProfileController`/`Service` entraram aqui pelo mesmo motivo do grafo: eles precisam da
 * `SocialAccessPolicy` e da tabela de amizade, e um módulo vizinho teria de importar este para não
 * duplicar as duas.
 *
 * A parte que merece atenção é a fonte de progresso. `SyncedSocialProgressSource` lê **estado
 * sincronizado** (`sync_entities`) para responder uma pergunta agregada — quantas sessões
 * concluídas nesta semana. Ela faz isso pelo `SqliteService`, que é infraestrutura do processo, e
 * **não** por `SyncModule`/`SyncRepository`: os imports proibidos continuam proibidos, e o teste
 * estrutural sobre eles continua valendo. `BackupModule` e `AiModule` permanecem inalcançáveis em
 * qualquer forma — nenhuma projeção social lê snapshot ou payload de backup.
 *
 * ## Limitador
 *
 * A T17.0 não tinha um: as rotas de identidade escrevem um nome e três booleanos, e o teto geral
 * do `BearerAuthGuard` (600/min) bastava. O lookup por `friendCode` muda isso — ele **é** a rota
 * que alguém tentaria varrer —, e o envio de pedidos merece o mesmo tratamento por outra razão: um
 * bug em laço não pode virar centenas de convites. Os dois tetos vivem em `FriendshipRateLimiter`,
 * com a política declarada em `social.limits.ts`.
 *
 * A T17.3 acrescentou dois (`ChallengeRateLimiter`): criar dispara convites para pessoas que não
 * pediram nada, e é a operação mais cara do módulo em consequência social.
 *
 * A T17.2 não acrescentou teto próprio (§123). As rotas dela são leitura do próprio perfil e do
 * perfil de **um** amigo nomeado, sem enumeração possível: não há o que varrer, porque a resposta
 * exige uma amizade que o outro lado aceitou. O teto geral de 600/min do `BearerAuthGuard` basta,
 * e um limitador a mais sem uma ameaça a conter seria um número para manter sem razão.
 *
 * Nenhum provider aqui é substituível por configuração. Não há flag que desligue autenticação,
 * ownership ou validação: as três são invariantes, não opções de deploy.
 */
@Module({
  imports: [AuthModule],
  controllers: [
    SocialController,
    FriendshipController,
    SocialProfileController,
    ChallengeController,
    SocialActivityController,
    NotificationController,
    BlockController,
    ReportController,
    WorkoutShareController,
    WorkoutCheckInController,
    SocialMediaController,
    SocialGroupController,
  ],
  providers: [
    SocialService,
    SocialRepository,
    SocialAccessPolicy,
    FriendshipService,
    FriendshipRepository,
    FriendshipAccessPolicy,
    FriendshipRateLimiter,
    // T17.2 — o perfil enriquecido. Ele reusa `FriendshipRepository` (a amizade é a autorização)
    // e `SocialAccessPolicy` (a política é uma só), e acrescenta o pipeline de projeção.
    SocialProfileService,
    SocialProgressSettingsRepository,
    SocialProgressProjector,
    SocialProgressPrivacyFilter,
    { provide: SOCIAL_PROGRESS_SOURCE, useClass: SyncedSocialProgressSource },
    // T17.3 — os desafios. Eles reusam `FriendshipRepository` (a amizade é quem pode ser
    // convidado) e acrescentam a própria fonte canônica de pontuação, separada da do perfil.
    ChallengeService,
    ChallengeRepository,
    ChallengeAccessPolicy,
    ChallengeScoringService,
    ChallengeRateLimiter,
    { provide: CHALLENGE_PROGRESS_SOURCE, useClass: SyncedChallengeProgressSource },
    // T17.4 — atividade dos amigos e rankings contextuais.
    SocialActivityService,
    FriendRankingService,
    { provide: CANONICAL_TRAINING_SOURCE, useClass: SyncedCanonicalTrainingSource },
    // T17.5 — notificações sociais com Firebase Cloud Messaging.
    NotificationRepository,
    NotificationService,
    NotificationDispatcher,
    { provide: PUSH_GATEWAY, useClass: FirebasePushGateway },
    // T17.6 — Hardening social: bloqueio e denúncia de abuso.
    BlockRepository,
    BlockService,
    ReportRepository,
    ReportService,
    // T17.7 — Compartilhamento de treinos entre amigos.
    WorkoutShareRepository,
    WorkoutShareService,
    // T17.8 — Check-ins de treino + Feed Social. Ele reusa a `CanonicalTrainingSource` da T17.4.1
    // (a mesma fronteira que responde perfil, desafio e atividade) em vez de abrir um quarto
    // caminho até `sync_entities`, e `SocialRepository` para exigir perfil ativo dos dois lados.
    // O teto próprio existe para conter laço de cliente; a proteção contra spam é de domínio —
    // uma sessão canônica concluída, no máximo um check-in.
    WorkoutCheckInRepository,
    WorkoutCheckInService,
    WorkoutCheckInRateLimiter,
    // T17.9 — legenda, foto, reações e comentários sobre o **mesmo** agregado (§5). A política de
    // acesso saiu de dentro da consulta do feed e virou um provider próprio, consumido por seis
    // superfícies: Feed, detalhe, mídia, reações, comentários e denúncia (§129/§130).
    WorkoutCheckInAccessPolicy,
    CheckInInteractionRepository,
    SocialContentRateLimiter,
    SocialMediaRepository,
    SocialMediaProcessor,
    SocialMediaService,
    SocialMediaCleaner,
    { provide: SOCIAL_MEDIA_STORE, useClass: LocalSocialMediaStore },
    // T17.11 — Squads privados e feed de grupo. Eles reusam `FriendshipRepository` (a amizade é
    // quem pode ser convidado), `BlockRepository` (o bloqueio continua soberano),
    // `WorkoutCheckInRepository` (o feed do Squad é o **mesmo** check-in) e o
    // `WorkoutCheckInAccessPolicy`, que ganhou o terceiro caminho de acesso — SELF, FRIEND, GROUP.
    //
    // `CheckInProjector` nasceu aqui por necessidade: a montagem do card passou a ter duas
    // superfícies, e duas cópias divergiriam no primeiro campo novo (§50).
    CheckInProjector,
    SocialGroupRepository,
    SocialGroupService,
    SocialGroupRateLimiter,
  ],
  exports: [
    SocialAccessPolicy,
    NotificationService,
    NotificationRepository,
    BlockService,
    BlockRepository,
    WorkoutShareService,
    WorkoutShareRepository,
    // A exclusão de conta (T17.6) precisa apagar os **arquivos** de mídia (T17.9 §114): o
    // `ON DELETE CASCADE` do SQLite leva a metadata e não alcança o sistema de arquivos.
    SocialMediaRepository,
    SOCIAL_MEDIA_STORE,
    // A exclusão de conta e o bloqueio precisam alcançar o contexto de grupo (T17.11 §100/§105).
    SocialGroupRepository,
  ],
})
export class SocialModule implements NestModule {
  constructor(@Inject(APP_CONFIG) private readonly config: AppConfig) {}

  /**
   * O parser binário do upload de mídia (T17.9 §33), montado **só** na rota dele.
   *
   * ## Por que não global
   *
   * O processo já tem um parser JSON com o teto do backup (4 MiB). Registrar um parser binário
   * global faria toda rota bufferizar corpo, com um teto que não é o dela — e o teto do upload é
   * outro (`SOCIAL_MEDIA_MAX_UPLOAD_BYTES`). Escopar por rota é o que mantém os dois tetos
   * separados e o custo onde ele pertence.
   *
   * ## Por que não multipart
   *
   * `multipart/form-data` traria `multer` para o caminho de execução por um ganho de zero: o corpo
   * tem **um** arquivo e nenhum campo, e os dois identificadores (`sessionSyncId`,
   * `clientUploadId`) cabem no query string. Menos superfície, menos dependência, menos parser
   * entre a rede e a decodificação da imagem.
   *
   * ## O `type` não autoriza nada (§14)
   *
   * A lista de `Content-Type` abaixo escolhe **qual parser roda**, e não o que é aceito: um
   * cabeçalho `image/jpeg` sobre bytes de outra coisa passa por aqui e é recusado no
   * `SocialMediaProcessor`, que decodifica de verdade. Ela existe para que uma requisição com
   * `Content-Type: application/json` não seja bufferizada como binário por engano.
   */
  configure(consumer: MiddlewareConsumer): void {
    consumer
      .apply(
        raw({
          type: ACCEPTED_IMAGE_FORMATS.map((format) => `image/${format}`),
          limit: this.config.socialMediaMaxUploadBytes,
        }),
      )
      .forRoutes({ path: 'v1/social/checkin-media', method: RequestMethod.POST });
  }
}
