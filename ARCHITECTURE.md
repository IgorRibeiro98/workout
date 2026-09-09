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

> **Status (verificado em 2026-09-07): T16.0 a T16.7.1 implementadas.** A T16.0 criou o backend em `backend/` com
> configuração, SQLite, migrations, health, logging, Docker e os contratos arquiteturais. A T16.1
> acrescentou **conta opcional**: Firebase Auth com Sign in with Google no Android, verificação de
> Firebase ID Token no backend e `GET /v1/auth/me`. A T16.2 migrou o **Coach IA**:
> `POST /v1/ai/coach`, prompt/modelo/credencial server-side, quota e validação no servidor. A T16.3
> acrescentou **identidade global dos dados e a Outbox transacional** no Android. A T16.4
> acrescentou **backup estruturado**: adoção explícita do conjunto de dados por uma Conta Spark,
> snapshot completo, `POST /v1/backups` e `GET /v1/backups/latest`. A T16.5 acrescentou **restore
> seguro**: descoberta (`GET /v1/backups`), download do conteúdo
> (`GET /v1/backups/{id}/content`), verificação de integridade, validação integral, preview,
> confirmação explícita, snapshot de segurança local e substituição transacional do dataset. A
> T16.6 acrescentou **sincronização incremental multi-device**: `POST /v1/sync/push` e
> `GET /v1/sync/pull`, estado remoto por agregado com `serverRevision`, ledger de idempotência,
> change log append-only com sequência global, cursor durável no Android, apply transacional e
> conflitos detectados e preservados. A T16.7 acrescentou **resolução explícita de conflito,
> exclusão versionada e tombstone**: o usuário escolhe, a exclusão propaga pelo change log e a
> identidade morta não ressuscita. A T16.7.1 fechou o bloco: **`GET /v1/sync/entities/...`**, a
> leitura somente-leitura que confirma o estado remoto antes de "usar a versão da nuvem"
> sobrescrever o local, com a conta revalidada **depois** da resposta — e o **CI do Android**
> (`.github/workflows/android.yml`).
> **Não existe** merge por campo, *last write wins*, CRDT, resolução automática em background,
> realtime/WebSocket, limpeza de tombstone, backup automático ou backup off-site da VPS.

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
| T16.6 | Sync incremental multi-device | **implementado** |
| T16.7 | Conflitos, deletes, tombstones e consistência offline | **implementado** |
| T16.7.1 | Fechamento técnico: confirmação do estado remoto, CI do Android, documentação | **implementado** |
| T16.8 | Hardening, segurança, backup do servidor e observabilidade | **implementado** (produção NOT VERIFIED) |
| T17.0 | Fundação social: identidade pública e privacidade | **implementado** |
| T17.1 | Amigos, convites por código e QR Code | **implementado** |
| T17.2 | Perfil social e compartilhamento controlado de progresso | **implementado** (1 de 4 métricas projetável — ver §18) |
| T17.3 | Desafios entre amigos: pontuação canônica e consentimento próprio | **implementado** |
| T17.4 | Atividade dos amigos e rankings contextuais | **implementado** |
| T17.5 | Notificações sociais com Firebase Cloud Messaging | **implementado** |

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

#### O que a T16.3 deliberadamente não fez — e onde cada item foi feito depois

Esta lista é o **escopo da T16.3**, não o estado do projeto. Ao final dela nada saía do aparelho; o
que faltava foi entregue nas fases seguintes, e o link diz onde:

| O que faltava na T16.3 | Onde existe hoje |
| --- | --- |
| upload, download | **T16.4** (backup) e **T16.5** (restore) |
| sync, acknowledgment, `revision`, `cursor` | **T16.6** (§ sync incremental, abaixo) |
| conflitos resolvidos, tombstone remoto | **T16.7** (§ conflitos e tombstones, abaixo) |
| confirmação do estado remoto antes de sobrescrever o local | **T16.7.1** |
| ownership remoto persistido, adoção real | **T16.4** (`cloud_data_binding`) |
| `WorkManager` para a Outbox | **T16.6** — trabalho **único**, com rede e backoff. Continua sem `PeriodicWorkRequest`, sem polling e sem retry automático de recusa |

O que continua **não** existindo, por decisão: qualquer tabela de domínio do Spark espelhada no
servidor. O schema remoto guarda agregados como payload versionado, e não uma cópia do Room.

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
 ──→  push incremental + ack           ← existe desde a T16.6 (§ sync, abaixo)
 ──→  trabalho único do WorkManager    ← existe desde a T16.6, com rede e backoff
 -X→  trabalho periódico / polling     ← não existe, e há teste estrutural
```

O backup **não** consome a Outbox como fila de envio: ele sobe um snapshot completo e, depois da
confirmação do servidor, marca como cobertas as entradas anteriores ao corte. Quem consome a fila
como fila é o push da T16.6 — e ele também só a libera depois da confirmação.

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
backup agora" recaptura os três.

**Nem a T16.6 nem a T16.7 fecharam esta pendência**, e a decisão está documentada com o motivo de
cada um em
[`sync-protocol.md`](docs/architecture/sync-protocol.md): a customização de exercício é escrita hoje
direto pelo DAO a partir de um ViewModel, a meta semanal é derivada de uma preferência do DataStore,
e as preferências vivem no DataStore, que não participa da transação Room. O servidor os recusa com
`UNSUPPORTED` em vez de aceitá-los pela metade, e o snapshot completo continua cobrindo os três.
Consequência aceita: uma alteração nesses três propaga por **backup completo**, não por sync
incremental. A T16.7 é sobre conflito e exclusão — trazer os três para o incremental é mudar **onde
a escrita nasce**, e continua endereçado a uma tarefa própria.

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
T16.6  SYNC       Android ⇄ VPS, incremental              existe — e é outra coisa
```

Restore é uma **substituição explícita do dataset local**, não uma união. O que ele faz é
`REPLACE LOCAL DATASET WITH BACKUP`; *keep both*, *last write wins* e merge por campo pertencem à
T16.7 e continuam não existindo no código — há teste estrutural sobre isso, e a T16.6 **não** os
introduziu: ela detecta conflito e preserva os dois lados sem escolher.

**O restore zera o estado de sync no mesmo commit** (T16.6): revision conhecida, cursor e conflitos
descreviam o dataset que acabou de ser substituído. O cursor volta ao começo de propósito — um
dataset restaurado não carrega revision nenhuma, e reler o change log é como ele as aprende, de
forma idempotente.

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

#### O que continua não existindo **no restore**

```text
✗ merge / keep both / last write wins / field-level merge
✗ rebind de dataset entre contas
✗ upload/download de mídia
✗ restore automático (login, abertura de tela, WorkManager)
```

Sync incremental — push, pull, cursor, `serverRevision` e trabalho em background — passou a existir
na **T16.6**, em `com.example.data.sync`, e continua sendo uma coisa **diferente** do restore. Ver
a seção abaixo.

### Sync incremental multi-device (T16.6)

> **Status (verificado em 2026-09-07): implementado.** Room `version = 35`; backend com as
> migrations `0005_sync.sql` e `0006_sync_tombstones.sql`. Dois aparelhos da mesma Conta Spark
> convergem sem que o usuário exporte nada, exclusões propagam com tombstone e um conflito é
> resolvido por escolha explícita dele (§ T16.7, abaixo). **Não existe** merge por campo, *last
> write wins*, CRDT, realtime, WebSocket ou push do servidor.

#### Sync não é backup, e não substitui nada

```text
T16.4  BACKUP     Android ──snapshot completo──▶ VPS       imutável, autocontido, sob demanda
T16.5  RESTORE    Android ◀──snapshot completo── VPS       substituição explícita do dataset
T16.6  SYNC       Android ⇄ mudanças ⇄ VPS                 convergência incremental
```

