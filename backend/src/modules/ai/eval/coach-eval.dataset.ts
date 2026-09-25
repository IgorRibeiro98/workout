import { z } from 'zod';
import { AI_COACH_REQUEST_TYPES, AI_SCHEMA_VERSION } from '../ai-coach.contract';
import { ADAPTATION_TYPE_NAMES } from '../ai-coach.output.schema';
import { aiCoachRequestSchema, type AiCoachRequestBody } from '../ai-coach.request.schema';

/**
 * O dataset do benchmark do Coach (T19.H4 §32–§35).
 *
 * Cada cenário é um **contexto** — exatamente o que o Android mandaria em `context` — e o tipo de
 * request. O corpo completo é montado aqui e passa pelo `aiCoachRequestSchema`, o mesmo contrato
 * que o `POST /v1/ai/coach` aplica: um cenário que o endpoint real recusaria não entra no
 * benchmark, e o prompt de cada cenário é o que o `AiCoachPromptRegistry` montaria em produção.
 *
 * `expect` é opcional e mede **aderência** ao pedido (o treino tinha candidatos suficientes? havia
 * algo a ajustar?). Não é validação: uma resposta pode ser válida e não atender a expectativa, e o
 * relatório mostra isso como qualidade, não como violação.
 *
 * A versão commitada é sintética. Um dataset privado (contextos reais anonimizados) vive fora do
 * Git — `backend/.private/ai-eval/` — e passa pela mesma trava de dado identificável.
 */

const scenarioIdSchema = z
  .string()
  .regex(/^[a-z0-9][a-z0-9-]{2,63}$/, 'id de cenário: [a-z0-9-], 3 a 64 caracteres');

const expectationSchema = z
  .object({
    /** GENERATE_WORKOUT: os candidatos sustentam (false) ou não (true) o pedido. */
    insufficientCandidates: z.boolean().optional(),
    /** ADAPT_WORKOUT: deveria propor alguma mudança, ou nenhuma. */
    changes: z.enum(['none', 'some']).optional(),
    /** ADAPT_WORKOUT: ao menos uma mudança de um destes tipos. */
    changeTypesAnyOf: z.array(z.enum(ADAPTATION_TYPE_NAMES as [string, ...string[]])).optional(),
  })
  .strict();

const scenarioSchema = z
  .object({
    id: scenarioIdSchema,
    requestType: z.enum(AI_COACH_REQUEST_TYPES),
    tags: z.array(z.string().regex(/^[a-z0-9-]{1,40}$/)).default([]),
    description: z.string().max(200).optional(),
    expect: expectationSchema.optional(),
    context: z.unknown(),
  })
  .strict();

const datasetSchema = z
  .object({
    version: z.literal(1),
    description: z.string().max(400).optional(),
    scenarios: z.array(scenarioSchema).min(1).max(200),
  })
  .strict();

export type CoachEvalExpectation = z.infer<typeof expectationSchema>;

export interface CoachEvalScenario {
  readonly id: string;
  readonly requestType: AiCoachRequestBody['requestType'];
  readonly tags: readonly string[];
  readonly description?: string;
  readonly expect?: CoachEvalExpectation;
  /** O corpo que o Android enviaria — já passado pelo contrato real do endpoint. */
  readonly request: AiCoachRequestBody;
}

export class CoachEvalDatasetError extends Error {
  constructor(readonly issues: string[]) {
    super(`dataset inválido:\n${issues.map((issue) => `  - ${issue}`).join('\n')}`);
    this.name = 'CoachEvalDatasetError';
  }
}

export function loadCoachEvalDataset(raw: unknown): CoachEvalScenario[] {
  const parsed = datasetSchema.safeParse(raw);
  if (!parsed.success) {
    throw new CoachEvalDatasetError(
      parsed.error.issues.map((issue) => `${issue.path.join('.') || '(raiz)'}: ${issue.message}`),
    );
  }

  const issues: string[] = [];
  const seen = new Set<string>();
  const scenarios: CoachEvalScenario[] = [];

  for (const scenario of parsed.data.scenarios) {
    if (seen.has(scenario.id)) {
      issues.push(`${scenario.id}: id repetido`);
      continue;
    }
    seen.add(scenario.id);

    const body = aiCoachRequestSchema.safeParse({
      clientRequestId: `bench-${scenario.id}`.slice(0, 128),
      requestType: scenario.requestType,
      schemaVersion: AI_SCHEMA_VERSION,
      context: scenario.context,
    });
    if (!body.success) {
      const issue = body.error.issues[0];
      issues.push(
        `${scenario.id}: o endpoint recusaria este contexto (${issue?.path.join('.') ?? ''}: ${issue?.message ?? 'inválido'})`,
      );
      continue;
    }

    for (const finding of identifiableDataFindings(scenario.context)) {
      issues.push(`${scenario.id}: ${finding}`);
    }

    scenarios.push({
      id: scenario.id,
      requestType: scenario.requestType,
      tags: scenario.tags,
      description: scenario.description,
      expect: scenario.expect,
      request: body.data,
    });
  }

  if (issues.length > 0) {
    throw new CoachEvalDatasetError(issues);
  }
  return scenarios;
}

/**
 * Trava contra dado identificável num contexto de benchmark (§34/§48).
 *
 * Não é anonimização — é o alarme de que ela não foi feita: e-mail, algo com a forma de um
 * Firebase UID (28 caracteres alfanuméricos) ou de telefone não pertencem a um dataset de
 * avaliação, sintético ou anonimizado. Um cenário com qualquer um deles é recusado inteiro.
 */
export function identifiableDataFindings(value: unknown, path = 'context'): string[] {
  if (typeof value === 'string') {
    const findings: string[] = [];
    if (EMAIL.test(value)) findings.push(`${path}: parece um e-mail`);
    if (FIREBASE_UID.test(value)) findings.push(`${path}: parece um Firebase UID`);
    if (PHONE.test(value)) findings.push(`${path}: parece um telefone`);
    return findings;
  }
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => identifiableDataFindings(item, `${path}[${index}]`));
  }
  if (typeof value === 'object' && value !== null) {
    return Object.entries(value).flatMap(([key, item]) => {
      const findings = identifiableDataFindings(item, `${path}.${key}`);
      if (IDENTITY_KEYS.has(key.toLowerCase())) {
        findings.push(`${path}.${key}: campo de identidade não pertence ao contexto do Coach`);
      }
      return findings;
    });
  }
  return [];
}

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/;
const FIREBASE_UID =
  /(^|[^A-Za-z0-9])(?=[A-Za-z0-9]*[A-Z])(?=[A-Za-z0-9]*\d)[A-Za-z0-9]{28}([^A-Za-z0-9]|$)/;
const PHONE = /\+?\d[\d\s().-]{9,}\d/;
const IDENTITY_KEYS = new Set(['uid', 'email', 'socialid', 'friendcode', 'displayname', 'phone']);
