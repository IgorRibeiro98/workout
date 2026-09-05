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

## 15. Important known regression patterns

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
