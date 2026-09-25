# Coach IA — providers, benchmark e troca de provider (T19.H4)

> **Estado em 2026-09-25: CODE COMPLETE / PRODUCTION VALIDATION PENDING.** O backend fala com
> Gemini **ou** Groq atrás da mesma fronteira (`AiProviderGateway`). O benchmark escolheu
> **`groq/openai/gpt-oss-120b`** ([Decisão](#decisão)), o Zero Data Retention da organização Groq foi
> verificado pelo dono da conta, e `ops/gcp/lib.gcp.sh` passou a declarar a Groq — ela entra em
> produção no **próximo deploy** (até lá, a revision no ar atende com `gemini/gemini-3.5-flash`).
> Falta: o deploy (o smoke do provider roda antes do tráfego), o Coach num aparelho, a revisão humana
> cega e a rotação da chave (a atual foi exposta num chat) — ver [Trocar de provider](#trocar-de-provider).

## Arquitetura

```text
Android ── POST /v1/ai/coach ──► AiCoachService (auth → AI_ENABLED → entitlement → vaga → quota)
                                   │   prompt, schema, validação: UM caminho, qualquer provider
                                   ▼
                          AiProviderGateway  ◄── ai-provider.factory.ts (único lugar que lê AI_PROVIDER)
                           │               │
             GeminiAiProviderGateway   GroqAiProviderGateway
               (@google/genai)            (groq-sdk)
```

- **Uma ação do usuário = no máximo uma inferência, num provider só.** Não existe fallback
  automático, retry, segunda opinião nem roteamento por usuário ou por tipo de pedido. O `groq-sdk`
  repete 429/5xx duas vezes por padrão; o gateway passa `maxRetries: 0`. O `@google/genai` só
  repete com `retryOptions`, que o gateway nunca passa.
- **Prompt, schema, validação, quota e entitlement são únicos.** `coachProviderRequest` monta o
  pedido, `validateCoachOutput` decide o que é aceito — usados pelo serviço **e** pelo benchmark.
- **O Android não sabe qual provider responde.** O contrato HTTP não mudou; `model` continua como
  metadata de diagnóstico. Nenhuma chave de provider existe no APK.
- Os invariantes acima são testes, não convenção: `test/ai-provider-structure.spec.ts` (um
  importador por SDK, nenhuma checagem de provider fora da factory/config, nenhum nome de modelo
  fora da configuração, nenhum provider real no CI, nada de Groq no Android).

## Configuração

| Variável | Default | O que é |
| --- | --- | --- |
| `AI_PROVIDER` | `gemini` | `gemini` ou `groq`. Valor desconhecido derruba o startup. |
| `GEMINI_API_KEY` / `GROQ_API_KEY` | — | Só no servidor (Secret Manager em produção). Sem a do provider selecionado, o Coach responde `AI_PROVIDER_UNAVAILABLE`. |
| `GEMINI_MODEL` | `gemini-3.5-flash` | Modelo do Gemini. |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Precisa estar em `groq-model-profiles.ts` (modelos **avaliados**); vazio ou desconhecido derruba o startup. |
| `GROQ_ALLOW_PREVIEW_MODEL` | `false` | Com `NODE_ENV=production`, modelo Preview (hoje `qwen/qwen3.8-27b`) só sobe com `true` — "preview risk accepted", escrito. |
| `AI_MAX_OUTPUT_TOKENS` | `2048` | Teto de saída compartilhado. **Inclui o raciocínio** nos dois providers. |
| `GEMINI_MAX_OUTPUT_TOKENS` / `GROQ_MAX_OUTPUT_TOKENS` | — | Sobrepõem o compartilhado só para aquele provider. |
| `AI_THINKING_LEVEL` | `MEDIUM` | Traduzido por provider (tabela abaixo). Combinação que o modelo não suporta derruba o startup. |
| `REQUIRE_AI_PROVIDER` | `false` | `true` exige no startup a chave do provider **selecionado**. |
| `REQUIRE_GEMINI` | `false` | Legado. Com `gemini`, significa o que sempre significou; com `groq`, `true` derruba o startup pedindo `REQUIRE_AI_PROVIDER`. |
| `AI_ENABLED` | `true` | Interruptor de custo: `false` = zero chamada externa, qualquer provider. |

`AI_TIMEOUT_MS`, `AI_TEMPERATURE`, quotas e concorrência continuam compartilhados e com o mesmo
significado nos dois providers.

### Tradução explícita por provider

| `AI_THINKING_LEVEL` | Gemini (`thinkingLevel`) | `openai/gpt-oss-120b` (`reasoning_effort`) | `qwen/qwen3.8-27b` (`reasoning_effort`) |
| --- | --- | --- | --- |
| `OFF` | omite (modelo decide) | **recusado no startup** (raciocínio não desliga) | `none` |
| `MINIMAL` | `MINIMAL` | **recusado** | **recusado** |
| `LOW` / `MEDIUM` / `HIGH` | idem | `low` / `medium` / `high` | `low` / `medium` / `high` (`high` = modo `xhigh` nativo) |

Na Groq o raciocínio **não volta** no corpo (`include_reasoning: false` no GPT-OSS,
`reasoning_format: hidden` no Qwen). O structured output é `response_format: json_schema` com
`strict: true`; o schema do Coach é convertido na fronteira (`groq-json-schema.ts`): tipos em
minúsculas, objetos fechados (`additionalProperties: false`), todo campo em `required` (opcional
vira anulável), `enum` anulável ganha `null`, `maxItems` vira número. Os `null` que só existem por
exigência do strict são removidos antes de devolver o texto (`dropStrictOnlyNulls`). `zod` e o
validador semântico rodam depois, sempre.

## Produção

O provider de produção é decidido em **`ops/gcp/lib.gcp.sh`**, versionado:

```bash
SPARK_AI_PROVIDER=groq                      # gemini | groq — groq desde 2026-09-25
SPARK_GEMINI_MODEL=gemini-3.5-flash
SPARK_GROQ_MODEL=openai/gpt-oss-120b
SPARK_GEMINI_MAX_OUTPUT_TOKENS=8192         # medido: 2048 truncava (ver Diagnóstico)
SPARK_GROQ_MAX_OUTPUT_TOKENS=3000           # medido: saída p95 2 354, máx. 2 800 (93%); reservado no TPM
SPARK_GEMINI_MAX_REQUESTS_GLOBAL_DAY=15     # free tier: 20 RPD por modelo
SPARK_GROQ_MAX_REQUESTS_GLOBAL_DAY=25       # medido: seguro/dia = 28–29 (80% do TPD ÷ p95)
SPARK_AI_MAX_REQUESTS_PER_USER_DAY=8
SPARK_GROQ_ALLOW_PREVIEW_MODEL=false
SPARK_AI_SMOKE_POLICY=fail                  # fail | warn | skip
```

`ops/gcp/deploy-cloud-run.sh`:

1. valida provider, política de smoke, modelo e números **antes do build**;
2. resolve e pina **só** o secret do provider selecionado (a chave de reserva sem versão nunca
   bloqueia o deploy de quem não a usa); a revision recebe `AI_PROVIDER`, o modelo, o teto de saída,
   a quota e `REQUIRE_AI_PROVIDER=false` — a chave do outro provider não é montada;
3. depois do smoke HTTP do candidate e **antes** de qualquer tráfego, publica e executa o Job
   `spark-ai-provider-smoke` com a mesma imagem, a runtime SA, a mesma configuração de IA e **só**
   a chave do provider (nem banco, nem HMAC). Falhou → tráfego antigo permanece (no primeiro deploy,
   o serviço real não é criado). `SPARK_AI_SMOKE_POLICY=warn` publica mesmo assim (emergência, com
   o provider fora do ar); `skip` não chama o provider;
4. registra `provider=… model=…` no log — nunca a chave.

Um override por ambiente (`SPARK_AI_PROVIDER=gemini ops/gcp/deploy-cloud-run.sh`) vale só até o
próximo deploy com o default: o `--set-env-vars` substitui o conjunto inteiro. Troca de verdade é
commit em `lib.gcp.sh`.

`ops/gcp/config-drift-audit.sh` confere env e secret pinado do provider selecionado, acusa a chave
do outro provider montada e a variável `REQUIRE_GEMINI` de volta, e informa se o secret do provider
de reserva tem versão (failover pronto) ou não (`NOT_VERIFIED`).

### Secrets

| Secret | Quem lê | Deployer |
| --- | --- | --- |
| `spark-gemini-api-key` | `spark-backend-runtime` (Secret Accessor) | `secretmanager.viewer` (só metadata) |
| `spark-groq-api-key` | `spark-backend-runtime` (Secret Accessor) | `secretmanager.viewer` (só metadata) |

Criado em 2026-09-24. Para definir a chave — sem eco, fora do histórico do shell e **sem quebra de
linha no fim** (um Enter antes do Ctrl-D, ou um here-string `<<<`, grava o `\n` junto; foi o que
aconteceu com as versões 1 e 2, com 57 bytes em vez de 56):

```bash
read -rsp 'Chave Groq: ' K && printf '%s' "$K" | gcloud secrets versions add spark-groq-api-key --data-file=- --project=project-47b17b25-909d-4ae8-943; unset K
```

O backend também apara espaço e quebra de linha nas pontas das chaves (`GEMINI_API_KEY`,
`GROQ_API_KEY`) — defesa, não licença: o secret certo é o sem `\n`. Nunca cole a chave em chat,
ticket ou log; se isso acontecer, revogue no console da Groq e grave uma nova.

## Trocar de provider

1. **Benchmark do candidato** com os mesmos números que vão para produção (teto de saída, esforço de
   raciocínio) — ver [Benchmark](#benchmark). Qualquer violação crítica aceita desclassifica.
2. **Revisão humana cega** de uma amostra (`review` / `review-score`).
3. **Privacidade**: para a Groq, **ZDR ativado** na organização (console → Settings → Data
   Controls) antes de qualquer contexto real. Registrar em `OPERATIONS_CHECKLIST.md`.
4. **Chave** com versão habilitada no secret do provider.
5. **Capacidade**: recalcular a quota global com o p95 medido (seção seguinte) e ajustar
   `SPARK_*_MAX_REQUESTS_GLOBAL_DAY` e o teto de saída em `lib.gcp.sh`.
6. Commit de `lib.gcp.sh`, CI `backend` verde, deploy. O smoke do provider roda antes do tráfego.
7. **Depois do deploy**: `ai.request.finished` com o `provider` novo nos logs, e o Coach usado de
   verdade num aparelho (Analyze, Generate, Adapt, Explain).
8. **Voltar**: reverter `lib.gcp.sh` e fazer deploy, ou `ops/gcp/rollback-cloud-run.sh <revision>`
   — cada revision guarda o próprio provider e o próprio secret pinado.

Onde `groq/openai/gpt-oss-120b` está (2026-09-25): **1** feito (90% com o prompt v2, zero violação
crítica aceita); **2** pacote gerado, falta a pontuação; **3** verificado pelo dono da conta;
**4** há versão habilitada, mas a chave foi exposta num chat — gravar uma chave nova, revogar a
antiga no console da Groq e só então desabilitar as versões antigas; **5** feito (quota 25, teto
3 000); **6** commit feito, deploy pendente; **7** pendente.

## Limites dos providers

Fonte da verdade: `backend/ai-eval/provider-limits.json` (com `verifiedAt` e `source`). Revalidar
antes de decidir — limites de free tier mudam sem aviso.

| Provider / modelo | Plano | Status | RPM | RPD | TPM | TPD | Verificado |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| `groq:openai/gpt-oss-120b` | Free | Production | 30 | 1 000 | 8 000 | 200 000 | 2026-09-25, docs + cabeçalhos `x-ratelimit-*` da API |
| `groq:qwen/qwen3.8-27b` | Free | **Preview** | 30 | 1 000 | 8 000 (+ **OTPM 1 000**) | 200 000 | 2026-09-25, docs + 429 real da API |
| `gemini:gemini-3.5-flash` | Free (billing desabilitado no projeto da chave) | GA | 5 | **20** | 250 000 (entrada) | — | 2026-09-24, `gcloud alpha services quota list` |

Preços pagos (referência, 2026-09-24): GPT-OSS 120B US$ 0,15 / 0,60 por 1M tokens de
entrada/saída; Qwen 3.8 27B US$ 0,80 / 4,00.

Duas coisas que só a API real mostrou (2026-09-25):

- **O Qwen 3.8 tem, no free tier, um limite de tokens de _saída_ por minuto (OTPM) de 1 000** — fora
  da tabela pública. Uma chamada do Coach gera 1 300–2 800 tokens de saída (o raciocínio conta): o
  Qwen gratuito não sustenta nem uma chamada por minuto.
- **A Groq reserva o teto de saída na chegada.** Uma chamada de 13 + 10 tokens reais com
  `max_completion_tokens=100` baixou o saldo do minuto em 113. Cada chamada do Coach "ocupa"
  `entrada + GROQ_MAX_OUTPUT_TOKENS` do TPM até terminar — daí o teto de 3 000, e não mais.

## Capacidade e quota do Spark

```text
dailyCapacity     = TPD / p95TokensPorChamada
safeDailyCapacity = 0,8 × TPD / p95TokensPorChamada
seguro/dia        = min(safeDailyCapacity, RPD)
```

A quota global do Spark precisa ficar **abaixo** do seguro/dia (senão todos batem no limite do
provider — um 429 opaco — antes da proteção interna), e a maior chamada precisa caber no TPM do
plano. Estado:

| Provider | p95 por chamada | Seguro/dia | Quota global do Spark | Resultado |
| --- | ---: | ---: | ---: | --- |
| Gemini free | ~3,4k (real) · 9,1k (benchmark, contexto máximo) | **20** (RPD) | 500 → **15** | com 15: SUFICIENTE para a demanda real (pico 9/dia) |
| Groq free — GPT-OSS 120B | 5 424 (benchmark v2) · 5 679 (v1) | **28–29** (80% do TPD) | 500 → **25** | **SUFFICIENT** (pico real 9/dia) |
| Groq free — Qwen 3.8 27B | — | < 1 chamada/min (OTPM 1 000) | — | **INSUFFICIENT** |

Nota de janela: o dia do Gemini vira à meia-noite do Pacífico (07:00 UTC); o do Spark à meia-noite
UTC — por isso a margem de 15 em 20 (e o smoke de cada deploy também gasta uma requisição).

**TPM (Groq free, 8 000/min):** com a reserva do teto de saída, cada chamada do Coach ocupa
`entrada (1,3–5k) + 3 000` do minuto até terminar: uma a duas chamadas simultâneas para **todos** os
usuários somados. A maior chamada do dataset (`analyze-muitos-exercicios`, ~5 000 de entrada) fica na
borda dos 8 000 e passou nas duas rodadas; um contexto bem maior recebe "Request too large" (429/413,
`requestTooLarge` no log) — e esperar não resolve. Com a demanda atual (pico 9/dia) isso basta; se
crescer, a saída é plano pago ou contexto menor **decidido no Android** — nunca truncar dado
importante em silêncio no backend.

**Teto de saída (3 000):** a maior saída medida foi 2 800 (93% do teto; o raciocínio conta). Subir o
teto rouba TPM de todas as chamadas; baixá-lo trunca. Se `ai.request.finished` começar a mostrar
`failureKind=EMPTY_RESPONSE finishReason=length`, o teto ficou curto — decidir entre subir o teto e
aceitar menos chamadas por minuto.

## Diagnóstico do Gemini (2026-09-24)

```text
Gemini current failure:

model:           gemini-3.5-flash (default desde 2026-09-23, be1efcc; revision spark-backend-00022-jom)
status:          503 (intermitente) · 429 · MAX_TOKENS
provider error:  503 "high demand" do free tier (41–51 s até falhar) · 429 cota diária (20 RPD) ·
                 JSON truncado no teto de saída (finishReason MAX_TOKENS)
Spark error:     503 AI_PROVIDER_UNAVAILABLE · 429 AI_GLOBAL_QUOTA_EXCEEDED · 422 INVALID_AI_RESPONSE
root cause:      a chave de produção é free tier (projeto gen-lang-client-0960728045, billing
                 desabilitado): 5 RPM / 20 RPD por modelo para o projeto inteiro, e o free tier é o
                 primeiro a receber 503 sob demanda alta. Além disso, com thinkingLevel=MEDIUM o
                 gemini-3.5-flash gasta quase todo o AI_MAX_OUTPUT_TOKENS=2048 raciocinando.
```

Evidência: logs do Cloud Run (`ai.provider.failed status=503` em 2026-09-24T16:32Z;
`ai.provider.rate_limited` ×5 em 2026-09-23T22:33Z depois de ~49 chamadas de investigação no mesmo
dia do Gemini); cota lida por `gcloud alpha services quota list`; uma chamada mínima real
respondeu 200 em 0,8 s (a falha é intermitente, não permanente); o benchmark do Coach real
(8 cenários) teve 25% de sucesso fim a fim — 4 truncados, 2 × 503 —, e com teto de 8 192 os três
que receberam resposta foram aceitos (saída real de 2,3k–3,9k tokens).

## Consumo real

`npm run ai:usage-report -- --days 30 --calls <export de ai.request.finished>`, contra produção em
2026-09-24 (somente leitura):

| Request type | Contas | Tentativas | Chamadas com tokens | Média total/chamada | P95 total | Máx | P95 duração |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| ANALYZE_WORKOUT | 4 | 7 | 1 | 1 718 | 1 718 | 1 718 | 7,6 s |
| GENERATE_WORKOUT | 5 | 24 | 8 | 2 752 | 3 442 | 3 442 | 42,8 s |
| ADAPT_WORKOUT | 3 | 11 | 1 | 2 992 | 2 992 | 2 992 | 4,1 s |
| EXPLAIN_* | 0 | 0 | 0 | — | — | — | — |
| **Total** | — | **42** | **10** | 2 673 | 3 442 | 3 442 | — |

Pico diário: 9 tentativas. 32 das 42 tentativas não trouxeram tokens (falha no provider). Entrada
p95: 2 179 tokens. Nenhum `EXPLAIN_*` chegou ao backend — `EXPLAIN_RECOMMENDATION` é respondido
localmente pelo app, e os outros não foram usados no período.

Leitura: "tentativa" inclui as chamadas que o provider recusou; a média por tentativa da tabela
(`ai_usage_daily`) é um piso, e a distribuição por chamada vem do log. Até a T19.H4 o
`output_tokens` do Gemini excluía o raciocínio; o relatório deriva a saída como `total − entrada`
nas duas épocas. Desde a T19.H4 os tokens de uma resposta recusada pela validação (ou truncada)
também são registrados — ela custou.

## Benchmark

Opt-in por construção: nenhum teste, workflow ou deploy o chama. A chave vem do ambiente.

```bash
cd backend && npm run build

# 1. custo antes de gastar (nenhuma chamada, nenhuma chave)
npm run ai:benchmark -- --provider groq --model openai/gpt-oss-120b \
  --dataset ai-eval/datasets/coach-synthetic.v1.json --dry-run

# 2. execução real — os mesmos números que vão para produção
GROQ_API_KEY="$(gcloud secrets versions access latest --secret=spark-groq-api-key --project=project-47b17b25-909d-4ae8-943)" \
GROQ_MAX_OUTPUT_TOKENS=3000 \
npm run ai:benchmark -- --provider groq --model openai/gpt-oss-120b \
  --dataset ai-eval/datasets/coach-synthetic.v1.json --save-responses --peak-daily 9 --spark-global-quota 30

# o mesmo para o challenger (Preview)
GROQ_API_KEY="…" GROQ_MAX_OUTPUT_TOKENS=3000 npm run ai:benchmark -- --provider groq \
  --model qwen/qwen3.8-27b --dataset ai-eval/datasets/coach-synthetic.v1.json --save-responses

# 3. revisão humana cega (rótulos embaralhados por cenário; a chave fica em key.json)
npm run ai:benchmark -- review --runs .private/ai-eval/runs/<gemini>,.private/ai-eval/runs/<gpt-oss>,.private/ai-eval/runs/<qwen> --sample 12
npm run ai:benchmark -- review-score --packet .private/ai-eval/review-<data>
```

- **Dataset**: `ai-eval/datasets/coach-synthetic.v1.json` — 29 cenários sintéticos (7 Analyze, 9
  Generate incluindo injeção nas notas e candidatos insuficientes, 7 Adapt cobrindo os cinco tipos,
  "sem mudança" e "dados insuficientes", 6 Explain), com o vocabulário real do app (ids do catálogo,
  grupos musculares em pt-BR, `goalGuidance` literal). Todo cenário passa pelo contrato do
  endpoint e pela trava de dado identificável. Um dataset real anonimizado vive só em
  `backend/.private/ai-eval/` (fora do Git, fora da imagem) — e nunca é enviado à Groq antes do ZDR.
- **Custo**: o dry-run estima ~96k tokens por modelo para os 29 cenários (~48% do TPD free da
  Groq); o GPT-OSS 120B gastou exatamente isso (96k) em 19 min. Duas rodadas completas cabem num dia
  da Groq, três não. Gemini free: no máximo ~15 cenários por dia do Gemini, dividindo cota com a
  produção.
- **Métricas**: por família e no total — erros do provider por tipo, válido estrutural (JSON +
  forma), válido semântico, fim a fim, latência p50/p95, tokens de entrada/saída/total (média, p95,
  máx.), aderência às expectativas do cenário e a capacidade estimada.
- **Violações críticas** (oráculo independente do validador, sobre o que foi **aceito**):
  `exerciseId` inventado, substituto fora dos candidatos, séries/reps/descanso fora dos limites,
  carga sem evidência, `dataQuality` inflado. Qualquer uma → `DISQUALIFIED`.
- **O que é commitável**: `report.md`/`report.json` (provider, modelo, cenário, status, etapa e regra
  de recusa sem trechos do modelo, latência, tokens) — em `ai-eval/reports/`. Resposta crua só com
  `--save-responses`, só dentro de `.private/`.

### Resultados

| Provider | Modelo | Teto de saída | Cenários | Estrutural | Semântico | Fim a fim | p50 | p95 | Tokens méd. | Tokens p95 | Violações críticas |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Gemini | gemini-3.5-flash | 2 048 | 8 | 100% (2/2) | 100% (2/2) | **25%** | 3,6 s | 6,8 s | 4 081 | 7 190 | 0 |
| Gemini | gemini-3.5-flash | 8 192 | 4 (os truncados) | 100% (3/3) | 100% (3/3) | 75% (1 × 503) | 20,9 s | 24,3 s | 5 924 | 9 106 | 0 |
| Gemini | gemini-3.5-flash | 8 192 | 1 (`generate-hipertrofia`) | 100% | 100% | 100% | 24,3 s | 24,3 s | 3 526 | 3 526 | 0 |
| Groq | openai/gpt-oss-120b (prompt v1) | 3 000 | 29 | 100% | 79,3% (ADAPT 1/7) | 79,3% | 3,1 s | 5,3 s | 3 311 | 5 679 | 0 |
| Groq | openai/gpt-oss-120b (prompt v2) | 3 000 | 20 (ANALYZE, ADAPT, EXPLAIN) | 100% | **90,0%** (ADAPT 6/7) | 90,0% | 3,1 s | 5,7 s | 3 604 | 5 424 | 0 |
| Groq | qwen/qwen3.8-27b | 3 000 | 7 + 7 (interrompido) | 100% | 7/8 respostas | — | 4,5 s | — | — | — | 0 |

Relatórios em `backend/ai-eval/reports/`. Leitura:

- **Gemini**: amostra pequena de propósito (a chave é a de produção, 20/dia). Com teto de 8 192, as
  quatro operações foram aceitas — é o smoke do Gemini desta tarefa; o GENERATE usou 2 106 tokens de
  saída e teria truncado em 2 048. Todas as chamadas do Gemini usaram o **prompt v1**; o v2 no Gemini
  está NOT VERIFIED (a única tentativa, em 2026-09-25, recebeu 503).
- **GPT-OSS 120B, prompt v1**: ANALYZE, GENERATE e EXPLAIN 100%; **ADAPT 14%** — o modelo tratava
  cada mudança como retrato completo (numa `ADJUST_LOAD`, preenchia também séries, reps e descanso),
  e o validador — aqui e no Android — recusa campo de outro tipo. Nenhum erro de provider, nenhum 429.
- **Prompt v2** (a regra 6 do ADAPT e os tetos de texto do validador escritos no schema): **ADAPT 6/7**.
  As duas recusas que sobraram: um ADAPT que ainda repetiu `currentSets`/`currentRestSeconds` "como
  contexto", e um EXPLAIN acima de 900 caracteres. GENERATE não foi repetido no v2 (orçamento diário
  do modelo; no v1 foi 9/9, e o v2 só acrescentou tetos de texto às descrições dele).
- **Qwen 3.8**: interrompido pelo OTPM do free tier; ver
  `2026-09-25-groq-qwen3.8-27b-parcial.md`.
- **Revisão humana cega**: pacote de 6 cenários × 2 modelos (GPT-OSS × Gemini) gerado em
  `backend/.private/ai-eval/review-20260925T004820Z/` — **pendente**: precisa das notas de uma pessoa
  (`packet.md` → `scores.csv` → `npm run ai:benchmark -- review-score --packet <dir>`).

## Decisão

```text
provider:  groq
model:     openai/gpt-oss-120b
```

- **Qualidade**: 100% estrutural (o strict da Groq aceita o schema inteiro do Coach, inclusive
  `maxItems`); 90% semântico com o prompt v2, e o que falha falha **fechado** — zero violação crítica
  aceita nos três modelos. Revisão humana: pendente.
- **Latência**: p50 ~3 s, p95 ~5,5 s — contra 20–24 s do Gemini com teto de 8 192, e 41–51 s dos 503.
- **Tokens**: média ~3,5k, p95 ~5,5k por chamada; cabe no TPM com o teto de 3 000.
- **Status**: Production na Groq (o Qwen é Preview).
- **Capacidade gratuita**: ~28 chamadas seguras/dia (TPD), 1 000/dia (RPD); com a quota de 25 e o pico
  real de 9/dia, SUFFICIENT. O Gemini free dá 20/dia com 503 intermitente; o Qwen free, menos de uma
  chamada por minuto.
- **Custo pago**: US$ 0,15 / 0,60 por 1M tokens (o Qwen é 5–7× mais caro).
- **Privacidade**: a Groq não retém inputs/outputs de inferência por padrão, salvo até 30 dias para
  confiabilidade/abuso; o **ZDR** remove essa exceção — verificado pelo dono da conta em 2026-09-25.
- **Qwen 3.8**: não recomendado — Preview, OTPM de 1 000/min no free tier e 5–7× o custo.

A troca foi **declarada** em 2026-09-25 (`SPARK_AI_PROVIDER=groq` em `ops/gcp/lib.gcp.sh`) e vale a
partir do próximo deploy. Voltar ao Gemini: `SPARK_AI_PROVIDER=gemini` + deploy, ou o rollback para
uma revision anterior.

## Smoke do provider

```bash
AI_PROVIDER=groq GROQ_API_KEY=… npm run ai:provider-smoke                  # ANALYZE, uma chamada
AI_PROVIDER=groq GROQ_API_KEY=… npm run ai:provider-smoke -- --type all    # as quatro operações
AI_PROVIDER=gemini GEMINI_API_KEY=… npm run ai:provider-smoke -- --type explain
```

Chamadas sintéticas pelo caminho de produção (factory → gateway → `validateCoachOutput`). O default
é um `ANALYZE_WORKOUT` porque o schema de análise exercita **todas** as construções de schema do
Coach (`maxItems`, anuláveis, `enum`, `minimum`/`maximum`, objetos em arrays): um provider que
recuse uma delas recusa o schema inteiro com 400 — a documentação da Groq não cita `maxItems`, e a
API real o aceita. `--type all` (ANALYZE, GENERATE, ADAPT, EXPLAIN) é o smoke completo de uma troca de
provider — em 2026-09-25, `groq/openai/gpt-oss-120b` com o prompt v2: as quatro PASS, 1,6–3,7 s,
1,6k–3,4k tokens por chamada. Saída por chamada:
`AI_PROVIDER_SMOKE PASS|WARN|FAIL provider=… model=… structuredOutput=… semantic=… latencyMs=… tokens…`.
Código 0 = tudo aceito, 3 = formato certo mas recusa semântica, 1 = falha do provider/formato.
O Job do deploy roda o default com `AI_PROVIDER_SMOKE_GATE=provider` (o 3 vira 0: o que o deploy
prova é chave, quota e schema).

## Observabilidade

Um evento `ai.request.finished` por chamada que chegou ao provider:

| Campo | Valores |
| --- | --- |
| `status` | `SUCCESS` · `PROVIDER_FAILED` · `INVALID_RESPONSE` |
| `provider`, `model` | quem atendeu (ou quem estava configurado, numa falha sem resposta) |
| `requestType`, `durationMs`, `promptTokens`, `outputTokens`, `totalTokens` | metadata |
| `failureKind` | `NOT_CONFIGURED` · `RATE_LIMITED` · `TIMEOUT` · `EMPTY_RESPONSE` · `UNAVAILABLE` |
| `providerStatus`, `providerCode`, `finishReason` | status HTTP, código curto e motivo de parada do provider |
| `limitSource`, `limit`, `retryAfterSeconds` | `PROVIDER` + `RPM`/`RPD`/`TPM`/`TPD`/`OTPM` quando o provider declara |
| `requestTooLarge` | `true` quando a chamada, **sozinha**, passa do limite — esperar não resolve |
| `rejectionStage` | `JSON` · `STRUCTURE` · `SEMANTIC` |

A quota do Spark sai em `ai.quota.exceeded` com `limitSource: "SPARK"` e `scope` `USER`/`GLOBAL`.
Para o app as duas são 429; para o operador, não. Nunca: prompt, resposta, contexto, uid inteiro,
token, chave ou mensagem crua do provider (a da Groq carrega o id da organização).

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="spark-backend"
  AND jsonPayload.event="ai.request.finished" AND jsonPayload.status!="SUCCESS"' \
  --project project-47b17b25-909d-4ae8-943 --freshness=7d \
  --format='table(timestamp,jsonPayload.provider,jsonPayload.requestType,jsonPayload.failureKind,jsonPayload.providerStatus,jsonPayload.limit,jsonPayload.finishReason)'
```

## Privacidade: o que chega ao provider

O contexto é montado no Android e validado pelo contrato estrito do endpoint (`.strict()`; campo
desconhecido é recusado). Nada é acrescentado para a Groq.

| Operação | Classes de dado enviadas |
| --- | --- |
| `ANALYZE_WORKOUT` | meta semanal; treino atual (nome do treino, exercícios com séries/reps/carga/descanso planejados); histórico por exercício (até 6 execuções: data, séries concluídas, maior carga, total de reps); PRs (tipo, valor, data); evidência (contagens, teto de qualidade) |
| `GENERATE_WORKOUT` | objetivo e orientação; duração; grupos de foco; equipamentos; **notas do usuário** (até 280 caracteres, texto livre); candidatos (id, nome, grupo, equipamento); evidência de carga (última carga/reps) |
| `ADAPT_WORKOUT` | treino (nome e exercícios planejados); histórico por exercício; PRs; substitutos candidatos; tipos de mudança permitidos; evidência |
| `EXPLAIN_*` | assunto e fatos já calculados pelo app; exercício, valores atual/sugerido, motivo, evidência, histórico e limitações conhecidas |

Nunca: Firebase UID, e-mail, `socialId`, `friendCode`, nome de exibição, token, dado de amigos. O
que pode identificar alguém indiretamente: nomes de treino/exercício personalizados, as notas e as
datas das execuções. Por isso:

- **Groq**: por padrão não retém inputs/outputs de inferência, **exceto** até 30 dias para
  confiabilidade e abuso. Zero Data Retention (console → Settings → Data Controls, disponível a
  todos os clientes) remove essa exceção — **exigido antes de contexto real em produção**.
  Estado: **VERIFIED** pelo dono da conta em 2026-09-25 (conferido no console; não há API para o
  backend conferir sozinho — reconfira se a organização Groq mudar).
- Enquanto o ZDR não estiver verificado, o benchmark usa só o dataset sintético.
