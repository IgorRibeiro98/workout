# Spark / Gym Tracker — Bug Fix Protocol

Use this protocol for any bug, regression, crash, incorrect state, visual inconsistency or persistence problem.

## 1. Do not patch the symptom first

Before editing code, identify the root cause.

A UI symptom may originate from:

- incorrect persisted data;
- a stale Flow/state projection;
- duplicated sources of truth;
- route/cursor progression;
- incorrect timestamp anchor;
- wrong party/session configuration;
- Compose measurement/coordinate behavior;
- legacy code still participating;
- race/stale event processing.

Trace the behavior end-to-end before choosing the fix location.

## 2. Required bug investigation

Document internally:

```text
Observed behavior:
Expected behavior:
Reproduction path:
First incorrect state/value:
Source of truth:
Root cause:
Why the current code allows it:
Minimal safe fix:
Regression risks:
```

## 3. Trace from symptom to source

For state bugs, trace:

```text
UI
 -> UiState
 -> ViewModel intent/collector
 -> domain/use case
 -> repository
 -> persisted entity/preferences
```

For active workout bugs, also trace:

```text
WorkoutRoute
 -> NavigationCursor/current node
 -> NavigationEventProcessor
 -> persistence
 -> UI projection
```

For timing bugs, identify the exact anchor timestamp and calculator used.

## 4. Check for competing implementations

Search for all code that can perform the same action/calculation.

Examples:

- multiple ETA formulas;
- UI timer + domain timer;
- legacy execution engine + route navigation;
- preference state in both DataStore and local ViewModel defaults;
- visual reorder without persisted reorder;
- undo that changes UI but not Room.

If two implementations compete, fix the ownership problem rather than adding synchronization hacks between them.

## 5. Spark-specific regression traps

### 5.1 Stale route progression

`NavigationEventProcessor` must not repeatedly advance instant nodes using a stale route snapshot.

When an event mutates the route, subsequent processing in the same operation must use the updated route/current node.

### 5.2 Stale/concurrent events

Preserve serialization/mutex/stale-event protection already present in navigation code.

Do not "fix" a timing bug by removing concurrency guards.

### 5.3 Recovery anchor errors

`RecoveryTimeCalculator` or the current canonical equivalent must use the correct persisted recovery anchor/timestamp.

Check both solo and party behavior.

### 5.4 Remaining route duration

`RemainingRouteDurationCalculator` must calculate from the correct current anchor and account for the actual remaining nodes.

Do not fall back to a fixed original total.

### 5.5 Party route REST relationships

When party routes contain REST nodes, preserve their relationship to the relevant set/user-set node.

Detached REST nodes can break recovery/participant semantics.

### 5.6 Old workout start timestamp

After cancelling/abandoning a workout and starting a new one, verify that `workoutStartedAt` is new.

Never reuse an old session's start time simply because the same template is used.

### 5.7 Configuration mismatch

When Home says SOLO/DUO/TRIO, verify the actual created session/route and active ViewModel receive the same mode.

Check DataStore -> configuration -> start -> session/route -> active UI.

### 5.8 Double start

Verify repeated taps/navigation events cannot create two active sessions/routes.

### 5.9 Fast navigation/timer bugs

When reproducing timer bugs, intentionally advance actions quickly.

Look for:

- duplicate events;
- stale node ids;
- stale timestamps;
- timers anchored to previous nodes;
- UI collectors briefly rendering old state;
- non-atomic persistence transitions.

### 5.10 Drag ghost drift

For Compose drag bugs, compare the coordinate spaces of:

- pointer input;
- item bounds;
- parent container;
- ghost overlay.

If a `Popup`/window-based preview drifts, prefer a same-parent overlay using `LayoutCoordinates` and local/container translation.

### 5.11 Responsive layout regressions

If a screenshot looks squeezed/overlapping, do not simply reduce fonts/padding until it fits.

Inspect constraints, fixed sizes, weights, intrinsic sizing and text wrapping.

Validate more than one screen width and longer PT-BR labels.

### 5.12 Catalog errors

For incorrectly classified/missing-media exercises:

- inspect canonical exercise id;
- inspect manifest/update mapping;
- inspect category/muscle mapping;
- verify media belongs to the same canonical exercise;
- ensure import does not overwrite valid user data/history.

## 6. Reproduction test before the fix

When practical, create or identify a test that fails for the actual bug before changing the implementation.

The test should express the invariant, not the current internal implementation.

Examples:

```text
Given DUO is persisted
When a workout starts
Then the created route/session is DUO
```

```text
Given a workout was abandoned
When the same template is started again
Then the new session start timestamp is greater than the old one
```

```text
Given an undo changes the current node
When ETA is computed
Then it uses now + the new remaining duration
```

## 7. Implement at the owning layer

Fix the earliest layer that owns the incorrect behavior.

Examples:

- persistence invariant -> repository/domain;
- route progression -> navigation processor/domain;
- preference mismatch -> configuration/start pipeline;
- presentation-only spacing -> Compose;
- catalog mapping -> importer/domain data.

Avoid compensating for domain bugs in UI code.

## 8. Compilation failures are feedback, not cleanup

If the implementation does not compile:

1. stop stacking new changes;
2. inspect the exact signature/API/import mismatch;
3. compare with the current corrected codebase;
4. fix the cause;
5. compile again.

Do not replace a correct current API with an older remembered API.

Pay special attention to Jetpack Compose APIs/imports that differ by library version.

## 9. Learning from user-corrected code

If the user had to manually repair a previous delivery:

- inspect the repaired version before the next related change;
- preserve those repairs;
- identify the previous failure cause;
- add that cause to the local task checklist;
- do not reintroduce it.

Typical categories to inspect:

- wrong/imported Compose API;
- wrong function signature;
- stale call site;
- incompatible test fixture;
- fake test that passes without exercising real behavior;
- lost persistence update;
- legacy path accidentally restored.

## 10. Required validation after fix

At minimum:

- reproduce the original path;
- validate the corrected behavior;
- run relevant tests;
- run `./gradlew :app:testDebugUnitTest` when applicable;
- build/compile the affected app/module;
- validate at least one adjacent behavior likely to regress;
- inspect final diff.

## 11. Bug-fix completion report

Use this structure:

```text
Root cause:
Why it happened:
Fix:
Files changed:
Regression protection:
Tests/build run:
Original reproduction after fix:
Adjacent scenarios checked:
Unverified items:
```
