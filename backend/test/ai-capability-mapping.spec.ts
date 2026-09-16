import type { AiCoachRequestType } from '../src/modules/ai/ai-coach.contract';
import {
  AI_CAPABILITIES,
  capabilityFor,
  isAiCapability,
} from '../src/modules/ai/entitlement/ai-capability';

/**
 * O mapeamento operação → capability (T19.0 §6.5) e o reconhecimento das quatro capabilities
 * conhecidas. Nenhum destes testes toca rede ou banco — é lógica pura.
 */
describe('Mapeamento operação do Coach → capability', () => {
  it.each([
    ['ANALYZE_WORKOUT', 'AI_ANALYZE_WORKOUT'],
    ['GENERATE_WORKOUT', 'AI_GENERATE_WORKOUT'],
    ['ADAPT_WORKOUT', 'AI_ADAPT_WORKOUT'],
    ['EXPLAIN_RECOMMENDATION', 'AI_EXPLAIN'],
    ['EXPLAIN_WORKOUT', 'AI_EXPLAIN'],
    ['EXPLAIN_ADAPTATION', 'AI_EXPLAIN'],
    ['EXPLAIN_PROGRESS', 'AI_EXPLAIN'],
  ] as const)('%s → %s', (requestType, expected) => {
    expect(capabilityFor(requestType)).toBe(expected);
  });

  it('as quatro capabilities e as sete operações continuam alinhadas', () => {
    // Nenhuma operação do contrato fica sem capability, e nenhuma capability fica sem operação —
    // uma futura operação nova sem entrada aqui quebra a compilação de `capabilityFor`, não só
    // este teste.
    expect(AI_CAPABILITIES).toEqual([
      'AI_ANALYZE_WORKOUT',
      'AI_GENERATE_WORKOUT',
      'AI_ADAPT_WORKOUT',
      'AI_EXPLAIN',
    ]);
  });

  it('uma operação fora do contrato nunca cai em capability genérica', () => {
    expect(() => capabilityFor('SOMETHING_NEW' as AiCoachRequestType)).toThrow();
  });
});

describe('isAiCapability', () => {
  it('reconhece as quatro capabilities conhecidas', () => {
    for (const capability of AI_CAPABILITIES) {
      expect(isAiCapability(capability)).toBe(true);
    }
  });

  it.each(['AI_SOMETHING_NEW', '', 'ai_analyze_workout', 'AI_ANALYZE_WORKOUT '])(
    'recusa %p',
    (value) => {
      expect(isAiCapability(value)).toBe(false);
    },
  );
});
