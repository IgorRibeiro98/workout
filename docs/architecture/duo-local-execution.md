# Treino em dupla local — `SOLO` e `DUO_LOCAL`

- **Tarefa:** T19.4.
- **Status (verificado em 2026-09-16):** implementado. Room `version = 39` (`MIGRATION_38_39`);
  Android `data/local/WorkoutParticipantEntities.kt`, `domain/workout/execution/DuoExecution.kt`,
  `WorkoutEngine` (início, espelho e descanso do convidado), `ExecutionViewModel` (predicado de
  pendência com dimensão de participante), `ExecutionScreen` + `DuoTurnBanner`, `TodayScreen`
  (entrada "Treinar em dupla"). Backend: **nenhuma alteração**.
- **O que este documento é:** o contrato do modo dupla e o registro do que ele **não** é. Os
  documentos raiz (`ARCHITECTURE.md` §9, `PROJECT_RULES.md` §7) descreviam um "Party mode" com
  `PartyRouteBuilder`, rotas e nós que **não existem no código** — continuam sendo direção
  pretendida; o runtime implementado é o daqui.

## 1. O princípio

```text
duas pessoas  +  um aparelho  +  uma única execução local
```

```text
Supino, 3 séries

Owner série 1  →  Guest série 1  →  Owner série 2  →  Guest série 2  →  Owner série 3  →  Guest série 3
```

A dupla **estende a execução que já existia** — `WorkoutEngine` + `ExecutionViewModel` sobre as
entidades de sessão em Room — com uma dimensão a mais: o participante. Não existe uma segunda
engine, uma segunda máquina de estados nem uma segunda sessão. `SOLO` continua sendo o padrão e o
caminho canônico: numa sessão solo nenhuma tabela de participante é lida, e cada regra da tela é
literalmente a mesma de antes (o `duo` é `null`).

Não faz parte desta versão: `TRIO`, guest com conta, histórico ou XP próprios do guest, exercícios
diferentes por participante. Dois celulares é a **T19.5** (`DUO_REMOTE`,
[`multiplayer-remote.md`](multiplayer-remote.md)) — outro modo, com outra sessão em cada aparelho.

## 2. Autoridades

| Recurso | Fonte de verdade |
| --- | --- |
| Modo da sessão | `workout_sessions.executionMode` (`SOLO` / `DUO_LOCAL`), gravado no início |
| Participantes | `workout_session_participants` — uma linha `OWNER`, uma `GUEST`, ordem por `position` |
| Séries do dono | `set_logs`, exatamente como em solo |
| Séries do convidado | `workout_guest_set_logs` — espelho operacional, uma linha por `(participante, exercício, setNumber)` |
| Participante atual | **derivado** das séries persistidas dos dois (`DuoTurnResolver`), nunca de índice visual |
| Descanso do dono | temporizador do aparelho (`SettingsManager.restTimerDeadline` + `WorkoutEngine.restTimerTarget`), com notificação e restauração de sempre |
| Descanso do convidado | `workout_session_participants.restEndsAt`, timestamp |
| Preferências | `SettingsManager` / DataStore, sem chave nova |
| Backend / Social | **N/A** — nada da dupla é requisito online, e nada do convidado sai do aparelho |

## 3. Identidade

- **Sessão:** uma só, do dono, com o `syncId` de sempre. O convidado **não** ganha `WorkoutSession`.
- **Participante:** `workout_session_participants.id` é a identidade técnica dentro da execução.
  `displayName` é rótulo — nunca chave, nunca identidade. O dono não tem nome local (o Spark não
  guarda nome de perfil): a tela mostra "Você".
- **Convidado:** sem conta, sem `socialId`, sem Firebase UID, sem `friendCode`, sem e-mail. Um nome
  digitado na hora de iniciar, e só.

## 4. A regra de alternância

`DuoTurnResolver.resolve(duo, exercício)`:

1. a série da vez é a **menor** `setNumber` em que o dono **ou** o convidado ainda não concluiu;
2. dentro dela, o dono vem antes do convidado;
3. uma série do dono sem espelho do convidado conta como pendente para o convidado (a ausência de
   linha nunca faz uma série "pular"; o motor insere a linha ao gravar);
4. sem série pendente para nenhum dos dois, não há vez: o exercício está concluído e a fase é a
   transição, conduzida pelo dono.

`ExecutionState.isPending(exercício)` é a **única** definição de "este exercício ainda tem série a
fazer" — em solo, a regra de sempre; em dupla, também enquanto o convidado tiver série. Todo cursor
(`currentExerciseIndex` na abertura, `nextExercise`, `nextPendingExercise`, `isLastPendingExercise`,
`isExerciseCompleted`, `isAllExercisesCompleted`, status na lista de exercícios) passa por ela.

Os dois participantes percorrem o **mesmo** exercício; não há "dono no Supino, convidado no
Agachamento".

## 5. Descansos: dois relógios, os dois por timestamp

```text
Owner conclui série 1   →  descanso do Owner começa (temporizador do aparelho)
Guest executa série 1   →  o relógio do Owner continua correndo
Guest conclui série 1   →  descanso do Guest começa (restEndsAt)
Owner: se o descanso dele ainda corre, a tela mostra o descanso; senão, a série 2
```

