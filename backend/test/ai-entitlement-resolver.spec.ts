import { SparkLogger } from '../src/common/logger';
import type {
  AiEntitlementRepository,
  AiEntitlementState,
} from '../src/modules/ai/entitlement/ai-entitlement.repository';
import { AiEntitlementResolver } from '../src/modules/ai/entitlement/ai-entitlement.resolver';
import { configFor } from './support/temp-db';

/**
 * Dublê em memória do repositório — sem banco, sem transação, só o suficiente para exercitar as
 * decisões do resolver. `AiEntitlementResolver` é o **único** lugar sob teste aqui; a persistência
 * real (`ai-entitlement.repository.ts`) é coberta pelos testes de ponta a ponta em
 * `ai-entitlement-enforcement.spec.ts` e `ai-capabilities-endpoint.spec.ts`.
 */
class FakeAiEntitlementRepository {
  private readonly states = new Map<string, AiEntitlementState>();
  private failing = false;

  set(uid: string, capability: string, state: AiEntitlementState): void {
    this.states.set(`${uid}:${capability}`, state);
  }

  failNext(): void {
    this.failing = true;
  }

  stateFor(uid: string, capability: string): Promise<AiEntitlementState | null> {
    if (this.failing) throw new Error('conexão indisponível');
    return Promise.resolve(this.states.get(`${uid}:${capability}`) ?? null);
  }

  statesFor(uid: string): Promise<ReadonlyMap<string, AiEntitlementState>> {
    if (this.failing) throw new Error('conexão indisponível');
    const result = new Map<string, AiEntitlementState>();
    for (const [key, state] of this.states) {
      const [rowUid, capability] = key.split(':');
      if (rowUid === uid) {
        result.set(capability, state);
      }
    }
    return Promise.resolve(result);
  }
}

function resolverWith(repo: FakeAiEntitlementRepository): AiEntitlementResolver {
  const logger = new SparkLogger(configFor());
  return new AiEntitlementResolver(repo as unknown as AiEntitlementRepository, logger);
}

describe('AiEntitlementResolver.resolve', () => {
  it('capability conhecida sem linha é liberada por default — a estratégia de compatibilidade', async () => {
    const decision = await resolverWith(new FakeAiEntitlementRepository()).resolve(
      'uid-a',
      'AI_ANALYZE_WORKOUT',
    );
    expect(decision).toEqual({ allowed: true, reason: 'DEFAULT_ALLOW' });
  });

  it('capability GRANTED explicitamente é liberada', async () => {
    const repo = new FakeAiEntitlementRepository();
    repo.set('uid-a', 'AI_ANALYZE_WORKOUT', 'GRANTED');
    const decision = await resolverWith(repo).resolve('uid-a', 'AI_ANALYZE_WORKOUT');
    expect(decision).toEqual({ allowed: true, reason: 'GRANTED' });
  });

  it('capability REVOKED é negada', async () => {
    const repo = new FakeAiEntitlementRepository();
    repo.set('uid-a', 'AI_ANALYZE_WORKOUT', 'REVOKED');
    const decision = await resolverWith(repo).resolve('uid-a', 'AI_ANALYZE_WORKOUT');
    expect(decision).toEqual({ allowed: false, reason: 'REVOKED' });
  });

  it('a revogação de uma conta não vaza para outra', async () => {
    const repo = new FakeAiEntitlementRepository();
    repo.set('uid-a', 'AI_ANALYZE_WORKOUT', 'REVOKED');
    const decision = await resolverWith(repo).resolve('uid-b', 'AI_ANALYZE_WORKOUT');
    expect(decision).toEqual({ allowed: true, reason: 'DEFAULT_ALLOW' });
  });

  it('capability fora da lista conhecida nunca é liberada — fail-closed, não permissão genérica', async () => {
    const decision = await resolverWith(new FakeAiEntitlementRepository()).resolve(
      'uid-a',
      'AI_SOMETHING_NEW',
    );
    expect(decision).toEqual({ allowed: false, reason: 'UNKNOWN_CAPABILITY' });
  });

  it('falha ao consultar o banco nunca é liberada — fail-closed', async () => {
    const repo = new FakeAiEntitlementRepository();
    repo.failNext();
    const decision = await resolverWith(repo).resolve('uid-a', 'AI_ANALYZE_WORKOUT');
    expect(decision).toEqual({ allowed: false, reason: 'RESOLUTION_FAILED' });
  });
});

describe('AiEntitlementResolver.resolveAll', () => {
  it('sem nenhuma linha, as quatro capabilities vêm liberadas por default', async () => {
    const decisions = await resolverWith(new FakeAiEntitlementRepository()).resolveAll('uid-a');
    expect(decisions.size).toBe(4);
    for (const decision of decisions.values()) {
      expect(decision).toEqual({ allowed: true, reason: 'DEFAULT_ALLOW' });
    }
  });

  it('reflete uma revogação seletiva sem afetar as demais capabilities', async () => {
    const repo = new FakeAiEntitlementRepository();
    repo.set('uid-a', 'AI_ADAPT_WORKOUT', 'REVOKED');
    const decisions = await resolverWith(repo).resolveAll('uid-a');
    expect(decisions.get('AI_ADAPT_WORKOUT')).toEqual({ allowed: false, reason: 'REVOKED' });
    expect(decisions.get('AI_ANALYZE_WORKOUT')).toEqual({ allowed: true, reason: 'DEFAULT_ALLOW' });
    expect(decisions.get('AI_GENERATE_WORKOUT')).toEqual({
      allowed: true,
      reason: 'DEFAULT_ALLOW',
    });
    expect(decisions.get('AI_EXPLAIN')).toEqual({ allowed: true, reason: 'DEFAULT_ALLOW' });
  });

  it('falha do banco nunca produz sucesso parcial — nenhuma capability é liberada', async () => {
    const repo = new FakeAiEntitlementRepository();
    repo.failNext();
    const decisions = await resolverWith(repo).resolveAll('uid-a');
    expect(decisions.size).toBe(4);
    for (const decision of decisions.values()) {
      expect(decision).toEqual({ allowed: false, reason: 'RESOLUTION_FAILED' });
    }
  });
});
