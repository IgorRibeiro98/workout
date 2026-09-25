import 'reflect-metadata';
import { createHash, randomInt } from 'node:crypto';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, join, relative, resolve, sep } from 'node:path';
import { SparkLogger } from '../common/logger';
import {
  AiProviderConfig,
  AiProviderConfigError,
  API_KEY_ENV_BY_PROVIDER,
  MODEL_ENV_BY_PROVIDER,
  selectedProviderApiKey,
  selectedProviderMaxOutputTokens,
  selectedProviderModel,
} from '../config/ai-provider-settings';
import { coachProviderRequest } from '../modules/ai/ai-coach.provider-request';
import {
  loadCoachEvalDataset,
  type CoachEvalScenario,
} from '../modules/ai/eval/coach-eval.dataset';
import {
  familyOf,
  providerLimitsFileSchema,
  renderSummary,
  summarize,
  type ProviderLimits,
} from '../modules/ai/eval/coach-eval.report';
import {
  estimatePromptTokens,
  RatePacer,
  runCoachEval,
  type ScenarioOutcome,
} from '../modules/ai/eval/coach-eval.runner';
import { AI_PROVIDER_NAMES, type AiProviderName } from '../modules/ai/provider/ai-provider.gateway';
import { createAiProviderGateway } from '../modules/ai/provider/ai-provider.factory';

/**
 * `ai:benchmark` — o Coach real contra um provider/modelo, cenário a cenário (T19.H4 §30–§39).
 *
 * ```bash
 * # custo antes de gastar: nenhuma chamada, nenhuma chave
 * npm run ai:benchmark -- --provider groq --model openai/gpt-oss-120b \
 *   --dataset ai-eval/datasets/coach-synthetic.v1.json --dry-run
 *
 * # execução real (a chave vem do ambiente)
 * GROQ_API_KEY=… npm run ai:benchmark -- --provider groq --model openai/gpt-oss-120b \
 *   --dataset ai-eval/datasets/coach-synthetic.v1.json --save-responses
 *
 * # revisão humana cega de uma amostra, entre execuções já gravadas
 * npm run ai:benchmark -- review --runs <dir1>,<dir2>,<dir3> --sample 12
 * npm run ai:benchmark -- review-score --packet <dir>
 * ```
 *
 * Opt-in por construção (§65/§66): nenhum teste, nenhum workflow e nenhum script de deploy o
 * chama. Uma execução real só acontece quando alguém roda este comando com a chave no ambiente.
 *
 * O relatório (`report.json`/`report.md`) é só metadata e pode ser commitado. O texto do modelo
 * só é gravado com `--save-responses`, e só dentro de `backend/.private/` — que o `.gitignore`
 * ignora —; o comando recusa gravá-lo em qualquer outro lugar (§36).
 */

const BACKEND_ROOT = resolve(__dirname, '..', '..');
const PRIVATE_ROOT = join(BACKEND_ROOT, '.private');
const DEFAULT_LIMITS_PATH = join(BACKEND_ROOT, 'ai-eval', 'provider-limits.json');

export async function runAiBenchmarkCli(argv: readonly string[]): Promise<number> {
  const [first, ...rest] = argv;
  if (first === 'review') return runReviewPacket(rest);
  if (first === 'review-score') return runReviewScore(rest);
  return runBenchmark(argv);
}

// ---------------------------------------------------------------------------------- execução

interface BenchmarkArgs {
  provider: AiProviderName;
  model?: string;
  dataset: string;
  scenarios?: Set<string>;
  types?: Set<string>;
  limit?: number;
  dryRun: boolean;
  out?: string;
  saveResponses: boolean;
  rpm?: number;
  tpm?: number;
  limitsPath: string;
  sparkGlobalQuota?: number;
  peakDaily?: number;
  assumedOutputTokens: number;
}

