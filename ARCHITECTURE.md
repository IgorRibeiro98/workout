# Spark / Gym Tracker — Architecture Guide

This document describes the intended architecture and the currently important technical decisions. Before changing a subsystem, verify its current implementation in the repository and preserve corrected/newer code when it differs from stale documentation.

## 1. Platform and stack

- Android native
- Kotlin
- Jetpack Compose
- Material 3
- Coroutines
- Flow
- Room
- DataStore
- WorkManager / Android notifications when needed
- Local/offline-first by default

No backend or Firebase dependency should be introduced as a requirement for core workout execution unless explicitly requested.

## 2. Layering

Preferred dependency direction:

```text
Compose UI
   |
   v
ViewModel / UI state
   |
   v
Domain / Use Cases
   |
   v
Repositories
   |
   v
Room / DataStore / external data sources
```

### UI

Responsibilities:

- render immutable/observable UI state;
- emit user intents/events;
- handle presentation-only state;
- avoid owning canonical workout progression rules.

### ViewModel

Responsibilities:

- coordinate UI intents;
- expose state to Compose;
- call domain/repository operations;
- avoid duplicating domain calculations already owned by canonical calculators/services.

### Domain / Use Cases

Responsibilities:

- workout progression rules;
- route/cursor transitions;
- time/recovery/ETA calculations;
- business validation;
- party rotation behavior;
- template/session semantics.

### Repository

Responsibilities:

- isolate durable storage/data sources;
- expose persisted state consistently;
- maintain atomicity where state transitions require it.

## 3. Workout model

### WorkoutTemplate

Represents the reusable/planned workout.

Typical concerns:

- groups/order;
- exercises/order;
- target sets/reps/load configuration;
- metadata;
- defaults.

Changing a template affects future/planned executions and must not mutate historical sessions.

### WorkoutSession

Represents one actual workout execution.

Expected lifecycle states include concepts equivalent to:

- `PLANNED`
- `IN_PROGRESS`
- `PAUSED`
- `COMPLETED`
- `CANCELLED`

Session history must preserve what actually happened, including substitutions/changes made during execution.

For exercise replacement during a session, preserve traceability such as original exercise, actual exercise and replacement reason when supported by the current model.

## 4. Canonical navigation/execution model

> **Status (verificado em 2026-09-05): não implementado.** Nenhum dos nomes desta seção existe
> no código (`WorkoutRoute`, `WorkoutRouteNode`, `NavigationCursor`, `NavigationEventProcessor`,
> `NavigationRepository` — 0 ocorrências). Hoje a execução ativa é conduzida por
> `WorkoutEngine` + `ExecutionViewModel` sobre `WorkoutSessionEntity` / `ExerciseSessionEntity` /
> `SetLogEntity`. Esta seção descreve o destino pretendido, não o estado atual: não a use como
> prova de que um componente existe, e não crie um destes só para satisfazer o documento.

The active workout uses a persisted "Workout GPS" style route/navigation model.

Relevant concepts include:

- `WorkoutRoute`
- `WorkoutRouteNode`
- `NavigationCursor`
- `NavigationEventProcessor`
- `NavigationRepository`

A route node may carry timing data such as:

- `startedAt`
- `finishedAt`
- `plannedSeconds`
- `actualSeconds`

The persisted route/cursor should drive the active UI.

Preferred flow:

```text
Room
  -> NavigationRepository
  -> persisted route + cursor/current node
  -> ViewModel projection
  -> ActiveWorkoutUiState
  -> Compose
```

### Important architectural constraint

Older concepts such as `ExecutionStateMachine`, `ExecutionFlowEngine`, `ExecutionTransitionEngine` or similar may still exist in the repository.

Do not assume they remain authoritative.

Before using or extending them, verify whether they are legacy or whether the persisted route/navigation flow already superseded them. Do not let two engines advance the workout independently.

## 5. Navigation event processing

`NavigationEventProcessor` is expected to process progression against the **latest route state**.

A previous correction refactored instant-node advancement so the processor re-reads/uses the updated route after each transition rather than iterating against stale state.

Do not reintroduce a loop that advances multiple nodes using an outdated route snapshot.

Concurrency/stale-event protection is part of correctness. Preserve mutex/serialization logic when present.

## 6. Execution state simplification

Product direction is to keep the user-facing progression simple:

```text
EQUIPMENT -> EXECUTION -> REST
```

Then continue to the next required set/person/exercise according to route rules.

Do not reintroduce obsolete visible states such as:

- find equipment;
- prepare execution.

If legacy states remain internally for migration, do not expose or expand them without explicit need.

## 7. Recovery/rest architecture

> **Status (verificado em 2026-09-05): não implementado.** `RecoveryTimeCalculator` não existe no
> código. O descanso hoje é controlado pelo estado de timer do `ExecutionViewModel` com os
> valores persistidos em `SettingsManager` (`restTimerDeadline`, `defaultRestSeconds`).

Recovery timing should be centralized.

Relevant concept:

- `RecoveryTimeCalculator`

Recovery must use canonical timing anchors such as `finishedAt`, `recoveryAnchorTimestampMs` or the currently persisted equivalent.

Do not compute competing recovery timers separately in different Composables/ViewModels.

Party mode may require simultaneous recovery information for multiple participants, but all timers should derive from persisted/canonical anchors.

## 8. ETA architecture

> **Status (verificado em 2026-09-05): não implementado.** `CurrentNodeTimeEstimator`,
> `RemainingRouteDurationCalculator` e `ETAEstimator` não existem no código. Não há ETA
> canônico implementado hoje.

Relevant concepts include:

- `CurrentNodeTimeEstimator`
- `RemainingRouteDurationCalculator`
- `ETAEstimator`

Canonical equation:

```text
ETA = now + remaining route duration
```

The remaining duration must be recalculated after route-changing actions such as:

- undo;
- skip;
- next set;
- completing a node;
- changing workout structure during execution;
- party progression/rotation.

Do not display ETA based only on the original workout start time plus an old fixed estimate.

A fresh session must get a fresh `workoutStartedAt`.

## 9. Party route architecture

> **Status (verificado em 2026-09-05): não implementado.** `PartyRouteBuilder` não existe no
> código.

Relevant concept:

- `PartyRouteBuilder`

A previous correction ensured REST nodes are linked to the corresponding set/user-set node rather than being detached from their execution anchor.

Preserve explicit node relationships when modifying party route construction.

### Party configuration

Party mode/participant count must flow from persisted configuration into the created session/route and active ViewModel state.

Do not:

- show DUO/TRIO in Home and create a SOLO route;
- hardcode participant names;
- calculate party rotation only in UI state.

### Remote Party session feature

The project also has/has explored a collaborative Party invitation/session flow with Nostr-inspired synchronization.

When modifying that subsystem, first map its current classes and protocol. Preserve the contract that a party invitation/session has explicit identifiers/participants and transitions through its own connection lifecycle. Do not couple remote transport state to the local workout route in an ad-hoc way.