Os três coexistem e resolvem problemas diferentes. Um snapshot imutável é um ponto no tempo ao qual
dá para voltar; o sync converge cópias vivas. Fundir os dois custaria as duas coisas — e há teste
estrutural nos dois sentidos: o pacote de backup não fala o vocabulário do sync, e o pacote de sync
não cria backup.

#### O fluxo, e o que ele nunca inverte

```text
DEVICE A                                   DEVICE B
ação do usuário
   ↓
domínio → Room  ← a UI observa daqui, imediatamente
   ↓
Outbox (mesma transação)
   ↓
push ──────────▶ Spark Backend
                 ├── sync_entities   estado atual por agregado + serverRevision
                 ├── sync_mutations  ledger de clientMutationId
                 └── sync_changes    log append-only, sequência global
                              │
                              └──── pull ──────▶ validação
                                                    ↓
                                                 Room (transação)
                                                    ↓
                                                 UI observa
```

O fluxo proibido é `editar → esperar HTTP → salvar`. Ele não existe em caminho nenhum: Room continua
sendo a autoridade operacional do aparelho, e o servidor é autoridade **de ordem** — `revision`,
`serverSequence`, convergência — nunca de execução de treino.

#### Componentes reais

| Papel | Classe / arquivo |
| --- | --- |
| Contrato (Kotlin ⇄ TypeScript) | `SyncProtocol` + `sync.contract.ts` — e [`contracts/sync/v1/`](contracts/sync/v1/README.md) |
| Estado remoto | `sync_entities` (migration `0005_sync.sql`) |
| Ledger de idempotência | `sync_mutations`, único em `(owner_uid, client_mutation_id)` |
| Change log | `sync_changes`, `server_sequence` `AUTOINCREMENT` |
| Validação de push | `sync.validator.ts` + **o mesmo** `BackupEntityRegistry` do backup |
| Decisão por mutação | `SyncService` (backend) |
| Proteção por conta | `SyncRateLimiter` (60 req/min por uid) |
| Revision conhecida (Android) | `EntitySyncMetadataEntity` + `EntitySyncMetadataDao` |
| Cursor durável (Android) | `SyncCursorEntity` + `SyncCursorDao`, chave `ownerUid` |
| Conflito preservado | `SyncConflictEntity` + `SyncConflictKind` |
| Lote de push | `SyncPushBuilder` (coalescência no envio, `baseRevision`) |
| Apply remoto | `SyncRemoteApplier` (transação, ordem de dependência, sem Outbox) |
| Ciclo | `SyncRepository` (push → ACK → pull → apply → cursor) |
| Gatilhos e estado | `SyncCoordinator` + `SyncScheduler` |
| Agendamento | `SparkSyncWorker` + `WorkManagerSyncScheduler` (`service/`) |
| Transporte | `SyncApi` / `SparkSyncApi` sobre o `SparkBackendClient` da T16.1 |
| Tela | `SyncViewModel` + `SyncSection` (Perfil → Conta Spark) |

#### Invariantes da T16.6

1. **Só dataset adotado sincroniza, e só com a conta dona.** Sem `cloud_data_binding` o ciclo nem
   consulta a sessão; com a conta errada conectada o resultado é `AccountMismatch`, e nada sobe nem
   desce. Login continua não ligando nada.
2. **O dono vem do token.** O corpo do push não tem campo de dono, e o envelope estrito recusa um
   se ele aparecer. `deviceId` é metadado e não autoriza nada.
3. **Room continua sendo a autoridade operacional.** A escrita local acontece primeiro e a UI a
   observa; o servidor nunca está no caminho de salvar um treino.
4. **A Outbox só é liberada com confirmação.** Erro de rede, 5xx, 429 e resposta perdida deixam a
   fila intacta — e o reenvio é seguro pelo `clientMutationId`.
5. **Escrita stale não sobrescreve nada.** Nem o remoto (o servidor recusa), nem o local (o app
   preserva). A revision conhecida **não** é atualizada por um `STALE`.
6. **Conflito é detectado, isolado e preservado.** Um agregado em conflito não impede os outros de
   convergirem, e os dois lados ficam guardados até a decisão explícita do usuário (T16.7).
7. **Histórico concluído é imutável.** `revision = 1` e nunca mais; conteúdo divergente é conflito
   de integridade dos dois lados.
8. **O cursor só avança depois do apply**, na mesma transação. Uma mudança que o app não sabe ler
   pausa o sync ali, sem perder nada.
9. **O apply remoto não gera Outbox.** Sem laço — há teste estrutural.
10. **Sem efeito colateral de domínio.** Receber uma sessão concluída não dá XP, conquista, recorde
    nem notificação.
11. **Sem *fuzzy matching*.** `canonicalId` que não resolve pausa o sync; nunca vira "o exercício
    mais parecido".
12. **Nada é periódico.** Um trabalho único, com rede e backoff, disparado por alteração local;
    foreground conservador; toque manual. Sem polling, sem WebSocket, sem push do servidor.
13. **A `UPSERT` anterior a um `DELETE` nunca é enviada em lugar dele.** Convertê-la ressuscitaria
    no servidor o que o usuário apagou. A T16.6 mantinha a exclusão pendente; a T16.7 a propaga
    com tombstone — e essa regra continua valendo.
14. **Treino em execução não é alterado por baixo.** Uma mudança remota no template que está sendo
    executado é **adiada** até a sessão terminar; o histórico já gravado nunca é afetado, porque
    `exercise_sessions` e `set_logs` carregam os próprios snapshots desde a T16.3.

#### O que a T16.6 deixou para a T16.7 — e o que foi fechado

```text
✓ resolução de conflito e UI de escolha entre as duas versões
✓ propagação de exclusão, tombstone e retenção de tombstone
✓ prevenção de ressurreição
✓ política por tipo de agregado (registry, sem default)
✓ CURSOR_EXPIRED + orientação de rebaseline
✗ EXERCISE_OVERRIDE, WEEKLY_GOAL e USER_PREFERENCES no incremental   ← continua aberto
✗ compactação do change log                                          ← deliberadamente não feita
```

### Conflitos, exclusão e tombstones (T16.7)

> **Status (verificado em 2026-09-07): implementado.** Room `version = 35`
> (`sync_conflicts.status`); backend com `0006_sync_tombstones.sql`. Um conflito é resolvido pelo
> **usuário**, e uma exclusão viaja como mudança versionada. **Não existe** merge por campo,
> resolução automática, CRDT, `force`/`overwrite` no servidor, nem limpeza de tombstone.

#### Conflito é um estado, não um erro

```text
A e B na revision 4
     ↓
A edita  →  servidor revision 5
B edita sobre 4  →  STALE
     ↓
sync_conflicts (durável)   ← local no Room + Outbox BLOCKED, remoto guardado com payload e hash
     ↓
decisão do usuário
 ├── "Manter deste aparelho"  →  mutação NOVA, baseRevision = 5  →  servidor 6
 └── "Usar versão da nuvem"   →  aplica local, descarta a tentativa, ZERO mutação
```

Nada disso acontece sozinho. Um ciclo de sync com conflito pendente continua sincronizando **todos
os outros agregados** e não toca no conflitado; o trabalho em background nunca resolve.

#### Matriz de política por agregado

Declarada em `SyncEntityPolicies` (Kotlin) e `SyncEntityPolicyRegistry` (`sync.policy.ts`) — os dois
precisam concordar, e nenhum tem `default`.

| Agregado | Mutável | Delete remoto | Conflito | Resolução |
| --- | --- | --- | --- | --- |
| `WORKOUT_PROGRAM` | sim | sim | edição concorrente | `USER_CHOICE` |
| `WORKOUT_TEMPLATE` | sim | sim | edição concorrente | `USER_CHOICE` |
| `CUSTOM_EXERCISE` | sim | sim | edição concorrente | `USER_CHOICE` |
| `BODY_MEASUREMENT` | sim | sim | edição da **mesma** medida | `USER_CHOICE` |
| `CHECK_IN` | sim | **não** | edição concorrente | `USER_CHOICE` |
| `WORKOUT_SESSION` (`COMPLETED`) | **não** | sim | divergência de histórico | `IMMUTABLE_CONFLICT` |

