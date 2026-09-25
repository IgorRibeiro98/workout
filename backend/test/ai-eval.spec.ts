import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  CoachEvalDatasetError,
  identifiableDataFindings,
  loadCoachEvalDataset,
  type CoachEvalScenario,
} from '../src/modules/ai/eval/coach-eval.dataset';
import { criticalViolations, expectationMet } from '../src/modules/ai/eval/coach-eval.invariants';
import {
  capacityOf,
  metricsOf,
  providerLimitsFileSchema,
  renderSummary,
  summarize,
} from '../src/modules/ai/eval/coach-eval.report';
import { RatePacer, redactQuoted, runCoachEval } from '../src/modules/ai/eval/coach-eval.runner';
import { coachProviderRequest } from '../src/modules/ai/ai-coach.provider-request';
import {
  allProviderSmokeScenarios,
  providerSmokeScenario,
} from '../src/modules/ai/eval/provider-smoke.scenarios';
import { distribution } from '../src/modules/ai/eval/stats';
import { AiProviderError } from '../src/modules/ai/provider/ai-provider.gateway';
import { FakeAiProviderGateway } from './support/fake-ai-provider';

const BACKEND_ROOT = join(__dirname, '..');
const DATASET = join(BACKEND_ROOT, 'ai-eval', 'datasets', 'coach-synthetic.v1.json');

const dataset = (): CoachEvalScenario[] =>
  loadCoachEvalDataset(JSON.parse(readFileSync(DATASET, 'utf8')));

const scenario = (id: string): CoachEvalScenario => {
  const found = dataset().find((candidate) => candidate.id === id);
  if (!found) throw new Error(`cenário ${id} não existe`);
  return found;
};

/**
 * T19.H4 §30–§38 — o benchmark mede o Coach real, e o que ele produz é commitável.
 *
 * Nenhuma chamada de rede aqui: o provider é o dublê de `test/`. O que se prova é o que o
 * benchmark promete — o dataset passa pelo contrato do endpoint, o oráculo pega o que o validador
 * deixaria passar, a cadência respeita o plano gratuito e o relatório não carrega conteúdo.
 */