## 10. Preferences and workout start

`SettingsManager` (DataStore) é a autoridade canônica de preferências — o nome
`UserPreferencesDataStore` aparecia neste documento, mas não existe no código. Valores como:

- party mode / participant count;
- available duration;
- Focus Mode;
- start defaults.

The same values must drive:

```text
Home
 -> workout configuration
 -> start command
 -> WorkoutSession / route creation
 -> ActiveWorkoutViewModel
 -> active UI
```

### Start behavior

The current intended UX is:

- no automatic configuration modal on every start;
- configuration opens from the settings/config icon;
- no implicit start caused only by navigating to a screen;
- centralized start operation (currently associated with `TodayViewModel.startWorkout()` in the corrected flow);
- guard against double-start.

## 11. Focus Mode

Focus Mode is not simply a smaller version of the normal screen.

Its goal is to remove secondary information and preserve only the execution essentials, such as:

- current exercise;
- current set;
- relevant timer;
- current participant / recovery context in party mode;
- next essential action;
- compact ETA.

Normal mode may expose richer context.

## 12. Exercise catalog

The canonical exercise catalog is local and PT-BR.

External data/media sources are optional enrichment.

Requirements:

- correct muscle/category classification;
- consistent identifiers;
- media only when valid/appropriate;
- idempotent imports;
- preserve user custom exercises/customizations;
- preserve historical session references;
- detect invalid references;
- remain usable offline.

Exercise import code must not silently classify an exercise into an unrelated group simply to satisfy a required field.

## 13. Workout builder and ordering

Workout templates must support durable ordering for:

- workout groups;
- exercises within a group.

The persisted order is domain data, not merely UI list position.

### Drag interaction target

Preferred UX:

1. long press initiates drag;
2. ghost follows the finger closely;
3. destination slot shows a preview/placeholder representing the dragged item;
4. surrounding items may animate into prospective positions;
5. drop commits the new order;
6. persistence is verified by reopening/reloading.

### Compose coordinate guidance

Prefer keeping the drag ghost in the same parent/container coordinate system.

A robust direction is:

```text
parent Box
 + item LayoutCoordinates
 + local/container coordinates
 + graphicsLayer/translation for ghost
```

Avoid using a separate `Popup`/window coordinate space if it produces finger-to-ghost drift.

## 14. Active workout resilience

The active session must not rely only on ephemeral Compose state.

The architecture should support recovery across:

- recomposition;
- configuration changes;
- background/foreground;
- process recreation where persisted state allows it;
- reopening the app.

When returning to an unfinished session, the UX should be driven from durable session state rather than reconstructed guesses.

## 15. Coach IA (T14)

> **Status (verificado em 2026-09-06): implementado, com o transporte migrado na T16.2.** Tudo
> descrito aqui existe no código. O que mudou na T16.2: o provider **não é mais** o Firebase AI
> Logic dentro do app — quem fala com o Gemini é o Spark Backend, e o Coach passou a ser uma
> capacidade **online autenticada**. O núcleo do Spark continua local-first: sem conta, sem
> backend e sem internet, treino, execução, histórico, templates e gamificação seguem completos.

### 15.1 Fluxo canônico

```text
Autoridades locais (WorkoutDao/Room, catálogo canônico, SettingsManager)
  -> Context Builder do tipo de request                    [Android]
  -> Use Case (Analyze / Generate / Adapt / Explain)       [Android]
  -> AiCoachGateway / SparkBackendAiCoachGateway           [Android]
  -> POST /v1/ai/coach  +  Firebase ID Token               [HTTP]
  -> BearerAuthGuard -> quota -> concorrência              [Backend]
  -> AiCoachPromptRegistry (prompt, modelo, schema)        [Backend]
  -> AiProviderGateway -> GeminiAiProviderGateway -> Gemini
  -> Structured Output
  -> validação estrutural + semântica                      [Backend]
  -> AiCoachResponseValidator                              [Android, validação final]
  -> Advice / Draft / Explanation
  -> confirmação explícita do usuário
  -> WorkoutRepository (o mesmo caminho da criação/edição manual)
```

A validação acontece **nos dois lados de propósito** (defense in depth): o servidor valida contra
o contexto que recebeu; o app valida de novo contra o domínio atual, que pode ter mudado enquanto
o modelo pensava. O app pode recusar uma resposta que o servidor aceitou — e isso é esperado.

O modelo é **consumidor e proponente**, nunca fonte de verdade. Structured output garante a
forma; o validador garante a semântica. Uma violação invalida a resposta inteira — nada é
adivinhado, corrigido por aproximação ou resolvido por nome.

### 15.2 Componentes reais

| Papel | Classe |
| --- | --- |
| Fronteira com o backend | `AiCoachGateway` / `SparkBackendAiCoachGateway` |
| Transporte autenticado | `SparkBackendClient` + `SparkAuthInterceptor` (T16.1) |
| Configuração do app (versão de contrato, timeouts, tetos de contexto) | `AiModelConfig` |
| Prompt, modelo e versão de prompt | **backend** (`AiCoachPromptRegistry`, `AppConfig`) |
| Schemas de saída | **backend** (`ai-coach.output.schema.ts`) |
| Saneamento de texto do usuário | `AiUserText` |
| Validação semântica final | `AiCoachResponseValidator` |
| Contexto de análise | `AiCoachContextBuilder` / `WorkoutAiCoachContextBuilder` |
| Contexto de geração | `AiWorkoutGenerationContextBuilder` / `WorkoutAiGenerationContextBuilder` |
| Contexto de adaptação | `AiWorkoutAdaptationContextBuilder` / `WorkoutAiAdaptationContextBuilder` |
| Contexto de explicação | `AiCoachExplanationContextBuilder` |
| Recorte do catálogo | `ExerciseCandidateBuilder` |
| Teto de evidência | `AiDataQualityPolicy` |
| Observabilidade | `AiCoachTelemetry` / `LogcatAiCoachTelemetry` |
| App Check por variante | `SparkAppCheck` (`src/debug` e `src/release`), instalado pelo `FirebaseAuthGateway` |

### 15.3 Tipos de request

`ANALYZE_WORKOUT`, `GENERATE_WORKOUT`, `ADAPT_WORKOUT` e os quatro `EXPLAIN_*`
(`EXPLAIN_RECOMMENDATION`, `EXPLAIN_WORKOUT`, `EXPLAIN_ADAPTATION`, `EXPLAIN_PROGRESS`).

- `ANALYZE_*` e `EXPLAIN_*` são **read-only**: não criam nem alteram treino, sessão, XP, PR,
  conquista ou missão.
- `GENERATE_WORKOUT` é **draft-first**: só a confirmação explícita cria um `WorkoutTemplate`.
- `ADAPT_WORKOUT` é **confirmation-first**: o usuário seleciona mudança a mudança, e a aplicação
  revalida a revisão do template (proposta obsoleta não sobrescreve edição mais nova).
- Nenhum fluxo altera `WorkoutSession` concluída. Histórico é imutável.

