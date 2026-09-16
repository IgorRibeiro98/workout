import { Injectable } from '@nestjs/common';
import { SparkLogger } from '../../../common/logger';
import { uidPrefix } from '../../auth/bearer-auth.guard';
import { AI_CAPABILITIES, isAiCapability, type AiCapability } from './ai-capability';
import { AiEntitlementRepository, type AiEntitlementState } from './ai-entitlement.repository';

export type EntitlementDecisionReason =
  | 'GRANTED'
  | 'DEFAULT_ALLOW'
  | 'REVOKED'
  | 'UNKNOWN_CAPABILITY'
  | 'RESOLUTION_FAILED';

export interface EntitlementDecision {
  readonly allowed: boolean;
  readonly reason: EntitlementDecisionReason;
}

/**
 * O único lugar do Spark Backend que responde "esta conta pode usar esta capability de IA?"
 * (T19.0 §5/§6).
 *
 * Nenhum controller ou serviço deve consultar `AiEntitlementRepository` diretamente para decidir
 * autorização — só este resolver decide, do mesmo jeito que `SocialAccessPolicy` é o único lugar
 * que decide visibilidade social. `AiCoachService` usa `resolve()` antes de reservar concorrência
 * ou quota; `AccountCapabilitiesController` usa `resolveAll()` para o próprio endpoint de leitura;
 * o CLI de administração usa o mesmo `isAiCapability` para recusar um nome desconhecido antes de
 * gravar qualquer linha.
 *
 * ## A semântica de "linha ausente" (§6.6, decisão central desta tarefa)
 *
 * A tabela guarda **exceções**, não concessões: para uma capability conhecida, nenhuma linha
 * significa `DEFAULT_ALLOW` — a mesma permissão que toda conta autenticada já tinha antes da
 * T19.0 existir. Isto não é "cair" em permissão por acidente; é a estratégia de compatibilidade
 * documentada e testada (§20/§21): a introdução da ACL não pode revogar o Coach de quem já o usava.
 * `REVOKED` é a única forma de negar uma capability conhecida.
 *
 * ## Fail-closed (§6.4)
 *
 * Uma capability **fora** da lista conhecida, ou uma falha ao consultar o banco, nunca é
 * interpretada como permissão — as duas produzem `allowed: false`, com razões diferentes para que
 * o log distinga "negado de propósito" de "não foi possível decidir" (§24). O `AiCoachService`
 * traduz a segunda em `503` (como `ACCOUNT_STATE_UNAVAILABLE` já faz para o tombstone de exclusão),
 * nunca em `403`: a diferença importa para quem opera o servidor, mesmo que as duas façam o mesmo
 * — recusar a operação online — do ponto de vista do produto. Nenhuma das duas afeta o Workout
 * local, que não passa por aqui.
 */
@Injectable()
export class AiEntitlementResolver {
  constructor(
    private readonly repository: AiEntitlementRepository,
    private readonly logger: SparkLogger,
  ) {}

  async resolve(uid: string, capability: string): Promise<EntitlementDecision> {
    if (!isAiCapability(capability)) {
      this.logger.warn('ai.entitlement.unknown_capability', {
        uidPrefix: uidPrefix(uid),
        capability,
      });
      return { allowed: false, reason: 'UNKNOWN_CAPABILITY' };
    }

    let state: AiEntitlementState | null;
    try {
      state = await this.repository.stateFor(uid, capability);
    } catch (error) {
      this.logger.error('ai.entitlement.resolution_failed', {
        uidPrefix: uidPrefix(uid),
        capability,
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
      return { allowed: false, reason: 'RESOLUTION_FAILED' };
    }

    return this.decisionFor(state);
  }

  /**
   * O estado das quatro capabilities conhecidas, em uma consulta — para o endpoint de leitura.
   *
   * Uma falha do banco aqui produz `RESOLUTION_FAILED` para as quatro, nunca uma mistura silenciosa
   * de "descobri algumas, as outras eu suponho": o endpoint precisa decidir, como um todo, entre
   * responder ou recusar (§18 — falha ao consultar não pode virar permissão).
   */
  async resolveAll(uid: string): Promise<ReadonlyMap<AiCapability, EntitlementDecision>> {
    let states: ReadonlyMap<AiCapability, AiEntitlementState>;
    try {
      states = await this.repository.statesFor(uid);
    } catch (error) {
      this.logger.error('ai.entitlement.resolution_failed', {
        uidPrefix: uidPrefix(uid),
        capability: 'ALL',
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
      const failed = new Map<AiCapability, EntitlementDecision>();
      for (const capability of AI_CAPABILITIES) {
        failed.set(capability, { allowed: false, reason: 'RESOLUTION_FAILED' });
      }
      return failed;
    }

    const decisions = new Map<AiCapability, EntitlementDecision>();
    for (const capability of AI_CAPABILITIES) {
      decisions.set(capability, this.decisionFor(states.get(capability) ?? null));
    }
    return decisions;
  }

  private decisionFor(state: AiEntitlementState | null): EntitlementDecision {
    switch (state) {
      case 'REVOKED':
        return { allowed: false, reason: 'REVOKED' };
      case 'GRANTED':
        return { allowed: true, reason: 'GRANTED' };
      case null:
        return { allowed: true, reason: 'DEFAULT_ALLOW' };
    }
  }
}