async function runBenchmark(argv: readonly string[]): Promise<number> {
  const args = parseBenchmarkArgs(argv);
  if (typeof args === 'string') {
    process.stderr.write(`${args}\n${USAGE}`);
    return 2;
  }

  let scenarios: CoachEvalScenario[];
  try {
    scenarios = loadCoachEvalDataset(JSON.parse(readFileSync(args.dataset, 'utf8')));
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    return 2;
  }
  scenarios = scenarios
    .filter((scenario) => !args.scenarios || args.scenarios.has(scenario.id))
    .filter((scenario) => !args.types || args.types.has(scenario.requestType))
    .slice(0, args.limit ?? Number.MAX_SAFE_INTEGER);
  if (scenarios.length === 0) {
    process.stderr.write('nenhum cenário selecionado\n');
    return 2;
  }

  let settings: AiProviderConfig;
  try {
    settings = AiProviderConfig.fromEnv({
      ...process.env,
      AI_PROVIDER: args.provider,
      ...(args.model ? { [MODEL_ENV_BY_PROVIDER[args.provider]]: args.model } : {}),
      LOG_LEVEL: process.env.LOG_LEVEL ?? 'warn',
    });
  } catch (error) {
    if (error instanceof AiProviderConfigError) {
      process.stderr.write(`${error.message}\n`);
      return 2;
    }
    throw error;
  }
  const model = selectedProviderModel(settings);
  const limits = readLimits(args.limitsPath)[`${args.provider}:${model}`];
  const rpm = args.rpm ?? Math.max(1, (limits?.rpm ?? 30) - 1);
  const tpm = args.tpm ?? (limits?.tpm !== undefined ? Math.floor(limits.tpm * 0.9) : undefined);
  const fingerprint = datasetFingerprint(scenarios);

  if (args.dryRun) {
    process.stdout.write(renderDryRun(scenarios, args, model, limits, rpm, tpm, fingerprint));
    return 0;
  }

  if (!selectedProviderApiKey(settings)) {
    process.stderr.write(
      `${API_KEY_ENV_BY_PROVIDER[args.provider]} não está definido — o benchmark real exige a chave no ambiente (use --dry-run para estimar sem chamar)\n`,
    );
    return 2;
  }

  const startedAt = new Date();
  const outDir = resolve(
    args.out ??
      join(PRIVATE_ROOT, 'ai-eval', 'runs', `${stamp(startedAt)}-${args.provider}-${slug(model)}`),
  );
  if (args.saveResponses && !isInside(outDir, PRIVATE_ROOT)) {
    process.stderr.write(
      `--save-responses só grava dentro de ${relative(process.cwd(), PRIVATE_ROOT) || PRIVATE_ROOT} (fora do Git); --out aponta para ${outDir}\n`,
    );
    return 2;
  }
  mkdirSync(outDir, { recursive: true });
  const responsesDir = join(outDir, 'responses');
  if (args.saveResponses) mkdirSync(responsesDir, { recursive: true });

  process.stderr.write(
    `benchmark: ${scenarios.length} cenário(s), ${args.provider}/${model}, cadência ${rpm} RPM${tpm ? ` / ${tpm} TPM` : ''} → ${outDir}\n`,
  );

  const gateway = await createAiProviderGateway(settings, new SparkLogger(settings));
  const run = await runCoachEval({
    gateway,
    scenarios,
    pacer: new RatePacer({ rpm, tpm }),
    // Um 429 de minuto é cadência (o TPM free da Groq comporta ~3 chamadas do Coach por minuto);
    // repetir depois do retry-after mantém a métrica de qualidade limpa, e a repetição é contada.
    minuteLimitRetries: 3,
    assumedOutputTokens: args.assumedOutputTokens,
    onOutcome: (outcome, index, total) =>
      process.stderr.write(`${progressLine(outcome, index, total)}\n`),
    onResponse: args.saveResponses
      ? (scenario, request, text, outcome) =>
          writeFileSync(
            join(responsesDir, `${scenario.id}.json`),
            `${JSON.stringify(
              {
                scenarioId: scenario.id,
                requestType: scenario.requestType,
                description: scenario.description,
                provider: args.provider,
                model: outcome.model ?? model,
                status: outcome.status,
                context: scenario.request.context,
                requestId: request.requestId,
                text: text ?? null,
              },
              null,
              2,
            )}\n`,
          )
      : undefined,
  });
  const finishedAt = new Date();

  const summary = summarize({
    provider: args.provider,
    model,
    run,
    startedAt,
    finishedAt,
    datasetFingerprint: fingerprint,
    settings: {
      temperature: settings.aiTemperature,
      maxOutputTokens: selectedProviderMaxOutputTokens(settings),
      thinkingLevel: settings.aiThinkingLevel,
      timeoutMs: settings.aiTimeoutMs,
      dataset: basename(args.dataset),
      scenarios: scenarios.length,
    },
    limits,
    sparkGlobalQuota: args.sparkGlobalQuota ?? 500,
    peakDailyDemand: args.peakDaily,
  });

  writeFileSync(join(outDir, 'report.json'), `${JSON.stringify(summary, null, 2)}\n`);
  const markdown = renderSummary(summary);
  writeFileSync(join(outDir, 'report.md'), `${markdown}\n`);
  process.stdout.write(`${markdown}\n`);
  return summary.verdict === 'DISQUALIFIED' ? 4 : 0;
}

