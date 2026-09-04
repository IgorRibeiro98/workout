# Spark / Gym Tracker — Feature Protocol

Use this protocol when implementing a new capability or intentionally changing product behavior.

## 1. Define the capability before coding

Convert the request into:

```text
Objective:
Current behavior:
Expected behavior:
User-visible result:
Canonical owner/layer:
Persistence impact:
Non-goals:
Acceptance criteria:
Validation plan:
```

Do not infer a broad redesign from a narrow feature request.

## 2. Acceptance criteria are mandatory

Every feature must have observable acceptance criteria.

Bad:

```text
Improve party mode.
```

Good:

```text
[ ] Home persists DUO
[ ] Starting the workout creates a DUO session/route
[ ] ActiveWorkoutViewModel exposes DUO
[ ] Rotation includes both configured participants
[ ] Solo behavior remains unchanged
[ ] Configuration survives reopening
```

Use subchecks for broad criteria.

## 3. Define non-goals

Explicitly list what the feature must not change.

Examples:

- do not replace navigation architecture;
- do not migrate unrelated entities;
- do not redesign other screens;
- do not add backend dependency;
- do not change exercise catalog identifiers;
- do not refactor ETA while implementing an unrelated visual feature.

## 4. Investigate reusable architecture

Before designing a new component/service, search for an existing owner.

Examples:

- workout progression -> persisted route/navigation;
- recovery -> `RecoveryTimeCalculator`;
- remaining duration -> `RemainingRouteDurationCalculator`;
- ETA -> existing ETA estimator;
- party route generation -> `PartyRouteBuilder`;
- user defaults -> `UserPreferencesDataStore`;
- start orchestration -> current centralized workout start flow;
- durable order -> template/domain persistence.

New abstractions require a concrete gap that existing abstractions cannot safely fill.

## 5. Implement in microphases

Prefer a sequence like:

```text
Phase A — domain/persistence
Phase B — ViewModel integration
Phase C — UI interaction
Phase D — tests
Phase E — validation
```

Compile after coherent phases.

Do not postpone all validation until after a large multi-file rewrite.

## 6. Feature-specific guidance

### 6.1 Workout start/configuration

Requirements already decided:

- configuration is not automatically forced on every start;
- configuration opens via the workout configuration/settings icon;
- preferences persist for future workouts;
- Home and actual execution use the same stored party/duration/focus configuration;
- starting must be centralized;
- guard against double-start;
- a new session gets a fresh start timestamp.

Validate SOLO, DUO and TRIO separately when those modes are supported by the current build.

### 6.2 Party / participant behavior

Do not hardcode participant names/count/order.

Model participant configuration explicitly and derive route/rotation from it.

In active execution, expose only the information needed by UI; do not rebuild party progression in Compose.

When one participant executes and others recover, recovery state/timers must remain visible when required by UX and derive from canonical anchors.

### 6.3 Focus Mode

Focus Mode is a visibility/information-priority feature.

It should prioritize:

- current exercise;
- current set;
- relevant timer;
- current participant/recovery context;
- next essential action;
- compact ETA.

Do not "implement" Focus Mode by compressing every normal-mode component into less space.

### 6.4 Workout builder reordering

Feature acceptance should include both interaction and persistence.

For exercise/group drag-and-drop:

- long press prevents accidental click;
- ghost stays close to finger;
- target slot shows item-shaped placeholder/preview;
- surrounding items can animate;
- drop updates domain order;
- reopening/reloading preserves it.

Do not implement only a visual reorder of the current list.

### 6.5 Exercise catalog/import

For catalog changes:

- preserve canonical IDs when representing the same exercise;
- validate category/muscle classification;
- validate media association;
- preserve custom/user data;
- preserve session/template historical references;
- make import/update idempotent where possible;
- detect invalid references rather than silently dropping them.

### 6.6 Time/duration/ETA

When implementing duration-related features:

- user-selected available duration is configuration, not the ETA itself;
- ETA remains `now + remaining`;
- avoid fixed closed-duration assumptions when UI now accepts free hours/minutes;
- propagate duration through the canonical configuration/start/session path;
- validate undo/skip/advance after time-model changes.

## 7. UI feature quality

Before finalizing Compose UI:

- inspect constraints rather than patching symptoms with smaller fonts;
- handle longer PT-BR text;
- avoid fixed sizes unless truly intrinsic;
- preserve touch target usability;
- verify the active workout screen remains practical without unnecessary scrolling;
- verify Focus Mode removes secondary content cleanly;
- validate at more than one relevant viewport size when possible.

## 8. Data migration/persistence changes

Any feature that changes durable data must explicitly answer:

1. Is this new data or a change in meaning?
2. Does Room schema change?
3. Is migration required?
4. What happens to existing templates/sessions?
5. What happens after app restart?
6. Can old/canonical references still resolve?

Do not modify durable semantics casually.

## 9. Tests

Add tests at the layer where the invariant lives.

Prefer behavior tests such as:

```text
configured party mode -> created route mode
```

```text
new persisted order -> reload -> same order
```

```text
route mutation -> remaining duration -> updated ETA
```

Avoid tests that only reproduce internal implementation structure.

## 10. Definition of feature completion

A feature is complete only when:

- all acceptance criteria pass;
- all required subchecks pass;
- relevant build/tests pass;
- persistence behavior is validated when applicable;
- adjacent solo/party or template/session behavior is validated when applicable;
- final diff contains no unexplained unrelated changes;
- any unverified item is explicitly reported as unverified.