Notas que a tabela não cabe:

- **medida corporal é *append-only* por identidade.** Cada registro tem `syncId` próprio, então dois
  aparelhos criando medidas no mesmo dia **coexistem** — isso nunca é conflito. Conflito é editar a
  mesma medida nos dois;
- **check-in não aceita exclusão remota** porque o domínio não a produz: não há tela, repositório
  nem mutação que apague um check-in. O servidor recusa com `DELETE_NOT_ALLOWED`;
- **sessão concluída é imutável e ainda assim excluível.** Apagar não é reescrever;
- `LAST_WRITE_WINS_ALLOWED` **existe como valor declarável e nenhum agregado o usa** — teste dos
  dois lados.

#### Tipos de conflito

| `SyncConflictKind` | Quando |
| --- | --- |
| `STALE_LOCAL_CHANGE` | o push local foi recusado: o servidor já estava adiante |
| `REMOTE_AHEAD_LOCAL_DIRTY` | chegou mudança remota para um agregado com alteração local pendente |
| `REMOTE_DELETED_LOCAL_MODIFIED` | a nuvem tem tombstone e este aparelho tem alteração pendente |
| `LOCAL_DELETED_REMOTE_MODIFIED` | este aparelho apagou e a nuvem tem versão mais nova |
| `IMMUTABLE_HISTORY` | mesma sessão concluída, conteúdo divergente |
| `REJECTED_BY_SERVER` | recusa de contrato — defeito, não divergência entre pessoas |
| `IDEMPOTENCY` | mesmo `clientMutationId`, alvo ou conteúdo outro |

Os dois primeiros dizem **onde** a divergência foi detectada e recebem as mesmas escolhas. Uma
classificação já registrada é preservada, exceto quando a nova é uma exclusão: um tombstone muda o
que o conflito **é**.

#### Exclusão e tombstone

```text
Template X revision 5
     ↓ DELETE baseRevision = 5
sync_entities.deleted = 1, revision 6      ← uma autoridade só: a própria linha da entidade
     +
sync_changes operation = DELETE, revision 6, payload null
     ↓ pull
Device B: apaga localmente, guarda a revision, NENHUMA Outbox
```

- exclusão **gasta revision** e entra no change log como qualquer mudança;
- exclusão **stale** é `STALE`, nunca exclusão por cima de algo mais novo;
- exclusão de identidade que o servidor nunca viu **também** cria tombstone — dois aparelhos podem
  ter o mesmo `syncId` vindo do mesmo backup restaurado;
- `UPSERT` contra tombstone é `REMOTE_DELETED`, **sempre**, inclusive com a `baseRevision` do
  próprio tombstone. A garantia é do banco (`AND sync_entities.deleted = 0`), não do serviço;
- **recriar é criar**: manter um item que a nuvem apagou produz `syncId` novo. Não é oferecido para
  programa nem exercício pessoal, porque outros agregados os referenciam por `localId`;
- o apply remoto respeita as guardas do domínio: `ON DELETE RESTRICT` de exercício pausa o cursor
  com motivo, programa com alteração pendente dentro vira conflito, e template em execução é
  **adiado** — a sessão em andamento nunca é destruída;
- **nada apaga tombstone.** `SYNC_TOMBSTONE_RETENTION_DAYS` declara a retenção pretendida; a limpeza
  não existe, e o motivo é o custo assimétrico: guardar custa uma linha, apagar cedo custa
  ressurreição.

#### Componentes reais (T16.7)

| Papel | Classe / arquivo |
| --- | --- |
| Política por agregado | `SyncEntityPolicies` (app) + `sync.policy.ts` (backend) |
| Tombstone | `sync_entities.deleted` + `0006_sync_tombstones.sql` |
| Exclusão no servidor | `SyncService.applyDelete` + `SyncRepository.applyDelete` |
| Conflito durável | `SyncConflictEntity` + `SyncConflictStatus` (`PENDING` / `AWAITING_PUSH`) |
| Resolução | `SyncConflictResolver` (uma transação por decisão) |
| Tradução para a tela | `SyncConflictSummary` + `SyncConflictPreview` |
| Exclusão local pelo sync | `SyncRemoteApplier.deleteAggregateLocally` + `SyncLocalDeleteGuard` |
| Cursor irrecuperável | `CURSOR_EXPIRED` → `SyncPhase.NeedsRebaseline` |
| Tela | `SyncSection` (lista de conflitos, diferenças e escolhas) |

#### Invariantes da T16.7

1. **Nenhum conflito é resolvido sozinho.** Sem *last write wins*, sem relógio, sem "server vence",
   sem merge por campo. Há teste estrutural e de comportamento.
2. **Os dois lados sobrevivem até a decisão** — e a decisão sobrevive ao processo morrer.
3. **Escolher o local gera mutação nova**, com `clientMutationId` novo e `baseRevision` igual à
   revision remota atual. Se o servidor andou de novo, volta a ser conflito.
4. **Escolher o remoto não gera mutação** — e o snapshot guardado é reconferido pelo hash antes de
   virar escrita.
5. **Resolução é idempotente** por escrita condicional no banco, não por flag de tela.
6. **A conta é revalidada imediatamente antes de gravar.** Conflito de A nunca aparece nem é
   resolvido por B.
7. **Tombstone impede ressurreição**, e recriar usa identidade nova.
8. **Exclusão remota não gera Outbox** e não quebra sessão ativa, histórico nem gamificação.
9. **Tombstone não é apagado**, e um cursor irrecuperável recusa em vez de reiniciar em silêncio.
10. **O worker não resolve nada.** Ele continua sincronizando o que é independente.

### Fechamento técnico da sincronização (T16.7.1)

> **Status (verificado em 2026-09-07): implementado.** Sem migration nova dos dois lados: Room
> continua em `version = 35` e o servidor em `0006_sync_tombstones.sql`. A T16.7.1 não acrescenta
> feature de sync — ela fecha três buracos que uma auditoria encontrou depois da T16.7.

#### 1. "Usar a versão da nuvem" confirma antes de sobrescrever

O defeito era silencioso e custava dado:

```text
conflito de B guarda a revision 5 da nuvem
        ↓
aparelho C edita          →  servidor vai para 6
        ↓
B ainda mostra a 5, e o usuário escolhe "Usar versão da nuvem"
        ↓
ANTES:  B grava localmente a revision 5, que o servidor já sabe estar superada,
        e só descobre a 6 no ciclo seguinte
```

A cópia remota guardada em `sync_conflicts.remotePayload` veio de uma página de pull e foi validada
**naquele momento**. Reconferir o hash prova que ela não corrompeu — não prova que ela ainda é a
atual, e essas são perguntas diferentes.

```text
DEPOIS:
USE_REMOTE
   ↓
GET /v1/sync/entities/{entityType}/{entitySyncId}     ← o estado de AGORA
   ↓
revalida a conta com o valor lido DEPOIS da resposta
   ↓
mesma revision, mesmo hash, mesmo tombstone?
   ├── sim  →  SyncConflictResolver  →  transação Room  →  zero Outbox
   └── não  →  conflito ATUALIZADO com o estado atual, status PENDING
                nada local é escrito, e o usuário decide de novo
```

**A escolha anterior não é reaproveitada.** "Ele já escolheu remoto, então usa a revision nova"
aplicaria um conteúdo que ninguém conferiu.

#### 2. A assimetria é deliberada

Só **uma** escolha exige rede, e é a única que sobrescreve dado local com conteúdo do servidor:

