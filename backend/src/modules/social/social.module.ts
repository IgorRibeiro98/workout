import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { FriendshipAccessPolicy } from './friendship.access-policy';
import { FriendshipController } from './friendship.controller';
import { FriendshipRateLimiter } from './friendship.rate-limit';
import { FriendshipRepository } from './friendship.repository';
import { FriendshipService } from './friendship.service';
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
 * ## Limitador
 *
 * A T17.0 não tinha um: as rotas de identidade escrevem um nome e três booleanos, e o teto geral
 * do `BearerAuthGuard` (600/min) bastava. O lookup por `friendCode` muda isso — ele **é** a rota
 * que alguém tentaria varrer —, e o envio de pedidos merece o mesmo tratamento por outra razão: um
 * bug em laço não pode virar centenas de convites. Os dois tetos vivem em `FriendshipRateLimiter`,
 * com a política declarada em `social.limits.ts`.
 *
 * Nenhum provider aqui é substituível por configuração. Não há flag que desligue autenticação,
 * ownership ou validação: as três são invariantes, não opções de deploy.
 */
@Module({
  imports: [AuthModule],
  controllers: [SocialController, FriendshipController],
  providers: [
    SocialService,
    SocialRepository,
    SocialAccessPolicy,
    FriendshipService,
    FriendshipRepository,
    FriendshipAccessPolicy,
    FriendshipRateLimiter,
  ],
  exports: [SocialAccessPolicy],
})
export class SocialModule {}
