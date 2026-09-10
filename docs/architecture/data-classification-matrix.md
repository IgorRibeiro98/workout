# Matriz de dados do Spark — classificação para sincronização

- **Tarefa:** T16.0 (classificação) — revisada contra o código na T16.3, T16.4, T16.5 e **na T16.6**
- **Base:** código real em `app/src/main/java/com/example/data/` (Room `version = 34` desde a
  T16.6) e `SettingsManager` (DataStore), relidos em 2026-09-07.
- **Status:** a coluna `syncId` **está implementada** para as raízes de agregado do Grupo A, a
  Outbox existe e é transacional, desde a **T16.4** o Grupo A **sobe** como snapshot completo
  (depois de adoção explícita), desde a **T16.5** ele **volta** por ação explícita substituindo o
  dataset local, e desde a **T16.6** seis dos oito agregados **convergem incrementalmente** entre
  aparelhos da mesma conta. Conflitos e exclusões são detectados e preservados, não resolvidos:
  isso é T16.7.

## Matriz de sincronização incremental (T16.6)

A coluna que faltava desta vez. Para cada agregado, qual é a semântica de atualização — e ela
**não** é genérica: `UPSERT qualquer coisa` trataria uma sessão concluída como documento
colaborativo.

| Agregado | Mutável? | Append-only? | Delete hoje | Sync T16.6? | Política |
| --- | --- | --- | --- | --- | --- |
| `WORKOUT_PROGRAM` | sim | não | físico local, **não propaga** | **sim** | snapshot com `serverRevision`; stale = conflito |
| `WORKOUT_TEMPLATE` | sim | não | físico local (cascata), **não propaga** | **sim** | snapshot do treino inteiro, filhos substituídos; stale = conflito |
| `WORKOUT_SESSION` (`COMPLETED`) | **não** | sim | físico local, **não propaga** | **sim** | histórico imutável: `revision = 1` e nunca mais; divergência = `IMMUTABLE_HISTORY_CONFLICT` |
| `CUSTOM_EXERCISE` | sim | não | físico local, **não propaga** | **sim** | snapshot; precisa chegar **antes** dos treinos que o referenciam |
| `BODY_MEASUREMENT` | sim | não | físico local, **propaga com tombstone** (T16.7) | **sim** | snapshot; medidas distintas coexistem — conflito é editar a **mesma** medida |
| `CHECK_IN` | sim | não | físico local, **não propaga** | **sim** | snapshot; referência de sessão pode ficar nula até ela chegar |
| `EXERCISE_OVERRIDE` | sim | não | cascata com o exercício | **não** | só backup completo — escrito hoje por ViewModel direto no DAO |
| `WEEKLY_GOAL` | sim | não | físico local | **não** | só backup completo — derivado de preferência do DataStore |
| `USER_PREFERENCES` | sim | não | — | **não** | só backup completo — DataStore não participa da transação Room |
| Catálogo canônico, conteúdo premium | — | — | — | **nunca** | conteúdo do app, vem do manifesto versionado |
| Gamificação, XP, conquistas, PRs | — | — | — | **nunca** | derivado; cada aparelho recalcula do histórico |
| Preferências de aparelho, timer, `deviceId` | — | — | — | **nunca** | descrevem a instalação, não a pessoa |

Sessões `IN_PROGRESS`/`PAUSED`/`PLANNED`/`CANCELLED` continuam fora: o registry de payload aceita
**apenas** `COMPLETED`, dos dois lados.

Os três `não` da coluna "Sync T16.6?" são a pendência que a T16.4 registrou e que a T16.6
**não** fechou — com motivo, e com o servidor recusando-os explicitamente em vez de aceitá-los pela
metade. Ver `sync-protocol.md`, "Três agregados ainda fora do incremental".

> **A coluna "Estratégia de restore" deixou de ser plano.** Ela está implementada em
> `com.example.data.restore` e é exercitada por teste de ida e volta (dataset → backup → servidor →
> restore → dataset semanticamente idêntico). Onde a estratégia mudou na implementação, a linha foi
> corrigida abaixo — o código executável é a autoridade.

## Matriz de backup (T16.4)

