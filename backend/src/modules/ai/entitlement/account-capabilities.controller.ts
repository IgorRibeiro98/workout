import { Controller, Get, UseGuards } from '@nestjs/common';
import type { AuthenticatedPrincipal } from '../../auth/authenticated-principal';
import { BearerAuthGuard } from '../../auth/bearer-auth.guard';
import { Principal } from '../../auth/principal.decorator';
import { AiCoachErrors } from '../ai-coach.errors';
import { AI_CAPABILITIES } from './ai-capability';
import { AiEntitlementResolver } from './ai-entitlement.resolver';
import type { AccountCapabilitiesResponseDto } from './account-capabilities.contract';

/**
 * `GET /v1/account/capabilities` — o que **esta** conta pode fazer no Coach IA (T19.0 §12).
 *
 * O `uid` vem só do token verificado, pelo `@Principal()` — não existe (e não pode existir)
 * parâmetro de conta na rota, na query ou no corpo. Pedir a capability de outra conta por aqui não
 * é possível de propósito: não há como endereçar outra conta nesta requisição (§13/§14).
 *
 * Estado account-scoped: o Android descarta este resultado ao trocar de conta, do mesmo jeito que
 * já descarta qualquer outra resposta em voo (`onAccountChanged`). Este endpoint não introduz um
 * segundo mecanismo de account-scoping — reaproveita o que o app já tem.
 */
@Controller('account')
export class AccountCapabilitiesController {
  constructor(private readonly resolver: AiEntitlementResolver) {}

  @UseGuards(BearerAuthGuard)
  @Get('capabilities')
  async capabilities(
    @Principal() principal: AuthenticatedPrincipal,
  ): Promise<AccountCapabilitiesResponseDto> {
    const decisions = await this.resolver.resolveAll(principal.uid);

    // Falha ao determinar não pode virar "nenhuma capability liberada" (§18): um `allowed: false`
    // por não saber é indistinguível, para o Android, de uma revogação deliberada. A recusa
    // explícita do endpoint inteiro é o que preserva essa distinção.
    const unresolved = [...decisions.values()].some(
      (decision) => decision.reason === 'RESOLUTION_FAILED',
    );
    if (unresolved) {
      throw AiCoachErrors.entitlementUnavailable();
    }

    return {
      capabilities: AI_CAPABILITIES.map((capability) => ({
        capability,
        allowed: decisions.get(capability)?.allowed ?? false,
      })),
    };
  }
}
