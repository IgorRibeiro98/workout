# Spark / Gym Tracker — Definition of Done

Use this checklist before declaring any implementation complete.

A task is not done because code was generated or because the UI renders once.

## 1. Requirement coverage

- [ ] The original request was converted into explicit acceptance criteria.
- [ ] Broad criteria were split into subchecks.
- [ ] Every required criterion/subcheck is PASS or explicitly reported as NOT VERIFIED/FAIL.
- [ ] No requirement was silently dropped.
- [ ] Non-goals were respected.

## 2. Architecture

- [ ] The current code was investigated before modification.
- [ ] The canonical source of truth was identified.
- [ ] No parallel implementation of the same domain behavior was introduced.
- [ ] No unrelated architecture/refactor was introduced.
- [ ] UI code does not own domain progression that belongs in the route/domain layer.
- [ ] Existing corrected implementations were preserved.

## 3. Compilation/build

- [ ] The affected module compiles.
- [ ] An appropriate application/module build or assemble task passes.
- [ ] No new compiler errors remain.
- [ ] No incompatible Compose API/import/signature was left unresolved.

## 4. Automated tests

- [ ] Relevant focused tests pass.
- [ ] `./gradlew :app:testDebugUnitTest` passes when applicable to the change.
- [ ] New domain behavior has regression coverage where practical.
- [ ] Tests validate behavior/invariants rather than merely implementation details.
- [ ] Tests were not weakened or made artificial simply to obtain green results.

## 5. Persistence/state

When the task touches durable or active-workout state:

- [ ] State survives recomposition.
- [ ] State survives screen recreation/configuration change where expected.
- [ ] Durable state is persisted in Room/DataStore rather than only Compose state.
- [ ] Reopening/reloading reproduces the expected state.
- [ ] Existing historical workout sessions remain valid.
- [ ] Template edits do not mutate historical sessions.

## 6. Workout start/configuration

When relevant:

- [ ] Configuration is not forced automatically on every start.
- [ ] Settings/config icon opens the intended configuration UI.
- [ ] Home preference equals effective session/route configuration.
- [ ] SOLO starts as SOLO.
- [ ] DUO starts as DUO.
- [ ] TRIO starts as TRIO when supported.
- [ ] Double-start is prevented.
- [ ] A fresh session receives a fresh `workoutStartedAt`.
- [ ] Abandon/restart does not reuse previous start time.

## 7. Route/navigation

When relevant:

- [ ] Persisted route/cursor is the execution authority.
- [ ] No second engine independently advances the workout.
- [ ] Instant-node advancement uses the updated route after each mutation.
- [ ] Stale/concurrent event guards remain intact.
- [ ] Undo changes persisted navigation state, not only UI.
- [ ] Skip/next/complete transitions persist correctly.

## 8. Recovery / timers

When relevant:

- [ ] Recovery uses the canonical anchor/timestamp.
- [ ] Timers do not remain attached to a previous node after fast advancement.
- [ ] Party waiting/recovery timers derive from canonical persisted state.
- [ ] Solo timer behavior remains correct.

## 9. ETA/time

When relevant:

- [ ] ETA = current time + current remaining route duration.
- [ ] ETA changes correctly after advance.
- [ ] ETA changes correctly after undo.
- [ ] ETA changes correctly after skip.
- [ ] ETA does not rely on stale original totals.
- [ ] No duplicate ETA calculator/formula was introduced.
- [ ] No unnecessary fixed 45/60/90-second assumptions were reintroduced.

## 10. Party mode

When relevant:

- [ ] Participant count comes from configuration/session state.
- [ ] Participant names/order are not hardcoded.
- [ ] Rotation is deterministic and correct.
- [ ] Current participant is correct.
- [ ] Recovery state for waiting participants is correct.
- [ ] REST nodes retain the correct relationship to their execution/set anchor.
- [ ] Solo mode still works after party changes.
- [ ] Reopen/recovery behavior is validated where applicable.

## 11. Workout builder / drag and drop

When relevant:

- [ ] Long press initiates drag without accidental normal click.
- [ ] Exercise reordering works inside the intended group.
- [ ] Group reordering works when part of scope.
- [ ] Ghost follows finger without noticeable coordinate drift.
- [ ] Target slot shows the intended placeholder/preview.
- [ ] Surrounding item movement remains stable/readable.
- [ ] Drop commits the new domain order.
- [ ] Order persists after leaving/reopening/reloading.

## 12. Exercise catalog/import

When relevant:

- [ ] Canonical exercise ID/reference is correct.
- [ ] Muscle/category classification is correct.
- [ ] Media belongs to the correct exercise.
- [ ] Missing media is handled intentionally rather than mapped incorrectly.
- [ ] Import is idempotent where expected.
- [ ] Existing custom/user data is preserved.
- [ ] Historical/template references remain valid.
- [ ] Invalid references are detected/reported.
- [ ] Offline use remains functional.

## 13. Compose/UI quality

When relevant:

- [ ] Layout is not tuned only to one screenshot/device.
- [ ] Long PT-BR text does not cause obvious overlap/clipping.
- [ ] Fixed dimensions are justified.
- [ ] Touch targets remain usable.
- [ ] Primary actions are clear/reachable.
- [ ] Active workout screen does not gain unnecessary scrolling.
- [ ] Focus Mode removes secondary information rather than merely shrinking it.
- [ ] No obvious visual regression was introduced on adjacent states.

## 14. Regression review

- [ ] Original bug/feature path was manually or programmatically validated.
- [ ] At least one adjacent high-risk path was validated.
- [ ] User-corrected code from previous iterations was not overwritten.
- [ ] Known regression patterns relevant to this area were checked.

## 15. Diff review

- [ ] Final diff was inspected.
- [ ] No unexplained unrelated files were changed.
- [ ] No dead temporary/compatibility code remains.
- [ ] No accidental hardcoded user/participant data was introduced.
- [ ] No duplicated source of truth was introduced.
- [ ] No public/domain contract changed without need.

## 16. Completion report

The final response must include:

- [ ] root cause or design implemented;
- [ ] files changed;
- [ ] important decisions;
- [ ] tests/build commands executed;
- [ ] validation results;
- [ ] any NOT VERIFIED item;
- [ ] remaining risk, if any.

If any required item is FAIL, do not claim the task is complete.
