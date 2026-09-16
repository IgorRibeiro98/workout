import { isExplanation, type AiCoachRequestType } from '../ai-coach.contract';

/**
 * As capabilities de IA que a T19.0 introduz.
 *
 * Cada uma responde a uma pergunta binária — "esta conta pode usar esta funcionalidade?" — e
 * nenhuma delas é um plano. Uma capability nova precisa entrar aqui **e** em
 * `AiCapability.kt` no Android; os dois lados são o mesmo contrato, do mesmo jeito que
 * `AI_COACH_REQUEST_TYPES` já é espelhado nos dois lados.
 */
export const AI_CAPABILITIES = [
  'AI_ANALYZE_WORKOUT',
  'AI_GENERATE_WORKOUT',
  'AI_ADAPT_WORKOUT',
  'AI_EXPLAIN',
] as const;

export type AiCapability = (typeof AI_CAPABILITIES)[number];

export function isAiCapability(value: string): value is AiCapability {
  return (AI_CAPABILITIES as readonly string[]).includes(value);
}

/**
 * Operação do Coach → capability exigida.
 *
 * Único lugar com este mapeamento (§6.5): os quatro `EXPLAIN_*` reaproveitam o discriminador
 * `isExplanation()` que o contrato já tem, em vez de repetir a lista de quatro nomes aqui. Uma
 * capability nova para uma nova operação entra numa correspondência 1:1 explícita — este `switch`
 * nunca tem um `default` que devolve uma capability genérica, porque isso é exatamente o "cair
 * silenciosamente em permissão genérica" que a tarefa proíbe (§11).
 */
export function capabilityFor(requestType: AiCoachRequestType): AiCapability {
  if (isExplanation(requestType)) {
    return 'AI_EXPLAIN';
  }
  switch (requestType) {
    case 'ANALYZE_WORKOUT':
      return 'AI_ANALYZE_WORKOUT';
    case 'GENERATE_WORKOUT':
      return 'AI_GENERATE_WORKOUT';
    case 'ADAPT_WORKOUT':
      return 'AI_ADAPT_WORKOUT';
    default: {
      // Exaustividade em tempo de compilação: um `AiCoachRequestType` novo sem entrada aqui quebra
      // o build, em vez de silenciosamente herdar uma capability errada.
      const exhaustive: never = requestType;
      throw new Error(`operação de Coach sem capability mapeada: ${String(exhaustive)}`);
    }
  }
}