### 15.4 Versionamento

Depois da T16.2 as autoridades estão separadas por quem decide:

| Onde | O quê |
| --- | --- |
| `AiModelConfig` (app) | `SCHEMA_VERSION` + `SUPPORTED_SCHEMA_VERSIONS` — versão do contrato de conversa, recusada no gateway antes de qualquer rede; tetos de contexto; tetos de espera do transporte |
| `AiCoachPromptRegistry` (backend) | `PROMPT_VERSION` — suba a cada mudança de instrução ou de formato |
| `AppConfig` (backend) | `GEMINI_MODEL`, temperatura, esforço de raciocínio, teto de saída, timeout do provider |

**Não existe nome de modelo nem versão de prompt no app** — há teste estrutural para os dois.
Trocar de modelo é mudar uma variável de ambiente na VPS, não publicar um APK.

Toda chamada carrega `requestId`, `clientRequestId`, `schemaVersion` e `promptVersion`; a
telemetria do app registra `requestId`, tipo, o modelo e a versão de prompt **que o servidor
informou**, duração e classe do resultado. Sem chamada, o modelo registrado é `desconhecido` —
nunca um nome inventado.

### 15.5 Política de contexto

Regra: **o menor contexto suficiente**, nunca "todo dado disponível do usuário". Gamificação
(XP, nível, streak, conquistas, missões) e medidas corporais não entram em nenhum contexto de
análise, geração ou adaptação; os números de progressão só aparecem, prontos, no contexto de
`EXPLAIN_PROGRESS`. Os tetos vivem em `AiModelConfig` (histórico por exercício, sessões
percorridas, exercícios, PRs, candidatos, candidatos por grupo, grupos de foco).

### 15.6 Custo e resiliência

- Nenhuma chamada acontece em `init`, ao abrir tela ou em recomposição — só em ação explícita.
- Enquanto uma chamada está em andamento, novos toques são ignorados (um request por pedido). O
  servidor repete a proteção: uma chamada ativa por conta e deduplicação por `clientRequestId`.
- Não existe retry automático, polling nem chamada em background — nem no app, nem no servidor.
- Quotas diárias por conta e globais vivem no servidor e são configuráveis.
- `RATE_LIMITED` não convida a repetir; `TIMEOUT` permite nova tentativa manual.
- `AUTH_REQUIRED` não é falha: é o convite para entrar na Conta Spark, e o login **nunca** abre
  sozinho.
- Explicação com razão e evidência suficientes é montada **localmente**, sem provider; quando o
  provider falha, o app cai para essa explicação local e declara a limitação.

### 15.7 Segurança

- Texto livre do usuário (`notes`, da geração) é dado, nunca instrução: é saneado na fronteira e
  o prompt declara que o bloco de contexto não altera as regras. A garantia, porém, é o
  validador — prompt é orientação.
- `exerciseId` inexistente é rejeitado em todos os fluxos; nos fluxos com candidate set, um id
  real que não estava autorizado naquela requisição também é.
- App Check é escolhido por variante de build: `src/debug` usa o provedor de depuração,
  `src/release` usa Play Integrity. Não há token de depuração no código nem no APK de release.
  Desde a T16.2 quem o instala é o `FirebaseAuthGateway` — o Coach não fala mais com o Firebase.
- **A credencial do Gemini não existe no aplicativo.** Ela vive só no servidor
  (`GEMINI_API_KEY`), nunca no APK, no `BuildConfig`, em resource, em DataStore, no Git ou em
  teste. Há teste estrutural que varre os três source sets procurando por ela.
- A configuração do Firebase vem de `app/google-services.json` (não versionado). Nenhuma chave
  vive no código.
- Comunicação em texto claro é proibida (`network_security_config.xml`). O build de depuração tem
  exceção **nominal** para `10.0.2.2`/`localhost`; ela não é compilada no APK de release.

### 15.8 Suíte de avaliação

`app/src/test/java/com/example/domain/ai/eval/` contém a suíte determinística
(`AiCoachEvaluationSuiteTest` + cenários), os testes de prompt injection, de versionamento, de
observabilidade e de configuração de segurança. Ela roda **offline**, com `FakeAiCoachGateway`,
e não consome cota — a migração da T16.2 não a fez depender de VPS, Firebase, Gemini ou internet.
`app/src/test/java/com/example/data/ai/SparkBackendAiCoachGatewayTest.kt` cobre a nova fronteira
HTTP com um interceptor terminal, sem abrir socket. Avaliação com o caminho real (Firebase Auth →
Spark Backend → Gemini) é opt-in e instrumentada
(`app/src/androidTest/.../RealProviderEvaluationTest`).

## 16. Important known regression patterns

Be especially cautious around:

- stale route snapshots inside multi-step progression;
- stale navigation events;
- timer bugs caused by advancing quickly;
- old `workoutStartedAt` reused after abandoning/restarting;
- Home configuration diverging from actual session configuration;
- DUO/TRIO falling back to SOLO;
- hardcoded participant identities;
- duplicated ETA implementations;
- visual undo without persisted state rollback;
- Compose drag ghost coordinate drift;
- exercise catalog classification/media mismatches;
- UI that only looks correct on one screen size.

## 17. Spark Backend e arquitetura online (T16)

> **Status (verificado em 2026-09-06): fundação, identidade, Coach online, fundação de sync,
> backup e restore implementados; sincronização, não.** A T16.0 criou o backend em `backend/` com
> configuração, SQLite, migrations, health, logging, Docker e os contratos arquiteturais. A T16.1
> acrescentou **conta opcional**: Firebase Auth com Sign in with Google no Android, verificação de
> Firebase ID Token no backend e `GET /v1/auth/me`. A T16.2 migrou o **Coach IA**:
> `POST /v1/ai/coach`, prompt/modelo/credencial server-side, quota e validação no servidor. A T16.3
> acrescentou **identidade global dos dados e a Outbox transacional** no Android. A T16.4
> acrescentou **backup estruturado**: adoção explícita do conjunto de dados por uma Conta Spark,
> snapshot completo, `POST /v1/backups` e `GET /v1/backups/latest`. A T16.5 acrescentou **restore
> seguro**: descoberta (`GET /v1/backups`), download do conteúdo
> (`GET /v1/backups/{id}/content`), verificação de integridade, validação integral, preview,
> confirmação explícita, snapshot de segurança local e substituição transacional do dataset.
> **Não existe** sincronização incremental, push/pull, convergência multi-device, conflito,
> tombstone remoto, merge, backup automático ou backup off-site da VPS.

A partir da T16, o Spark tem uma fronteira online oficial. Ela **não** transforma o Spark em um app
dependente de servidor: o núcleo continua funcionando por completo sem internet, sem VPS, sem
Firebase e sem Gemini.

### Autoridades

| Autoridade | Responsabilidade |
| --- | --- |
| Android / Room + DataStore | autoridade **operacional local** — treino, execução, histórico, templates, catálogo, gamificação, preferências |
| Spark Backend | estado remoto da conta e convergência entre dispositivos |
| Firebase | identidade/autenticação (`Firebase Auth`) |
| Gemini | serviço probabilístico — nunca autoridade do domínio |