| Escolha | Precisa de rede? | Por quê |
| --- | --- | --- |
| `USE_REMOTE` | **sim** | grava conteúdo remoto por cima do local |
| `KEEP_LOCAL` | não | mantém o Room, vira mutação nova; um servidor que andou devolve `STALE` e o conflito reabre |
| `CONFIRM_LOCAL_DELETE` | não | idem, com `DELETE` |
| `CONFIRM_REMOTE_DELETE` | não | depende de um tombstone, e tombstone é terminal: `deleted = 1` nunca volta atrás |
| `KEEP_LOCAL_AS_NEW` | não | cria entidade com `syncId` novo; o que a nuvem tem na identidade morta é irrelevante |

Exigir confirmação nas outras "para padronizar" transformaria "escolhi" em "escolhi se a rede
estiver boa" — e a T16.6 e a T16.7 existem em cima de local-first. Há teste estrutural sobre a
tabela acima.

#### 3. Falha de rede não é fallback

Sem confirmação, **nada** é aplicado: o Room fica intacto, a Outbox fica intacta e o conflito fica
inteiro. Aplicar o snapshot guardado como plano B seria exatamente o defeito que a confirmação
existe para impedir.

#### 4. A conta é revalidada depois da resposta

```text
1. quem o servidor autenticou nesta requisição   (ownerUid da resposta, do token verificado)
2. o dono do dataset deste aparelho              (cloud_data_binding, relido agora)
3. a sessão do Firebase neste instante           (AuthState, relido agora)
```

Os três precisam ser a mesma conta. O `uid` capturado **antes** da requisição não serve: entre o
começo da chamada e a resposta, o usuário pode ter saído e entrado com outra conta — e uma resposta
obtida como B jamais pode virar escrita no dataset de A.

#### 5. Um toque, uma operação

Dois toques rápidos produzem **uma** consulta remota e **uma** aplicação. A proteção tem três
camadas: o `isBusy` da tela, um `Mutex` no `SyncRepository` que serializa resoluções deste processo,
e a escrita condicional no banco — que é a única que sobrevive ao processo morrer.

#### Componentes reais (T16.7.1)

| Papel | Classe / arquivo |
| --- | --- |
| Rota de estado atual | `SyncController.entityState` + `SyncService.entityState` (`GET /v1/sync/entities/...`) |
| Leitura somente-leitura | `SyncRepository.findEntitySnapshot` (backend) — um `SELECT`, filtrado por `owner_uid` |
| Contrato | `SyncEntityStateResponse` (TS) / `SyncEntityStateDto` (Kotlin) |
| Transporte | `SyncApi.entityState` / `SparkSyncApi` |
| Regra de comparação | `SyncRemotePreflight` — pura: sem DAO, sem rede, sem transação |
| Coordenação e revalidação de conta | `SyncRepository.resolveConflict` |
| Resultados novos | `SyncConflictResolution.RemoteChanged` / `RemoteUnavailable` / `RemoteInconsistent` |
| Tela | `SyncResolutionProblem.REMOTE_CHANGED` / `REMOTE_UNAVAILABLE` / `REMOTE_INCONSISTENT` |
| CI do Android | `.github/workflows/android.yml` |

#### Invariantes da T16.7.1

1. **`USE_REMOTE` nunca aplica uma revision que o servidor já sabe estar superada.**
2. **A leitura de estado atual é somente leitura**: não gasta `revision`, não anexa mudança ao
   change log, não escreve no ledger, não move cursor e não altera tombstone. Há teste que conta as
   três tabelas antes e depois.
3. **Ownership sai do token.** Não existe `?ownerUid=`; uma identidade de outra conta é `404`,
   indistinguível de inexistente.
4. **Falha de rede não altera nada** — nem Room, nem Outbox, nem conflito.
5. **`KEEP_LOCAL` continua offline**, com `clientMutationId` novo e proteção por `STALE` posterior.
6. **`USE_REMOTE` continua gerando zero Outbox.**
7. **Uma resposta autenticada como B não entra no dataset de A.**
8. **Tombstone não ressuscita**, e um tombstone que voltasse vivo é tratado como violação de
   integridade — nunca aceito em silêncio.

#### Integração contínua

| Workflow | O que roda | O que ele **não** usa |
| --- | --- | --- |
| `backend.yml` | `npm ci`, lint, `format:check`, test, build, `docker build` + smoke (health, persistência, schema, rotas fechadas com 401) | Firebase real, Gemini real, VPS |
| `android.yml` | `:app:testDebugUnitTest` (suíte inteira, inclusive migrations do Room) e `:app:assembleDebug`, em checkout limpo | Firebase real, Gemini real, VPS, Google Sign-In real, segredo |

O `google-services.json` real continua **fora do Git**. O plugin Google Services falha sem um
arquivo, então o workflow gera um **sintético e inerte** (`CI_ONLY`,
`ci-only-not-a-real-firebase-project`) com o `applicationId` real e identificadores obviamente
falsos. O que o runner compila é estruturalmente o mesmo debug de sempre: nenhuma variante especial,
nenhum `if (CI)`, nenhum plugin removido — e há um passo que falha o job se o arquivo real for
versionado por engano.

`assembleRelease` fica para a T16.8: ele passa por `lintVitalRelease`, que hoje falha por um falso
positivo preexistente de `androidx.fragment`, e um baseline esconderia problemas reais.

#### Pendências registradas para a T16.8 — e o que aconteceu com elas

| Pendência da T16.7.1 | Estado depois da T16.8 |
| --- | --- |
| Vulnerabilidades npm (7 *high*, 13 *moderate*, 1 *low*, zero críticas) | **Resolvido.** `high` → 0 por upgrade deliberado do NestJS (11.2.3 / cli 11.0.24 / schematics 11.1.0), sem `npm audit fix --force`. Restam 6 *moderate* numa cadeia de `firebase-admin` que já está na última versão e **não é alcançável** — com teste que prova isso. |
| `assembleRelease` no CI + falso positivo de `androidx.fragment` | **Resolvido** declarando `androidx.fragment:fragment:1.5.7` — a versão que o Gradle já resolvia. O lint lia a declarada (1.1.0); agora lê a verdade. Não é baseline e não muda um byte do que é empacotado. |
| Backup off-site da VPS | **Resolvido.** `ops/backup.sh` + restic criptografado + ensaio de restauração executável. |
| Retenção de tombstone e compactação do change log | **Continua fora, e continua sendo a decisão certa.** Elas só são seguras junto com o registro do menor cursor entre os aparelhos ativos da conta, que o servidor não guarda. Apagar cedo demais é ressurreição. |
| Observabilidade, TLS, firewall, secrets manager | **Resolvido no que cabe a esta escala**: `check-health.sh`, Caddy + Let's Encrypt, guia de `ufw`, segredos em arquivos `600` fora do Git e fora da imagem. Sem stack de métricas, por decisão (a observabilidade não pode ser maior que o serviço observado). |
| Revogação por aparelho | **Continua fora.** É feature de produto, não de infraestrutura. |

### Hardening, backup do servidor e prontidão de produção (T16.8)

> **Status (verificado em 2026-09-07): `CODE READY`, `LOCAL DOCKER VERIFIED`,
> `REAL VPS NOT VERIFIED`.** Room continua em `version = 35` e o servidor em
> `0006_sync_tombstones.sql`: a T16.8 **não** muda schema, contrato de sync, de backup ou de
> restore. Ela transforma a arquitetura online em infraestrutura operável.

O que muda no **Android** é uma coisa só, e ela é uma trava:

```text
BuildConfig.SPARK_BACKEND_BASE_URL
        ↓
SparkBackendEndpoint.resolve(url, isDebugBuild)
        ↓
release  → HTTPS em host público, ou null
debug    → HTTPS em qualquer host; texto claro só em 10.0.2.2/localhost/127.0.0.1
        ↓
null = "backend não configurado" → nuvem desligada, núcleo do Spark intacto
```

O `build.gradle.kts` aplica a mesma regra em tempo de build e **falha `assembleRelease`** antes de
o APK existir. As duas camadas respondem a perguntas diferentes: a do Gradle impede o engano de
acontecer, a de runtime impede o engano de funcionar. Recusar um endereço **nunca** degrada o
núcleo — ele apenas deixa a nuvem desligada, exatamente como um build sem endereço.

