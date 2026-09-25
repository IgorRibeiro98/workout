import { Module } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { AuthModule } from '../auth/auth.module';
import { AiCoachController } from './ai-coach.controller';
import { AiCoachService } from './ai-coach.service';
import { AiRequestRegistry } from './ai-request.registry';
import { AiUsageRepository } from './ai-usage.repository';
import { AccountCapabilitiesController } from './entitlement/account-capabilities.controller';
import { AiEntitlementRepository } from './entitlement/ai-entitlement.repository';
import { AiEntitlementResolver } from './entitlement/ai-entitlement.resolver';
import { AI_PROVIDER_GATEWAY } from './provider/ai-provider.gateway';
import { createAiProviderGateway } from './provider/ai-provider.factory';

/**
 * Módulo do Coach IA (T16.2; multi-provider na T19.H4).
 *
 * O provider real é registrado sob `AI_PROVIDER_GATEWAY` pela factory — o único lugar que lê
 * `AI_PROVIDER`; serviço e controller conhecem apenas a interface. É isso que permite aos testes
 * exercitarem quota, concorrência, validação e mapeamento de erro com um dublê — sem rede, sem
 * chave e sem cota — e sem que exista qualquer chave de configuração capaz de desligar a validação
 * em produção.
 *
 * Importa `AuthModule` porque a rota é protegida pelo `BearerAuthGuard`: no Spark Backend não
 * existe endpoint de IA público.
 *
 * `AiEntitlementResolver` (T19.0) é o único lugar que decide entitlement, usado por
 * `AiCoachService` (para autorizar uma operação) e por `AccountCapabilitiesController` (para
 * `GET /v1/account/capabilities`) — as duas pontas do mesmo módulo, nunca duas fontes.
 */
@Module({
  imports: [AuthModule],
  controllers: [AiCoachController, AccountCapabilitiesController],
  providers: [
    AiCoachService,
    AiUsageRepository,
    AiRequestRegistry,
    AiEntitlementRepository,
    AiEntitlementResolver,
    {
      provide: AI_PROVIDER_GATEWAY,
      useFactory: (config: AppConfig, logger: SparkLogger) =>
        createAiProviderGateway(config, logger),
      inject: [APP_CONFIG, SparkLogger],
    },
  ],
})
export class AiModule {}
