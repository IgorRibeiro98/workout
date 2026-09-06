import { z } from 'zod';
import { RESPONSE_LIMITS } from './ai-coach.limits';
import type { AiCoachRequestType } from './ai-coach.contract';

/**
 * O contrato de **saída** do modelo, nos dois formatos em que ele precisa existir:
 *
 * - `responseSchemaFor` — o structured output pedido ao Gemini. Ele garante a **forma**;
 * - `outputSchemaFor` — o `zod` que confere a forma de novo ao ler a resposta. Structured output
 *   é promessa do provider, não prova: um JSON que não bate com o contrato vira
 *   `INVALID_AI_RESPONSE` em vez de virar objeto meio preenchido.
 *
 * Nenhum dos dois substitui a validação semântica (`ai-coach.validator.ts`), que é onde
 * `exerciseId` inventado, carga fora dos candidatos e `dataQuality` inflado morrem.
 *
 * Os campos são exatamente os que o Android desserializa hoje (`AiCoachResponse`,
 * `AiGeneratedWorkoutResponse`, `AiWorkoutAdaptationResponse`, `AiCoachExplanationResponse`): a
 * migração troca o transporte, não o formato da conversa.
 */

/**
 * O schema de saída, no vocabulário do **Spark** e não no do SDK.
 *
 * É um subconjunto do OpenAPI que a Gemini API entende, escrito aqui para que a fronteira do
 * provider (`AiProviderGateway`) não dependa de tipo nenhum do `@google/genai`. Só o gateway do
 * Gemini conhece o SDK; trocar de provider é reescrever aquele arquivo, não este.
 */
export interface AiOutputSchema {
  type: 'OBJECT' | 'ARRAY' | 'STRING' | 'NUMBER' | 'INTEGER' | 'BOOLEAN';
  description?: string;
  nullable?: boolean;
  enum?: string[];
  properties?: Record<string, AiOutputSchema>;
  required?: string[];
  items?: AiOutputSchema;
  maxItems?: string;
  minimum?: number;
  maximum?: number;
}

const R = RESPONSE_LIMITS;

const RECOMMENDATION_TYPES = [
  'GENERAL',
  'KEEP_CURRENT_PLAN',
  'REVIEW_LOAD',
  'REVIEW_REPS',
  'REVIEW_VOLUME',
  'REVIEW_EXERCISE',
] as const;

const ADAPTATION_TYPES = [
  'ADJUST_LOAD',
  'ADJUST_REPS',
  'ADJUST_SETS',
  'ADJUST_REST',
  'REPLACE_EXERCISE',
] as const;

const DATA_QUALITY_LEVELS = ['INSUFFICIENT', 'LIMITED', 'GOOD'] as const;

export const RECOMMENDATION_TYPE_NAMES: readonly string[] = RECOMMENDATION_TYPES;
export const ADAPTATION_TYPE_NAMES: readonly string[] = ADAPTATION_TYPES;
export const DATA_QUALITY_LEVEL_NAMES: readonly string[] = DATA_QUALITY_LEVELS;

// ---------------------------------------------------------------------------------------------
// Structured output pedido ao provider
// ---------------------------------------------------------------------------------------------

function observationsSchema(description: string): AiOutputSchema {
  return {
    type: 'ARRAY',
    description,
    maxItems: String(R.maxObservations),
    items: {
      type: 'OBJECT',
      properties: {
        exerciseId: {
          type: 'STRING',
          nullable: true,
          description:
            'exerciseId exatamente como recebido no contexto, ou nulo quando a observação for do treino como um todo.',
        },
        title: { type: 'STRING', description: 'Rótulo curto da observação.' },
        description: {
          type: 'STRING',
          description: 'O fato observado nos dados, sem interpretação.',
        },
      },
      required: ['title', 'description'],
    },
  };
}