Nada mais do app muda: nenhum ViewModel, nenhuma tela, nenhum caso de uso, nenhum DAO. Os
interruptores do servidor (`AI_ENABLED`, `SYNC_WRITE_ENABLED`, `MAINTENANCE_MODE`) respondem `503`,
que o `SparkBackendAiCoachGateway`, o `SparkBackupApi`, o `RestoreApi` e o `SyncApi` já traduzem
em erro tipado e recuperável desde a T16.2 — desligar uma capacidade no servidor não exige publicar
APK novo, e a Outbox permanece pendente porque 5xx nunca confirma.

O resto da fase vive fora do app: `backend/docker-compose.prod.yml`, `backend/Caddyfile.prod`,
`ops/` e `docs/operations/`. Ver
[ADR-0001](docs/architecture/ADR-0001-spark-online-architecture.md) e
[`docs/operations/`](docs/operations/).

#### Invariantes da T16.8

- **O núcleo não paga nada.** Com VPS, Firebase, Gemini, backup e sync todos fora, o usuário abre o
  app, executa treino, registra série, conclui e consulta histórico. Nenhum interruptor, limite ou
  timeout pode mudar isso.
- **Produção é HTTPS**, e o backend não publica porta — há gate de CI.
- **`cp spark.db` ativo é proibido**: o snapshot é `VACUUM INTO` a partir de conexão somente
  leitura, verificado por `integrity_check` e `foreign_key_check` sobre a cópia.
- **Backup só está validado depois de restaurado**, com o backend real subindo sobre a cópia.
- **Migration de produção não roda sem ponto de recuperação**, e rollback de aplicação não desfaz
  migration.
- **Interruptor é pausa, nunca perda**: `503`, Outbox pendente, nada apagado.
- **Tombstone, change log e ledger continuam intocados.**

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
- [`docs/architecture/social-domain.md`](docs/architecture/social-domain.md)
- [`docs/architecture/friendship-contract.md`](docs/architecture/friendship-contract.md)
- [`docs/FIREBASE_AUTH_SETUP.md`](docs/FIREBASE_AUTH_SETUP.md)
- [`backend/README.md`](backend/README.md)
- [`contracts/social/v1/README.md`](contracts/social/v1/README.md)

---

## 18. Domínio social (T17)

> **Status (verificado em 2026-09-09): T17.0 a T17.13.1 implementadas — T17 fechado.**
> **T17.0** — identidade social (`socialId`, `friendCode`, `displayName`), estados
> `NOT_ENABLED`/`ACTIVE`/`DISABLED`, privacidade, seis rotas sob `/v1/social`, migration
> `0007_social_foundation.sql`, gateway e seção de Perfil no Android.
> **T17.1** — amizade bilateral, pedidos de amizade, descoberta por `friendCode` exato,
> compartilhamento e QR Code, migration `0008_friend_graph.sql`, nove rotas sob `/v1/social`,
> telas de Amigos e Solicitações no Android.
> **T17.2** — perfil social enriquecido: projeção de progresso autorizada por amizade,
> privacidade por campo (quatro interruptores, todos `false`), migration
> `0009_social_progress_profile.sql`, quatro rotas sob `/v1/social`, telas de Perfil de amigo e
> Compartilhar progresso no Android. **Uma das quatro métricas é projetável hoje** — treinos da
> semana; nível, sequência e conquistas respondem `UNSUPPORTED` porque a gamificação é `DERIVED` e
> não chega ao servidor.
> **T17.3** — desafios privados entre amigos: dois tipos (`WORKOUTS_COMPLETED`, `ACTIVE_DAYS`),
> pontuação **derivada na leitura** dos dados canônicos de treino, ciclo de vida derivado do
> relógio do servidor, migration `0010_social_challenges.sql`, oito rotas sob `/v1/social`, e telas
> de Desafios, Criar desafio e Detalhe/placar no Android.
> **T17.4** — atividade dos amigos (14 dias civis) e ranking semanal contextual entre amigos com
> opt-in, migration `0011_social_activity_timezone.sql`, duas rotas sob `/v1/social`, telas de
> Atividade e Ranking no Android.
> **T17.5** — notificações sociais com Firebase Cloud Messaging: 5 tipos de evento, transactional
> outbox no servidor, payload FCM minimalista data-only, isolamento rigoroso por conta,
> deduplicação LRU no cliente, preferências granulares com switch master, deep links seguros,
> migration `0012_social_notifications.sql`, quatro rotas sob `/v1/social/notifications`.
> **T17.6** — hardening social: bloqueio bilateral, denúncia minimalista sem texto livre, exclusão
> de conta server-authoritative com tombstone HMAC, migration `0013_social_hardening.sql`.
> **T17.7** — compartilhamento de treinos entre amigos por **cópia independente** (snapshot V1, sem
> carga, nota nem identificador local), migration `0014_workout_shares.sql`.
> **T17.8** — check-ins de treino e Feed social: publicação **explícita por sessão**, validada
> contra a sessão canônica sincronizada pela `CanonicalTrainingSource`, janela de 48h com o relógio
> do servidor, Feed `FRIENDS_ONLY` bounded (30 dias, teto 50), migration
> `0015_social_workout_checkins.sql`, três rotas sob `/v1/social`, e o Feed dentro da área Social do
> Perfil.
> **Não existe:** seleção de conquistas em destaque, ranking global perpétuo, busca aberta por nome,
> busca por e-mail, sugestão algorítmica de pessoas, avatar, upload de mídia, legenda/foto/vídeo em
> check-in, comentários, reações, curtidas, feed público e push de check-in.
>
> **T17.9** — check-ins ricos sobre o **mesmo** agregado: legenda opcional (0..280, texto puro), no
> máximo uma foto (decodificada, sanitizada e re-encodada no servidor, sem EXIF/GPS, servida só por
> endpoint autenticado), reações de enum fechado (`FIRE`/`MUSCLE`/`CLAP`) e comentários (1..300).
> A política de visibilidade virou um objeto único, e as contagens são **por viewer**. Denúncia
> passou a ter alvo (`USER`/`CHECKIN`/`COMMENT`), resolvido no servidor. Continuam fora: vídeo, GIF
> animado, múltiplas fotos, feed público, mention, hashtag, link clicável, edição de publicação e
> push de reação ou comentário.
>
> **T17.10** — auditoria final do Social. Nenhuma funcionalidade nova: T17.0–T17.9 auditadas como
> um sistema só, com correção dos defeitos que a revisão por fase não pegaria (tombstone por
> caminho, chave HMAC obrigatória em produção, checksum de migration, varreduras que não varriam,
> escopo de conta em toda ViewModel social, teto do proxy acima do teto do backend).
>
> **T17.11** — **Social V2: Squads privados**. Grupos pequenos formados por convite, com feed
> privado onde os membros trazem **explicitamente** check-ins que já publicaram. Não existe busca,
> listagem pública, link de convite, QR nem código de entrada: conhecer o `groupId` não concede
> nada, e quem não é membro recebe o mesmo `404` de "não existe". O feed do Squad é o **mesmo**
> `WorkoutCheckIn` lido por outra audiência — `social_group_checkin_shares` é uma aresta, e não um
> post. Papéis `OWNER`/`MEMBER` com **exatamente um** dono garantido por índice único parcial;
> convite só do dono e só para amigo direto ativo, revalidado no envio **e** no aceite; bloqueio
> corta visibilidade sem destruir participação; relação puramente de grupo nasceu **read-only**
> (reagir e comentar exigiam relação direta — a T17.12 removeu essa restrição resolvendo a causa).
> `WorkoutCheckInAccessPolicy` passou a responder
> `self ∨ amizade ∨ Squad`, e a mídia usa literalmente o mesmo predicado. Um push
> (`GROUP_INVITATION_RECEIVED`, data-only, sem nome de Squad). Migration `0019_social_groups.sql`,
> dezessete rotas sob `/v1/social`, e a área de Squads dentro do Social do Perfil.
> **Continuam fora:** chat, DM, post de texto, enquete, desafio ou ranking de Squad,
> template/programa compartilhado para Squad, evento, agenda, presença online, grupo público,
> descoberta e denúncia de grupo.
>
> **T17.12** — **interações contextuais em Squads**. A restrição de leitura da T17.11 existia porque
> o mesmo check-in pode estar no Feed de amigos e em vários Squads, e uma interação sem audiência
> vazaria de um para o outro; a T17.12 resolve a causa: **a interação passa a pertencer a uma
> audiência explícita**, `FRIEND` ou `GROUP(groupId)`. O `WorkoutCheckIn` continua único — nenhum
> post duplicado, nenhum segundo Feed. Um membro sem amizade nenhuma reage e comenta dentro do
> Squad, e **só** ali: participação de grupo não vira amizade, perfil, Feed de amigos, desafio nem
> compartilhamento de treino. O contexto é proposta do cliente e é revalidado a cada requisição
> (Squad ativo ∧ compartilhamento ∧ participação dos dois lados ∧ ¬bloqueio); contexto inválido é
> `404`, nunca um rebaixamento para `FRIEND`. Contagens e listas passam a ser por audiência **e**
> por viewer, com o bloqueio viewer-safe da T17.9 intacto. Uma reação por pessoa/publicação/audiência,
> garantida por **dois índices únicos parciais** — uma `UNIQUE` comum não serviria, porque no SQLite
> cada `NULL` é distinto de qualquer outro. O dono do Squad passa a moderar comentários daquela
> audiência, e o privilégio não atravessa para o Feed de amigos. Migration
> `0020_social_interaction_audience.sql` (rebuild em 12 passos, backfill de todo o histórico como
> `FRIEND`). **Continuam fora:** push de reação/comentário, XP, Activity, ranking, contador de não
> lidos, realtime, thread, menção — e nada disso mora no Room ou no Outbox.
>
> **T17.13.1** — **fechamento pós-auditoria**. Nenhuma funcionalidade social nova; sete correções de
> integridade encontradas por auditoria independente depois da T17.13.
> **Exclusão de conta atômica:** tombstone, job e purge das tabelas account-scoped passam a ser uma
> transação SQLite única — uma falha no meio faz `ROLLBACK` de tudo, em vez de deixar a conta
> bloqueada sobre dados apagados pela metade. A mídia continua fora da transação (chaves lidas
> antes, arquivos apagados depois do commit).
> **Ledger de DR durável:** `deletion_tombstones.tsv` deixa de ser `appendFileSync` dentro de um
> `catch {}` vazio — a escrita é `append` + `fsync`, a falha propaga, e a exclusão responde
> `DELETION_PENDING` em vez de `DELETED` sem o registro anti-ressurreição. O que falta tem nome
> durável em `account_deletion_jobs.phase` (`LEDGER_PENDING` → `FIREBASE_PENDING`) e sobrevive a
> restart. O arquivo passa a entrar no backup.
> **Reconciliação com comando real:** `dist/cli/reconcile-account-deletions.js`, chamado por
> `ops/restore.sh --install` antes de a restauração ser declarada completa. Ledger ausente ou
> malformado **falha fechado**. O inventário de colunas de uid saiu de 7 para 35 origens, é
> declarado num só lugar e um teste o confronta com o schema real.
> **Auth guard fail-closed:** não conseguir avaliar o tombstone responde `503`, e não "conta ativa".
> **Convite de Squad expira de verdade:** `EXPIRED` passa a ser gravado (migration `0022`) — como
> estado derivado ele não liberava a vaga do índice único parcial de pendentes, e um convite vencido
> travava o par (Squad, pessoa) para sempre.
> **Idempotência padronizada:** a conferência de chave vem antes do rate limit (um retry legítimo
> deixa de virar `429`) e passa a comparar o payload canônico — nome do Squad, destinatário do
> convite e **bytes da imagem** (migration `0023`, `input_content_hash`). Reusar a chave com conteúdo
> diferente é `409`, e nunca o resultado antigo.
> **WorkoutShare:** o share e o evento de notificação nascem na mesma transação, e as transições de
> estado viraram CAS (`WHERE id = ? AND status = ?`). O FCM continua fora da transação.
> Detalhes em [`docs/runbooks/account-deletion-dr.md`](docs/runbooks/account-deletion-dr.md).