A coluna que faltava. Para cada dado, uma de quatro decisões — e a decisão é esta tabela, não o
código do montador de snapshot.

| Dado / agregado | Backup? | Motivo | ID portátil | Schema version | Estratégia de restore (T16.5) |
| --- | --- | --- | --- | --- | --- |
| `workout_programs` | **BACKUP** | criado pelo usuário, insuperável se perdido | `syncId` (UUID) | `entitySchemaVersion` 1 | recriar por `syncId` |
| `workout_templates` (+ `workout_template_exercises`) | **BACKUP** | idem; filhos viajam no snapshot da raiz | `syncId` da raiz | 1 | recriar o agregado inteiro |
| `workout_sessions` `COMPLETED` (+ `exercise_sessions`, `set_logs`) | **BACKUP** | histórico do que aconteceu | `syncId` da raiz | 1 | inserir se ausente; divergência = conflito de integridade, nunca sobrescrita |
| `workout_sessions` `PLANNED` | **DERIVED** | derivável do template e da agenda | — | — | não restaura (não entra no snapshot) |
| `workout_sessions` `IN_PROGRESS` / `PAUSED` | **LOCAL_ONLY** | execução **neste** aparelho; sincronizar faria dois aparelhos disputarem o mesmo cursor | — | — | não restaura |
| `workout_sessions` `CANCELLED` | **LOCAL_ONLY** | **T16.6 confirmou:** continua local, e o registry de payload aceita só `COMPLETED` | — | — | não restaura |
| `exercises` com `isUserCreated = 1` | **BACKUP** | criado pelo usuário | `syncId` (UUID) | 1 | recriar por `syncId` |
| `exercises` de catálogo | **LOCAL_ONLY** | conteúdo do app, vem do manifesto versionado | `canonicalId` (já global) | — | reinstalar pelo manifesto |
| `exercise_user_overrides` | **BACKUP** (sem `customPhotoUri`) | customização pessoal | identidade **do exercício alvo**: `canonical:<id>` ou `custom:<uuid>` | 1 | aplicar sobre o exercício resolvido; `customPhotoUri` volta nulo |
| `body_measurements` | **BACKUP** | dado pessoal insubstituível | `syncId` (UUID) | 1 | inserir por `syncId` |
| `check_ins` | **BACKUP** | dado pessoal | `syncId` (UUID) | 1 | inserir por `syncId` |
| `weekly_goal_history` | **BACKUP** | histórico de meta do atleta | `week:<epochDay>` (PK natural, já global) | 1 | inserir por semana |
| Preferências do atleta (`WEEKLY_GOAL`, `USE_KG`, `DEFAULT_REST_SECONDS`, `DEFAULT_EXERCISE_REST_SECONDS`, `RIR_RPE_ENABLED`, `AUTO_REST_TIMER_ON_SET`) | **BACKUP** | descrevem a pessoa, não o aparelho | `preferences` (singleton) | 1 | aplicar no DataStore |
| Preferências do aparelho (tema, som, vibração, tela ligada, notificação de timer, GIFs) | **LOCAL_ONLY** | dependem da tela, do hardware e do contexto de uso | — | — | não restaura |
| Estado do timer de descanso | **LOCAL_ONLY** | estado transitório de execução; restaurar dispararia timer em outro aparelho | — | — | não restaura |
| `gamification_events`, `xp_transactions`, `achievement_unlocks`, `personal_records`, nível/XP/streak | **DERIVED** | reconstruíveis do histórico pelas regras que já existem | — | — | **T16.5:** limpos dentro da transação de restore e reconstruídos pelas reconciliações da abertura (`XpReconciler`, `AchievementReconciler`, `MissionReconciler`). Zero XP/conquista novos |
| `exercise_alternatives` e catálogo premium | **LOCAL_ONLY** | conteúdo do app, do manifesto | — | — | reinstalar pelo manifesto |
| Fotos personalizadas (`customPhotoUri` em `exercises` e `exercise_user_overrides`) | **LOCAL_ONLY** | é `content://` deste aparelho; sem object storage na T16.4, e Base64 no snapshot seria contornar a decisão | — | — | permanece local — a UI avisa |
| Chave da ExerciseDB | **LOCAL_ONLY** | credencial | — | — | não restaura |
| `deviceId`, estado da nuvem, versões de conteúdo instaladas | **LOCAL_ONLY** | identidade/estado da instalação | — | — | não restaura |
| `sync_outbox`, `backup_attempts`, `cloud_data_binding`, `restore_attempts`, `sync_entity_metadata`, `sync_cursor`, `sync_conflicts` | **LOCAL_ONLY** | mecanismo interno, não dado do usuário | — | — | não restaura. **T16.5/T16.6:** a Outbox, a revision conhecida, o cursor e os conflitos são zerados **dentro do commit** do restore — eles descreviam o dataset que acabou de ser substituído |
| Firebase ID Token, credencial Google, App Check, credencial/prompt/contexto/resposta do Gemini | **nunca** | segredo ou estado transitório | — | — | — |
| Vínculo `uid` ↔ dataset no servidor, `sync_entities` (com tombstone desde a T16.7), `sync_changes`, `sync_mutations`, quota de IA | **SERVER_ONLY** | só faz sentido com identidade autenticada; **existe desde a T16.6** | — | — | — |