- A fase `RESTING` é decidida pelo descanso do **participante da vez**
  (`ExecutionState.currentParticipantRestTarget`). O descanso do outro aparece no
  `DuoTurnBanner` ("João: descansando 0:42" / "pronto"), derivado do timestamp a cada segundo.
- `+15s`, `+30s` e "pular" na tela de descanso levam o participante **capturado na composição**:
  um toque atrasado nunca alcança o relógio do outro.
- A duração vem da mesma `resolveRestRecommendation` do dono: entre séries, ou entre exercícios
  quando aquele participante concluiu a última série dele no exercício.
- O treino inteiro concluído (os dois) zera os dois relógios, como em solo.
- Não há notificação de sistema para o descanso do convidado nesta versão; o alerta em tela
  (`FocusedRestView`) vale quando ele é o participante da vez.
- `RestCompletionBehavior` (T19.9) vale para os dois relógios, porque os dois passam pela mesma
  `FocusedRestView`: em `MANUAL_OVERTIME` o descanso do convidado também para em `RESTING` além do
  zero — sem avançar sozinho — até o toque de quem está com a tela. Não é uma preferência por
  participante; é a mesma preferência do dono, aplicada onde quer que o timestamp mande.

## 6. Persistência e recovery

Tudo o que a alternância precisa está no Room: modo, participantes, séries dos dois, `restEndsAt`
do convidado. O descanso do dono continua no DataStore, restaurado por `restoreTimerState()` como
sempre. Uma morte de processo entre "dono concluiu" e "convidado apareceu" reabre **na vez do
convidado**, porque a vez sai das séries gravadas e não de nada que vivia em memória — coberto por
`ExecutionViewModelDuoTest`.

Migração 38 → 39, aditiva: `ADD COLUMN executionMode ... DEFAULT 'SOLO'` e duas tabelas novas.
Toda sessão anterior é `SOLO`. `set_logs` não muda.

## 7. O que o convidado nunca recebe — por construção

`workout_guest_set_logs` não é lida por PR (`evaluatePersonalRecords`), eventos de gamificação
(`publishWorkoutEvents`), estatísticas (`StatsEngine`), calendário/histórico
(`SessionCalendarSummary`), `SyncAggregateSnapshotBuilder` (sync **e** backup) nem export. Logo:

| | Owner | Guest |
| --- | --- | --- |
| `WorkoutSession` | uma, a de sempre | nenhuma |
| PR / 1RM / volume | regra de sempre, só sobre `set_logs` | nada |
| XP / conquistas / missão | uma consequência por sessão, a de sempre | nada |
| histórico / calendário | a sessão, com as séries dele | nada |
| sync / backup | o agregado de sempre (sem `executionMode`, sem convidado) | nada sai do aparelho |

`WorkoutEngineDuoTest` prova as três linhas que importam: PR ignora a carga do convidado, o resumo
tem só as séries do dono, e o payload do agregado de sync não contém nome nem carga do convidado.

As linhas do convidado ficam anexadas à sessão (cascade no delete, apagadas explicitamente no
restore) como estado operacional — não são histórico do convidado e nenhuma tela as lista depois
do treino.

## 8. Ciclo de vida

- **Início:** `TodayViewModel.startWorkout(templateId, mode, guestDisplayName)` →
  `WorkoutEngine.startSession(...)`. Mesma trava (`sessionLifecycleMutex`), mesma transação, mesma
  proteção contra toque duplo (`isStartingWorkout`). Dupla sem nome de convidado é
  `IllegalArgumentException` — nunca uma dupla anônima nem um solo silencioso.
- **Séries:** dono → `updateSet`/`completeSet` (inalterados); convidado → `updateGuestSet` /
  `completeGuestSet`, sempre com a linha do convidado **na intenção**, capturada pela tela.
- **`addSet` / `removeSet`** do dono espelham a série para o convidado.
- **Sessão concluída não recebe série:** `updateSet` e `completeGuestSet` descartam a escrita se a
  sessão não está `IN_PROGRESS`.
- **Finish / cancel:** a regra de sempre, mais `clearParticipantRests`. Uma `WorkoutSession`
  `COMPLETED`, do dono.
- **Account switch / logout:** a política existente não muda — a sessão é do aparelho e continua
  `IN_PROGRESS`; o restore continua recusando com `WORKOUT_IN_PROGRESS`. O convidado nunca vira
  conta.

## 9. Tela

- **Hoje:** "INICIAR TREINO" continua sendo solo, um toque. "TREINAR EM DUPLA" abre um bottom sheet
  com o nome do convidado. Nenhuma configuração é imposta a quem não pediu.
- **Execução:** chip "DUPLA" no cabeçalho; `DuoTurnBanner` com "VEZ DE", "DEPOIS" e o descanso de
  quem espera; a mesma `FocusedActiveSetView` para os dois, com `participantKey` nas chaves de
  `remember` (a série 2 do dono e a série 2 do convidado são linhas de tabelas diferentes) e o
  nome de quem conclui no botão. Contexto de histórico, recorde e dica do Coach não aparecem na vez
  do convidado — ele não tem nenhum dos três.
- "Ver todas as séries", "Sincronizar séries" e "Último treino" são do dono, em qualquer vez.

## 10. Futuro (fora desta tarefa)

Participantes recentes, `TRIO`, guest com conta reivindicando as próprias séries, histórico próprio
do guest, notificação de sistema para o descanso do guest, multiplayer remoto (T19.5).