function renderDryRun(
  scenarios: readonly CoachEvalScenario[],
  args: BenchmarkArgs,
  model: string,
  limits: ProviderLimits | undefined,
  rpm: number,
  tpm: number | undefined,
  fingerprint: string,
): string {
  const lines: string[] = [];
  lines.push(`# Dry-run — ${args.provider} / ${model} (nenhuma chamada foi feita)`);
  lines.push('');
  lines.push(
    `Dataset \`${fingerprint}\`, ${scenarios.length} cenário(s). Estimativa de entrada: ~3,2 caracteres por token (conservadora). Saída assumida: ${args.assumedOutputTokens} tokens por chamada (--assume-output).`,
  );
  lines.push('');
  lines.push('| Cenário | Família | Entrada estimada | Total estimado |');
  lines.push('| --- | --- | ---: | ---: |');
  let total = 0;
  let largest = 0;
  for (const scenario of scenarios) {
    const prompt = estimatePromptTokens(coachProviderRequest(scenario.request, 'dry-run-00000000'));
    const call = prompt + args.assumedOutputTokens;
    total += call;
    largest = Math.max(largest, call);
    lines.push(`| ${scenario.id} | ${familyOf(scenario.requestType)} | ${prompt} | ${call} |`);
  }
  lines.push('');
  lines.push(
    `Total estimado: **${total} tokens** em ${scenarios.length} chamadas; maior chamada ~${largest}.`,
  );
  if (limits) {
    lines.push(
      `Limites (${limits.tier}, verificado em ${limits.verifiedAt}): RPM ${limits.rpm ?? '—'}, RPD ${limits.rpd ?? '—'}, TPM ${limits.tpm ?? '—'}, TPD ${limits.tpd ?? '—'}.`,
    );
    if (limits.tpd !== undefined) {
      lines.push(`Uso do TPD diário: ~${((total / limits.tpd) * 100).toFixed(1)}%.`);
    }
    if (limits.rpd !== undefined && scenarios.length > limits.rpd) {
      lines.push(`**Excede o RPD**: ${scenarios.length} chamadas > ${limits.rpd}/dia.`);
    }
    if (limits.tpm !== undefined && largest > limits.tpm) {
      lines.push(`**A maior chamada estimada passa do TPM** (${largest} > ${limits.tpm}).`);
    }
  } else {
    lines.push('Sem limites conhecidos para este provider/modelo em provider-limits.json.');
  }
  const byTokens = tpm ? Math.ceil(total / tpm) : 0;
  const byRequests = Math.ceil(scenarios.length / rpm);
  lines.push(
    `Duração mínima pela cadência (${rpm} RPM${tpm ? `, ${tpm} TPM` : ''}): ~${Math.max(byTokens, byRequests)} min.`,
  );
  return `${lines.join('\n')}\n`;
}

