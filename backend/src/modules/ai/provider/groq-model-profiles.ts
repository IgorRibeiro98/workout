/**
 * Os modelos da Groq que o Spark sabe usar — e **como** (T19.H4 §13, §14, §41).
 *
 * Um modelo só entra aqui depois de avaliado pelo benchmark do Coach (`npm run ai:benchmark`):
 * esta tabela é a lista do que foi medido, não um catálogo. Um `GROQ_MODEL` fora dela derruba o
 * startup — trocar entre modelos já avaliados é configuração + deploy; adotar um modelo novo é
 * avaliá-lo e acrescentar a linha.
 *
 * Nenhum SDK é importado aqui: `AppConfig` lê esta tabela para validar a configuração antes de o
 * processo aceitar requisição, e só o gateway da Groq conhece o `groq-sdk`.
 *
 * Revisado contra a documentação da Groq em 2026-09-24
 * (console.groq.com/docs/models, /docs/reasoning, /docs/structured-outputs).
 */

/** O vocabulário de `AI_THINKING_LEVEL`, compartilhado pelos providers. */
export const AI_THINKING_LEVELS = ['MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'OFF'] as const;

export type AiThinkingLevel = (typeof AI_THINKING_LEVELS)[number];

/** Os valores de `reasoning_effort` que a Groq aceita (o suporte varia por modelo). */
export type GroqReasoningEffort = 'none' | 'low' | 'medium' | 'high';

export interface GroqModelProfile {
  /**
   * Classificação do modelo na Groq. `PREVIEW` é "para avaliação": nunca vira produção por
   * acidente — `NODE_ENV=production` com um modelo `PREVIEW` exige `GROQ_ALLOW_PREVIEW_MODEL=true`,
   * que é a forma escrita de "preview risk accepted" (§41).
   */
  readonly lifecycle: 'PRODUCTION' | 'PREVIEW';
  /**
   * `AI_THINKING_LEVEL` → `reasoning_effort`. Um nível **ausente** é uma combinação que o modelo
   * não suporta, e ela derruba o startup: nunca "valor desconhecido → default do provider" (§14).
   */
  readonly reasoningEffort: Readonly<Partial<Record<AiThinkingLevel, GroqReasoningEffort>>>;
  /**
   * Como pedir que o raciocínio **não** volte no corpo da resposta. O Spark nunca o usa, nunca o
   * guarda e nunca o loga — não recebê-lo é a forma mais simples de garantir as três coisas. Os dois
   * parâmetros são mutuamente exclusivos na API, e cada família aceita um deles.
   */
  readonly reasoningVisibility:
    | { readonly include_reasoning: false }
    | { readonly reasoning_format: 'hidden' };
}

export const GROQ_MODEL_PROFILES: Readonly<Record<string, GroqModelProfile>> = {
  // Production. Structured output strict (constrained decoding). `reasoning_effort` só aceita
  // low/medium/high — o raciocínio não desliga, então `OFF` e `MINIMAL` não têm tradução honesta.
  'openai/gpt-oss-120b': {
    lifecycle: 'PRODUCTION',
    reasoningEffort: { LOW: 'low', MEDIUM: 'medium', HIGH: 'high' },
    reasoningVisibility: { include_reasoning: false },
  },
  // Preview. Structured output strict. `none` desliga o raciocínio; `high` seleciona o modo
  // `xhigh` nativo do modelo. Não existe `minimal`.
  'qwen/qwen3.8-27b': {
    lifecycle: 'PREVIEW',
    reasoningEffort: { OFF: 'none', LOW: 'low', MEDIUM: 'medium', HIGH: 'high' },
    reasoningVisibility: { reasoning_format: 'hidden' },
  },
};

export function groqModelProfile(model: string): GroqModelProfile | undefined {
  return Object.prototype.hasOwnProperty.call(GROQ_MODEL_PROFILES, model)
    ? GROQ_MODEL_PROFILES[model]
    : undefined;
}

/**
 * O que está errado em usar `model` com `thinkingLevel` — vazio quando a combinação é suportada.
 *
 * Mensagens de configuração, escritas para quem opera o deploy: dizem o que foi pedido e o que
 * existe, nunca "use o default".
 */
export function groqModelIssues(model: string, thinkingLevel: AiThinkingLevel): string[] {
  const profile = groqModelProfile(model);
  if (!profile) {
    return [
      `GROQ_MODEL=${model} não está entre os modelos avaliados (${Object.keys(GROQ_MODEL_PROFILES).join(', ')}); avalie-o com npm run ai:benchmark e acrescente o perfil em groq-model-profiles.ts`,
    ];
  }
  if (profile.reasoningEffort[thinkingLevel] === undefined) {
    const supported = Object.keys(profile.reasoningEffort).join(', ');
    return [
      `AI_THINKING_LEVEL=${thinkingLevel} não é suportado por ${model} na Groq (suportados: ${supported})`,
    ];
  }
  return [];
}