const dataQualitySchema: AiOutputSchema = {
  type: 'OBJECT',
  properties: {
    level: {
      type: 'STRING',
      enum: [...DATA_QUALITY_LEVELS],
      description: 'Nunca maior que evidence.maxDataQuality do contexto.',
    },
    description: { type: 'STRING', description: 'Em uma frase, no que a análise se baseou.' },
  },
  required: ['level', 'description'],
};

const analysisResponseSchema: AiOutputSchema = {
  type: 'OBJECT',
  properties: {
    summary: { type: 'STRING', description: 'Resumo curto da análise, em português do Brasil.' },
    positiveSignals: observationsSchema('O que os dados mostram de positivo. Podem ser zero.'),
    attentionPoints: observationsSchema('O que merece atenção nos dados. Podem ser zero.'),
    recommendations: {
      type: 'ARRAY',
      description: 'Sugestões do Coach. Podem ser zero.',
      maxItems: String(R.maxRecommendations),
      items: {
        type: 'OBJECT',
        properties: {
          type: {
            type: 'STRING',
            enum: [...RECOMMENDATION_TYPES],
            description: 'Tipo da recomendação.',
          },
          exerciseId: {
            type: 'STRING',
            nullable: true,
            description:
              'exerciseId exatamente como recebido no contexto, ou nulo quando a recomendação for geral.',
          },
          reason: {
            type: 'STRING',
            description: 'Justificativa curta baseada apenas nos dados fornecidos.',
          },
          confidence: {
            type: 'NUMBER',
            description: 'Confiança entre 0.0 e 1.0.',
            minimum: 0,
            maximum: 1,
          },
          evidence: {
            type: 'STRING',
            nullable: true,
            description:
              'Dado do contexto que sustenta a recomendação. Obrigatório quando houver exerciseId.',
          },
        },
        required: ['type', 'reason', 'confidence'],
      },
    },
    dataQuality: dataQualitySchema,
  },
  required: ['summary', 'positiveSignals', 'attentionPoints', 'recommendations', 'dataQuality'],
};

const generationResponseSchema: AiOutputSchema = {
  type: 'OBJECT',
  properties: {
    name: {
      type: 'STRING',
      description: `Nome curto do treino, no máximo ${R.maxWorkoutNameLength} caracteres.`,
    },
    exercises: {
      type: 'ARRAY',
      description:
        'Exercícios do treino proposto. Vazio somente quando insufficientCandidates for verdadeiro.',
      maxItems: String(R.maxGeneratedExercises),
      items: {
        type: 'OBJECT',
        properties: {
          exerciseId: {
            type: 'STRING',
            description:
              'exerciseId copiado exatamente de candidateExercises. Nenhum outro valor é aceito.',
          },
          order: {
            type: 'INTEGER',
            description:
              'Posição do exercício no treino, de 1 até a quantidade de exercícios, sem repetir e sem pular.',
          },
          sets: {
            type: 'INTEGER',
            description: `Número de séries, de ${R.minSets} a ${R.maxSets}.`,
          },
          minReps: {
            type: 'INTEGER',
            description: `Menor repetição da faixa alvo, de ${R.minReps} a ${R.maxReps}.`,
          },
          maxReps: {
            type: 'INTEGER',
            description: 'Maior repetição da faixa alvo, nunca menor que minReps.',
          },
          restSeconds: {
            type: 'INTEGER',
            description: `Descanso entre séries em segundos, de ${R.minRestSeconds} a ${R.maxRestSeconds}.`,
          },
          weightKg: {
            type: 'NUMBER',
            nullable: true,
            description:
              'Carga sugerida em kg. Só preencha quando o exercício aparecer em loadEvidence com carga registrada; caso contrário, nulo.',
          },
          reason: {
            type: 'STRING',
            description: 'Em uma frase, por que este exercício está aqui.',
          },
        },
        required: ['exerciseId', 'order', 'sets', 'minReps', 'maxReps', 'restSeconds', 'reason'],
      },
    },
    explanation: {
      type: 'STRING',
      description: 'Em poucas frases, por que o treino foi montado assim.',
    },
    insufficientCandidates: {
      type: 'BOOLEAN',
      description:
        'Verdadeiro quando os exercícios candidatos não sustentam o pedido. Nesse caso, exercises precisa estar vazio.',
    },
  },
  required: ['name', 'exercises', 'explanation', 'insufficientCandidates'],
};