function progressLine(outcome: ScenarioOutcome, index: number, total: number): string {
  const detail =
    outcome.status === 'PROVIDER_ERROR'
      ? `${outcome.failureKind}${outcome.providerStatus ? `/${outcome.providerStatus}` : ''}${outcome.limit ? `/${outcome.limit}` : ''}`
      : outcome.status === 'REJECTED'
        ? `${outcome.rejectionStage}: ${outcome.rejectionRule ?? ''}`
        : outcome.criticalViolations.length > 0
          ? `VIOLAÇÃO CRÍTICA: ${outcome.criticalViolations.join(', ')}`
          : 'ok';
  return `[${index + 1}/${total}] ${outcome.scenarioId} ${outcome.status} ${Math.round(outcome.latencyMs)}ms ${outcome.usage?.totalTokens ?? '—'} tok — ${detail}`;
}

// ---------------------------------------------------------------------------------- revisão cega

/**
 * Monta o pacote de revisão humana **cega** (§39): uma amostra de cenários que todas as execuções
 * responderam, com as respostas de cada modelo sob rótulos embaralhados (A, B, C…) por cenário.
 * A chave rótulo → modelo fica num arquivo separado, para ser aberto só depois das notas.
 *
 * Tudo vai para `backend/.private/` — contém contexto e resposta, e não é commitável.
 */
function runReviewPacket(argv: readonly string[]): number {
  const runs = valueOf(argv, '--runs')?.split(',').filter(Boolean) ?? [];
  const sample = Number(valueOf(argv, '--sample') ?? '12');
  if (runs.length < 2 || !Number.isInteger(sample) || sample < 1) {
    process.stderr.write('uso: review --runs <dir1>,<dir2>[,…] [--sample N] [--out <dir>]\n');
    return 2;
  }
  const outDir = resolve(
    valueOf(argv, '--out') ?? join(PRIVATE_ROOT, 'ai-eval', `review-${stamp(new Date())}`),
  );
  if (!isInside(outDir, PRIVATE_ROOT)) {
    process.stderr.write(`o pacote de revisão só é gravado dentro de ${PRIVATE_ROOT}\n`);
    return 2;
  }

  const loaded = runs.map((dir) => {
    const report = JSON.parse(readFileSync(join(dir, 'report.json'), 'utf8')) as {
      provider: string;
      model: string;
    };
    const responses = new Map<string, SavedResponse>();
    const responsesDir = join(dir, 'responses');
    if (existsSync(responsesDir)) {
      for (const file of readdirSync(responsesDir).filter((name) => name.endsWith('.json'))) {
        const saved = JSON.parse(readFileSync(join(responsesDir, file), 'utf8')) as SavedResponse;
        if (typeof saved.text === 'string') responses.set(saved.scenarioId, saved);
      }
    }
    return { label: `${report.provider}/${report.model}`, responses };
  });

  const common = [...loaded[0].responses.keys()].filter((id) =>
    loaded.every((run) => run.responses.has(id)),
  );
  if (common.length === 0) {
    process.stderr.write(
      'nenhum cenário respondido por todas as execuções (use --save-responses)\n',
    );
    return 2;
  }
  // Amostra estratificada por família, em ordem aleatória.
  const byFamily = new Map<string, string[]>();
  for (const id of shuffle(common)) {
    const family = familyOf(loaded[0].responses.get(id)!.requestType);
    byFamily.set(family, [...(byFamily.get(family) ?? []), id]);
  }
  const picked: string[] = [];
  while (picked.length < Math.min(sample, common.length)) {
    for (const ids of byFamily.values()) {
      const next = ids.shift();
      if (next && picked.length < sample) picked.push(next);
    }
  }

  mkdirSync(outDir, { recursive: true });
  const key: Record<string, Record<string, string>> = {};
  const packet: string[] = ['# Revisão cega do Coach', '', RUBRIC, ''];
  const scores: string[] = [
    'scenario,label,groundedness,usefulness,conservatism,clarity_ptbr,adherence,notes',
  ];
  for (const id of picked) {
    const order = shuffle(loaded.map((_, index) => index));
    const base = loaded[0].responses.get(id)!;
    packet.push(`## ${id} — ${base.requestType}`, '');
    if (base.description) packet.push(`_${base.description}_`, '');
    packet.push('<details><summary>Contexto enviado</summary>', '', '```json');
    packet.push(JSON.stringify(base.context, null, 2), '```', '</details>', '');
    key[id] = {};
    order.forEach((runIndex, position) => {
      const label = String.fromCharCode(65 + position);
      key[id][label] = loaded[runIndex].label;
      packet.push(`### Resposta ${label}`, '', '```json');
      packet.push(prettyJson(loaded[runIndex].responses.get(id)!.text ?? ''), '```', '');
      scores.push(`${id},${label},,,,,,`);
    });
  }
  writeFileSync(join(outDir, 'packet.md'), `${packet.join('\n')}\n`);
  writeFileSync(join(outDir, 'scores.csv'), `${scores.join('\n')}\n`);
  writeFileSync(join(outDir, 'key.json'), `${JSON.stringify(key, null, 2)}\n`);
  process.stdout.write(
    `pacote de revisão: ${picked.length} cenário(s) × ${loaded.length} modelos em ${outDir}\n` +
      'preencha scores.csv (1–5) lendo packet.md, sem abrir key.json; depois: review-score --packet <dir>\n',
  );
  return 0;
}

