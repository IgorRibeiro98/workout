import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { AiCoachController } from './ai-coach.controller';
import { AiCoachService } from './ai-coach.service';
import { AiRequestRegistry } from './ai-request.registry';
import { AiUsageRepository } from './ai-usage.repository';
import { AI_PROVIDER_GATEWAY } from './provider/ai-provider.gateway';
import { GeminiAiProviderGateway } from './provider/gemini-ai-provider.gateway';

/**
 * Módulo do Coach IA (T16.2).
 *
 * O provider real é registrado sob `AI_PROVIDER_GATEWAY`; serviço e controller conhecem apenas a
 * interface. É isso que permite aos testes exercitarem quota, concorrência, validação e
 * mapeamento de erro com um dublê — sem rede, sem chave e sem cota — e sem que exista qualquer
 * chave de configuração capaz de desligar a validação em produção.
 *
 * Importa `AuthModule` porque a rota é protegida pelo `BearerAuthGuard`: no Spark Backend não
 * existe endpoint de IA público.
 */
@Module({
  imports: [AuthModule],
  controllers: [AiCoachController],
  providers: [
    AiCoachService,
    AiUsageRepository,
    AiRequestRegistry,
    GeminiAiProviderGateway,
    { provide: AI_PROVIDER_GATEWAY, useExisting: GeminiAiProviderGateway },
  ],
})
export class AiModule {}