### Direção de fluxo

Obrigatória, quando o sync existir:

```text
ação do usuário → domínio → Room → outbox → sync → Spark Backend
Spark Backend → sync → validação → Room → UI observa Room
```

Proibida, em qualquer fase:

```text
UI → API → servidor → "se o servidor responder, o app funciona"
```

A UI observa Room. Um dado vindo do servidor entra pelo sync, é validado e é escrito no Room.

### Invariantes bloqueantes

1. Conta é **opcional**. Nenhuma fase da T16 pode exigir login para iniciar ou concluir treino.
2. `WorkoutSession` `COMPLETED` é **imutável**. Divergência no mesmo `syncId` é conflito de
   integridade, nunca *last write wins*.
3. O servidor **nunca** confia em `ownerUid` vindo do payload — o `uid` sai do token verificado.
4. O schema remoto **não** é cópia 1:1 do Room.
5. O backend não vira segunda fonte operacional de verdade.

### Coach IA (T16.2) — a nova fronteira com o Gemini

> **Status (verificado em 2026-09-06): implementado.** O `FirebaseAiCoachGateway` **não existe
> mais**; o gateway em uso é o `SparkBackendAiCoachGateway`, e a dependência `firebase-ai` saiu do
> app. Não há fallback automático entre os dois caminhos — ter dois providers concorrendo criaria
> custo duplicado e comportamento divergente.

```text
Spark Android
│  Room (autoridade) → ContextBuilder → contexto mínimo/estruturado
│  Firebase ID Token (obtido sob demanda, nunca persistido)
▼
POST /v1/ai/coach
│
├── BearerAuthGuard        uid sai do token verificado, nunca do corpo
├── contrato               schemaVersion, requestType, tetos de array/string/payload
├── concorrência           1 chamada ativa por conta + dedupe por clientRequestId
├── quota                  por conta e global, por dia (UTC), configuráveis
├── AiCoachPromptRegistry  prompt e promptVersion — o único lugar com prompt no Spark
├── AiProviderConfig       modelo, temperatura, thinking, teto de saída, timeout
└── AiProviderGateway → GeminiAiProviderGateway → Gemini API
        ↓ structured output
    validação estrutural + semântica (ids, candidatos, valores atuais, dataQuality)
        ↓
Android: AiCoachResponseValidator (validação final contra o domínio atual) → UI
```

#### A decisão de produto

| Sem conta | Com conta |
| --- | --- |
| treinos, templates, histórico, execução, gamificação, dados locais | tudo isso **+** chamadas novas ao Gemini |

O login continua **opcional para usar o Spark** e passou a ser necessário apenas para capacidades
online que dependem do Spark Backend, começando pelo Coach. Ao receber `AUTH_REQUIRED` a UI
convida a entrar (reutilizando a infraestrutura da T16.1) — e **nunca** abre o seletor de contas
sozinha.

#### Divisão de responsabilidades

```text
ANDROID                        BACKEND                         GEMINI
────────────────────           ────────────────────            ────────────────────
Room/domínio: autoridade       autenticação                    recomendação
Context Builder                rate limit e quota              probabilística,
validação semântica final      prompt e promptVersion          nunca autoridade
revisão de rascunho            configuração do modelo
confirmação do usuário         acesso ao provider
persistência do domínio        validação da resposta
                               metadata de uso
```

#### Invariantes da T16.2

1. **A credencial do Gemini é server-only.** Ela não existe no APK, no `BuildConfig`, em resource,
   em DataStore, no Git ou em teste — há teste estrutural nos dois lados.
2. **O contexto vem do Android.** O backend não lê dado sincronizado para montar contexto: não
   existe sync, e analisar sobre estado velho seria pior que não analisar.
3. **O contexto não é persistido.** Ele entra, é usado e vai embora. O SQLite do servidor guarda
   só `ai_usage_daily` — uid, dia (UTC), tipo, contagem e tokens. Nada de prompt, histórico,
   resposta ou texto do usuário.
4. **Uma ação explícita = no máximo uma invocação do modelo.** Sem crítica, reescrita, segunda
   opinião ou retry automático. Toque duplo é barrado no app e, de novo, no servidor.
5. **A quota conta tentativas que chegaram ao provider**, inclusive as que falharam — erro não é
   spam grátis. Requisição inválida é recusada antes, e não consome quota.
6. **O backend nunca decide nem persiste alteração de treino.** Geração continua *draft-first*,
   adaptação continua *confirmation-first*, `EXPLAIN_*` continua read-only e sessão concluída
   continua imutável.
7. **Explicação local suficiente não gera requisição.** Zero HTTP, zero Gemini — a política de
   custo da T14.4 sobreviveu à migração.
8. **Backend fora do ar não quebra o núcleo.** O Coach responde indisponível; treino, execução,
   histórico, templates e gamificação continuam.

### Roadmap

| Fase | Escopo | Estado |
| --- | --- | --- |
| T16.0 | Fundação do backend + contratos de identidade e sync | **implementado** |
| T16.1 | Conta opcional + Firebase Auth | **implementado** |
| T16.2 | Migração do Coach IA para o Spark Backend | **implementado** |
| T16.3 | Identidade global dos dados + Outbox | **implementado** |
| T16.4 | Backup estruturado | **implementado** |
| T16.5 | Restore seguro | **implementado** |
| T16.6 | Sync incremental multi-device | planejado |
| T16.7 | Conflitos, deletes e consistência offline | planejado |
| T16.8 | Hardening, segurança, backup do servidor e observabilidade | planejado |
| T17 | Amigos, convites, desafios e social | planejado |

### Identidade global dos dados e Outbox (T16.3)

> **Status (verificado em 2026-09-06): implementado — e nada é enviado.** Room `version = 31`.
> `syncId` existe nas raízes de agregado pessoais, a Outbox é persistida e transacional, o
> `deviceId` identifica a instalação e os DTOs de agregado existem. **Não existe** push, pull,
> backup, restore, ack, conflito, tombstone remoto, `WorkManager` de sync ou ownership persistido.

#### IMPLEMENTADO na T16.3

- `syncId` nas entidades selecionadas — `workout_programs`, `workout_templates`,
  `workout_sessions`, `body_measurements`, `check_ins` (todas `NOT NULL` + `UNIQUE`) e `exercises`
  quando `isUserCreated = 1` (coluna anulável + `UNIQUE`);
- `deviceId` — UUID aleatório da instalação, no DataStore;
- **Transactional Outbox** — tabela `sync_outbox` no mesmo Room, gravada na mesma transação da
  alteração de domínio;
- fundações de DTO/serialização — envelope com `schemaVersion` por agregado e montador de snapshot
  a partir da identidade global;
- contrato de ownership/adoção — `CloudSyncScope` (`Disabled` / `Preparing` / `Enabled`), com
  `Disabled` como padrão.

