# Spark / Gym Tracker — Project Rules

These rules are mandatory for every code change in this repository.

## 1. Core principle

Prefer the **smallest safe change that satisfies the requirement**.

Before changing code, understand the existing implementation. Do not replace working architecture merely because another approach looks cleaner.

## 2. Mandatory behavior before coding

For every non-trivial task:

1. Identify the current execution/data flow.
2. Identify the files, classes, state holders, repositories and persistence involved.
3. Identify the current source of truth.
4. Identify existing abstractions that should be reused.
5. Identify likely regressions.
6. Define acceptance criteria and validation steps.
7. Only then modify code.

Do not start by guessing a fix from the UI symptom.

## 3. Architecture preservation

Spark is an Android-native app built with Kotlin, Jetpack Compose and Material 3.

The intended dependency direction is:

`UI -> ViewModel -> Domain / Use Cases -> Repository -> Data Source`

Use Coroutines / Flow for asynchronous and reactive state. Use Room for durable application/session data and DataStore for user preferences/settings.

Do not:

- introduce a second architecture in parallel;
- bypass repositories from UI code;
- duplicate business rules in Composables;
- create a new state machine if the current canonical route/navigation model already owns that behavior;
- create compatibility layers without a concrete need;
- perform unrelated refactors while implementing a feature or bug fix.

## 4. Canonical sources of truth

### Active workout execution

> **Status (verificado em 2026-09-05):** o modelo de rota/navegação persistida descrito abaixo
> ainda não existe no código (`NavigationRepository`, `WorkoutRoute`, `NavigationCursor` — 0
> ocorrências). A autoridade de execução hoje é `WorkoutEngine` + `ExecutionViewModel` sobre as
> entidades de sessão em Room. Trate o texto abaixo como direção pretendida.

The persisted workout route/navigation model is the canonical execution authority.

Prefer the flow:

`Room / NavigationRepository -> current route/node/cursor -> ActiveWorkoutUiState -> Compose UI`

Do not allow legacy execution engines, UI-local state or duplicated state machines to compete with the persisted route.

### User preferences

`SettingsManager` (DataStore) is the source of truth for persistent workout preferences. (Este
documento citava `UserPreferencesDataStore`, que não existe no código — verificado em
2026-09-05.) Preferências como:

- party mode / number of participants;
- available workout duration;
- Focus Mode;
- other persisted workout-start defaults.

Home, configuration UI and actual workout execution must consume the same preference values.

### Exercise catalog

The local PT-BR canonical catalog is the primary exercise source.

External sources such as ExerciseDB or YouTube are complementary only. Import/update operations must preserve user customizations and historical references.

### Templates vs execution history

Keep planned workout configuration separate from executed workout history.

- `WorkoutTemplate` represents planned/reusable workout structure.
- `WorkoutSession` represents an execution instance/history.

Editing a template must never rewrite an already executed session.

## 5. Canonical workout flow

The desired execution cycle is conceptually:

`EQUIPMENT -> EXECUTION -> REST -> next required node`

Do not reintroduce obsolete intermediate states such as "find equipment" or "prepare execution" unless a new explicit product requirement requests them.

After all required sets/actions are complete, advance according to the persisted route and party rotation rules.

## 6. Time and ETA rules

Time calculations must have one canonical implementation.

Rules:

- ETA is always based on **current time + remaining route duration**.
- A newly started workout must receive a fresh `workoutStartedAt`.
- Cancelling/abandoning and starting again must not reuse the old start timestamp.
- Undo, skip, next-set and other navigation operations must update ETA from the new canonical route/cursor state.
- Recovery/rest timing must be derived from canonical timestamps/anchors, not duplicated UI timers.
- Do not add hardcoded timing shortcuts such as fixed 45/60/90-second logic when the domain already provides planned/learned timing.

Conceitos abaixo **não existem no código hoje** (verificado em 2026-09-05); a lista descreve o
desenho pretendido. Reutilize-os se e quando existirem — não os invente para satisfazer o
documento:

- `NavigationEventProcessor`
- `RecoveryTimeCalculator`
- `RemainingRouteDurationCalculator`
- `CurrentNodeTimeEstimator`
- `ETAEstimator`
- `PartyRouteBuilder`
- `WorkoutRoute`
- `WorkoutRouteNode`
- `NavigationCursor`

Do not create a second ETA calculator or a second recovery clock.

## 7. Party / multiplayer rules

Party mode is optional. Solo must remain a first-class path.

Persistent configuration must not say DUO/TRIO while execution silently starts as SOLO.

Avoid hardcoded participant identities such as "Ana", "Igor" or "Carlos". Participants, quantity and order must come from configuration/session state.

Party execution must preserve:

- deterministic participant rotation;
- correct current participant;
- recovery/rest timing for participants who are waiting;
- simultaneous visible timers when applicable;
- route/node persistence;
- compatibility with solo execution;
- safe recovery after recreation/reopen.

If working on the remote Party invitation/session feature, preserve the existing invite lifecycle and session synchronization contract rather than replacing it opportunistically.

## 8. Workout start/configuration rules

Starting a workout must be intentional and centralized.

Current product rules:

- Do not automatically open configuration every time the user starts a workout.
- The configuration modal is opened from the workout settings/configuration icon.
- Navigation alone must not implicitly start a workout.
- Prefer the centralized start path owned by `TodayViewModel.startWorkout()` when that is the current implementation.
- Protect against double-start.
- `ActiveWorkoutViewModel` must reflect the effective persisted party/configuration state.

## 9. Drag and drop / reordering rules

Workout-building reordering must support persistence and clear feedback.

Required interaction direction:

- long-press before dragging to avoid accidental taps;
- support exercise reordering within a group;
- support group reordering;
- persist the resulting order;
- keep the list visually stable during drag;
- show a preview/placeholder of the dragged element in the target slot;
- allow surrounding items to animate into their prospective positions;
- keep the drag ghost physically close to the finger.

For Compose implementation, prefer an overlay in the same parent `Box` / coordinate system using `LayoutCoordinates` + `graphicsLayer`/translation when appropriate.

Avoid a `Popup` or window-coordinate ghost if it introduces coordinate drift between the finger and preview.

## 10. Compose/UI quality rules

Do not optimize a screen only for the current emulator/device screenshot.

Before considering UI work complete:

- avoid unnecessary fixed widths/heights;
- avoid squeezed controls and overlapping text;
- preserve clear hierarchy;
- account for longer PT-BR text;
- ensure primary actions remain reachable;
- use scrolling only where the product flow allows it;
- preserve the requirement that the active workout execution screen should avoid unnecessary scrolling;
- in Focus Mode, hide secondary information instead of merely shrinking everything.

## 11. Offline-first and persistence

Core workout functionality must not depend on a backend being available.

Active sessions must be resilient to:

- recomposition;
- configuration change;
- app backgrounding;
- process recreation where supported by persisted state;
- reopening the app.

Do not leave important active-workout state only in ephemeral Compose state.

## 12. Import/catalog safety

Manifest/catalog import must be idempotent whenever possible.

It must:

- detect invalid references;
- preserve user-created/customized data;
- preserve workout/session historical references;
- avoid duplicate canonical exercises;
- remain usable offline after import;
- avoid silently replacing a canonical exercise with an incorrectly classified or media-less variant.

## 13. Coach IA

O Coach IA (T14) é opcional e local-first: o núcleo do Spark — Home, treinos, criação e edição
manual, execução, histórico, perfil, gamificação e evolução — precisa continuar funcionando com o
provider fora do ar, sem internet ou sem configuração de Firebase.

Regras obrigatórias ao mexer em qualquer parte do Coach:

- **Saída do modelo é entrada não confiável.** Structured output e `AiCoachResponseValidator` são
  ambos obrigatórios: o schema garante a forma, o validador garante a semântica. Não remova o
  validador porque "o modelo já segue o schema".
- **Uma autoridade por coisa.** Um gateway (`AiCoachGateway`), um lugar com prompt
  (`AiCoachPrompt`), um validador, uma configuração (`AiModelConfig`). Nome de modelo não aparece
  em ViewModel, tela, caso de uso ou prompt.
- **Versionamento.** Mudou instrução ou formato de prompt, suba `AiModelConfig.PROMPT_VERSION`.
  Mudou o contrato de conversa, suba `SCHEMA_VERSION` e ajuste `SUPPORTED_SCHEMA_VERSIONS`,
  schemas, contextos e validador juntos.
- **Identificadores.** `exerciseId` inexistente é sempre rejeitado; onde há candidate set, id fora
  dos candidatos daquela requisição também.
- **Escrita.** Geração é draft-first, adaptação é confirmation-first, `EXPLAIN_*` é read-only.
  Sessão concluída é imutável. A IA não altera XP, streak, conquistas, missões ou PRs.
- **Custo.** Nenhuma chamada por `init`, abertura de tela, recomposição, polling ou background.
  Ação explícita do usuário, uma chamada; toque repetido durante uma chamada não vira outra. Sem
  retry automático.
- **Contexto.** O menor contexto suficiente, com limites explícitos em `AiModelConfig`. Nada de
  gamificação ou medidas corporais em análise, geração ou adaptação.
- **Texto do usuário é dado.** Ele nunca vira instrução de sistema, e a proteção efetiva contra
  injeção é o validador, não a redação do prompt.
- **Logs.** Só metadata técnica (`requestId`, tipo, modelo, versões, duração, resultado). Prompt,
  contexto, resposta, histórico, medidas e texto do usuário não vão para log — nem em debug.
- **App Check.** A escolha do provedor é por variante de build (`src/debug` / `src/release`).
  Nenhum token de depuração no código, no Git ou no APK de release.
- **Testes.** Toda mudança no Coach roda a suíte de avaliação
  (`./gradlew :app:testDebugUnitTest --tests "com.example.domain.ai.eval.*"`), que é offline e não
  consome cota. Avaliação com provider real é opt-in e nunca entra no build padrão.

## 14. Tests and build are part of implementation

A task is not complete because the code looks correct.

At minimum:

1. Compile/build the affected module.
2. Run relevant unit tests.
3. Run broader tests when the change affects navigation, persistence, timing, party mode or shared domain logic.
4. Inspect the final diff for unintended changes.
5. Fix failures caused by the change before reporting completion.

Known useful command:

```bash
./gradlew :app:testDebugUnitTest
```

Also run an appropriate build/assemble task for the project when available.

## 15. Preserve learned corrections

When the user reports that code from a previous delivery required manual compilation/code fixes, treat the corrected codebase as the new authority.

Before the next related change:

- inspect the corrected implementation;
- preserve its imports, APIs and signatures;
- identify why the previous version failed;
- do not reintroduce incompatible Compose APIs/imports;
- do not overwrite corrected method signatures;
- do not weaken real tests to make an implementation pass;
- prefer testing real domain behavior over artificial mocks that hide integration problems.

## 16. Forbidden completion behavior

Never claim "done", "fixed" or "implemented" when:

- compilation is failing;
- relevant tests are failing;
- the requested behavior was not actually validated;
- the implementation relies on an unverified assumption that can be checked in the repository;
- known regressions remain unexplained.