Detalhamento em [`docs/architecture/social-domain.md`](docs/architecture/social-domain.md) (T17.0),
[`docs/architecture/friendship-contract.md`](docs/architecture/friendship-contract.md) (T17.1),
[`docs/architecture/social-profile-contract.md`](docs/architecture/social-profile-contract.md) (T17.2),
[`docs/architecture/challenge-domain.md`](docs/architecture/challenge-domain.md) (T17.3),
[`docs/architecture/social-activity-ranking.md`](docs/architecture/social-activity-ranking.md) (T17.4) e
[`docs/architecture/social-notifications.md`](docs/architecture/social-notifications.md) (T17.5) e
[`docs/architecture/social-domain.md` §11–§13](docs/architecture/social-domain.md) (T17.6, T17.7 e
T17.8) e
[`docs/architecture/social-groups.md`](docs/architecture/social-groups.md) (T17.11) e
[`docs/architecture/social-interaction-audience.md`](docs/architecture/social-interaction-audience.md)
(T17.12);
contrato em [`contracts/social/v1/README.md`](contracts/social/v1/README.md).

### As duas autoridades

```text
TREINO — local-first                      SOCIAL — server-authoritative

UI                                        UI
 ↓                                         ↓
Domain                                    Spark Backend
 ↓                                         ↓
Room  ← autoridade operacional            resultado
 ↓                                         ↓
Outbox → Spark Backend                    UI / cache em memória
```

O treino é local-first porque **executar um treino não pode depender de rede**. O social é
server-authoritative pela razão simétrica: **uma identidade pública não pode ser decidida por um
aparelho** — dois celulares offline não podem inventar dois `friendCode` e depois "convergir",
porque convergir aqui significaria invalidar o código que uma das pessoas já distribuiu.

Isso **não** torna o Spark dependente de servidor: Social é opcional, e uma conta sem perfil social
continua treinando, consultando histórico, sincronizando, fazendo backup, restaurando e usando o
Coach IA.

### Identidade

```text
Firebase Auth
     │ Firebase ID Token
     ▼
Spark Backend
     ├── identidade privada  →  Firebase UID (owner_uid, nunca em DTO)
     └── identidade social   →  socialId · friendCode · displayName · privacy
```

| Identidade | Função | Pública? |
| --- | --- | --- |
| Firebase UID | autenticação / ownership de conta | **Não** |
| `socialId` | identidade social | Sim |
| `friendCode` | convite / descoberta controlada | Compartilhável |
| `deviceId` | instalação | Não |
| `syncId` | entidade de treino sincronizada | Não social |

**O Firebase UID é identidade privada de infraestrutura e nunca identidade pública do usuário.**
E-mail, `localId`, `deviceId` e `syncId` também não são identificadores sociais.

### Invariantes bloqueantes

1. **Login não ativa Social.** `GET /v1/social/me` responde `{ enabled: false }` sem escrever nada.
2. **Social nunca é obrigatório** para treino, histórico, backup, restore, sync ou Coach.
3. **`socialId` e `friendCode` são server-generated, únicos e imutáveis**, e não derivam do uid, do
   e-mail nem do nome. O cliente não os propõe — um campo desses no corpo recusa a requisição.
4. **Desativar não apaga nada**, e reativar preserva a identidade.
5. **Social não usa Outbox, `sync_entities`, tombstone nem Room como autoridade**, e não entra no
   backup nem no restore.
