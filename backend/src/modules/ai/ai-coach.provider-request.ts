import { AiCoachPromptRegistry } from './ai-coach-prompt.registry';
import type { AiCoachRequestBody } from './ai-coach.request.schema';
import type { AiProviderRequest } from './provider/ai-provider.gateway';

/**
 * O que o provider recebe para um pedido do Coach — montado num lugar só (T19.H4 §31).
 *
 * `AiCoachService` usa esta função em toda chamada real, e o benchmark (`ai:benchmark`) em cada
 * cenário: instrução de sistema, prompt do usuário e schema de saída são, byte a byte, os mesmos
 * nos dois caminhos. É isso que faz o benchmark medir o Coach do Spark, e não um exemplo
 * artificial montado ao lado dele.
 */
export function coachProviderRequest(
  request: AiCoachRequestBody,
  requestId: string,
): AiProviderRequest {
  const entry = AiCoachPromptRegistry.entryFor(request.requestType);
  return {
    requestId,
    systemInstruction: entry.systemInstruction,
    userPrompt: AiCoachPromptRegistry.userPrompt({
      type: request.requestType,
      requestId,
      schemaVersion: request.schemaVersion,
      context: request.context,
    }),
    responseSchema: entry.responseSchema,
  };
}
