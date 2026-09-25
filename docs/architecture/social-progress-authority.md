# Social Progress V2 — autoridade remota de consistência, XP, nível e conquistas

- **Tarefa:** T19.2 — Social Progress V2 (T19.2A consistência · T19.2B autoridade de gamificação ·
  T19.2C nível e conquistas).
- **Status (verificado em 2026-09-16): implementado.** Migration
  `0005_social_progress_consistency_parameters.sql`; `social-consistency.ts`,
  `social-gamification.ts` e `social-progress.source.ts` em `backend/src/modules/social/`;
  parâmetros de consistência no gateway e no `SocialProfileViewModel` do Android; conquistas
  renderizadas no perfil de amigo e na prévia.
- **Base:** [`social-profile-contract.md`](./social-profile-contract.md) (T17.2 — o pipeline, a
  privacidade e a semana canônica) e
  [`data-classification-matrix.md`](./data-classification-matrix.md) (T16 — o que é `DERIVED`).
- **Fixtures compartilhadas (lidas pelos testes dos dois lados):**
  [`contracts/social/v1/consistency-streak.json`](../../contracts/social/v1/consistency-streak.json)
  e [`contracts/social/v1/progress-projection.json`](../../contracts/social/v1/progress-projection.json).

**Não existe** nesta fase: seleção manual de conquistas em destaque, XP de recorde pessoal no
servidor, conquistas de `PERFORMANCE` publicáveis, ranking por nível, XP por ação social,
substituição da gamificação local pelo servidor.

---

## 1. O problema que a T19.2 resolve, e a regra que ela não pode quebrar

Depois da T17.2 o perfil social publicava **uma** das quatro métricas. Nível, sequência semanal e
conquistas respondiam `UNSUPPORTED`, porque a gamificação é `DERIVED` na matriz da T16: XP,
unlocks e eventos nunca saem do aparelho, e o servidor não tinha autoridade para afirmá-los.

A T19.2 não resolve isso aceitando o valor do aparelho. A regra central continua a da T17.2:

```text
fato canônico ──▶ servidor verifica/reconstrói ──▶ servidor deriva a métrica ──▶ privacidade ──▶ amigo
```

e **nunca** `cliente afirma ──▶ servidor acredita`. O que mudou é que o servidor passou a ter
**autoridade remota própria** sobre o que pode ser publicado: a regra canônica de consistência e a
parte reconstruível da gamificação, escritas em TypeScript sobre os fatos que já sincronizam, e
amarradas ao Kotlin por fixtures compartilhadas.

### Duas autoridades, e por que isso não é "duas implementações da mesma regra"

| | Gamificação local (Android) | Projeção social (servidor) |
| --- | --- | --- |
| Para quem | o **dono**, no aparelho | os **amigos** do dono |
| Sobre o quê | todo o histórico local, inclusive PRs e o que nunca sincronizou | só o que chegou por sync + parâmetros declarados |
| Ao vivo? | sim — anima barra de XP, comemora conquista, move missão | não — read model derivado na leitura, sem evento |
| Autoridade | operacional | sobre o que pode ser **publicado** |

A T17.2 proibia "recriar `ConsistencyCalculator` em TypeScript" porque duas implementações
divergem no primeiro ajuste. A T19.2 aceita a segunda implementação **com a condição** que a
T17.2 já usava para a semana canônica: uma fixture compartilhada que quebra o teste de quem mudar
um lado só. A divergência silenciosa era o problema; a divergência que reprova um teste é o
mecanismo.

---

## 2. T19.2A — consistência server-side

### A definição canônica (lida do código, não inventada)

`ConsistencyCalculator.calculateWeeklyConsistencies` + `calculateProgress`
(`app/src/main/java/com/example/domain/evolution/calculator/`):

```text
semana         segunda-feira da data LOCAL do dono (weekStart)
sessão         atribuída ao dia local de `startedAt` (o mesmo campo da semana canônica da T17.2)
meta da semana último snapshot de `weekly_goal_history` com effectiveFromWeek <= semana;
               sem snapshot → NOT_COUNTED (meta 0)
faixa          da segunda do início do acompanhamento (trackingStartedAt) até a segunda atual;
               treino anterior a ela não conta
status         atual: COMPLETED se treinos >= meta, senão IN_PROGRESS
               passada: COMPLETED ou MISSED
               primeira semana, iniciada depois de segunda e MISSED → NOT_COUNTED
sequência      atual COMPLETED conta 1; para trás, COMPLETED soma, NOT_COUNTED pula, o resto quebra
maior          maior corrida de COMPLETED (NOT_COUNTED transparente), inclui a atual quando COMPLETED
```