#### NÃO IMPLEMENTADO

- upload, download, backup, restore, sync;
- acknowledgment, `revision`, `cursor`, conflitos, tombstone remoto;
- ownership remoto persistido e adoção real de dados locais;
- `WorkManager`, HTTP, polling ou retry para a Outbox;
- qualquer tabela de domínio do Spark no servidor.

#### Operação local

```text
        OPERAÇÃO LOCAL

UI
 ↓
ViewModel / UseCase
 ↓
Repository
 ↓
Room Transaction
 ├── dado de domínio
 └── SyncOutbox  [quando a nuvem estiver associada a uma conta]
 ↓
COMMIT
```

#### O que mudou na T16.4 — e o que continua não existindo

```text
Outbox
 ──→  baseline por snapshot completo   ← existe desde a T16.4 (§ backup, abaixo)
 -X→  Sync Worker                      ← não existe
 -X→  push incremental                 ← não existe (T16.6)
```

O backup **não** consome a Outbox como fila de envio: ele sobe um snapshot completo e, depois da
confirmação do servidor, marca como cobertas as entradas anteriores ao corte. Não há worker, não há
retry automático e não há push por mutação.

#### Agregados, não tabelas

A sincronização não espelha o Room 1:1. A unidade é o agregado, e só a raiz tem identidade global:

```text
WORKOUT_TEMPLATE          WORKOUT_SESSION
└── template exercises    └── exercise sessions
    (ordem + config)          └── set logs
```

Renomear o treino, mover um exercício e mudar a carga de uma série produzem, os três,
`UPSERT WORKOUT_TEMPLATE <syncId>` — não três mutações diferentes.

