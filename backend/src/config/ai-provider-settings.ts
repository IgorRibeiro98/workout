import type { z } from 'zod';
import type { AiProviderName } from '../modules/ai/provider/ai-provider.gateway';
import {
  groqModelIssues,
  groqModelProfile,
  type AiThinkingLevel,
} from '../modules/ai/provider/groq-model-profiles';
import { envSchema } from './env.schema';
import type { LoggerSettings } from './dr-job-config';

/**
 * O que um gateway de provider de IA precisa saber — e nada além disso (T19.H4).
 *
 * `AppConfig` satisfaz esta interface por estrutura; os comandos operacionais do Coach
 * (`ai:benchmark`, `ai:provider-smoke`) usam `AiProviderConfig`, que lê **só** estas variáveis:
 * avaliar um provider não exige `DATABASE_URL`, Firebase nem HMAC — o mesmo desenho de
 * `DrJobConfig` para os Jobs de DR.
 *
 * Os campos são **os mesmos** do `envSchema` (`.pick`): a validação de modelo, timeout,
 * temperatura e esforço de raciocínio nunca diverge entre a API e as ferramentas.
 */
export interface AiProviderSettings {
  readonly aiProvider: AiProviderName;
  readonly geminiApiKey: string | undefined;
  readonly geminiModel: string;
  readonly groqApiKey: string | undefined;
  readonly groqModel: string;
  readonly aiTimeoutMs: number;
  readonly aiTemperature: number;
  /** O teto compartilhado — cada provider lê o seu via `geminiMaxOutputTokens`/`groqMaxOutputTokens`. */
  readonly aiMaxOutputTokens: number;
  /** `GEMINI_MAX_OUTPUT_TOKENS`, ou o compartilhado quando ausente. */
  readonly geminiMaxOutputTokens: number;
  /** `GROQ_MAX_OUTPUT_TOKENS`, ou o compartilhado quando ausente. */
  readonly groqMaxOutputTokens: number;
  readonly aiThinkingLevel: AiThinkingLevel;
}

/** A variável que guarda a credencial de cada provider — para mensagens de configuração. */
export const API_KEY_ENV_BY_PROVIDER: Readonly<Record<AiProviderName, string>> = {
  gemini: 'GEMINI_API_KEY',
  groq: 'GROQ_API_KEY',
};

/** A variável que escolhe o modelo de cada provider — um id de um não tem sentido no outro (§12). */
export const MODEL_ENV_BY_PROVIDER: Readonly<Record<AiProviderName, string>> = {
  gemini: 'GEMINI_MODEL',
  groq: 'GROQ_MODEL',
};

/** A credencial do provider selecionado. `undefined` = Coach indisponível, nunca inseguro. */
export function selectedProviderApiKey(settings: AiProviderSettings): string | undefined {
  return settings.aiProvider === 'groq' ? settings.groqApiKey : settings.geminiApiKey;
}

/** O modelo configurado para o provider selecionado. */
export function selectedProviderModel(settings: AiProviderSettings): string {
  return settings.aiProvider === 'groq' ? settings.groqModel : settings.geminiModel;
}

/** O teto de saída efetivo do provider selecionado (inclui o raciocínio do modelo). */
export function selectedProviderMaxOutputTokens(settings: AiProviderSettings): number {
  return settings.aiProvider === 'groq'
    ? settings.groqMaxOutputTokens
    : settings.geminiMaxOutputTokens;
}

/**
 * Combinações que a configuração declara e o provider selecionado não sustenta.
 *
 * Validada no startup (`AppConfig.fromEnv`) e nas ferramentas (`AiProviderConfig.fromEnv`):
 * uma combinação inválida derruba o processo antes da primeira requisição — nunca vira "o provider
 * usa o default dele" em silêncio (§14), e nunca vira 503 em cada chamada parecendo instabilidade.
 *
 * Só o provider **selecionado** é conferido: um `GROQ_MODEL` qualquer não pode derrubar um
 * servidor que atende pelo Gemini.
 */