### O que faltava ao servidor, e como chegou

Das três entradas, o servidor tinha uma (sessões `COMPLETED`, em `sync_entities`). As outras duas
são **configuração do dono**, não progresso:

| Entrada | Onde vive no aparelho | Como chega ao servidor (T19.2A) |
| --- | --- | --- |
| meta por semana | `weekly_goal_history` (Room) | `PATCH /v1/social/me/progress-sharing { consistency: { weeklyGoals } }` |
| início do acompanhamento | DataStore `consistency_tracking_started_at` | `{ consistency: { trackingStartedAtEpochDay } }` |

Exatamente a categoria do `weekTimeZone` da T17.2: o fuso tornou a **semana** reproduzível; os
parâmetros tornam a **sequência** reproduzível. O que o servidor valida é a **forma** (inteiros,
segundas-feiras, meta 1..7, piso 2020-01-01, ≤ 520 snapshots); o que impede um parâmetro de virar
sequência inventada não é a validação, é a derivação — ela só conta sessões `COMPLETED` que de
fato foram sincronizadas. Com qualquer meta e qualquer início, a sequência remota é sempre
≤ a que os fatos suportam.

Os campos de **resultado** continuam recusados por nome, inclusive aninhados em `consistency`:
`streak`, `currentStreakWeeks`, `longestStreak`, `completedWorkouts`, `level`, `xp`, `unlocked`...
Um corpo com qualquer um deles invalida a requisição inteira (`400 INVALID_PROGRESS_SETTINGS`).

Persistência: `social_progress_settings.tracking_started_at_epoch_day` e
`social_progress_weekly_goals (owner_uid, week_start_epoch_day, goal)`. Um `PATCH` com
`consistency` **substitui o conjunto inteiro** na mesma transação — o histórico de metas é um
conjunto, e um conjunto meio substituído descreveria uma configuração que nunca existiu.

### Quando o app envia

`SocialProfileViewModel` recebe os parâmetros como função (montada em `MainViewModelFactory` a
partir de `ConsistencyRepository.getGoalSnapshots()` e `SettingsManager.trackingStartedAtFlow`; o
pacote social continua sem importar repositório de treino nem de gamificação):

- ao **abrir** "Compartilhar progresso", se o que o servidor devolveu em `settings.consistency`
  difere do local → um `PATCH` só com `consistency`, silencioso (não move interruptor, não gera
  aviso; a disponibilidade já diz "ainda não disponível" se falhar);
- em cada **toque** em um interruptor, `consistency` viaja junto quando difere do que o servidor
  conhece — a mesma regra do fuso.

Offline nada disso acontece, e nada fica pendente: não há Outbox social (T17.2 §10).

### Como o servidor calcula

`SyncedSocialProgressSource.project` lê, em **uma** consulta, quantos treinos `COMPLETED`
começaram em cada dia local do dono desde 2020-01-01 até o domingo da semana corrente
(`CanonicalTrainingSource.countCompletedWorkoutsPerDay`: janelas de dia calculadas em ICU por
`social-time.ts`, `UNNEST` + `COUNT(*)` por janela — o mesmo desenho de `countActiveDays` da T17.3).
Sai `Map<epochDay, contagem>`; nenhum timestamp de treino sai do SQL. `social-consistency.ts`
aplica a regra acima sobre esse mapa. O "hoje" é o relógio do **servidor** no fuso do dono.

`consistencyStreak` responde `AVAILABLE(currentStreakWeeks)` quando há fuso, parâmetros e ao menos
uma sessão sincronizada; `UNAVAILABLE` faltando qualquer um. **Nunca** `UNSUPPORTED`.

### Divergências identificadas e a decisão sobre cada uma

| Divergência | Decisão |
| --- | --- |
| `ConsistencyRepositoryImpl` usa `startedAt` no caminho `Flow` (tela) e `COALESCE(finishedAt, startedAt)` no `suspend` | servidor usa `startedAt`, o da tela e o de `weekly-window.json` (T17.2). Pré-existente, não corrigida aqui |
| histórico anterior à adoção da nuvem não está em `sync_entities` (só no backup) | semanas sem sessão sincronizada saem `MISSED` → a sequência remota é **≤ local** e converge conforme as semanas passam. Nunca infla |
| reinstalação zera `trackingStartedAt` no aparelho | o app reenvia o novo valor; servidor e aparelho voltam a concordar. Nunca infla |
| dois aparelhos com parâmetros diferentes | último a declarar vence — como o `weekTimeZone`. Parâmetro, não progresso |