6. **Nenhum dado de treino entra em `social_profiles`.** O caminho futuro é a `SocialProjection`,
   com `OWNER_SCOPED` e `CONSENT_REQUIRED`.
7. **Não existe enumeração**: sem busca por nome, sem busca por e-mail, sem listagem global e sem
   rota pública. A **única** descoberta é o lookup por `friendCode` exato (T17.1), autenticado, com
   teto próprio de requisições, e cuja resposta para código malformado, inexistente e de perfil
   desativado é a mesma.

### O grafo social (T17.1)

```text
SocialProfile A ──friendCode──▶ lookup exato ──▶ preview mínimo
                                                      │
                                          FriendRequest (PENDING)
                                                      │
                        ┌─────────────────────────────┼──────────────────────────┐
                    ACCEPTED                      REJECTED                   CANCELLED
                  (só o destinatário)          (só o destinatário)         (só quem enviou)
                        │
                        ▼
                 Friendship(A,B)   ← uma linha, par canônico min(uid) < max(uid)
```

Invariantes bloqueantes que se somam aos de cima:

8. **Amizade é um par, não duas relações.** `PRIMARY KEY (user_a_uid, user_b_uid)` mais
   `CHECK (user_a_uid < user_b_uid)` tornam **irrepresentáveis** a duplicata A-B, a duplicata B-A e
   a amizade consigo mesmo. A garantia é do banco, não da disciplina do serviço.
9. **Aceitar é transacional.** Marcar o pedido `ACCEPTED` e criar a amizade acontecem juntos ou não
   acontecem. Nunca "amizade criada com o pedido ainda pendente", e nunca "pedido cancelado com
   amizade criada escondida".
10. **Pedido cruzado auto-aceita**, deterministicamente e na mesma transação: quando A pede B e B
    pede A, o consentimento bilateral já foi expresso pelos dois.
11. **Estado terminal não volta para `PENDING`**, e enviar/aceitar/recusar/cancelar são
    idempotentes por escrita condicional no banco — não por flag em memória.
12. **Só participante age, e só o participante certo.** Uma conta C não aceita, não recusa, não
    cancela e não desfaz nada entre A e B — e recebe "não existe", não "não é seu".
13. **Desfazer amizade não bloqueia e não apaga nada além da relação.** Bloqueio é outra
    capacidade (T17.6, pendente).
14. **Perfil desativado suspende as relações; não as apaga.** Elas somem das listas dos dois lados,
    param de ser acionáveis, e voltam inteiras ao reativar.
15. **O QR carrega apenas `spark://friend/v1/<friendCode>`** — sem uid, e-mail, token, `socialId`,
    `deviceId` ou endereço de servidor —, é gerado no aparelho, e o scanner **nunca** navega, abre
    `Intent` ou carrega URL.

### O perfil social enriquecido (T17.2)

```text
Friendship(A,B) ──▶ SocialAccessPolicy ──▶ SocialProgressSource ──▶ SocialProgressProjector
                                                                            │
                                                              SocialProgressPrivacyFilter
                                                                            │
                                                                  SocialFriendProfileDto
```

Invariantes bloqueantes que se somam aos de cima:

16. **O Social projeta progresso; ele nunca é autoridade de progresso.** Não há curva de XP,
    contagem de semanas consecutivas nem avaliação de conquista no domínio social — nem no servidor,
    nem no app. Uma métrica sem autoridade **remota** responde `UNSUPPORTED`, e não um número
    parecido calculado por uma regra paralela.
17. **O servidor nunca aceita progresso vindo do cliente.** `level`, `streak`,
    `weeklyWorkoutCount`, `totalXp` e listas de conquistas são recusados **por nome**, invalidando a
    requisição inteira. Um APK modificado não consegue se declarar nível 99.
18. **Ausência de dado não vira zero.** Quem nunca sincronizou uma sessão concluída recebe campo
    **ausente**, e não "0 treinos". Zero só é publicado quando é fato comprovado.
19. **A privacidade é aplicada no servidor.** Um campo desligado **não está** no JSON — ele não é
    escondido no Compose. Escondido e indisponível produzem a mesma ausência para o amigo; só o
    dono distingue os dois.
20. **Amizade ativa é a única porta, verificada a cada leitura.** Pedido pendente não abre,
    terceiro não abre, `unfriend` revoga na requisição seguinte, e desativar o Social fecha dos
    dois lados. Não há cache de perfil — nem no servidor, nem no Room.
21. **A semana é a canônica do Spark** (`ConsistencyCalculator.weekStart`, segunda a domingo, na
    data local do dono), e só sessões `COMPLETED` contam. A fixture
    `contracts/social/v1/weekly-window.json` amarra as duas implementações.
22. **Perfil ≠ pontuação de desafio.** `SocialProgressProjection` é para exibição. A T17.3 usa
    dados canônicos próprios (`ChallengeProgressSource`), com janela e autorização próprias — ver
    a seção seguinte.

### Os desafios entre amigos (T17.3)

```text
Friendship ──convite──▶ ChallengeInvitation ──aceitar──▶ ChallengeParticipant
                                                                 │
                              dados canônicos de treino (sync_entities / WORKOUT_SESSION)
                                                                 │
                                                     ChallengeProgressSource
                                                                 │
                                                     ChallengeScoringService
                                                                 │
                                                    score · rank · goalReached
```

A separação que define a fase:

```text
PROIBIDO                          IMPLEMENTADO
SocialProgressProjection          dados canônicos de treino
         ↓                             ├── SocialProgressSource    → perfil (T17.2)
pontuação do desafio                   └── ChallengeProgressSource → desafio (T17.3)
```

Invariantes bloqueantes que se somam aos de cima:

23. **O cliente nunca envia pontuação.** `score`, `progress`, `rank`, `winner` e `goalReached` são
    recusados **por nome**, invalidando a requisição inteira — como a T17.2 recusa `level`. O
    `creatorUid` cai pela regra da T17.0: o dono sai do token verificado.
24. **A pontuação é derivada na leitura**, e não existe coluna de placar em lugar nenhum do schema.
    Um contador incremental precisaria de correção retroativa para o treino que chega depois do
    fim, e os modos de falhar são conhecidos: *drift*, incremento duplo no reenvio, placar que não
    converge.
25. **Elegibilidade é o instante do treino, nunca o da chegada.** `ENDED` significa "a janela de
    elegibilidade fechou" — **não** "o resultado é final". A resposta carrega
    `resultMayStillChange`, e a tela diz isso. É essa regra que impede o Spark de punir quem
    treinou offline.
26. **`startedAt` é o instante canônico**, porque o Spark não tem `completedAt` e `finishedAt` é
    nulável. Usar `finishedAt` criaria uma segunda regra de atribuição de treino a dia, e a T17.2 e
    a tela de consistência discordariam do desafio sobre o mesmo treino.
27. **O ciclo de vida é derivado do relógio do servidor**, e não agendado. O banco guarda
    `lifecycle` (`OPEN`/`CANCELLED`); `UPCOMING`, `ACTIVE`, `ENDED` e `VOID` — e o `EXPIRED` de
    convite — saem da leitura. Um cron que não roda é um desafio que nunca começa, em silêncio.
28. **O fuso é do desafio, um só para todos**, validado contra o ICU e com a janela em instantes
    **gravada na criação**: as regras aceitas não mudam se o país mudar o horário de verão no meio.
    Um dia não é 24 horas, e a conversão é a mesma da semana canônica (`social-time.ts`).
29. **As regras são imutáveis depois da criação.** Não existe rota de edição, e é isso que torna
    *bait-and-switch* impossível.
30. **Três autorizações distintas.** Amizade permite **convidar**; aceitar permite compartilhar a
    pontuação **daquele** desafio; os interruptores da T17.2 decidem o **perfil**. Depois do
    aceite, `unfriend` revoga o perfil e **não** remove ninguém do desafio.
