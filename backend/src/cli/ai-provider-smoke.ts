import 'reflect-metadata';
import { SparkLogger } from '../common/logger';
import {
  AiProviderConfig,
  AiProviderConfigError,
  API_KEY_ENV_BY_PROVIDER,
  selectedProviderApiKey,
  selectedProviderModel,
} from '../config/ai-provider-settings';
import { type CoachEvalScenario } from '../modules/ai/eval/coach-eval.dataset';
import { runCoachEval, type ScenarioOutcome } from '../modules/ai/eval/coach-eval.runner';
import {
  allProviderSmokeScenarios,
  PROVIDER_SMOKE_TYPES,
  providerSmokeScenario,
  type ProviderSmokeType,
} from '../modules/ai/eval/provider-smoke.scenarios';
import { formatNumber } from '../modules/ai/eval/stats';
import { createAiProviderGateway } from '../modules/ai/provider/ai-provider.factory';

/**
 * `ai:provider-smoke` — chamadas pequenas e seguras ao provider **selecionado** (T19.H4 §71).
 *
 * ```bash
 * AI_PROVIDER=groq GROQ_API_KEY=… npm run ai:provider-smoke                 # ANALYZE (uma chamada)
 * AI_PROVIDER=groq GROQ_API_KEY=… npm run ai:provider-smoke -- --type all   # as quatro operações
 * AI_PROVIDER=gemini GEMINI_API_KEY=… npm run ai:provider-smoke -- --type explain
 * ```
 *
 * O `/health/ready` do backend não prova que a chave externa existe, vale, tem quota e aceita o
 * schema do Coach — este comando prova. Ele passa pelo caminho de produção (factory → gateway →
 * `validateCoachOutput`) com contextos sintéticos fixos, e imprime só metadata: provider, modelo,
 * structured output PASS/FAIL, validação semântica, latência e tokens. Nem prompt nem resposta.
 *
 * O default (`analyze`) é uma chamada que exercita todas as construções de schema do Coach — é o
 * que o Job do deploy roda antes do tráfego. `all` é o smoke completo de uma troca de provider.
 *
 * Código de saída: `0` tudo aceito; `3` o provider respondeu no formato do contrato, mas o validador
 * semântico recusou alguma resposta (o provider está de pé — é qualidade do modelo, não
 * infraestrutura); `1` qualquer outra falha (credencial, quota, schema recusado, timeout, JSON
 * inválido); `2` uso.
 *
 * `AI_PROVIDER_SMOKE_GATE=provider` (o Job do deploy, `ops/gcp/deploy-cloud-run.sh`) troca o `3`
 * por `0`: o que o deploy precisa provar antes do tráfego é que o provider selecionado responde no
 * formato do contrato com a chave e a quota de produção. A recusa semântica continua impressa (WARN).
 */
