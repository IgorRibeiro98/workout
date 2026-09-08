import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
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
  controllers: [SocialController, FriendshipController, SocialProfileController],
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
  ],
  exports: [SocialAccessPolicy],
})
export class SocialModule {}
