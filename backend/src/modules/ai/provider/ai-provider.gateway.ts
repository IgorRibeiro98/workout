import type { AiOutputSchema } from '../ai-coach.output.schema';

/**
 * Fronteira do Spark Backend com **qualquer** provider de modelo.
 *
 * Acima desta interface ninguém conhece Gemini, SDK, endpoint ou chave: o serviço do Coach monta
 * prompt e schema, chama `generate` e recebe texto ou erro tipado. É isso que permite testar
 * quota, concorrência, validação e mapeamento de erro **sem rede e sem cota** — e é isso que
 * torna a troca de provider uma troca de implementação.
 *
 * Um método só, porque a diferença entre os tipos de request é prompt e schema, não transporte.
 */
export interface AiProviderGateway {
  generate(request: AiProviderRequest): Promise<AiProviderResult>;
}

export interface AiProviderRequest {
  /** Correlação: aparece em log, nunca no prompt de sistema. */
  readonly requestId: string;
  readonly systemInstruction: string;
  readonly userPrompt: string;
  readonly responseSchema: AiOutputSchema;
}

export interface AiProviderResult {
  /** O texto cru do modelo — JSON, pelo structured output. Ainda não é confiável. */
  readonly text: string;
  /** O modelo que efetivamente respondeu. Metadata de diagnóstico. */
  readonly model: string;
  readonly usage?: AiProviderUsage;
}

/** Contagem de tokens, quando o provider a informa de forma confiável. */
export interface AiProviderUsage {
  readonly promptTokens: number;
  readonly outputTokens: number;
  readonly totalTokens: number;
}

/** Por que uma chamada ao provider não produziu texto. */
export type AiProviderFailureKind =
  /** Não há credencial/configuração de provider neste servidor. */
  | 'NOT_CONFIGURED'
  /** O provider recusou por limite de uso dele. */
  | 'RATE_LIMITED'
  /** Estourou o tempo máximo. */
  | 'TIMEOUT'
  /** O provider respondeu, mas sem conteúdo utilizável (bloqueio, resposta vazia). */
  | 'EMPTY_RESPONSE'
  /** Qualquer outra falha do provider. */
  | 'UNAVAILABLE';

/**
 * Falha do provider, já traduzida.
 *
 * A mensagem original do SDK **não** atravessa esta fronteira: ela pode carregar endpoint
 * interno, fragmento de chave ou trecho do prompt, e nada disso pode chegar ao cliente nem ao
 * log (§38, §50).
 */
export class AiProviderError extends Error {
  constructor(
    readonly kind: AiProviderFailureKind,
    /** Rótulo curto e seguro para log. Nunca conteúdo, nunca mensagem crua do SDK. */
    readonly reason: string,
  ) {
    super(`ai provider: ${kind}`);
    this.name = 'AiProviderError';
  }
}

/** Token de injeção: o serviço depende da interface, nunca da implementação. */
export const AI_PROVIDER_GATEWAY = Symbol('AI_PROVIDER_GATEWAY');
