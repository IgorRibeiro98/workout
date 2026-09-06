/**
 * O contrato de conversa do Coach IA entre o Spark Android e o Spark Backend (T16.2).
 *
 * A partir daqui o Android **não fala com o Gemini**: ele manda contexto e intenção, e o backend
 * decide prompt, modelo e limites. O que este arquivo declara é a fronteira HTTP — os nomes que
 * existem nos dois lados e que não podem divergir em silêncio.
 */

/** Os tipos de pedido que o Coach aceita. Espelha `AiCoachRequestType` no Android. */
export const AI_COACH_REQUEST_TYPES = [
  'ANALYZE_WORKOUT',
  'GENERATE_WORKOUT',
  'ADAPT_WORKOUT',
  'EXPLAIN_RECOMMENDATION',
  'EXPLAIN_WORKOUT',
  'EXPLAIN_ADAPTATION',
  'EXPLAIN_PROGRESS',
] as const;

export type AiCoachRequestType = (typeof AI_COACH_REQUEST_TYPES)[number];

/** Os quatro `EXPLAIN_*` são read-only por contrato: eles explicam algo que o app já decidiu. */
export function isExplanation(type: AiCoachRequestType): boolean {
  return type.startsWith('EXPLAIN_');
}

/**
 * Versão do contrato de conversa, **a mesma** de `AiModelConfig.SCHEMA_VERSION` no Android.
 *
 * Uma versão desconhecida é recusada explicitamente (`UNSUPPORTED_SCHEMA_VERSION`), nunca
 * interpretada "no melhor esforço": schema de saída, validador e contextos andam juntos com ela.
 */
export const AI_SCHEMA_VERSION = 1;

export const SUPPORTED_SCHEMA_VERSIONS: readonly number[] = [1];

export function isSupportedSchemaVersion(version: number): boolean {
  return SUPPORTED_SCHEMA_VERSIONS.includes(version);
}

/**
 * Códigos de erro do Coach.
 *
 * Eles saem no envelope da T16.0 (`{ error: { code, message, requestId } }`) e o Android os
 * traduz para a taxonomia que a UI já conhece. Nenhum deles carrega detalhe do SDK do Gemini.
 */
export const AI_ERROR_CODES = {
  /** Corpo fora do contrato: campo faltando, tipo errado, array ou string acima do teto. */
  INVALID_AI_REQUEST: 'INVALID_AI_REQUEST',
  /** `schemaVersion` que este servidor não sabe interpretar. */
  UNSUPPORTED_SCHEMA_VERSION: 'UNSUPPORTED_SCHEMA_VERSION',
  /** Já existe uma chamada equivalente em andamento para esta conta. */
  AI_REQUEST_CONFLICT: 'AI_REQUEST_CONFLICT',
  /** O modelo respondeu, mas a resposta não passou na validação estrutural/semântica. */
  INVALID_AI_RESPONSE: 'INVALID_AI_RESPONSE',
  /** Teto diário desta conta. */
  AI_USER_QUOTA_EXCEEDED: 'AI_USER_QUOTA_EXCEEDED',
  /** Teto diário do servidor inteiro — a proteção de custo que não depende de um usuário. */
  AI_GLOBAL_QUOTA_EXCEEDED: 'AI_GLOBAL_QUOTA_EXCEEDED',
  /** O provider falhou ou não está configurado neste servidor. */
  AI_PROVIDER_UNAVAILABLE: 'AI_PROVIDER_UNAVAILABLE',
  /** O provider não respondeu dentro do tempo permitido. */
  AI_PROVIDER_TIMEOUT: 'AI_PROVIDER_TIMEOUT',
} as const;

export type AiErrorCode = (typeof AI_ERROR_CODES)[keyof typeof AI_ERROR_CODES];

/**
 * O que o Android recebe em uma chamada bem-sucedida.
 *
 * `result` é a saída **já validada** do modelo, no mesmo formato que o validador do Android
 * espera — a validação do backend não substitui a dele (defense in depth, §16 da T16.2).
 *
 * `model` e `promptVersion` são metadata de diagnóstico: a UI de produção não depende deles.
 */
export interface AiCoachHttpResponse<TResult = unknown> {
  readonly requestId: string;
  readonly clientRequestId: string;
  readonly schemaVersion: number;
  readonly promptVersion: number;
  readonly model: string;
  readonly result: TResult;
}