---

## 3. T19.2B — autoridade remota de gamificação

### A matriz de origens de XP (`XP_SOURCE_AUTHORITY`, `social-gamification.ts`)

Investigada em `XpRewardPolicy` (v1), `GamificationEventRecorder`, `ConsistencyMilestoneEvaluator`,
`MissionEvaluator`/`MissionCatalog` (v1) e `WorkoutEngine`:

| Evento (`GamificationEventType`) | XP local | Dados server-side | Autoridade |
| --- | --- | --- | --- |
| `WORKOUT_COMPLETED` | 100 | `WORKOUT_SESSION` `COMPLETED` em `sync_entities` | **RECONSTRUCTABLE** |
| `FIRST_WORKOUT_COMPLETED` | 100 | ≥ 1 sessão sincronizada | **RECONSTRUCTABLE** |
| `WEEKLY_GOAL_COMPLETED` | 150 | sessões + parâmetros (T19.2A) → semana `COMPLETED` | **RECONSTRUCTABLE** |
| `MISSION_COMPLETED` (4 missões v1) | 150 / 150 / 100 / 200 | agregados de sessões por semana e da consistência | **RECONSTRUCTABLE** |
| `PERSONAL_RECORD_CREATED` | 50 | regra do `WorkoutEngine` sobre cargas de série; `dedupeKey` cita `exerciseId` **local**; ler cargas violaria `AGGREGATE_ONLY` | **UNSUPPORTED_SERVER_SIDE** |
| `STREAK_MILESTONE_REACHED` | — | não vale XP | RECONSTRUCTABLE (irrelevante) |
| `WORKOUT_STARTED`, `EXERCISE_COMPLETED`, `FIRST_EXERCISE_COMPLETED` | — | não valem XP | LOCAL_ONLY |

`VERIFIABLE` (cliente envia o fato, servidor verifica) **não é usado por nenhuma origem**: tudo o
que vale XP no servidor é reconstruído do que já sincroniza. Não foi criado protocolo de ingestão
de eventos — a identidade canônica de `sync_entities` (`syncId`) já resolve idempotência.

### O menor conjunto para paridade suficiente

O XP verificado é:

```text
100 × sessões COMPLETED
+ 100 se sessões ≥ 1
+ 150 × semanas COMPLETED da consistência                        (WEEKLY_GOAL_COMPLETED)
+ 150 × semanas com ≥ 3 sessões                                    (weekly_workouts_3)
+ 150 × semanas com ≥ 3 dias locais distintos com sessão          (weekly_training_days_3)
+ 100 × semanas COMPLETED da consistência                        (weekly_goal)
+ 200 se sessões ≥ 10                                              (total_workouts_10)
```

Cada linha corresponde a uma `dedupeKey` do motor local (`workout_completed:<sessão>`,
`first_workout_completed`, `weekly_goal:<semana>`, `mission_completed:<missão>:<período>`), e é
por isso que a forma fechada e o motor ao vivo dão o mesmo número. As duas missões contadas por
sessão consideram **todas** as semanas com sessão sincronizada, sem o filtro de início do
acompanhamento — `MissionEvaluator` também não o aplica a elas; `weekly_goal` segue a consistência.

### Por que o recorde pessoal fica fora, e o que isso significa

