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

> **Status (verificado em 2026-09-05): implementado.** Diferente das seções 4, 7, 8 e 9, tudo
> descrito aqui existe no código. O provider é Firebase AI Logic + Gemini Developer API, e ele é
> opcional: sem configuração válida o Coach responde `UNAVAILABLE` e o restante do Spark continua
> funcionando offline.

### 15.1 Fluxo canônico

```text
Autoridades locais (WorkoutDao/Room, catálogo canônico, SettingsManager)
  -> Context Builder do tipo de request
  -> Use Case (Analyze / Generate / Adapt / Explain)
  -> AiCoachGateway
  -> Firebase AI Logic -> Gemini
  -> Structured Output (AiCoachResponseSchema)
  -> AiCoachResponseValidator  (validação semântica determinística)
  -> Advice / Draft / Explanation
  -> confirmação explícita do usuário
  -> WorkoutRepository (o mesmo caminho da criação/edição manual)
```

O modelo é **consumidor e proponente**, nunca fonte de verdade. Structured output garante a
forma; o validador garante a semântica. Uma violação invalida a resposta inteira — nada é
adivinhado, corrigido por aproximação ou resolvido por nome.

### 15.2 Componentes reais

| Papel | Classe |
| --- | --- |
| Fronteira com o provider | `AiCoachGateway` / `FirebaseAiCoachGateway` |
| Configuração (modelo, versões, tetos) | `AiModelConfig` |
| Prompts (único lugar do app com prompt) | `AiCoachPrompt` |
| Schemas de saída | `AiCoachResponseSchema` |
| Validação semântica | `AiCoachResponseValidator` |
| Contexto de análise | `AiCoachContextBuilder` / `WorkoutAiCoachContextBuilder` |
| Contexto de geração | `AiWorkoutGenerationContextBuilder` / `WorkoutAiGenerationContextBuilder` |
| Contexto de adaptação | `AiWorkoutAdaptationContextBuilder` / `WorkoutAiAdaptationContextBuilder` |
| Contexto de explicação | `AiCoachExplanationContextBuilder` |
| Recorte do catálogo | `ExerciseCandidateBuilder` |
| Teto de evidência | `AiDataQualityPolicy` |
| Observabilidade | `AiCoachTelemetry` / `LogcatAiCoachTelemetry` |
| App Check por variante | `AiCoachAppCheck` (`src/debug` e `src/release`) |

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

Autoridade única em `AiModelConfig`:

- `MODEL_NAME` — o único lugar do app onde existe nome de modelo;
- `PROMPT_VERSION` — versão dos prompts; suba a cada mudança de instrução ou de formato;
- `SCHEMA_VERSION` + `SUPPORTED_SCHEMA_VERSIONS` — versão do contrato de conversa. Uma versão
  desconhecida é recusada no gateway, antes de qualquer chamada.

Toda chamada carrega `requestId`, `schemaVersion` e `promptVersion` no prompt, e registra
`requestId`, tipo, modelo, `promptVersion`, `schemaVersion`, duração e classe do resultado na
telemetria.

### 15.5 Política de contexto

Regra: **o menor contexto suficiente**, nunca "todo dado disponível do usuário". Gamificação
(XP, nível, streak, conquistas, missões) e medidas corporais não entram em nenhum contexto de
análise, geração ou adaptação; os números de progressão só aparecem, prontos, no contexto de
`EXPLAIN_PROGRESS`. Os tetos vivem em `AiModelConfig` (histórico por exercício, sessões
percorridas, exercícios, PRs, candidatos, candidatos por grupo, grupos de foco).

### 15.6 Custo e resiliência

- Nenhuma chamada acontece em `init`, ao abrir tela ou em recomposição — só em ação explícita.
- Enquanto uma chamada está em andamento, novos toques são ignorados (um request por pedido).
- Não existe retry automático, polling nem chamada em background.
- `RATE_LIMITED` não convida a repetir; `TIMEOUT` permite nova tentativa manual.
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
- A configuração do Firebase vem de `app/google-services.json` (não versionado). Nenhuma chave
  vive no código.

### 15.8 Suíte de avaliação

`app/src/test/java/com/example/domain/ai/eval/` contém a suíte determinística
(`AiCoachEvaluationSuiteTest` + cenários), os testes de prompt injection, de versionamento, de
observabilidade e de configuração de segurança. Ela roda **offline**, com `FakeAiCoachGateway`,
e não consome cota. Avaliação com provider real é opt-in e instrumentada
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

> **Status (verificado em 2026-09-06): fundação implementada, features online não.** A T16.0 criou
> o backend em `backend/` com configuração, SQLite, migrations, health, logging, Docker e os
> contratos arquiteturais. **Não existe** autenticação, sincronização, backup, restore, outbox,
> `syncId` nas entidades Room ou proxy do Gemini. `/v1` está vazio, e há teste que garante isso.

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

### Coach IA

O `FirebaseAiCoachGateway` (seção 15) **permanece o gateway em uso**, inalterado. A migração para
`SparkBackendAiCoachGateway` está prevista para a **T16.2** e não foi iniciada. App Check, Firebase
AI Logic e a configuração do Gemini continuam como estão.

### Roadmap

| Fase | Escopo | Estado |
| --- | --- | --- |
| T16.0 | Fundação do backend + contratos de identidade e sync | **implementado** |
| T16.1 | Conta opcional + Firebase Auth | planejado |
| T16.2 | Migração do Coach IA para o Spark Backend | planejado |
| T16.3 | Identidade global dos dados + Outbox | planejado |
| T16.4 | Backup estruturado | planejado |
| T16.5 | Restore seguro | planejado |
| T16.6 | Sync incremental multi-device | planejado |
| T16.7 | Conflitos, deletes e consistência offline | planejado |
| T16.8 | Hardening, segurança, backup do servidor e observabilidade | planejado |
| T17 | Amigos, convites, desafios e social | planejado |

### Documentação detalhada

- [`docs/architecture/ADR-0001-spark-online-architecture.md`](docs/architecture/ADR-0001-spark-online-architecture.md)
- [`docs/architecture/data-classification-matrix.md`](docs/architecture/data-classification-matrix.md)
- [`docs/architecture/identity-contract.md`](docs/architecture/identity-contract.md)
- [`docs/architecture/sync-protocol.md`](docs/architecture/sync-protocol.md)
- [`backend/README.md`](backend/README.md)