function runReviewScore(argv: readonly string[]): number {
  const dir = valueOf(argv, '--packet');
  if (!dir) {
    process.stderr.write('uso: review-score --packet <dir>\n');
    return 2;
  }
  const key = JSON.parse(readFileSync(join(dir, 'key.json'), 'utf8')) as Record<
    string,
    Record<string, string>
  >;
  const rows = readFileSync(join(dir, 'scores.csv'), 'utf8').trim().split('\n').slice(1);
  const criteria = ['groundedness', 'usefulness', 'conservatism', 'clarity_ptbr', 'adherence'];
  const totals = new Map<string, { n: number; sums: number[] }>();
  let incomplete = 0;
  for (const row of rows) {
    const [scenario, label, ...values] = row.split(',');
    const model = key[scenario]?.[label];
    const scores = values.slice(0, criteria.length).map(Number);
    if (!model || scores.some((score) => !Number.isInteger(score) || score < 1 || score > 5)) {
      incomplete += 1;
      continue;
    }
    const entry = totals.get(model) ?? { n: 0, sums: criteria.map(() => 0) };
    entry.n += 1;
    scores.forEach((score, index) => (entry.sums[index] += score));
    totals.set(model, entry);
  }
  const lines = [
    `| Modelo | n | ${criteria.join(' | ')} | média |`,
    `| --- | ---: | ${criteria.map(() => '---:').join(' | ')} | ---: |`,
  ];
  for (const [model, entry] of totals) {
    const averages = entry.sums.map((sum) => sum / entry.n);
    const overall = averages.reduce((a, b) => a + b, 0) / averages.length;
    lines.push(
      `| ${model} | ${entry.n} | ${averages.map((avg) => avg.toFixed(2)).join(' | ')} | ${overall.toFixed(2)} |`,
    );
  }
  if (incomplete > 0)
    lines.push('', `${incomplete} linha(s) sem nota completa (1–5) foram ignoradas.`);
  process.stdout.write(`${lines.join('\n')}\n`);
  return 0;
}

interface SavedResponse {
  scenarioId: string;
  requestType: string;
  description?: string;
  context: unknown;
  text: string | null;
}

const RUBRIC = [
  'Notas de 1 (ruim) a 5 (excelente), sem saber qual modelo escreveu cada resposta:',
  '',
  '- **groundedness** — usa só o que está no contexto; nada inventado.',
  '- **usefulness** — ajuda o atleta a decidir algo concreto.',
  '- **conservatism** — cauteloso na medida da evidência; não força mudança sem dado.',
  '- **clarity_ptbr** — português do Brasil claro, curto e natural.',
  '- **adherence** — atende o pedido (objetivo, duração, foco, tipos permitidos).',
].join('\n');

// ---------------------------------------------------------------------------------- utilitários

const USAGE =
  'uso: ai-benchmark --provider gemini|groq [--model <id>] --dataset <arquivo.json>\n' +
  '       [--scenarios a,b] [--types ANALYZE_WORKOUT,…] [--limit N] [--dry-run]\n' +
  '       [--out <dir>] [--save-responses] [--rpm N] [--tpm N] [--limits <arquivo>]\n' +
  '       [--spark-global-quota N] [--peak-daily N] [--assume-output N]\n' +
  '     ai-benchmark review --runs <dir1>,<dir2>[,…] [--sample N]\n' +
  '     ai-benchmark review-score --packet <dir>\n';

