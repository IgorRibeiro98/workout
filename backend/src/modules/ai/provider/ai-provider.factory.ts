import { SparkLogger } from '../../../common/logger';
import type { AiProviderSettings } from '../../../config/ai-provider-settings';
import type { AiProviderGateway } from './ai-provider.gateway';

/**
 * O **único** ponto do processo que escolhe entre Gemini e Groq (T19.H4 §15/§16).
 *
 * O módulo Nest (`AiModule`) e as ferramentas do Coach (`ai:benchmark`, `ai:provider-smoke`)
 * passam por aqui. Acima desta função existe só `AiProviderGateway`: nenhum serviço, controller,
 * validador, registry de prompt ou quota pergunta qual provider está ativo — e o teste estrutural
 * (`test/ai-provider-structure.spec.ts`) garante que continue assim.
 *
 * Não existe fallback: uma falha do provider selecionado é uma falha, e o outro provider não é
 * chamado (§23). Trocar de provider é configuração (`AI_PROVIDER`) + deploy.
 *
 * Os gateways são carregados por import dinâmico, como em `object-storage.factory.ts`: com
 * `AI_PROVIDER=gemini` o `groq-sdk` nem entra no grafo de módulos do processo, e vice-versa.
 */
export async function createAiProviderGateway(
  settings: AiProviderSettings,
  logger: SparkLogger,
): Promise<AiProviderGateway> {
  switch (settings.aiProvider) {
    case 'gemini': {
      const { GeminiAiProviderGateway } = await import('./gemini-ai-provider.gateway');
      return new GeminiAiProviderGateway(settings, logger);
    }
    case 'groq': {
      const { GroqAiProviderGateway } = await import('./groq-ai-provider.gateway');
      return new GroqAiProviderGateway(settings, logger);
    }
  }
}
