import type { AiOutputSchema } from '../ai-coach.output.schema';

/**
 * Fronteira do Spark Backend com **qualquer** provider de modelo.
 *
 * Acima desta interface ninguém conhece Gemini, Groq, SDK, endpoint ou chave: o serviço do Coach
 * monta prompt e schema, chama `generate` e recebe texto ou erro tipado. É isso que permite testar
 * quota, concorrência, validação e mapeamento de erro **sem rede e sem cota** — e é isso que
 * torna a troca de provider uma troca de implementação (T19.H4).
 *
 * Um método só, porque a diferença entre os tipos de request é prompt e schema, não transporte.
 */
export interface AiProviderGateway {
  /**
   * Quem responde — metadata de diagnóstico para log e relatório, nunca base de decisão.
   *
   * Existe para que uma falha possa ser atribuída a provider e modelo mesmo quando não houve
   * resposta nenhuma (timeout, 503): o serviço loga `descriptor` e não precisa perguntar à
   * configuração qual provider está ativo — o que seria o `if (provider === ...)` que a T19.H4
   * proíbe fora do ponto de composição (`ai-provider.factory.ts`).
   */
  readonly descriptor: AiProviderDescriptor;
  generate(request: AiProviderRequest): Promise<AiProviderResult>;
}

export interface AiProviderDescriptor {
  /** `gemini`, `groq` — ou o rótulo de um dublê em `test/`. */
  readonly provider: string;
  /** O modelo configurado. O que efetivamente respondeu vem em `AiProviderResult.model`. */
  readonly model: string;
}

/** Os providers reais. A escolha entre eles mora em `ai-provider.factory.ts`, e só lá. */
export const AI_PROVIDER_NAMES = ['gemini', 'groq'] as const;

export type AiProviderName = (typeof AI_PROVIDER_NAMES)[number];

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
  /** Quem respondeu (`gemini`, `groq`). Metadata de diagnóstico; o Android não o recebe. */
  readonly provider: string;
  /** O modelo que efetivamente respondeu. Metadata de diagnóstico. */
  readonly model: string;
  readonly usage?: AiProviderUsage;
}

/** Contagem de tokens, quando o provider a informa de forma confiável. */
export interface AiProviderUsage {
  readonly promptTokens: number;
  /**
   * Tokens de saída **cobrados**: inclui o raciocínio do modelo quando o provider o conta como
   * saída (é o que o limite diário de tokens de um provider mede).
   */
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
  /** O provider respondeu, mas sem conteúdo utilizável (bloqueio, resposta vazia, truncada). */
  | 'EMPTY_RESPONSE'
  /** Qualquer outra falha do provider. */
  | 'UNAVAILABLE';

/**
 * Qual limite **do provider** estourou, quando ele o declara (T19.H4 §29).
 *
 * Vocabulário fechado de propósito: o valor sai de um casamento com esta lista, nunca da mensagem
 * crua do provider — que carrega identificador de organização, e não pode ir para log.
 */
export const AI_PROVIDER_LIMITS = ['RPM', 'RPD', 'TPM', 'TPD', 'OTPM'] as const;

export type AiProviderLimit = (typeof AI_PROVIDER_LIMITS)[number];

/** Metadata segura de uma falha: nada aqui é texto do provider, do usuário ou do modelo. */
export interface AiProviderFailureDetail {
  /** Status HTTP devolvido pelo provider, quando houve resposta. */
  readonly status?: number;
  /**
   * O limite do provider que recusou a chamada, quando ele o informa. `OTPM` é o de tokens de
   * **saída** por minuto — a Groq o aplica ao Qwen 3.8 no free tier (1 000/min, medido em
   * 2026-09-25), e ele não aparece na tabela pública de limites.
   */
  readonly limit?: AiProviderLimit;
  /**
   * O provider disse que **esta** requisição, sozinha, passa do limite ("Request too large") —
   * esperar não resolve; só uma requisição menor (ou outro plano).
   */
  readonly requestTooLarge?: boolean;
  /** Segundos que o provider pediu para esperar (`retry-after`), quando informados. */
  readonly retryAfterSeconds?: number;
  /**
   * Código curto do provider (`rate_limit_exceeded`, `json_validate_failed`…), só quando tem a
   * forma de um identificador. Mensagem nunca.
   */
  readonly providerCode?: string;
  /** Por que o modelo parou, quando parou antes da hora (`length`, `MAX_TOKENS`…). */
  readonly finishReason?: string;
  /** Tokens consumidos mesmo sem texto utilizável (uma resposta truncada custou). */
  readonly usage?: AiProviderUsage;
}

/**
 * Falha do provider, já traduzida.
 *
 * A mensagem original do SDK **não** atravessa esta fronteira: ela pode carregar endpoint
 * interno, fragmento de chave, identificador de organização ou trecho do prompt, e nada disso pode
 * chegar ao cliente nem ao log (§38, §50).
 */
export class AiProviderError extends Error {
  constructor(
    readonly kind: AiProviderFailureKind,
    /** Rótulo curto e seguro para log. Nunca conteúdo, nunca mensagem crua do SDK. */
    readonly reason: string,
    readonly detail: AiProviderFailureDetail = {},
  ) {
    super(`ai provider: ${kind}`);
    this.name = 'AiProviderError';
  }
}

/** Token de injeção: o serviço depende da interface, nunca da implementação. */
export const AI_PROVIDER_GATEWAY = Symbol('AI_PROVIDER_GATEWAY');