export function aiProviderConfigurationIssues(
  settings: Pick<AiProviderSettings, 'aiProvider' | 'groqModel' | 'aiThinkingLevel'>,
  deployment: { readonly isProduction: boolean; readonly groqAllowPreviewModel: boolean },
): string[] {
  if (settings.aiProvider !== 'groq') {
    return [];
  }
  const issues = groqModelIssues(settings.groqModel, settings.aiThinkingLevel);
  const profile = groqModelProfile(settings.groqModel);
  // §41 — modelo Preview em produção só com a decisão escrita na configuração. O benchmark e o
  // desenvolvimento podem usá-lo livremente: é para isso que Preview existe.
  if (
    profile?.lifecycle === 'PREVIEW' &&
    deployment.isProduction &&
    !deployment.groqAllowPreviewModel
  ) {
    issues.push(
      `GROQ_MODEL=${settings.groqModel} é Preview na Groq; em produção isso exige GROQ_ALLOW_PREVIEW_MODEL=true (risco de preview aceito explicitamente)`,
    );
  }
  return issues;
}

const aiProviderEnv = envSchema.pick({
  NODE_ENV: true,
  LOG_LEVEL: true,
  AI_PROVIDER: true,
  GEMINI_API_KEY: true,
  GEMINI_MODEL: true,
  GROQ_API_KEY: true,
  GROQ_MODEL: true,
  GROQ_ALLOW_PREVIEW_MODEL: true,
  AI_TIMEOUT_MS: true,
  AI_TEMPERATURE: true,
  AI_MAX_OUTPUT_TOKENS: true,
  GEMINI_MAX_OUTPUT_TOKENS: true,
  GROQ_MAX_OUTPUT_TOKENS: true,
  AI_THINKING_LEVEL: true,
});

export class AiProviderConfigError extends Error {
  constructor(readonly issues: string[]) {
    super(`Configuração do provider de IA inválida:\n${issues.map((i) => `  - ${i}`).join('\n')}`);
    this.name = 'AiProviderConfigError';
  }
}

/** A configuração de provider das ferramentas do Coach — sem banco, sem Firebase, sem HMAC. */
export class AiProviderConfig implements AiProviderSettings, LoggerSettings {
  private constructor(private readonly env: z.infer<typeof aiProviderEnv>) {}

  static fromEnv(source: NodeJS.ProcessEnv = process.env): AiProviderConfig {
    const result = aiProviderEnv.safeParse({ ...source });
    if (!result.success) {
      throw new AiProviderConfigError(
        result.error.issues.map((issue) => `${issue.path.join('.') || '(raiz)'}: ${issue.message}`),
      );
    }
    const config = new AiProviderConfig(result.data);
    const issues = aiProviderConfigurationIssues(config, {
      isProduction: result.data.NODE_ENV === 'production',
      groqAllowPreviewModel: result.data.GROQ_ALLOW_PREVIEW_MODEL,
    });
    if (issues.length > 0) {
      throw new AiProviderConfigError(issues);
    }
    return config;
  }

  get logLevel(): LoggerSettings['logLevel'] {
    return this.env.LOG_LEVEL;
  }

  get aiProvider(): AiProviderName {
    return this.env.AI_PROVIDER;
  }

  get geminiApiKey(): string | undefined {
    return this.env.GEMINI_API_KEY;
  }

  get geminiModel(): string {
    return this.env.GEMINI_MODEL;
  }

  get groqApiKey(): string | undefined {
    return this.env.GROQ_API_KEY;
  }

  get groqModel(): string {
    return this.env.GROQ_MODEL;
  }

  get aiTimeoutMs(): number {
    return this.env.AI_TIMEOUT_MS;
  }

  get aiTemperature(): number {
    return this.env.AI_TEMPERATURE;
  }

  get aiMaxOutputTokens(): number {
    return this.env.AI_MAX_OUTPUT_TOKENS;
  }

  get geminiMaxOutputTokens(): number {
    return this.env.GEMINI_MAX_OUTPUT_TOKENS ?? this.env.AI_MAX_OUTPUT_TOKENS;
  }

  get groqMaxOutputTokens(): number {
    return this.env.GROQ_MAX_OUTPUT_TOKENS ?? this.env.AI_MAX_OUTPUT_TOKENS;
  }

  get aiThinkingLevel(): AiThinkingLevel {
    return this.env.AI_THINKING_LEVEL;
  }
}