function parseBenchmarkArgs(argv: readonly string[]): BenchmarkArgs | string {
  const known = new Set([
    '--provider',
    '--model',
    '--dataset',
    '--scenarios',
    '--types',
    '--limit',
    '--out',
    '--rpm',
    '--tpm',
    '--limits',
    '--spark-global-quota',
    '--peak-daily',
    '--assume-output',
  ]);
  const flags = new Set(['--dry-run', '--save-responses']);
  const values = new Map<string, string>();
  const set = new Set<string>();
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (flags.has(arg)) {
      set.add(arg);
    } else if (known.has(arg)) {
      const value = argv[++index];
      if (value === undefined || value.startsWith('--')) return `${arg} precisa de um valor`;
      values.set(arg, value);
    } else {
      return `argumento desconhecido: ${arg}`;
    }
  }
  const provider = values.get('--provider');
  if (!provider || !AI_PROVIDER_NAMES.includes(provider as AiProviderName)) {
    return `--provider precisa ser ${AI_PROVIDER_NAMES.join(' ou ')}`;
  }
  const dataset = values.get('--dataset');
  if (!dataset) return '--dataset é obrigatório';
  const int = (name: string): number | undefined | string => {
    const raw = values.get(name);
    if (raw === undefined) return undefined;
    return /^\d+$/.test(raw) && Number(raw) > 0
      ? Number(raw)
      : `${name} precisa ser inteiro positivo`;
  };
  const numbers: Record<string, number | undefined> = {};
  for (const name of [
    '--limit',
    '--rpm',
    '--tpm',
    '--spark-global-quota',
    '--peak-daily',
    '--assume-output',
  ]) {
    const parsed = int(name);
    if (typeof parsed === 'string') return parsed;
    numbers[name] = parsed;
  }
  const list = (name: string) =>
    values.has(name) ? new Set(values.get(name)!.split(',').filter(Boolean)) : undefined;
  return {
    provider: provider as AiProviderName,
    model: values.get('--model'),
    dataset,
    scenarios: list('--scenarios'),
    types: list('--types'),
    limit: numbers['--limit'],
    dryRun: set.has('--dry-run'),
    out: values.get('--out'),
    saveResponses: set.has('--save-responses'),
    rpm: numbers['--rpm'],
    tpm: numbers['--tpm'],
    limitsPath: values.get('--limits') ?? DEFAULT_LIMITS_PATH,
    sparkGlobalQuota: numbers['--spark-global-quota'],
    peakDaily: numbers['--peak-daily'],
    assumedOutputTokens: numbers['--assume-output'] ?? 1000,
  };
}

function readLimits(path: string): Record<string, ProviderLimits> {
  if (!existsSync(path)) return {};
  return providerLimitsFileSchema.parse(JSON.parse(readFileSync(path, 'utf8')));
}

function datasetFingerprint(scenarios: readonly CoachEvalScenario[]): string {
  const canonical = JSON.stringify(
    scenarios.map((scenario) => ({ id: scenario.id, request: scenario.request })),
  );
  return createHash('sha256').update(canonical).digest('hex').slice(0, 12);
}

function valueOf(argv: readonly string[], name: string): string | undefined {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : undefined;
}

function isInside(path: string, root: string): boolean {
  const resolvedRoot = resolve(root);
  const resolvedPath = resolve(path);
  return resolvedPath === resolvedRoot || resolvedPath.startsWith(`${resolvedRoot}${sep}`);
}

function stamp(date: Date): string {
  return date
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d+Z$/, 'Z');
}

function slug(value: string): string {
  return value.replace(/[^A-Za-z0-9.-]+/g, '_');
}

function shuffle<T>(items: readonly T[]): T[] {
  const copy = [...items];
  for (let index = copy.length - 1; index > 0; index -= 1) {
    const other = randomInt(index + 1);
    [copy[index], copy[other]] = [copy[other], copy[index]];
  }
  return copy;
}

function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

if (require.main === module) {
  runAiBenchmarkCli(process.argv.slice(2))
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `benchmark abortado: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