const adaptationResponseSchema: AiOutputSchema = {
  type: 'OBJECT',
  properties: {
    summary: {
      type: 'STRING',
      description:
        'Resumo curto da adaptação. Explique aqui quando não houver nenhuma mudança a propor.',
    },
    changes: {
      type: 'ARRAY',
      description: 'Mudanças propostas. Pode ser vazia quando não há o que ajustar.',
      maxItems: String(R.maxAdaptationChanges),
      items: {
        type: 'OBJECT',
        properties: {
          type: {
            type: 'STRING',
            enum: [...ADAPTATION_TYPES],
            description: 'Tipo da mudança. Use somente os tipos listados em allowedChangeTypes.',
          },
          exerciseId: {
            type: 'STRING',
            description:
              'exerciseId de um exercício presente em template.exercises, copiado exatamente.',
          },
          currentWeightKg: {
            type: 'NUMBER',
            nullable: true,
            description: 'ADJUST_LOAD: carga planejada hoje, exatamente como está no contexto.',
          },
          suggestedWeightKg: {
            type: 'NUMBER',
            nullable: true,
            description: 'ADJUST_LOAD: nova carga em kg.',
          },
          currentSets: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_SETS: séries de hoje, exatamente como no contexto.',
          },
          suggestedSets: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_SETS: novo número de séries.',
          },
          currentMinReps: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_REPS: mínimo da faixa de hoje.',
          },
          currentMaxReps: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_REPS: máximo da faixa de hoje.',
          },
          suggestedMinReps: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_REPS: novo mínimo da faixa.',
          },
          suggestedMaxReps: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_REPS: novo máximo da faixa, nunca menor que o mínimo.',
          },
          currentRestSeconds: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_REST: descanso de hoje em segundos.',
          },
          suggestedRestSeconds: {
            type: 'INTEGER',
            nullable: true,
            description: 'ADJUST_REST: novo descanso em segundos.',
          },
          replacementExerciseId: {
            type: 'STRING',
            nullable: true,
            description:
              'REPLACE_EXERCISE: exerciseId do substituto, copiado de replacementCandidates.',
          },
          reason: { type: 'STRING', description: 'Em uma frase, por que esta mudança.' },
          evidence: {
            type: 'STRING',
            description: 'O dado do contexto que sustenta a mudança. Obrigatório.',
          },
          confidence: {
            type: 'NUMBER',
            description: 'Confiança entre 0.0 e 1.0.',
            minimum: 0,
            maximum: 1,
          },
        },
        required: ['type', 'exerciseId', 'reason', 'evidence', 'confidence'],
      },
    },
    dataQuality: dataQualitySchema,
  },
  required: ['summary', 'changes', 'dataQuality'],
};

const explanationResponseSchema: AiOutputSchema = {
  type: 'OBJECT',
  properties: {
    title: {
      type: 'STRING',
      description: `Título curto da explicação, no máximo ${R.maxTitleLength} caracteres.`,
    },
    explanation: {
      type: 'STRING',
      description:
        'A explicação em no máximo dois parágrafos curtos, usando somente os dados do contexto.',
    },
    limitations: {
      type: 'ARRAY',
      description:
        'Repita as limitações recebidas em knownLimitations e acrescente outras somente se o contexto as sustentar. Pode ser vazia.',
      maxItems: String(R.maxLimitations),
      items: { type: 'STRING', description: 'Uma limitação desta explicação, em uma frase.' },
    },
    referencedExerciseIds: {
      type: 'ARRAY',
      description:
        'Todo exerciseId citado na explicação. Vazio quando nenhum exercício for citado.',
      items: { type: 'STRING', description: 'exerciseId copiado exatamente do contexto.' },
    },
  },
  required: ['title', 'explanation', 'limitations', 'referencedExerciseIds'],
};