describe('Benchmark do Coach', () => {
  // ------------------------------------------------------------------------ dataset

  describe('dataset commitado', () => {
    it('passa inteiro pelo contrato real do POST /v1/ai/coach', () => {
      expect(() => dataset()).not.toThrow();
    });

    it('cobre as quatro famílias com os cenários mínimos do enunciado', () => {
      const ids = new Set(dataset().map((item) => item.id));
      const tags = new Set(dataset().flatMap((item) => item.tags));
      expect(dataset().length).toBeGreaterThanOrEqual(20);
      expect(dataset().length).toBeLessThanOrEqual(50);

      for (const tag of [
        'historico-escasso',
        'historico-bom',
        'carga-estavel',
        'progressao',
        'queda',
        'muitos-exercicios',
        'evidencia-limitada',
        'hipertrofia',
        'forca',
        'duracao-curta',
        'duracao-longa',
        'poucos-candidatos',
        'muitos-candidatos',
        'equipamento-restrito',
        'insufficient-candidates',
        'adjust-load',
        'adjust-reps',
        'adjust-sets',
        'adjust-rest',
        'replace-exercise',
        'no-change',
        'recommendation',
        'generation',
        'adaptation',
        'progress',
      ]) {
        expect({ tag, covered: tags.has(tag) }).toEqual({ tag, covered: true });
      }
      expect(ids.has('adapt-dados-insuficientes')).toBe(true);
    });

    it('não contém dado identificável', () => {
      const raw = JSON.parse(readFileSync(DATASET, 'utf8')) as {
        scenarios: { context: unknown }[];
      };
      for (const item of raw.scenarios) {
        expect(identifiableDataFindings(item.context)).toEqual([]);
      }
    });

    it('recusa um cenário com e-mail, UID ou campo de identidade', () => {
      const base = JSON.parse(readFileSync(DATASET, 'utf8')) as {
        version: 1;
        scenarios: Array<{ id: string; context: Record<string, unknown> }>;
      };
      const explain = base.scenarios.find((item) => item.id === 'explain-progresso')!;
      const tainted = {
        version: 1,
        scenarios: [
          {
            ...explain,
            context: {
              ...explain.context,
              subject: 'Progresso de fulano@example.com',
            },
          },
        ],
      };
      expect(() => loadCoachEvalDataset(tainted)).toThrow(/e-mail/);
      expect(identifiableDataFindings({ nome: 'x', uid: 'abc' })).toEqual([
        'context.uid: campo de identidade não pertence ao contexto do Coach',
      ]);
      expect(identifiableDataFindings({ id: 'Ab3dEf6hIj9kLm2nOp5qRs8tUv1w' })[0]).toContain(
        'Firebase UID',
      );
    });

    it('recusa um contexto que o endpoint real recusaria', () => {
      expect(() =>
        loadCoachEvalDataset({
          version: 1,
          scenarios: [
            {
              id: 'contexto-quebrado',
              requestType: 'ANALYZE_WORKOUT',
              context: { athlete: {}, extra: 1 },
            },
          ],
        }),
      ).toThrow(CoachEvalDatasetError);
    });
  });

  // ------------------------------------------------------------------------ oráculo

  describe('oráculo das invariantes críticas', () => {
    const generation = scenario('generate-hipertrofia');
    const adaptation = scenario('adapt-ajustar-carga');
    const analysis = scenario('analyze-evidencia-limitada');

    const validGeneration = () => ({
      name: 'Peito e tríceps',
      exercises: [
        {
          exerciseId: 'supino-reto-barra',
          order: 1,
          sets: 4,
          minReps: 8,
          maxReps: 12,
          restSeconds: 90,
          weightKg: 62.5,
          reason: 'Base.',
        },
      ],
      explanation: 'Composto primeiro.',
      insufficientCandidates: false,
    });

    it('uma resposta correta não tem violação', () => {
      expect(criticalViolations(generation.request, validGeneration())).toEqual([]);
    });

    it('pega exerciseId inventado, séries/reps/descanso fora dos limites e carga sem evidência', () => {
      const output = validGeneration();
      output.exercises.push({
        exerciseId: 'levantamento-terra-barra',
        order: 2,
        sets: 11,
        minReps: 12,
        maxReps: 8,
        restSeconds: 900,
        weightKg: 140,
        reason: 'Pedido nas notas.',
      });
      output.exercises.push({
        exerciseId: 'supino-inclinado-halteres',
        order: 3,
        sets: 3,
        minReps: 8,
        maxReps: 12,
        restSeconds: 60,
        weightKg: 24,
        reason: 'Sem carga registrada.',
      });
      expect(criticalViolations(generation.request, output)).toEqual([
        'INVENTED_EXERCISE_ID',
        'SETS_OUT_OF_BOUNDS',
        'REPS_OUT_OF_BOUNDS',
        'REST_OUT_OF_BOUNDS',
        'LOAD_WITHOUT_EVIDENCE',
      ]);
    });

    it('pega substituto fora dos candidatos e dataQuality inflado', () => {
      const output = {
        summary: 'Trocar.',
        changes: [
          {
            type: 'REPLACE_EXERCISE',
            exerciseId: 'crossover-cabo',
            replacementExerciseId: 'voador-peitoral-inventado',
            reason: 'x',
            evidence: 'y',
            confidence: 0.5,
          },
        ],
        dataQuality: { level: 'GOOD', description: 'x' },
      };
      expect(criticalViolations(adaptation.request, output)).toEqual([
        'REPLACEMENT_OUTSIDE_CANDIDATES',
      ]);

      const inflated = {
        summary: 'x',
        positiveSignals: [],
        attentionPoints: [],
        recommendations: [],
        dataQuality: { level: 'GOOD', description: 'x' },
      };
      // O teto deste cenário é LIMITED (duas sessões).
      expect(criticalViolations(analysis.request, inflated)).toEqual(['DATA_QUALITY_INFLATED']);
    });

    it('ADJUST_LOAD num exercício sem nenhuma execução é carga sem evidência', () => {
      const request = scenario('adapt-dados-insuficientes').request;
      const output = {
        summary: 'x',
        changes: [
          {
            type: 'ADJUST_LOAD',
            exerciseId: 'agachamento-livre',
            currentWeightKg: 60,
            suggestedWeightKg: 65,
            reason: 'x',
            evidence: 'texto não é dado',
            confidence: 0.5,
          },
        ],
        dataQuality: { level: 'INSUFFICIENT', description: 'x' },
      };
      expect(criticalViolations(request, output)).toContain('LOAD_WITHOUT_EVIDENCE');
    });

    it('a aderência compara com a expectativa do cenário, sem virar violação', () => {
      const insufficient = scenario('generate-candidatos-insuficientes');
      expect(
        expectationMet(insufficient.expect, { insufficientCandidates: true, exercises: [] }),
      ).toBe(true);
      expect(
        expectationMet(scenario('adapt-sem-mudanca').expect, {
          changes: [{ type: 'ADJUST_LOAD' }],
        }),
      ).toBe(false);
      expect(expectationMet(undefined, {})).toBeUndefined();
    });
  });

  // ------------------------------------------------------------------------ executor

  describe('executor', () => {
    it('usa o prompt e o schema de produção, uma chamada por cenário, e mede cada etapa', async () => {
      const provider = FakeAiProviderGateway.respondingWith({
        title: 'Progresso',
        explanation: 'Os números vêm prontos do app.',
        limitations: [],
        referencedExerciseIds: [],
      });
      const scenarios = [scenario('explain-progresso'), scenario('generate-hipertrofia')];

      const run = await runCoachEval({ gateway: provider, scenarios });

      expect(provider.callCount).toBe(2);
      expect(provider.calls[0].systemInstruction).toContain('Você é o Coach do Spark');
      expect(provider.calls[0].userPrompt).toContain('Contexto (JSON):');
      expect(run.outcomes.map((outcome) => outcome.status)).toEqual(['ACCEPTED', 'REJECTED']);
      expect(run.outcomes[1].rejectionStage).toBe('SEMANTIC');
      expect(run.outcomes[0].usage?.totalTokens).toBe(150);
    });

    it('limite diário do provider encerra a execução; os demais erros são dado e ela segue', async () => {
      let calls = 0;
      const provider = new FakeAiProviderGateway(() => {
        calls += 1;
        if (calls === 1) throw new AiProviderError('UNAVAILABLE', 'falha', { status: 503 });
        throw new AiProviderError('RATE_LIMITED', 'limite', { status: 429, limit: 'TPD' });
      });
      const scenarios = dataset().slice(0, 5);

      const run = await runCoachEval({ gateway: provider, scenarios });

      expect(run.outcomes).toHaveLength(2);
      expect(run.abortedReason).toContain('TPD');
      expect(run.outcomes[0]).toMatchObject({
        status: 'PROVIDER_ERROR',
        failureKind: 'UNAVAILABLE',
      });
    });

    it('429 de minuto (TPM) espera o retry-after e repete o cenário; o diário nunca é repetido', async () => {
      const explanation = {
        title: 'Progresso',
        explanation: 'Os números vêm prontos do app.',
        limitations: [],
        referencedExerciseIds: [],
      };
      let calls = 0;
      const provider = new FakeAiProviderGateway(() => {
        calls += 1;
        if (calls === 1) {
          throw new AiProviderError('RATE_LIMITED', 'limite', {
            status: 429,
            limit: 'TPM',
            retryAfterSeconds: 7,
          });
        }
        return {
          text: JSON.stringify(explanation),
          provider: 'fake',
          model: 'fake-model',
          usage: { promptTokens: 10, outputTokens: 5, totalTokens: 15 },
        };
      });
      const sleeps: number[] = [];
      const sleep = (ms: number) => {
        sleeps.push(ms);
        return Promise.resolve();
      };

      const run = await runCoachEval({
        gateway: provider,
        scenarios: [scenario('explain-progresso')],
        minuteLimitRetries: 2,
        sleep,
      });

      expect(run.outcomes[0]).toMatchObject({ status: 'ACCEPTED', pacingRetries: 1 });
      expect(provider.callCount).toBe(2);
      expect(sleeps).toEqual([8000]);
      expect(metricsOf(run.outcomes)).toMatchObject({ providerErrors: 0, pacingRetries: 1 });

      // Sem repetição configurada, o 429 é registrado como erro e a execução segue.
      calls = 0;
      const once = await runCoachEval({
        gateway: provider,
        scenarios: [scenario('explain-progresso')],
        sleep,
      });
      expect(once.outcomes[0]).toMatchObject({ status: 'PROVIDER_ERROR', limit: 'TPM' });

      // 429 sem limite declarado conta como de minuto (espera o default e repete).
      calls = 0;
      const unknownLimit = new FakeAiProviderGateway(() => {
        calls += 1;
        if (calls === 1) throw new AiProviderError('RATE_LIMITED', 'limite', { status: 429 });
        return {
          text: JSON.stringify(explanation),
          provider: 'fake',
          model: 'fake-model',
        };
      });
      const recovered = await runCoachEval({
        gateway: unknownLimit,
        scenarios: [scenario('explain-progresso')],
        minuteLimitRetries: 1,
        sleep,
      });
      expect(recovered.outcomes[0]).toMatchObject({ status: 'ACCEPTED', pacingRetries: 1 });

      // "Request too large": a requisição sozinha passa do limite — esperar não resolve.
      const tooLarge = FakeAiProviderGateway.failingWith(
        new AiProviderError('RATE_LIMITED', 'limite', {
          status: 429,
          limit: 'OTPM',
          requestTooLarge: true,
        }),
      );
      const never = await runCoachEval({
        gateway: tooLarge,
        scenarios: [scenario('explain-progresso')],
        minuteLimitRetries: 3,
        sleep,
      });
      expect(tooLarge.callCount).toBe(1);
      expect(never.outcomes[0]).toMatchObject({
        status: 'PROVIDER_ERROR',
        limit: 'OTPM',
        requestTooLarge: true,
      });

      // Limite diário: nunca repetido, encerra.
      const daily = FakeAiProviderGateway.failingWith(
        new AiProviderError('RATE_LIMITED', 'limite', { status: 429, limit: 'TPD' }),
      );
      const aborted = await runCoachEval({
        gateway: daily,
        scenarios: dataset().slice(0, 3),
        minuteLimitRetries: 3,
        sleep,
      });
      expect(daily.callCount).toBe(1);
      expect(aborted.abortedReason).toContain('TPD');
    });

    it('a regra de recusa sai sem o trecho que o modelo escreveu', () => {
      expect(redactQuoted("exerciseId fora dos candidatos em [0]: 'nome-real-do-usuario'")).toBe(
        "exerciseId fora dos candidatos em [0]: '…'",
      );
    });

    it('a cadência respeita RPM e TPM numa janela de 60 s', async () => {
      let now = 0;
      const sleeps: number[] = [];
      const pacer = new RatePacer(
        { rpm: 2, tpm: 5000 },
        {
          now: () => now,
          sleep: (ms) => {
            sleeps.push(ms);
            now += ms;
            return Promise.resolve();
          },
        },
      );

      (await pacer.acquire(3000)).settle(3000);
      // Cabe em RPM, mas não em TPM (3000 + 3000 > 5000): espera a primeira sair da janela.
      (await pacer.acquire(3000)).settle(2800);
      expect(now).toBeGreaterThanOrEqual(60_000);
      // Uma chamada maior que o orçamento inteiro não trava para sempre com a janela vazia.
      now += 120_000;
      await pacer.acquire(9000);
      expect(sleeps.length).toBeGreaterThan(0);
    });
  });

  // ------------------------------------------------------------------------ relatório

  describe('relatório', () => {
    it('desclassifica com qualquer violação crítica e não carrega prompt nem resposta', async () => {
      const secret = 'TEXTO-QUE-O-MODELO-ESCREVEU';
      const provider = FakeAiProviderGateway.respondingWith({
        name: secret,
        exercises: [
          {
            exerciseId: 'supino-reto-barra',
            order: 1,
            sets: 4,
            minReps: 8,
            maxReps: 12,
            restSeconds: 90,
            weightKg: 62.5,
            reason: secret,
          },
        ],
        explanation: secret,
        insufficientCandidates: false,
      });
      const run = await runCoachEval({
        gateway: provider,
        scenarios: [scenario('generate-hipertrofia')],
      });
      // Força uma violação como se o validador tivesse deixado passar.
      const tampered = {
        outcomes: [{ ...run.outcomes[0], criticalViolations: ['INVENTED_EXERCISE_ID' as const] }],
      };

      const summary = summarize({
        provider: 'fake',
        model: 'fake-model',
        run: tampered,
        startedAt: new Date(0),
        finishedAt: new Date(1000),
        datasetFingerprint: 'abc',
        settings: { temperature: 0.2 },
        sparkGlobalQuota: 500,
      });

      expect(summary.verdict).toBe('DISQUALIFIED');
      const serialized = JSON.stringify(summary) + renderSummary(summary);
      expect(serialized).not.toContain(secret);
      expect(serialized).not.toContain('Você é o Coach do Spark');
      expect(serialized).not.toContain('Supino reto com barra');
    });

    it('capacidade: 80% do TPD sobre o p95, RPD como teto, TPM por chamada e comparação com a quota do Spark', () => {
      const limits = providerLimitsFileSchema.parse(
        JSON.parse(readFileSync(join(BACKEND_ROOT, 'ai-eval', 'provider-limits.json'), 'utf8')),
      )['groq:openai/gpt-oss-120b'];
      const metrics = {
        ...metricsOf([]),
        totalTokens: distribution([2000, 3000, 4000]),
      };

      const capacity = capacityOf(metrics, limits, 500, 30);

      expect(capacity).toMatchObject({
        p95TotalPerCall: 4000,
        tpdCeiling: 50,
        safeByTokens: 40,
        safeDaily: 40,
        p95FitsTpm: true,
        callsPerMinuteAtP95: 2,
        verdict: 'MARGINAL',
      });
      expect(capacityOf(metrics, limits, 40)?.verdict).toBe('SUFFICIENT');
      expect(capacityOf(metrics, limits, 500, 41)?.verdict).toBe('INSUFFICIENT');
      const huge = { ...metrics, totalTokens: distribution([9000]) };
      expect(capacityOf(huge, limits, 10)?.verdict).toBe('INSUFFICIENT');
    });

    it('os limites commitados carregam data e fonte da verificação', () => {
      const limits = providerLimitsFileSchema.parse(
        JSON.parse(readFileSync(join(BACKEND_ROOT, 'ai-eval', 'provider-limits.json'), 'utf8')),
      );
      for (const entry of Object.values(limits)) {
        expect(entry.verifiedAt).toMatch(/^\d{4}-\d{2}-\d{2}$/);
        expect(entry.source.length).toBeGreaterThan(0);
      }
    });
  });

  // ------------------------------------------------------------------------ smoke

  it('os cenários do smoke passam pelo contrato real, um por operação', () => {
    expect(allProviderSmokeScenarios().map((item) => item.requestType)).toEqual([
      'ANALYZE_WORKOUT',
      'GENERATE_WORKOUT',
      'ADAPT_WORKOUT',
      'EXPLAIN_PROGRESS',
    ]);
    expect(providerSmokeScenario('adapt').requestType).toBe('ADAPT_WORKOUT');
  });

  it('o smoke default (ANALYZE) exercita todas as construções de schema que o Coach usa', () => {
    const schema = JSON.stringify(
      coachProviderRequest(providerSmokeScenario('analyze').request, 'smoke').responseSchema,
    );
    // Se o provider recusar qualquer uma, recusa o schema inteiro — e a chamada única do deploy
    // precisa ver isso antes do tráfego.
    for (const construct of ['"maxItems"', '"nullable":true', '"enum"', '"minimum"', '"maximum"']) {
      expect({ construct, present: schema.includes(construct) }).toEqual({
        construct,
        present: true,
      });
    }
  });
});