export async function runAiProviderSmoke(argv: readonly string[]): Promise<number> {
  const gate = process.env.AI_PROVIDER_SMOKE_GATE?.trim() || 'coach';
  if (gate !== 'coach' && gate !== 'provider') {
    process.stderr.write('AI_PROVIDER_SMOKE_GATE precisa ser coach ou provider\n');
    return 2;
  }
  let type: ProviderSmokeType | 'all' = 'analyze';
  for (let index = 0; index < argv.length; index += 1) {
    if (argv[index] === '--type') {
      const value = argv[++index];
      if (value !== 'all' && !PROVIDER_SMOKE_TYPES.includes(value as ProviderSmokeType)) {
        process.stderr.write(`--type precisa ser ${[...PROVIDER_SMOKE_TYPES, 'all'].join(', ')}\n`);
        return 2;
      }
      type = value as ProviderSmokeType | 'all';
    } else {
      process.stderr.write(`argumento desconhecido: ${argv[index]}\n`);
      return 2;
    }
  }

  let settings: AiProviderConfig;
  try {
    settings = AiProviderConfig.fromEnv({
      ...process.env,
      LOG_LEVEL: process.env.LOG_LEVEL ?? 'warn',
    });
  } catch (error) {
    if (error instanceof AiProviderConfigError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const provider = settings.aiProvider;
  const model = selectedProviderModel(settings);
  if (!selectedProviderApiKey(settings)) {
    process.stdout.write(
      `AI_PROVIDER_SMOKE FAIL provider=${provider} model=${model} reason=${API_KEY_ENV_BY_PROVIDER[provider]} ausente\n`,
    );
    return 1;
  }

  const scenarios: CoachEvalScenario[] =
    type === 'all' ? allProviderSmokeScenarios() : [providerSmokeScenario(type)];
  const gateway = await createAiProviderGateway(settings, new SparkLogger(settings));
  // Um 429 de **minuto** (outro usuário usou o TPM do plano naquele minuto) não é defeito do
  // provider: o smoke espera o retry-after e tenta de novo, no máximo duas vezes. Limite diário,
  // credencial, schema e timeout continuam falhando na primeira.
  const run = await runCoachEval({ gateway, scenarios, minuteLimitRetries: 2 });

  const verdicts = run.outcomes.map((outcome) => {
    const verdict = verdictOf(outcome);
    process.stdout.write(`${line(verdict, provider, model, outcome)}\n`);
    return verdict;
  });
  if (run.abortedReason) {
    process.stdout.write(
      `AI_PROVIDER_SMOKE FAIL provider=${provider} reason="${run.abortedReason}"\n`,
    );
    return 1;
  }

  if (verdicts.every((verdict) => verdict === 'PASS')) return 0;
  if (verdicts.every((verdict) => verdict !== 'FAIL')) return gate === 'provider' ? 0 : 3;
  return 1;
}

type SmokeVerdict = 'PASS' | 'WARN' | 'FAIL';

/** PASS aceito; WARN formato do contrato certo mas recusa semântica; FAIL o resto. */
function verdictOf(outcome: ScenarioOutcome): SmokeVerdict {
  if (outcome.status === 'ACCEPTED') return 'PASS';
  return outcome.rejectionStage === 'SEMANTIC' ? 'WARN' : 'FAIL';
}

function line(
  verdict: SmokeVerdict,
  provider: string,
  model: string,
  outcome: ScenarioOutcome,
): string {
  const structured = verdict === 'FAIL' ? 'FAIL' : 'PASS';
  const semantic =
    outcome.status === 'ACCEPTED' ? 'PASS' : outcome.status === 'REJECTED' ? 'FAIL' : 'N/A';
  const failure =
    outcome.status === 'PROVIDER_ERROR'
      ? ` failureKind=${outcome.failureKind}${outcome.providerStatus ? ` providerStatus=${outcome.providerStatus}` : ''}${outcome.limit ? ` limit=${outcome.limit}` : ''}${outcome.finishReason ? ` finishReason=${outcome.finishReason}` : ''}`
      : outcome.status === 'REJECTED'
        ? ` rejectionStage=${outcome.rejectionStage} rule="${outcome.rejectionRule ?? ''}"`
        : '';
  return (
    `AI_PROVIDER_SMOKE ${verdict} provider=${provider} model=${model} answeredBy=${outcome.model ?? '—'} ` +
    `type=${outcome.requestType} structuredOutput=${structured} semantic=${semantic} ` +
    `latencyMs=${formatNumber(outcome.latencyMs)} promptTokens=${formatNumber(outcome.usage?.promptTokens)} ` +
    `outputTokens=${formatNumber(outcome.usage?.outputTokens)} totalTokens=${formatNumber(outcome.usage?.totalTokens)}` +
    failure
  );
}

if (require.main === module) {
  runAiProviderSmoke(process.argv.slice(2))
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `AI_PROVIDER_SMOKE FAIL ${error instanceof Error ? error.name : 'erro'}\n`,
      );
      process.exitCode = 1;
    });
}