**Três agregados entram no backup e continuam sem mutação incremental** — `EXERCISE_OVERRIDE`,
`WEEKLY_GOAL` e `USER_PREFERENCES`. A T16.6 **não** fechou essa pendência, e a decisão está
documentada com o motivo de cada um em `sync-protocol.md`. O servidor os recusa com `UNSUPPORTED`
em vez de aceitar pela metade, e o snapshot completo continua cobrindo os três. Segue registrado
como pendência em `ARCHITECTURE.md`, agora endereçada à T16.7.

## Correções feitas na T16.3 contra o código real

A matriz original da T16.0 divergia da implementação em quatro pontos. O código executável venceu:

| Item | O que a T16.0 dizia | O que o código diz |
| --- | --- | --- |
| `exercise_alternatives` | "alternativas **definidas pelo usuário**", sincronizável | o único escritor é o `ManifestImporter`. É conteúdo de catálogo, não dado pessoal. **Não sincroniza e não recebe `syncId`.** |
| Grupos de treino | a matriz falava em "exercício do template" como filho | não existe tabela de **grupos**. A estrutura real é `workout_templates` → `workout_template_exercises`, com `sortOrder`. |
| `workout_template_exercises`, `exercise_sessions`, `set_logs` | "syncId: sim" | **não recebem `syncId`.** São filhos de agregado, viajam no snapshot da raiz e nunca são referenciados de fora. Ver [agregados](#agregados-de-sincronização). |
| `exercise_user_overrides` | "syncId: sim" | **não recebe `syncId` próprio.** Sua identidade global é a do exercício que ele customiza — dois aparelhos que customizam o mesmo supino estão falando da mesma coisa, e dois UUIDs aleatórios criariam duas customizações concorrentes para um exercício só. |

## Como ler

- **Autoridade atual** — quem decide o valor hoje.
- **Precisa de syncId** — se a entidade precisa de identidade global estável para convergir entre
  dispositivos (ver [`identity-contract.md`](./identity-contract.md)).
- **Estratégia** — `sync` | `derived` | `local` | `server-only`.
- **Fase** — em qual tarefa da T16 o item passa a existir remotamente.

---

## Agregados de sincronização

A sincronização **não** espelha o Room tabela a tabela. A unidade é o agregado: a raiz tem
identidade global, é serializada inteira e produz **uma** mutação.

| Agregado | Raiz (tabela) | Filhos | Identidade | Gera Outbox | Estratégia futura |
| --- | --- | --- | --- | --- | --- |
| `WORKOUT_PROGRAM` | `workout_programs` | — | `syncId` | sim | snapshot |
| `WORKOUT_TEMPLATE` | `workout_templates` | `workout_template_exercises` (ordem + configuração de séries) | `syncId` da raiz | sim | snapshot do treino inteiro |
| `WORKOUT_SESSION` | `workout_sessions` | `exercise_sessions` → `set_logs` | `syncId` da raiz | sim, ao concluir | snapshot histórico **imutável** quando `COMPLETED` |
| `CUSTOM_EXERCISE` | `exercises` com `isUserCreated = 1` | — | `syncId` | sim | snapshot |
| `BODY_MEASUREMENT` | `body_measurements` | — | `syncId` | sim | snapshot |
| `CHECK_IN` | `check_ins` | — | `syncId` | sim | snapshot |
| `EXERCISE_OVERRIDE` | `exercise_user_overrides` | — | identidade do exercício alvo (`canonicalId` ou `syncId`) | não (T16.4) | snapshot com chave derivada |
| `WEEKLY_GOAL` | `weekly_goal_history` | — | `effectiveFromWeekStartEpochDay` (PK natural já global) | não (T16.4) | snapshot por semana |

Editar o nome de um treino, mover um exercício e mudar a carga de uma série produzem, os três, a
mesma coisa: `UPSERT WORKOUT_TEMPLATE <syncId>`. Não existem `CHANGE_TEMPLATE_NAME` ou
`MOVE_EXERCISE` — para um sync de snapshot, seriam nomes diferentes para o mesmo push.

### Por que os filhos não têm identidade própria

Um filho ganharia `syncId` se fosse referenciado de fora, editado de forma independente ou tivesse
ciclo de vida próprio. Nenhum destes é:

- `workout_template_exercises` — só existe dentro do treino, CASCATA com ele, e nada aponta para
  ele;
- `exercise_sessions` e `set_logs` — são o conteúdo de uma sessão concluída, que é histórico
  imutável enviado de uma vez;
- adicionar UUID a cada série multiplicaria o banco e a fila sem nada consumir esses ids.

---

## Grupo A — Dados pessoais canônicos sincronizáveis

Criados pelo usuário, insubstituíveis se perdidos, e com significado igual em qualquer dispositivo.

Colunas: **raiz/filho** dentro do agregado; **mutável** (o conteúdo pode mudar depois de criado);
**delete** (comportamento local hoje); **histórico imutável**; **gera Outbox** (na T16.3);
**dono futuro** (quem será `ownerUid` quando houver adoção explícita).

| Domínio | Tabela Room | Autoridade | Identidade hoje | syncId (T16.3) | Raiz/filho | Mutável | Delete | Histórico imutável | Gera Outbox | Dono futuro | Estratégia | Conflito esperado |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Programa de treino | `workout_programs` | Room | `id` (+ `externalId` para conteúdo importado) | **sim**, `NOT NULL` + `UNIQUE` | raiz | sim | físico local | não | sim | conta | sync | última `revision` vence |
| Template de treino | `workout_templates` | Room | `id` | **sim**, `NOT NULL` + `UNIQUE` | raiz | sim | físico local (cascata nos filhos) | não | sim | conta | sync (snapshot) | última `revision` vence |
| Exercício do template | `workout_template_exercises` | Room | `id` | **não** — viaja no snapshot do treino | filho | sim | cascata | não | não (o pai gera) | conta | dentro do agregado | resolvido com o pai |
| Sessão de treino | `workout_sessions` | Room | `id` | **sim**, `NOT NULL` + `UNIQUE` | raiz | só até concluir | físico local | **sim, quando `COMPLETED`** | sim, ao concluir | conta | snapshot histórico | divergência = conflito de integridade |
| Exercício da sessão | `exercise_sessions` | Room | `id` | **não** | filho | idem raiz | cascata | sim | não (a sessão gera) | conta | dentro do agregado | herda a raiz |
| Série executada | `set_logs` | Room | `id` | **não** | filho | idem raiz | cascata | sim | não (a sessão gera) | conta | dentro do agregado | herda a raiz |
| Exercício criado pelo usuário | `exercises` com `isUserCreated = 1` | Room | `id` | **sim** (coluna anulável; preenchida só para `isUserCreated = 1`) | raiz | sim | físico local | não | sim | conta | sync | última `revision` vence |
| Customização de exercício | `exercise_user_overrides` | Room | `exerciseId` (PK = FK) | **não** — a identidade é a do exercício alvo | raiz | sim | cascata com o exercício | não | não (T16.4) | conta | snapshot com chave derivada | última `revision` vence |
| Medidas corporais | `body_measurements` | Room | `id` | **sim**, `NOT NULL` + `UNIQUE` | raiz | sim | físico local | não | sim | conta | sync | mesma data + conteúdo divergente = conflito |
| Check-in na academia | `check_ins` | Room | `id` | **sim**, `NOT NULL` + `UNIQUE` | raiz | sim | físico local | não | sim | conta | sync | última `revision` vence |
| Meta semanal (histórico) | `weekly_goal_history` | Room | `effectiveFromWeekStartEpochDay` (PK natural, já global) | **não** — a PK já é global | raiz | sim | físico local | não | não (T16.4) | conta | sync por semana | última `revision` vence |
| Alternativas de exercício | `exercise_alternatives` | **manifesto** (`ManifestImporter`) | `id` + índice único `(exerciseId, alternativeExerciseId, type)` | **não** | — | — | cascata | não | não | n/a — é catálogo | **local** (conteúdo do app) | n/a |

### Política por status de sessão

`workout_sessions` guarda quatro situações diferentes na mesma tabela. Identidade global é dada a
todas — `syncId` responde "qual sessão é esta", não "esta sessão sincroniza". A política de envio é
outra coisa, e desde a T16.6 ela **está implementada**:

| Status | Significado | Registra mutação | Política |
| --- | --- | --- | --- |
| `PLANNED` | sessão prevista, nunca executada | não | provavelmente não sincroniza: é derivável do template e da agenda |
| `IN_PROGRESS` / `PAUSED` | execução **em andamento neste aparelho** | não | não sincroniza como estado vivo. Sincronizar um treino em execução faria dois aparelhos disputarem o mesmo cursor de execução; a decisão de "retomar treino em outro aparelho" é de produto e não foi tomada |
| `CANCELLED` | abandonada | não | **T16.6 manteve local.** Não é histórico de treino, e propagá-la exigiria decidir o que ela significa no outro aparelho — decisão que continua sem dono |
| `COMPLETED` | histórico | **sim** | **T16.6:** snapshot imutável, `revision = 1`, divergência é `IMMUTABLE_HISTORY_CONFLICT` |

**Nota sobre `workout_sessions`:** este é o grupo mais sensível da matriz. Uma sessão `COMPLETED`
registra o que de fato aconteceu. Sincronização não pode reescrevê-la — ver
[`sync-protocol.md`](./sync-protocol.md#histórico-concluído).

---

## Grupo B — Dados derivados / recalculáveis

Reconstruíveis a partir do Grupo A pelas regras determinísticas que já existem no app
(`XpReconciler`, `AchievementReconciler`, `ConsistencyMilestoneEvaluator`, `XpCalculatorService`).

Sincronizar o **resultado** destes dados criaria uma segunda fonte de verdade: o servidor passaria
a ter uma opinião sobre XP que poderia divergir da regra local. A decisão é sincronizar o **fato**
(Grupo A) e deixar cada dispositivo recalcular.

| Domínio | Tabela Room | Autoridade atual | Derivado de | syncId | Estratégia | Fase |
| --- | --- | --- | --- | --- | --- | --- |
| Evento de gamificação | `gamification_events` | Room (`dedupeKey` único) | sessões concluídas, PRs, consistência | não | derived — recalculável; `dedupeKey` já garante idempotência local | — |
| Transação de XP | `xp_transactions` | Room (`eventId` único) | `gamification_events` + `XpRewardPolicy` | não | derived | — |
| Conquista desbloqueada | `achievement_unlocks` | Room | eventos + definições versionadas | não | derived | — |
| Recorde pessoal (PR) | `personal_records` | Room | `set_logs` | não | derived | — |
| Nível / XP total / streak | não persistido (calculado em `UserProgress`) | Domínio | `xp_transactions` | não | derived | — |

**Confirmado na T16.3 contra o código:** `personal_records` é gravado por
`WorkoutEngine.registerPersonalRecordIfImproved`, a partir das séries concluídas da sessão, com a
mesma regra que `evaluatePersonalRecords` aplica ao concluir o treino — é **derivado do histórico**
e reproduzível a partir dele. `gamification_events` já tem `dedupeKey` único e
`xp_transactions` já tem `eventId` único, então a idempotência local existe sem `syncId`. Nenhuma
das cinco linhas abaixo recebeu identidade global.

**Consequência aceita, e agora implementada (T16.5):** ao restaurar, a gamificação é **recalculada**
a partir do histórico restaurado, não copiada. A transação de restore limpa as quatro tabelas junto
com o histórico que elas descreviam — inclusive porque as `dedupeKey` daqueles eventos citam
`localId` de sessões que o restore regenerou, e mantê-las faria eventos futuros serem suprimidos por
engano. As reconciliações que já rodam na abertura do app reconstroem o que o histórico prova.

O restore **não premia nada**: ele escreve por DAO, fora do `WorkoutEngine`, e nenhum evento é
publicado. Há teste verificando zero XP, zero conquista e zero recorde depois de restaurar um
histórico inteiro.

**Revisão feita na T16.4 e confirmada na T16.5:** os cinco continuam **fora** do backup. Há teste
que varre o payload real procurando por PR, XP, conquista e streak. Se a recomputação em massa se
mostrar cara na prática, a saída continua sendo um *snapshot* de conveniência explicitamente marcado
como cache — nunca promover estes dados a autoridade remota.

---

## Grupo C — Dados locais / específicos do dispositivo

Não sincronizam. Alguns porque não têm significado fora do aparelho, outros porque sincronizá-los
seria ativamente ruim (o timer de descanso de um aparelho não deve tocar em outro).

| Domínio | Armazenamento | Autoridade | Por que não sincroniza |
| --- | --- | --- | --- |
| Catálogo canônico de exercícios | `exercises` com `canonicalId` | Manifesto versionado em `app/src/main/assets/catalog` | Conteúdo do app, igual em todo aparelho. Distribuído pelo APK/manifesto, não pelo sync. Ver [`identity-contract.md`](./identity-contract.md#exercícios-canônicos). |
| Enriquecimento premium do exercício | `exercise_education`, `exercise_media`, `exercise_progression`, `exercise_safety`, `exercise_substitutions`, `exercise_ai_context`, `exercise_biomechanics`, `exercise_execution` | Manifesto premium versionado | Idem — conteúdo, não dado do usuário. |
| Estado do timer de descanso | DataStore (`REST_TIMER_DEADLINE`, `REST_TIMER_WORKOUT_SESSION_ID`, `REST_TIMER_EXERCISE_SESSION_ID`, `REST_TIMER_TYPE`) | `SettingsManager` | Estado transitório de execução **neste** aparelho. Sincronizar dispararia um timer em outro dispositivo. |
| Preferências de aparelho | DataStore (`DARK_THEME`, `KEEP_SCREEN_ON`, `HAPTIC_ENABLED`, `SOUND_ENABLED`, `PRE_ALERT_ENABLED`, `SHOW_GIFS`, `TIMER_NOTIFICATION_ENABLED`) | `SettingsManager` | Dependem da tela, do hardware e do contexto de uso. |
| Cache e estado de importação | DataStore (`INSTALLED_CATALOG_CONTENT_VERSION`, `INSTALLED_PREMIUM_CONTENT_VERSION`, `LAST_MEDIA_SYNC_AT`, `MEDIA_SYNC_CONTENT_VERSION`, `LAST_SYNC_STATUS`) | `SettingsManager` | Descreve o estado local de instalação de conteúdo. |
| Chave de API do ExerciseDB | DataStore (`EXERCISE_DB_V2_API_KEY`) | `SettingsManager` | **Credencial.** Não sai do aparelho, não vai para backup, não vai para o servidor. |
| Token de depuração do App Check | não persistido — provedor escolhido por variante em `AiCoachAppCheck` (`src/debug`) | build variant | Nunca sai de debug, nunca entra em release, nunca é versionado. |
| Preferências de treino do atleta | DataStore (`WEEKLY_GOAL`, `USE_KG`, `DEFAULT_REST_SECONDS`, `DEFAULT_EXERCISE_REST_SECONDS`, `RIR_RPE_ENABLED`, `AUTO_REST_TIMER_ON_SET`) | `SettingsManager` | **Entram no backup desde a T16.4**, como previsto: elas descrevem a preferência do atleta, não do aparelho. Nada foi movido de DataStore para Room para isso — o snapshot tem DTO próprio (`UserPreferencesBackupDto`). Continuam **fora** do sync incremental, que não existe. |
| `deviceId` | DataStore (`DEVICE_ID`) — **T16.3** | `DeviceIdProvider` | Identidade da **instalação**, não do usuário. Reinstalar gera outro, e isso é correto. Não vai para backup: o que identifica dado é `syncId`. |
| Estado da nuvem | Room, tabela `cloud_data_binding` — **T16.4** (era DataStore na T16.3, e nunca foi gravado lá) | `CloudDataBindingScopeProvider` | Diz a que Conta Spark este **conjunto de dados** pertence. Ausente = sem dono, que é o padrão. Login não escreve aqui; só a adoção explícita. Mudou de lugar para que adoção, captura do snapshot, corte da Outbox e criação da tentativa caibam na mesma transação. |
| Tentativa de backup | Room, tabela `backup_attempts` — **T16.4** | `BackupRepository` | O snapshot congelado de uma tentativa em andamento. Mecanismo interno: não é dado do usuário, não entra em backup e some quando a tentativa é confirmada. |

---

## Grupo D — Dados futuros exclusivos do servidor

Não existem no Android e não devem existir. São estado que só faz sentido com identidade
autenticada e visão entre usuários.

| Domínio | Estratégia | Fase |
| --- | --- | --- |
| Conta / vínculo `uid` ↔ dados | server-only | T16.1 |
| Registro de dispositivos (`deviceId`) | server-only | T16.3 |
| Sequência de mudanças e cursor de sync | server-only | T16.6 |
| Registro de `clientMutationId` para idempotência | server-only | T16.3 |
| Tombstones e retenção | server-only | **implementado na T16.7** (`sync_entities.deleted`; nada os apaga) |
| Rate limit e controle de uso da IA | server-only | T16.2 |
| Identidade social e privacidade (`social_profiles`, `social_privacy_settings`) | server-only | **implementado na T17.0** |
| Amizades e convites (`friendships`, `friend_requests`) | server-only | **implementado na T17.1** |
| Desafios, ranking, feed, atividade | server-only | T17.2+ |

Destes, a T16.4 implementou o **vínculo `uid` ↔ dados**, na forma de snapshots pertencentes a um
`ownerUid`: `backup_snapshots` e `backup_items`; a T16.6/T16.7 implementaram sequência de mudanças,
cursor, ledger de `clientMutationId` e tombstones; a T17.0 implementou a **identidade social**; e a
T17.1, o **grafo social** — pedidos de amizade e amizade bilateral, também server-only.
Amizade, convite, desafio, ranking e feed continuam sem existir.

Nenhuma tabela do backend é uma tabela de domínio do Spark: o servidor guarda o snapshot como
payload e **não** desmonta treino em colunas consultáveis. Ele não é uma segunda autoridade
operacional — com uma exceção deliberada e delimitada: o social (T17.0–T17.2).

### Onde cada byte mora no servidor (T18.1)

A partir da T18.1 o servidor tem **duas** autoridades de armazenamento, e a divisão é por natureza
do dado, não por tabela:

| Dado | PostgreSQL / Neon | Object Storage (GCS privado; disco local com o provider `local`) |
| --- | --- | --- |
| Backup do usuário (T16.4/T16.5) | `backup_snapshots`: ownership, `client_backup_id`, `payload_hash`, `size_bytes`, `item_count`, `storage_key`; `backup_items`: identidade do agregado, `entity_schema_version`, `content_hash` | `backups/xx/yy/<backupId>.json` — o documento canônico, byte a byte |
| Foto do check-in (T17.9) | `social_checkin_media`: ownership, `storage_key`, `content_hash`, dimensões, status, ciclo de vida | `social/checkins/xx/yy/<uuid>.webp` — o WebP sanitizado |
| Sync, social, quota, tombstones, ledger de exclusão | tudo | nada |

Regras que esta divisão impõe:

- **o PostgreSQL é a única autoridade de metadata, ownership, hashes e estado.** Um objeto sem
  linha não existe para a API — é órfão, e a coleta o recolhe depois de 24 h de carência. Uma linha
  nunca aponta para um objeto que não foi criado: o objeto é gravado antes, e a metadata só depois;
- **backups novos não duplicam o documento no banco.** `backup_snapshots.payload` e
  `backup_items.payload` ficam `NULL`; os snapshots anteriores continuam válidos a partir da coluna
  até o migrador (`migrate-backup-payloads-to-object-storage`) movê-los, verificando o hash antes de
  esvaziar o banco;
- **a chave é do servidor e opaca.** `backups/…/<backupId>` e `checkins/…/<uuid>`: nunca uid,
  `socialId`, `friendCode`, `clientBackupId`, `deviceId`, e-mail, nome ou legenda;
- **o Android nunca fala com o bucket.** Sem credencial GCS no aparelho, sem URL pública, sem URL
  assinada: os bytes saem do backend, depois da autorização em SQL, com o hash conferido;
- **o social continua sem alcançar o backup, e vice-versa.** O bucket é o mesmo; as fronteiras são
  duas (`SocialMediaStore`, `BackupPayloadStore`) sobre uma camada neutra (`ObjectStorageClient`).

### O social é a exceção, e ele não é dado de treino

`social_profiles`, `social_privacy_settings` e `social_progress_settings` são as primeiras tabelas
do servidor cuja autoridade **é** o servidor: o perfil social nasce lá, existe lá, e o aparelho só o
lê. Isso não abre exceção para dado de treino, e a fronteira é explícita:

- **nada de treino entra nas tabelas sociais.** É proibido gravar XP, streak, contagem de treinos,
  último treino, peso corporal ou PR ali — mesmo "só para facilitar a UI". `social_progress_settings`
  (T17.2) guarda **quatro booleanos e um fuso**: consentimento, e nenhum valor de progresso;
- **o e-mail também não entra.** Ele continua sendo informação da camada de Auth (Firebase);
- **o Firebase UID entra apenas como `owner_uid`, e nunca sai em DTO.** Identidade pública é o
  `socialId`;
- **o caminho para progresso social é a projeção** — `SocialProgressProjector` sobre
  `SocialProgressSource`, obedecendo `OWNER_SCOPED`, `CONSENT_REQUIRED`, `DERIVED_NEVER_RAW`,
  `NO_BACKUP_READ`, `AGGREGATE_ONLY` e `SINGLE_AUTHORITY` — e não uma coluna nova aqui.

### O que a T17.2 provou sobre esta matriz

Ao construir o perfil social enriquecido, a única métrica projetável foi **treinos da semana**, e a
razão está nesta própria matriz: gamificação é `DERIVED` (linha "`gamification_events`,
`xp_transactions`, `achievement_unlocks`, `personal_records`, nível/XP/streak"), e portanto nível,
sequência e conquistas **não chegam ao servidor**. Publicá-los exigiria aceitar o valor que o
aparelho declara — o servidor confiando no cliente sobre progresso — ou recriar os motores de
domínio em TypeScript. Os dois foram recusados; os três campos respondem `UNSUPPORTED`.

A leitura que a projeção faz de `sync_entities` é um `COUNT(*)` por um adapter estreito
(`SocialProgressSource`), com `owner_uid` na cláusula `WHERE` e **nenhum payload materializado**.
`backup_snapshots`/`backup_items` e os objetos `backups/…` continuam inalcançáveis para o social,
em qualquer forma. Ver [`social-profile-contract.md`](./social-profile-contract.md).

E o social **não** entra na classificação local: ele não tem linha no Room, não entra no backup
(T16.4), não é tocado pelo restore (T16.5), não entra na Outbox (T16.6) e não usa tombstone
(T16.7).

---

## Regra derivada desta matriz

O schema remoto **não é** um espelho do Room:

- o Grupo B não vira tabela remota;
- o Grupo C não sai do aparelho;
- o Grupo A vira schema remoto **conforme cada fase precisar**, com forma própria (ownership,
  `syncId`, `revision`, tombstone), e não com as colunas que o Room usa para renderizar tela.