31. **Participar dá acesso ao placar, e não aos dados que o produziram.** Nenhum DTO carrega
    `sessionId`, `syncId`, exercício, série, carga, repetição, nota, horário, medida, uid, e-mail
    ou `friendCode`.
32. **Empate permanece empate** (*competition ranking* `1, 1, 3`). Não há desempate por ordem de
    chegada — puniria quem sincronizou depois — nem por `createdAt` da sessão.
33. **Nada entra na Outbox, no `sync_entities`, no backup ou no restore.** Não existe `entityType`
    `CHALLENGE`, e um push que tente declarar um responde `UNSUPPORTED`. Ler o placar é read-only:
    não gera XP, conquista, missão nem escrita nenhuma.

**A fronteira anti-fraude, dita honestamente:** esta fase garante que o cliente não envia pontuação
e que ela deriva do domínio canônico. Ela **não** torna o desafio à prova de fraude — um cliente
comprometido que fabrique `WorkoutSession` canônicas produziria pontuação correspondente, e esse é
um problema de integridade do dado de treino que existiria sem desafio nenhum.

### Os check-ins de treino e o Feed (T17.8)

```text
Room (WorkoutSession COMPLETED)   ← autoridade operacional, local-first
        │
        ▼  Sync T16   ← o ÚNICO caminho de upload de sessão de treino
sync_entities
        │
        ▼  CanonicalTrainingSource.findSessionForCheckIn   ← a MESMA fronteira da T17.2/T17.3/T17.4
        │
        │  ação explícita do usuário + confirmação do preview
        ▼
social_workout_checkins
        │
        ▼  amizade atual ∧ ¬bloqueio ∧ perfil ativo, avaliado a cada leitura
Feed dos amigos
```

Invariantes bloqueantes que se somam aos de cima:

34. **Concluir um treino não publica nada.** Não existe gatilho no fim da sessão, no sync, na
    abertura de tela ou em background — e o primeiro toque no CTA abre um preview, sem requisição.
    Só a confirmação publica. A ordem é `commit COMPLETED → resultado salvo → opção social`.
35. **O consentimento é por sessão, e é independente.** `activitySharingEnabled` (T17.4),
    `friendRankingParticipationEnabled`, Challenge (T17.3) e Workout Share (T17.7) não decidem nada
    aqui, e publicar não altera nenhum deles. Activity e CheckIn são superfícies distintas e
    **não** se deduplicam.
36. **O cliente não declara conclusão.** `completed`, `status`, `ownerUid`, `authorUid` e
    `photoUrl` são recusados **por nome**. O dono sai do token, o estado da sessão sai da fonte
    canônica, o instante sai do `Clock` do servidor. Desde a T17.9 o corpo aceita `caption` e
    `mediaId` — os dois com validação própria; `photoUrl` continua recusado, porque a foto entra
    por um identificador que o servidor emitiu e nunca por um endereço que o cliente escolhe.
37. **A fonte canônica não ganha concorrente.** Uma operação estreita no adapter da T17.4.1, que
    devolve cinco escalares e nunca `payload`. Nenhum arquivo de check-in consulta `sync_entities`.
38. **Sessão não sincronizada é indistinguível de inexistente e de alheia** (`SESSION_NOT_FOUND`).
    Quem conclui "falta sincronizar" é o app, que pede um ciclo do Sync T16 e tenta de novo com o
    **mesmo** `clientRequestId`. Não existe segundo uploader de sessão, e a nuvem nunca é adotada
    implicitamente.
39. **Um treino, no máximo um check-in**, garantido por duas `UNIQUE` do banco. Toque duplo e retry
    de resposta perdida convergem em uma publicação; `clientRequestId` reusado em outra sessão é
    conflito.
40. **O Feed é bounded e a autorização mora na consulta**: 30 dias, teto 50, ordenado por publicação
    com desempate por `checkInId`. Sem `?users=`, sem rota pública, sem cursor histórico, sem
    polling, sem WebSocket e sem push.
41. **O DTO é o contrato inteiro** — `type`, `checkInId`, `author`, `publishedAt`, `caption`,
    `media`, `reactions`, `currentUserReaction`, `commentCount`, `isCurrentUser`.
    `publishedAt` é quando publicou, nunca quando treinou; e `sessionSyncId`, exercício, carga,
    série, duração, horário do treino e nome do treino não cruzam a fronteira em forma nenhuma.
42. **Excluir a publicação ≠ excluir o treino**, nos dois sentidos. Depois de publicado, o check-in
    é um artefato social independente — e a tela de exclusão do Histórico diz isso.

### O conteúdo do check-in (T17.9)

43. **Um agregado, e nenhum segundo Feed.** Legenda, foto, reações e comentários expandem o
    `WorkoutCheckIn`. Publicação da T17.8 continua válida sem backfill.
44. **O `Content-Type` não decide nada.** O servidor decodifica de verdade (`sharp`/libvips), recusa
    animação, valida pixels e arestas antes de alocar, aplica a orientação EXIF aos **pixels** e
    re-encoda em WebP **sem** copiar metadata. É a ausência de `withMetadata()` que remove GPS,
    modelo do aparelho e data original. O original nunca encosta no disco.
45. **A imagem não entra no SQLite.** Metadata em `social_checkin_media`, bytes em
    `SocialMediaStore` sob `SOCIAL_MEDIA_ROOT` — obrigatória em produção, sob pena de falha de
    startup. Chave opaca gerada pelo servidor; path traversal com duas barreiras.
46. **`mediaId` não concede acesso.** `GET /v1/social/media/{id}` exige token e passa pela mesma
    política do Feed. Não existe URL pública, diretório estático, `ETag` ou `Cache-Control: public`.
47. **Uma política de visibilidade, em um lugar** (`workout-checkin.access-policy.ts`), consumida
    por Feed, detalhe, mídia, reações, comentários e denúncia. Controller que consulte `friendships`
    ou `social_blocks` por conta própria é bloqueante arquitetural — há teste.
48. **Bloqueio é por viewer, e as contagens também.** No post de um terceiro, A e B deixam de ver a
    interação um do outro sem que nada seja apagado para o dono do post; e um `COUNT(*)` global
    vazaria a participação de quem o bloqueio esconde.
49. **Texto é texto.** Legenda e comentário são normalizados (NFC), aparados, com controle C0/C1,
    zero-width e override bidirecional recusados — e **não** escapados: o Android desenha com
    `Text`, e escapar corromperia o texto da pessoa. URL, `@menção` e `#hashtag` são caracteres.
50. **A foto que falha não some em silêncio.** A publicação para, e a decisão entre "tentar de novo"
    e "publicar sem foto" é do usuário.
51. **Cache de mídia é memória, e é da conta.** Sem disco, trocado antes da primeira requisição da
    conta nova. A foto de A reaparecendo para B é bloqueante.
52. **Nada disso é evento de domínio.** Sem XP, sem conquista, sem missão, sem streak, sem ranking,
    sem Activity e **sem push**. Nenhuma preferência de notificação foi criada.
53. **A mídia entra no backup.** `ops/backup.sh` manda `$SPARK_MEDIA_DIR` no mesmo snapshot restic;
    o restore instala os dois; a reconciliação de tombstones purga banco **e** arquivos.

### Exclusão de conta — resolvida na T17.6

`DELETE /v1/account` faz o expurgo em cascata das tabelas sociais — inclusive
`social_workout_checkins` (T17.8) e, desde a T17.9, legendas, mídia, comentários (também os feitos
em posts alheios) e reações — e grava um tombstone HMAC contra ressurreição. As **chaves de
armazenamento** da mídia são lidas antes do purge e os arquivos apagados depois do commit: o
`ON DELETE CASCADE` do SQLite não alcança o sistema de arquivos, e uma exclusão que apagasse só a
metadata deixaria a foto da pessoa no disco de um servidor que jura tê-la apagado.

O dado **local** de treino continua no aparelho: excluir a conta é desfazer a identidade online,
não apagar o histórico de quem treinou.