/** O structured output do tipo de request pedido. */
export function responseSchemaFor(type: AiCoachRequestType): AiOutputSchema {
  switch (type) {
    case 'ANALYZE_WORKOUT':
      return analysisResponseSchema;
    case 'GENERATE_WORKOUT':
      return generationResponseSchema;
    case 'ADAPT_WORKOUT':
      return adaptationResponseSchema;
    default:
      // Os quatro EXPLAIN_* compartilham o schema: o que muda entre eles é o contexto enviado.
      return explanationResponseSchema;
  }
}

// ---------------------------------------------------------------------------------------------
// Releitura do JSON que o provider devolveu
// ---------------------------------------------------------------------------------------------

/**
 * Sentinelas em vez de defaults silenciosos.
 *
 * Onde o Android usa `-1` para número ausente, aqui o campo é obrigatório: um treino com "3
 * séries" que na verdade veio vazio seria pior que uma resposta recusada.
 */
const rawObservation = z.object({
  exerciseId: z.string().nullish(),
  title: z.string().default(''),
  description: z.string().default(''),
});

const rawDataQuality = z
  .object({ level: z.string().default(''), description: z.string().default('') })
  .nullish();

export const rawAnalysisOutputSchema = z.object({
  summary: z.string().default(''),
  positiveSignals: z.array(rawObservation).default([]),
  attentionPoints: z.array(rawObservation).default([]),
  recommendations: z
    .array(
      z.object({
        type: z.string().default(''),
        exerciseId: z.string().nullish(),
        reason: z.string().default(''),
        confidence: z.number().default(-1),
        evidence: z.string().nullish(),
      }),
    )
    .default([]),
  dataQuality: rawDataQuality,
});

export const rawGenerationOutputSchema = z.object({
  name: z.string().default(''),
  exercises: z
    .array(
      z.object({
        exerciseId: z.string().default(''),
        order: z.number().default(-1),
        sets: z.number().default(-1),
        minReps: z.number().default(-1),
        maxReps: z.number().default(-1),
        restSeconds: z.number().default(-1),
        weightKg: z.number().nullish(),
        reason: z.string().default(''),
      }),
    )
    .default([]),
  explanation: z.string().default(''),
  insufficientCandidates: z.boolean().default(false),
});

export const rawAdaptationOutputSchema = z.object({
  summary: z.string().default(''),
  changes: z
    .array(
      z.object({
        type: z.string().default(''),
        exerciseId: z.string().default(''),
        currentWeightKg: z.number().nullish(),
        suggestedWeightKg: z.number().nullish(),
        currentSets: z.number().nullish(),
        suggestedSets: z.number().nullish(),
        currentMinReps: z.number().nullish(),
        currentMaxReps: z.number().nullish(),
        suggestedMinReps: z.number().nullish(),
        suggestedMaxReps: z.number().nullish(),
        currentRestSeconds: z.number().nullish(),
        suggestedRestSeconds: z.number().nullish(),
        replacementExerciseId: z.string().nullish(),
        reason: z.string().default(''),
        evidence: z.string().default(''),
        confidence: z.number().default(-1),
      }),
    )
    .default([]),
  dataQuality: rawDataQuality,
});

export const rawExplanationOutputSchema = z.object({
  title: z.string().default(''),
  explanation: z.string().default(''),
  limitations: z.array(z.string()).default([]),
  referencedExerciseIds: z.array(z.string()).default([]),
});

export type RawAnalysisOutput = z.infer<typeof rawAnalysisOutputSchema>;
export type RawGenerationOutput = z.infer<typeof rawGenerationOutputSchema>;
export type RawAdaptationOutput = z.infer<typeof rawAdaptationOutputSchema>;
export type RawExplanationOutput = z.infer<typeof rawExplanationOutputSchema>;