Tabela completa em
[`docs/architecture/data-classification-matrix.md`](docs/architecture/data-classification-matrix.md#agregados-de-sincronização).

#### Componentes reais

| Papel | Classe / arquivo |
| --- | --- |
| Geração de identidade | `IdGenerator` / `RandomUuidIdGenerator` / `SyncIds` |
| Agregados e operações | `SyncEntityType`, `SyncOperation`, `SyncOutboxStatus` |
| Entrada da Outbox | `SyncOutboxEntryEntity` + `SyncOutboxDao` (tabela `sync_outbox`) |
| Fronteira transacional | `SyncMutationCoordinator` + `SyncMutationScope` + `TransactionRunner` |
| Estado da nuvem | `CloudSyncScope` / `CloudSyncScopeProvider` / `CloudDataBindingScopeProvider` (T16.4 — era `SettingsCloudSyncScopeProvider`, no DataStore, onde nunca chegou a ser gravado) |
| Identidade da instalação | `DeviceIdProvider` (DataStore) |
| Contratos de payload | `com.example.data.sync.dto.*` + `SyncAggregateEnvelope` |
| Montagem de snapshot | `SyncAggregateSnapshotBuilder` |
| Migração | `AppDatabase.MIGRATION_30_31` |

#### Invariantes da T16.3

1. **Room continua sendo a autoridade local.** A Outbox registra intenção; ela não decide nada e
   não é lida pela UI.
2. **A alteração e o registro são atômicos.** Um commit, um rollback. Regra de domínio que rejeita
   a alteração não registra intenção nenhuma.
3. **A UI não conhece a Outbox.** Há teste estrutural sobre `presentation/` e `ui/`.
4. **`syncId` é imutável e nasce offline.** Login, logout e troca de conta não o regeneram; criar e
   editar continuam funcionando sem conta, sem backend e sem internet.
5. **Identidade canônica não é substituída.** Exercício de catálogo continua identificado por
   `canonicalId` e **não** ganha `syncId`.
6. **Login não liga a nuvem.** O padrão é `CloudSyncScope.Disabled`, dado local é `LOCAL_UNOWNED`,
   e nenhuma entrada de Outbox é produzida. A adoção é explícita e aconteceu na T16.4 — continua
   exigindo toque **e** confirmação, e continua sendo o único caminho que dá dono a um dado local.
7. **Troca de conta não transfere nada.** Sair com A e entrar com B deixa identidade e dados
   exatamente como estavam.
8. **Nada sai do aparelho.** Sem worker, sem HTTP, sem retry, sem ack.

#### Contrato de identidade

```text
Firebase UID          → identidade da conta
deviceId              → identidade da instalação
syncId                → identidade global da entidade
clientMutationId      → identidade da alteração
localId               → identidade interna do banco local
canonicalExerciseId   → identidade do exercício canônico
```

Detalhes em
[`docs/architecture/identity-contract.md`](docs/architecture/identity-contract.md).

#### Política de adoção

```text
LOGIN  ≠  ADOTAR DADOS LOCAIS
```

A adoção acontece só quando o usuário ativar a nuvem explicitamente (T16.4): o Spark mostra o que
será associado, o usuário confirma, o snapshot inicial sobe, o ownership remoto nasce e só então a
Outbox passa a operar no escopo daquela conta. É o que evita a Conta B receber dados criados pela
Conta A no mesmo aparelho.

### Backup estruturado (T16.4)

> **Status (verificado em 2026-09-06): implementado.** Room `version = 32`. O Spark envia um
> snapshot completo do estado pessoal ao Spark Backend, que o guarda imutável sob o `uid` do token
> verificado. **Não existe** restore, download do conteúdo, sync incremental, pull, convergência
> multi-device, conflito, tombstone remoto, backup automático nem backup off-site da VPS.

#### Backup não é sincronização

```text
T16.4     Spark Android  ──snapshot completo──▶  Spark Backend  ──▶  SQLite (imutável)

AINDA NÃO EXISTE
          Spark Android  ◀──X──────────────────  Spark Backend
          sem restore · sem download · sem pull · sem merge · sem conflito
```

Cada backup é **autocontido**: ele não depende de backup anterior, de delta, da Outbox nem de
servidor antigo. É isso que permite ao restore da T16.5 pegar um snapshot e reconstruir o estado.

**A T16.4 protege contra a perda do aparelho.** Ela não protege contra a perda da VPS — backup
off-site do servidor é a T16.8, e a UI não promete o que não existe.

#### Adoção explícita: login ≠ adotar

```text
Google login
   ↓
dados continuam locais e sem dono              ← nada mudou
   ↓  o usuário toca "Ativar backup"
o Spark mostra QUANTO será associado
   ↓  o usuário confirma
CloudDataBinding(ownerUid) + snapshot + upload ← a adoção acontece aqui, e só aqui
```

Depois disso: **sair da conta não remove o vínculo**, reiniciar não remove, e entrar com outra
conta **não** o transfere — o resultado é um estado de descompasso em que só a nuvem fica
indisponível. Treino, execução, histórico, templates e gamificação continuam completos.

#### O fluxo, com o corte

```text
Room transaction
 ├── estabelece o vínculo (se ainda não houver dono)
 ├── captura o snapshot consistente
 ├── lê coveredOutboxSequence = MAX(sync_outbox.id)
 └── cria a BackupAttempt (payload já congelado)
COMMIT
 ↓
POST /v1/backups   (Bearer <Firebase ID Token>)
 ↓  o servidor confirma
Room transaction
 ├── marca a tentativa como confirmada
 ├── libera a Outbox com id <= coveredOutboxSequence
 └── registra o último backup (hora do SERVIDOR)
```

Uma alteração feita **durante** o upload recebe `id > coveredOutboxSequence` e continua pendente:
o snapshot não a contém, e alegar o contrário seria perder a alteração. Falha no upload não libera
nada — nem a fila, nem a tentativa, nem dado local.

#### Componentes reais

| Papel | Classe / arquivo |
| --- | --- |
| Contrato compartilhado | [`contracts/backup/v1/`](contracts/backup/v1/README.md) — README + fixtures lidas pelos testes dos dois lados |
| Registry de agregados (app) | `BackupContract` / `BackupEntityType` |
| DTOs do envelope e dos três agregados só-de-backup | `com.example.data.backup.BackupDtos` |
| Forma canônica + SHA-256 | `BackupCanonicalJson` (espelho de `canonical-json.ts`) |
| Vínculo do dataset | `CloudDataBindingEntity` + `CloudDataBindingDao` + `CloudDataBindingScopeProvider` |
| Tentativa durável | `BackupAttemptEntity` + `BackupAttemptDao` (tabela `backup_attempts`) |
| Montagem do snapshot | `BackupSnapshotBuilder` (reusa `SyncAggregateSnapshotBuilder`) |
| Transporte | `BackupApi` / `SparkBackupApi` sobre o `SparkBackendClient` da T16.1 |
| Caso de uso | `BackupRepository` |
| UI | `BackupViewModel` + `BackupSection`, dentro do Perfil |
| Migração local | `AppDatabase.MIGRATION_31_32` |
| Registry, validação e persistência (servidor) | `backend/src/modules/backup/` |
| Migração remota | `backend/migrations/0003_backups.sql` |

#### Formato

```text
backupSchemaVersion    versão do FORMATO DE BACKUP — não é Room, não é /v1, não é entitySchemaVersion
clientBackupId         identidade da tentativa lógica, gerada no aparelho, estável entre reenvios
deviceId               qual instalação produziu o snapshot
capturedAt             relógio do aparelho — INFORMATIVO; quem ordena é o servidor
items[]                { entityType, entitySchemaVersion, syncId, payload }
```

Nove `entityType` no registry fechado: os seis agregados da T16.3 mais `EXERCISE_OVERRIDE`,
`WEEKLY_GOAL` e `USER_PREFERENCES`. Detalhes, identidades derivadas, tetos e erros em
[`contracts/backup/v1/README.md`](contracts/backup/v1/README.md).

#### Hash determinístico entre Kotlin e TypeScript

O `payloadHash` é o SHA-256 de uma **forma canônica do texto**: chaves ordenadas, sem espaço, e
todo token escalar copiado verbatim. A regra do token é o ponto: `Float.toString()` do Kotlin e
`JSON.stringify` do JavaScript não formatam o mesmo número igual, e canonicalizar a partir do valor
faria a idempotência do backup depender de os dois lados imitarem o formatador do outro.

O servidor calcula **o seu próprio** hash e não aceita hash declarado pelo cliente.

#### Endpoints

```text
POST /v1/backups          → 201 (criado) | 200 (mesma tentativa) | 409 (mesmo id, outro conteúdo)
GET  /v1/backups/latest   → 200 metadata | 404 BACKUP_NOT_FOUND
```

Os dois exigem Bearer e o dono sai do token. **Não existe** endpoint de conteúdo: devolver o
snapshot já seria metade do restore, sem a validação, o preview e a escrita transacional que a
T16.5 precisa desenhar.

#### Invariantes da T16.4

1. **Login não adota.** Só toque explícito + confirmação vinculam dado a uma conta.
2. **O escopo é o vínculo, não o `FirebaseUser` atual.** Trocar de conta no aparelho não transfere
   dado; produz descompasso, que bloqueia só a nuvem.
3. **O dono sai do token.** O contrato de backup não tem campo `ownerUid`, e um campo desconhecido
   no corpo é recusado — não ignorado.
4. **A tentativa é imutável.** Um retry manda os mesmos bytes, com o mesmo `clientBackupId`, e o
   servidor devolve o backup que já existia em vez de criar outro.
5. **Nada parcial é persistido.** O servidor valida o snapshot inteiro antes de escrever, e
   snapshot + itens entram na mesma transação.
6. **A Outbox só é liberada depois da confirmação.** Falha mantém tudo; alteração posterior ao
   corte permanece pendente.
7. **Backup só observa.** Histórico concluído, templates, PRs e gamificação não são alterados,
   recalculados nem premiados pela serialização — há teste comparando o antes e o depois.
8. **Nada automático.** Sem `WorkManager`, sem agendador, sem retry automático, sem backup ao abrir
   o app, ao entrar na conta ou ao concluir treino.
9. **Sem mídia e sem segredo.** `content://`, `file://`, Base64 de binário, token, credencial e
   payload de Outbox não entram no snapshot.
10. **Retenção não pode custar o backup novo.** O snapshot é gravado e confirmado **antes** de
    qualquer limpeza; uma limpeza que falhe deixa backup a mais, nunca a menos.

#### Pendência registrada

`EXERCISE_OVERRIDE`, `WEEKLY_GOAL` e `USER_PREFERENCES` entram no snapshot completo e **não**
produzem entrada de Outbox. Enquanto só existir backup completo isso é consistente: todo "Fazer
backup agora" recaptura os três. Quando a T16.6 trouxer push incremental, eles precisam ganhar
mutação própria — senão uma alteração neles deixaria de ser propagada.

### Restore seguro (T16.5)

> **Status (verificado em 2026-09-06): implementado.** Room `version = 33`; backend com a migration
> `0004_backup_payload.sql`. O Spark baixa um snapshot escolhido pelo usuário, verifica, valida,
> mostra o que vai acontecer, pede confirmação e **substitui** o dataset local em uma transação.
> **Não existe** merge, sync incremental, pull automático, push automático, convergência
> multi-device ao vivo, resolução de conflito, tombstone remoto nem sync em background.

#### Restore não é sincronização

```text
T16.4  BACKUP     Android ──snapshot completo──▶ VPS
T16.5  RESTORE    Android ◀──snapshot completo── VPS      somente por ação explícita
T16.6  SYNC       Android ⇄ VPS, incremental              NÃO EXISTE
```

Restore é uma **substituição explícita do dataset local**, não uma união. O que ele faz é
`REPLACE LOCAL DATASET WITH BACKUP`; *keep both*, *last write wins* e merge por campo pertencem à
T16.6/T16.7 e não existem no código — há teste estrutural sobre isso.

#### A ordem, que é o invariante

```text
Conta Spark autenticada
 ↓  listar backups            metadata; nenhum snapshot é baixado
 ↓  escolher um
 ↓  baixar                    arquivo privado do app, com teto de bytes
 ↓  SHA-256                   conferido contra a metadata do servidor
 ↓  versão + schema + semântica
 ↓  RestorePlan  →  preview   contagens reais e avisos
 ↓  CONFIRMAÇÃO EXPLÍCITA     dupla, quando há dado local a perder
 ↓  snapshot de segurança     estado atual, em arquivo privado, sem rede
 ↓  transação Room            apaga o dataset pessoal, insere, vincula, zera a Outbox
 ↓  preferências (DataStore)
COMPLETED
```

O fluxo proibido é o inverso — apagar, baixar, falhar, não ter para onde voltar. **Nada local é
alterado antes da confirmação**, e a confirmação só é oferecida depois de o snapshot inteiro ter
sido baixado, conferido e validado.

#### Componentes reais

| Papel | Classe / arquivo |
| --- | --- |
| Contrato compartilhado | [`contracts/backup/v1/`](contracts/backup/v1/README.md) — **o mesmo** do backup, agora com as rotas de leitura |
| Fronteira HTTP | `RestoreApi` / `SparkRestoreApi` + `SparkBackendClient.getToFile` (download em streaming) |
| Tentativa durável | `RestoreAttemptEntity` + `RestoreAttemptDao` (tabela `restore_attempts`) |
| Fases | `RestorePhase` (`DOWNLOADING` → `VALIDATED` → `SAFETY_SNAPSHOT_CREATED` → `ROOM_APPLIED` → `PREFERENCES_APPLIED` → `COMPLETED`; `ABANDONED` terminal) |
| Integridade | `BackupIntegrityVerifier` (SHA-256 do arquivo baixado) |
| Fronteira de versão | `BackupMigrator` + `RestoreContract.SUPPORTED_BACKUP_SCHEMA_VERSIONS = {1}` |
| Validação integral | `RestoreSnapshotReader` (estrutural + semântica, reusando os DTOs da T16.3/T16.4) |
| Plano e preview | `RestorePlan` + `RestorePlanBuilder` + `RestoreCounts` + `RestoreWarning` |
| Substituição | `RestoreTransaction` + `RestoreDao` (as únicas instruções destrutivas do app) |
| Proteção local | `RestoreSafetySnapshotStore` + `RestoreFileStore` (armazenamento **privado**) |
| Caso de uso e recuperação | `RestoreRepository` (`prepare` / `confirm` / `discard` / `recover`) |
| Concorrência | `CloudOperationLock`, compartilhada com o backup |
| UI | `RestoreViewModel` + `RestoreSection`, dentro do Perfil |
| Migração local | `AppDatabase.MIGRATION_32_33` |
| Leitura no servidor | `GET /v1/backups`, `GET /v1/backups/{id}`, `GET /v1/backups/{id}/content` |
| Migração remota | `backend/migrations/0004_backup_payload.sql` (guarda o documento canônico) |

#### O que o servidor passou a guardar — e por quê

A T16.4 guardava a metadata e os itens, e isso bastava para o backup. Não bastava para o restore: o
`payloadHash` é o SHA-256 **daquele texto**, e remontar o documento a partir das colunas seria uma
segunda canonicalização — um segundo lugar capaz de divergir do primeiro, justamente no ponto em
que a divergência aparece como "backup corrompido" no aparelho de um usuário.

Então `backup_snapshots.payload` guarda o texto exato, e o download o devolve verbatim. Snapshots
criados antes disso continuam válidos como backup e recusam o download com
`BACKUP_CONTENT_UNAVAILABLE` — dizer a verdade sobre o que não dá para restaurar é melhor do que
devolver uma reconstrução que talvez não feche o hash.

#### Regras de conta

```text
dataset sem dono   + conta A  →  restore de A permitido  →  o dataset passa a ser de A
dataset de A       + conta A  →  restore de A permitido  →  continua de A
dataset de A       + conta B  →  BLOQUEADO (ACCOUNT_MISMATCH), nada muda
sem sessão                     →  AUTH_REQUIRED; nem lista, nem baixa
conta muda entre preview e aplicação →  ACCOUNT_CHANGED, zero alteração local
```

O vínculo nasce **no commit** da substituição, nunca antes: um vínculo gravado antes de a aplicação
dar certo apontaria a conta nova para o dataset velho. E ele registra o backup restaurado como
*última cópia conhecida* — não como backup novo: nada sobe, nenhuma tentativa de backup nasce.

Rebind (transformar um dataset de A em dataset de B) continua **fora de escopo**: é política de
troca de conta, não efeito colateral de um restore.

#### Recuperação de processo

A fase persistida é a autoridade sobre "o restore terminou?".

```text
DOWNLOADING / VALIDATED / SAFETY_SNAPSHOT_CREATED  → nada aplicado    → encerrar
ROOM_APPLIED                                        → dado substituído → retomar (preferências)
ROOM_APPLIED sem o snapshot baixado                 → não dá retomar   → desfazer pelo snapshot
                                                                          de segurança
PREFERENCES_APPLIED                                 → concluir
```

A recuperação roda na **abertura do app**, antes das reconciliações de gamificação e da importação
de catálogo: estado derivado de um dataset em transição seria derivado do estado errado. Ela não é
um restore automático — só termina o que o usuário já confirmou. Enquanto houver pendência, nenhuma
tela diz "restaurado" e um novo restore é recusado.

#### Gamificação depois de um restore

XP, conquistas, recordes e eventos são **derivados** (matriz de dados, Grupo B) e não viajam no
backup. A transação limpa essas tabelas junto com o histórico que elas descreviam — inclusive
porque as `dedupeKey` daqueles eventos citam `localId` de sessões que o restore regenerou — e as
reconciliações que já rodam na abertura (`XpReconciler`, `AchievementReconciler`,
`MissionReconciler`) as reconstroem pelas regras vigentes.

**O restore não premia nada**: ele escreve por DAO, fora do `WorkoutEngine`, e nenhum evento é
publicado. Há teste verificando zero XP, zero conquista e zero recorde depois de restaurar um
histórico inteiro.

#### Invariantes da T16.5

1. **Nada local muda antes da confirmação.** Download, hash, validação e preview acontecem com o
   banco intacto — e uma recusa em qualquer ponto deixa o aparelho exatamente como estava.
2. **Hash divergente bloqueia.** HTTPS protege o caminho; ele não diz nada sobre o arquivo depois
   de escrito, sobre um proxy que reempacota resposta ou sobre um servidor que devolveu o snapshot
   errado.
3. **Versão desconhecida não é interpretada.** `backupSchemaVersion` acima do suportado é recusada
   sem tentativa de leitura, e `entitySchemaVersion` desconhecida também.
4. **Não existe restore parcial.** Um agregado inválido entre cem válidos recusa o restore inteiro.
5. **Sem *fuzzy matching*.** Identidade que não bate, referência que não fecha ou `canonicalId`
   ausente no catálogo local são recusas — nunca "provavelmente era este exercício".
6. **O snapshot de segurança existe antes da mutação.** Ele é local, nunca é enviado ao servidor, e
   some quando a tentativa termina.
7. **Room é transacional.** Limpeza e inserção têm um commit e um rollback; falhar no meio devolve
   o dataset inteiro.
8. **A Outbox não vira replay.** Restaurar não gera mutação por item, e a fila anterior só é
   substituída **dentro** do commit — nunca antes.
9. **Catálogo canônico não é apagado.** O restore substitui dado pessoal; conteúdo do app vem do
   manifesto.
10. **`syncId` é a identidade; `localId` é novo.** Relações são reconstruídas por identidade
    portátil, e nada depende do `localId` do aparelho de origem.
11. **Histórico não é recalculado.** Duração, carga, repetições e horários voltam como estavam.
12. **Restore não cria backup, não apaga o snapshot remoto e não liga sync.**

#### O que continua não existindo

```text
✗ merge / keep both / last write wins / field-level merge
✗ sync incremental (push, pull, cursor, serverRevision)
✗ pull automático, push automático, sync em background, WorkManager
✗ resolução de conflito e tombstone remoto
✗ rebind de dataset entre contas
✗ upload/download de mídia
✗ backup automático
```

### Conta opcional e identidade (T16.1)

> **Status (verificado em 2026-09-06): implementado.** Tudo desta seção existe no código e é
> coberto por teste offline. `syncId`, `deviceId` e a outbox passaram a existir na **T16.3** — sem
> mudar nada desta seção. O que **não** existe: sincronização, backup, restore e qualquer
> persistência de usuário no servidor.

A conta **adiciona capacidades online**. Ela não desbloqueia o funcionamento básico: sem conta,
abrir o app, criar, editar, iniciar e concluir treino, histórico, gamificação e dados locais
continuam completos.

#### A fronteira

```text
┌──────────────────┐
│ Google Account   │
└────────┬─────────┘
         ↓  Credential Manager (Sign in with Google)
┌──────────────────┐
│ Firebase Auth    │   ← autoridade da sessão, no aparelho
└────────┬─────────┘
         ↓  Firebase ID Token  (obtido sob demanda, nunca persistido)
┌──────────────────┐
│ Spark Backend    │
│ Firebase Admin   │   ← verifyIdToken: assinatura, emissor, audiência, expiração
└────────┬─────────┘
         ↓
AuthenticatedPrincipal
         │
         └── uid
```

#### Componentes reais

| Papel | Classe / arquivo |
| --- | --- |
| Fronteira de identidade (Android) | `AuthGateway` / `FirebaseAuthGateway` |
| Estado explícito | `AuthState` (`SignedOut`, `SigningIn`, `SignedIn`, `SigningOut`, `Error`) |
| Conta no domínio | `SparkAccount` (só `uid` é obrigatório) |
| Token sob demanda | `AuthTokenProvider` / `AuthTokenResult` |
| Transporte autenticado | `SparkAuthInterceptor` + `SparkBackendClient` |
| Web Client ID | `GoogleServerClientId` (recurso `default_web_client_id`, do `google-services.json`) |
| UI | `AccountViewModel` + `AccountSection`, dentro do Perfil |
| Verificação (backend) | `AuthTokenVerifier` / `FirebaseAuthTokenVerifier` |
| Proteção de rota | `BearerAuthGuard` + `@Principal()` |
| Identidade interna | `AuthenticatedPrincipal { uid, email?, provider? }` |
| Endpoint | `GET /v1/auth/me` → `{ "uid": ... }` |

#### Invariantes da T16.1

1. **Login começa por ação explícita.** Abrir o app, abrir o Perfil, iniciar treino ou abrir o
   Coach nunca abrem o seletor de contas. Restaurar uma sessão que já existe, sim, é automático.
2. **Entrar e sair não tocam em dado local.** A fronteira de autenticação não conhece Room, DAO,
   repositório nem `SettingsManager` — não é disciplina, é ausência de dependência, e há teste
   estrutural e comportamental para os dois lados.
3. **Troca de conta não reassocia nada.** Sair com o usuário A e entrar com o B deixa o banco
   exatamente como estava. Continua valendo na T16.3, agora com `syncId` no meio: a identidade das
   entidades não é regenerada e nenhum dado ganha dono. Associar dado local a uma conta é a adoção
   explícita da T16.4.
4. **O ID Token não é persistido nem registrado.** Ele é pedido ao Firebase a cada requisição e
   usado na hora. Não existe refresh token, JWT ou sessão do Spark: o backend verifica, não emite.
5. **O servidor deriva o `uid` do token.** Query string, header próprio e corpo da requisição não
   influenciam a identidade — há teste que tenta os três.
6. **Indisponibilidade não apaga identidade.** Backend fora do ar responde `Unavailable`; a UI
   continua `SignedIn`, porque quem decide isso é o Firebase Auth local, não o servidor.
7. **App Check continua como estava.** Ele atesta o **app**; Firebase Auth identifica o
   **usuário**. São responsabilidades diferentes e nenhuma substitui a outra.

#### O que o Firebase UID **não** é

```text
Firebase UID != localId               (identidade de linha no Room, por aparelho)
Firebase UID != syncId                (identidade global de entidade pessoal — T16.3, existe)
Firebase UID != canonicalExerciseId   (identidade de conteúdo do catálogo)
Firebase UID != deviceId              (identidade da instalação — T16.3, existe)
```

O UID responde **quem é o usuário**, e nada além disso. Ver
[`docs/architecture/identity-contract.md`](docs/architecture/identity-contract.md).

#### Configuração

Nenhum client ID, chave ou credencial vive no código. O Web Client ID vem do
`app/google-services.json` (não versionado) pelo recurso gerado `default_web_client_id`; a
credencial do Admin SDK vem do caminho em `GOOGLE_APPLICATION_CREDENTIALS`, montado somente-leitura
na VPS. Passo a passo em [`docs/FIREBASE_AUTH_SETUP.md`](docs/FIREBASE_AUTH_SETUP.md).

#### Exclusão de conta — pendência registrada

Não implementada, e deliberadamente. Quando existir dado online, apagar a conta precisará coordenar
Firebase, Spark Backend, backup, mídia e social; um `FirebaseUser.delete()` isolado hoje criaria um
fluxo incompleto. Fica como **requisito pré-release da fase de hardening (T16.8)**.

### Documentação detalhada

- [`docs/architecture/ADR-0001-spark-online-architecture.md`](docs/architecture/ADR-0001-spark-online-architecture.md)
- [`docs/architecture/data-classification-matrix.md`](docs/architecture/data-classification-matrix.md)
- [`docs/architecture/identity-contract.md`](docs/architecture/identity-contract.md)
- [`docs/architecture/sync-protocol.md`](docs/architecture/sync-protocol.md)
- [`docs/FIREBASE_AUTH_SETUP.md`](docs/FIREBASE_AUTH_SETUP.md)
- [`backend/README.md`](backend/README.md)
