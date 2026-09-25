import type { z } from 'zod';
import {
  rawAdaptationOutputSchema,
  rawAnalysisOutputSchema,
  rawExplanationOutputSchema,
  rawGenerationOutputSchema,
} from './ai-coach.output.schema';
import type { AiCoachRequestBody } from './ai-coach.request.schema';
import {
  validateAdaptation,
  validateAnalysis,
  validateExplanation,
  validateGeneration,
  type AiCoachValidation,
} from './ai-coach.validator';

/**
 * O caminho **único** do texto do modelo até uma resposta aceita (T19.H4 §22/§31).
 *
 * `JSON.parse` → releitura estrutural (`zod`) → validação semântica contra o contexto recebido.
 * O `AiCoachService` o usa em toda chamada real; o benchmark (`ai:benchmark`) o usa em cada
 * cenário — e é por isso que ele existe separado do serviço: o benchmark mede **este** validador,
 * e não uma cópia que poderia divergir dele.
 *
 * Não loga, não lança e não conhece provider: devolve o veredito e em que etapa ele caiu.
 * Structured output (strict ou não, de qualquer provider) garante forma, e só forma — as três
 * etapas rodam sempre.
 */
export type CoachOutputVerdict =
  | { readonly ok: true; readonly result: unknown }
  | {
      readonly ok: false;
      readonly stage: CoachOutputRejectionStage;
      /**
       * Razão técnica (campo + regra). Na etapa `SEMANTIC` pode conter um fragmento escrito pelo
       * modelo — o `exerciseId` inventado —, e quem a loga passa por `safeReason`.
       */
      readonly reason: string;
    };

/** Onde a resposta caiu: não era JSON, não tinha a forma do contrato, ou violou uma regra. */
export type CoachOutputRejectionStage = 'JSON' | 'STRUCTURE' | 'SEMANTIC';

export function validateCoachOutput(request: AiCoachRequestBody, text: string): CoachOutputVerdict {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { ok: false, stage: 'JSON', reason: 'resposta não é JSON' };
  }

  switch (request.requestType) {
    case 'ANALYZE_WORKOUT':
      return check(rawAnalysisOutputSchema, parsed, (output) =>
        validateAnalysis(request.context, output),
      );
    case 'GENERATE_WORKOUT':
      return check(rawGenerationOutputSchema, parsed, (output) =>
        validateGeneration(request.context, output),
      );
    case 'ADAPT_WORKOUT':
      return check(rawAdaptationOutputSchema, parsed, (output) =>
        validateAdaptation(request.context, output),
      );
    default:
      return check(rawExplanationOutputSchema, parsed, (output) =>
        validateExplanation(request.context, output),
      );
  }
}

function check<TSchema extends z.ZodType>(
  schema: TSchema,
  parsed: unknown,
  semantic: (output: z.infer<TSchema>) => AiCoachValidation,
): CoachOutputVerdict {
  const output = schema.safeParse(parsed);
  if (!output.success) {
    // Só caminho e código do `zod` — nunca o valor recebido.
    const issue = output.error.issues[0];
    const where = issue ? issue.path.join('.') || '(raiz)' : '(raiz)';
    return { ok: false, stage: 'STRUCTURE', reason: `${where}: ${issue?.code ?? 'invalid'}` };
  }
  const validation = semantic(output.data);
  if (!validation.ok) {
    return { ok: false, stage: 'SEMANTIC', reason: validation.reason };
  }
  return { ok: true, result: output.data };
}
