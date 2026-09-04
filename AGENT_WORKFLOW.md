# Spark / Gym Tracker — Agent Workflow

Use this workflow for every meaningful implementation task.

The purpose is to reduce speculative coding, regressions and "looks finished" results that were never validated.

## 0. Start-of-task rule

Before modifying code, read:

1. `PROJECT_RULES.md`
2. `ARCHITECTURE.md`
3. the protocol relevant to the task (`BUG_FIX_PROTOCOL.md` or `FEATURE_PROTOCOL.md`)
4. `DEFINITION_OF_DONE.md`
5. the current code involved in the requested behavior

Repository code that has been corrected more recently than these documents wins when there is a conflict. If that happens, preserve the newer/corrected behavior and mention the documentation mismatch in the final report.

## 1. Convert the request into a checklist

Every task must have an explicit checklist before implementation.

For broad items, create subchecks.

Example:

```text
[ ] Reordering works
    [ ] Long press begins drag
    [ ] Exercise can move within a group
    [ ] Group can move
    [ ] Ghost follows finger
    [ ] Target placeholder is correct
    [ ] Order persists
    [ ] Reload preserves order
```

Do not mark a parent item complete unless all of its required subitems pass.

At the end, audit the implementation against the **same checklist**.

## 2. Investigation phase

Do not edit code yet.

Determine:

1. What currently happens?
2. What should happen?
3. What is the current execution/data path?
4. What is the source of truth?
5. Which files/classes/functions participate?
6. Which existing abstractions should be reused?
7. Are there legacy/parallel implementations nearby?
8. Which persisted data can be affected?
9. Which existing tests cover this area?
10. What could regress?

### Mandatory repository search

Search for:

- the user-visible label/action;
- state/event names;
- ViewModel handlers;
- use cases/domain functions;
- repositories/DAOs;
- persistence entities;
- tests;
- old/legacy implementations of the same concern.

Never assume a class is canonical solely from its name.

## 3. Root-cause / design statement

Before implementation, produce a short internal statement with:

```text
Current behavior:
Root cause / missing capability:
Canonical owner of the change:
Files likely affected:
Regression risks:
Validation:
```

For a feature, replace "Root cause" with "Required design change".

If the problem can be solved inside an existing abstraction, prefer that over adding another layer.

## 4. Change plan

Prefer microphases.

A good plan has small logical steps such as:

1. domain/persistence behavior;
2. ViewModel integration;
3. UI behavior;
4. tests;
5. validation.

Avoid editing many independent subsystems in one pass.

Do not combine unrelated cleanup/refactors with the requested task.

## 5. Implementation rules

While coding:

- preserve current naming/conventions;
- reuse existing domain services/calculators;
- keep one source of truth;
- keep persistence changes explicit;
- avoid hardcoded behavior already represented in state/config;
- keep solo and party behavior deliberately separated where their rules differ;
- do not weaken an invariant to make a test pass;
- do not replace a real integration path with UI-only state;
- keep Compose state presentation-focused;
- do not report progress as completion.

## 6. Compile early

After a coherent change, compile/build before stacking more changes on top.

This catches:

- wrong Compose APIs/imports;
- incompatible signatures;
- stale call sites;
- type/nullability mismatches;
- incorrect test fixtures;
- accidental API changes.

If compilation fails because of the new change, fix it before proceeding.

## 7. Test the changed layer

Run the narrowest relevant tests first, then broaden when needed.

Known baseline command:

```bash
./gradlew :app:testDebugUnitTest
```

Also run an appropriate app build/assemble task when available.

For domain-heavy changes, add or update tests around the actual invariant, not just around implementation details.

## 8. Behavior validation

Validate the actual requested behavior, not merely the code path.

Examples:

### Reordering

- perform reorder;
- leave/reopen screen;
- confirm persisted order.

### Start/configuration

- configure SOLO/DUO/TRIO;
- start workout;
- verify session/route uses the same mode;
- verify no double-start;
- verify a new start timestamp after abandoning/restarting.

### ETA

- advance;
- undo;
- skip;
- check that ETA recomputes from the updated route and current time.

### Party/recovery

- verify participant order;
- verify current participant;
- verify waiting recovery timers;
- verify solo path still works.

## 9. Diff review

Before declaring completion, inspect the final diff.

Ask:

- Did I modify unrelated files?
- Did I accidentally create a duplicate abstraction?
- Did I leave dead compatibility code?
- Did I change a public/domain contract unnecessarily?
- Did I introduce hardcoded data?
- Did I alter persisted semantics without migration/handling?
- Did I remove a previously corrected behavior?

## 10. Final checklist audit

Return to the task checklist created in step 1.

For every item/subitem, classify it as:

- PASS — validated;
- FAIL — does not work;
- NOT VERIFIED — validation was not possible.

Do not convert NOT VERIFIED into PASS based on code inspection alone.

## 11. Final report format

When the task is finished, report:

```text
1. Root cause / design implemented
2. Files changed
3. Important implementation decisions
4. What was deliberately not changed
5. Tests/build commands executed
6. Result of each validation
7. Remaining risks or NOT VERIFIED items
```

Never write only "done" or "implemented successfully".

## 12. When information appears missing

First search the repository and existing tests/docs.

Do not ask the user for information that the codebase can answer.

When a product/business decision is genuinely absent, do not invent a permanent rule. Make the smallest reversible implementation consistent with existing behavior and explicitly mark the assumption in the final report.

## 13. AI Studio task bootstrap

At the beginning of a new development task, follow this instruction:

```text
Read PROJECT_RULES.md, ARCHITECTURE.md, AGENT_WORKFLOW.md and DEFINITION_OF_DONE.md before modifying code. If this is a bug, also read BUG_FIX_PROTOCOL.md. If this is a feature, also read FEATURE_PROTOCOL.md. Investigate the current implementation and create a checklist with subchecks before editing. Treat the repository's corrected current code as authoritative when it conflicts with stale documentation. Do not create parallel architecture. Implement the smallest safe change, compile early, run relevant tests, validate the real behavior, review the final diff and audit the implementation against the original checklist before reporting completion.
```