O nível verificado é um **limite inferior** do nível local: a curva é a mesma, o conjunto de fatos
não. Para um atleta que treina 3× por semana cumprindo a meta, o servidor credita 850 dos
850 + (50 × recordes) XP semanais — a diferença é só o que o servidor não tem como defender. A
tela do dono diz isso ("Nível verificado pelo servidor a partir dos treinos sincronizados. Pode
ser menor que o do aparelho, porque recordes pessoais não entram."). Publicar um nível que o
servidor não consegue reconstruir seria acreditar no cliente com outro nome.

Casos em que o servidor pode creditar o que o aparelho **perdeu** (e que também não são inflação —
os fatos existem): restore (`T16.5` limpa `gamification_events` e só recria o primeiro treino),
crash antes de `evaluateConsistency`, semana fechada por sessão vinda de outro aparelho via pull
(o evento local só nasce em conclusão ao vivo), treino que atravessa a meia-noite de domingo
(missão avaliada pela semana de `finishedAt`). Em todos, a regra é a mesma e o servidor aplica-a
aos fatos; o aparelho não teve a oportunidade.

---

## 4. T19.2C — nível e conquistas

### Nível

`levelFor(totalXp)` = `XpTransactionRepositoryImpl.calculateProgress`, linha por linha: nível 1
pede 500 XP; cada nível seguinte pede `nível × 500` (limiares acumulados 500, 1500, 3000,
5000...). `progress-projection.json` traz doze amostras da curva, verificadas contra a
implementação Kotlin real (via `XpTransactionRepositoryImpl.getUserProgress()`).

`level` responde `AVAILABLE` quando há fuso, parâmetros e ≥ 1 sessão — sem parâmetros o XP de
meta ficaria fora e o nível publicado seria menor do que o servidor já consegue defender.

### Conquistas (`REMOTE_ACHIEVEMENTS`, espelho do `AchievementCatalog` v1)

| Categoria | Conquistas | Fato server-side | Autoridade |
| --- | --- | --- | --- |
| `TRAINING` | `first_workout`, `10_workouts`, `25_workouts`, `50_workouts`, `100_workouts` | total de sessões `COMPLETED` | **RECONSTRUCTABLE** |
| `CONSISTENCY` | `streak_2_weeks` … `streak_52_weeks` | `longestStreakWeeks` da consistência (T19.2A) | **RECONSTRUCTABLE** (exige parâmetros) |
| `BODY` | `first_measurement`, `4_measurements`, `12_measurements`, `24_measurements` | dias locais distintos com `BODY_MEASUREMENT` sincronizada (`countBodyMeasurementsPerDay`, só contagem — nenhum valor de medida sai) | **RECONSTRUCTABLE** (exige fuso) |
| `PERFORMANCE` | `first_pr`, `5_prs`, `10_prs`, `25_prs` | contagem de `PERSONAL_RECORD_CREATED` | **UNSUPPORTED_SERVER_SIDE** — nunca publicadas |

Uma categoria cujo fato falta (`null`) não é avaliada — nem como obtida, nem como não obtida. A
lista publicada é o que o servidor **consegue afirmar agora**, na ordem do catálogo. Lista vazia é
`UNAVAILABLE` ("nada a afirmar"), não "zero conquistas".

O que o Android faz com a lista: o servidor envia só os ids canônicos; título e ícone vêm do
`AchievementCatalog` deste APK; id desconhecido é omitido. "Em destaque" hoje significa "todas as
verificáveis" — a seleção manual continua fora (§FUTURE).

### O aparelho continua sendo quem desbloqueia

`AchievementRepositoryImpl.evaluateAndUnlock` só persiste um unlock quando encontra `reachedAt`
(o N-ésimo treino, o evento de marco, a N-ésima medição). O servidor não escreve nada no aparelho,
não emite "nova conquista", não notifica e não entra em feed: a projeção é leitura, e há teste que
conta linhas de `social_notification_events`, `social_workout_checkins`,
`social_notification_deliveries` e `sync_changes` antes e depois de ler o perfil.

---

## 5. Disponibilidade depois da T19.2

| Métrica | `AVAILABLE` quando | `UNAVAILABLE` quando | `UNSUPPORTED` |
| --- | --- | --- | --- |
| Treinos da semana | fuso + ≥ 1 sessão sincronizada | sem fuso, ou nenhuma sessão | — |
| Consistência semanal | fuso + parâmetros + ≥ 1 sessão | faltando qualquer um | — |
| Nível | fuso + parâmetros + ≥ 1 sessão | faltando qualquer um | — |
| Conquistas em destaque | fuso + ≥ 1 conquista verificável | sem fuso, ou nenhuma afirmável | — |

`UNSUPPORTED` continua no contrato (o valor existe, o Android o interpreta, um servidor anterior
à T19.2 o responde) e **nenhuma métrica o produz** no servidor atual. O que ficou sem autoridade
remota — XP de recorde e conquistas de `PERFORMANCE` — é uma **parte** de duas métricas, e essa
parte simplesmente não entra no valor publicado.

**Desde a T19.H5 todo `UNAVAILABLE` carrega o motivo** (`availabilityReasons`, só para o dono), na
precedência fuso → sessão → parâmetros: `WEEK_TIME_ZONE_MISSING`, `NO_SYNCED_WORKOUTS`,
`CONSISTENCY_PARAMETERS_MISSING`. Conquistas vazias só existem sem sessão e sem medição sincronizada
(`first_workout` e `first_measurement` têm alvo 1), e respondem `NO_SYNCED_WORKOUTS`;
`social-progress-availability.spec.ts` prende essa premissa ao catálogo. O app declara fuso e
parâmetros sozinho ao abrir "Compartilhar progresso" com conexão; `NO_SYNCED_WORKOUTS` ganha a ação
"Sincronizar dados", que é o ciclo da T16 e não um sync social. Tabela completa, com as estatísticas
da T19.H3, em [`social-profile-contract.md`](./social-profile-contract.md) §H5.

---

## 6. Idempotência, rebuild e concorrência

Não existe tabela de projeção. `level`, `consistencyStreak`, `weeklyWorkoutCount` e
`highlightedAchievementIds` são **derivados na leitura** de `sync_entities` + parâmetros — o mesmo
desenho da pontuação de desafio (T17.3), pelo mesmo motivo: um treino do passado pode chegar
depois, e um contador precisaria de correção retroativa que ninguém escreveu.

Consequências, todas testadas em `social-progress-v2.spec.ts`:

- **replay de sync**: `sync_entities` tem chave `(owner_uid, entity_type, entity_sync_id)`; a mesma
  sessão empurrada três vezes, por dois aparelhos, é uma sessão. Nem XP, nem semana, nem conquista
  em dobro;
- **rebuild**: recalcular é ler de novo; três leituras seguidas são idênticas. Não há comando de
  reconstrução porque não há estado a reconstruir — o "rebuild" é `GET`;
- **restore/backup**: o backup não escreve em `sync_entities` (T16.6), então restaurar não cria
  fato novo no servidor; no aparelho, a gamificação é recalculada (T16.5) e a projeção social não
  a lê;
- **tombstone / não concluída / outro agregado / outra conta**: fora, pela cláusula `WHERE`;
- **account deletion**: `social_progress_weekly_goals` está no purge e no inventário
  (`account-uid-inventory.ts`); `sync_entities` sai no passo 1. Sem tabela de projeção, não há o
  que ressuscitar; a reconciliação de DR (`listAllOwnerUidsInDatabase`) não encontra a conta;
- **privacy disable vs read** e **block vs read**: decididos antes da projeção, como na T17.2.

---

## 7. Segurança e privacidade

- Sai do aparelho, além do que já saía: `trackingStartedAtEpochDay` e `weeklyGoals`
  (`{ weekStartEpochDay, goal }`). Os dois já estavam no **backup** (`weekly_goal_history` e a
  preferência `WEEKLY_GOAL`), ou seja, já eram classificados como dado da pessoa, não do aparelho.
  Nenhuma nota, carga, `machineLabel`, medida ou dado clínico passou a ser lido pelo social.
- Chega ao amigo, além do que já chegava: `level` (inteiro), `consistencyStreak` (inteiro) e
  `highlightedAchievementIds` (ids do catálogo). **Não** chegam: parâmetros, fuso, XP, breakdown,
  contagens por dia, datas de medição, nada de `PERFORMANCE`. Há sweep na resposta real.
- O SQL novo (`countPerDay`) devolve `(epoch_day, COUNT(*))`; o campo de instante do payload fica
  na cláusula do `JOIN`. `canonical-training.source.ts` continua sem `JSON.parse`, `exercises`,
  `loads`, `reps`, `notes`; `social-progress.source.ts` continua sem `.payload`,
  `entity_sync_id`, `server_revision` (testes estruturais de `social-logging.spec.ts`).
- Logs: inalterados — `fieldCount`, nunca valor.

---

## 8. O que a T19.2 deliberadamente não fez

- **Não** aceitou nenhum valor de progresso do cliente, nem criou canal de eventos verificáveis
  (`VERIFIABLE` está definido e vazio).
- **Não** portou recordes pessoais nem seus achievements: exigiria ler cargas de série no social e
  reimplementar `registerPersonalRecordIfImproved`; a `dedupeKey` local cita `exerciseId` do Room.
- **Não** ampliou o sync incremental (`WEEKLY_GOAL` continua fora dele, como a T16.6 decidiu); os
  parâmetros viajam pela rota social, com validação própria.
- **Não** criou tabela de projeção, comando de rebuild, job nem cache.
- **Não** alterou a gamificação local, o Workout, o restore, o backup, o Room (v34) nem a
  navegação. Sem conta, sem perfil social ou com o servidor fora, tudo continua exatamente como
  estava.

### FUTURE

- seleção manual de conquistas em destaque (o servidor agora consegue validar "foi obtida");
- recorde pessoal como fato verificável (exigiria decisão explícita sobre ler cargas no social);
- reenvio dos parâmetros a partir da própria tela de meta semanal (hoje, ao abrir "Compartilhar
  progresso" ou em qualquer toque nela).
