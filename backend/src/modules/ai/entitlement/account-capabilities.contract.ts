import type { AiCapability } from './ai-capability';

/**
 * `GET /v1/account/capabilities` (T19.0 §12).
 *
 * O corpo é deliberadamente pobre: só a capability e se ela está liberada. Sem `uid`, sem o motivo
 * interno da decisão (`DEFAULT_ALLOW` vs `GRANTED` não interessam ao cliente) e sem qualquer outro
 * detalhe de configuração — o dono já saiu do token, e o Android não precisa de mais que isto para
 * decidir o que mostrar (§8).
 */
export interface AiCapabilityStateDto {
  readonly capability: AiCapability;
  readonly allowed: boolean;
}

export interface AccountCapabilitiesResponseDto {
  readonly capabilities: readonly AiCapabilityStateDto[];
}
