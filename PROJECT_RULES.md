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

O núcleo do Spark — Home, treinos, criação e edição manual, execução, histórico, perfil,
gamificação e evolução — precisa continuar funcionando com o provider fora do ar, sem internet,
sem Spark Backend, sem conta e sem configuração de Firebase.

Desde a **T16.2** o Coach é uma capacidade **online autenticada**: quem fala com o Gemini é o
Spark Backend, e uma chamada nova ao modelo exige Conta Spark. Isso não torna a conta necessária
para usar o Spark — torna-a necessária só para o que depende do servidor.

Regras obrigatórias ao mexer em qualquer parte do Coach:

- **Saída do modelo é entrada não confiável.** Structured output e `AiCoachResponseValidator` são
  ambos obrigatórios: o schema garante a forma, o validador garante a semântica. Não remova o
  validador porque "o modelo já segue o schema".
- **Uma autoridade por coisa.** Um gateway no app (`AiCoachGateway` /
  `SparkBackendAiCoachGateway`), um lugar com prompt (`AiCoachPromptRegistry`, **no backend**),
  um validador de cada lado, uma configuração de cada lado (`AiModelConfig` no app;
  `AppConfig` + registry no servidor). **Nome de modelo não existe no app** — nem em ViewModel,
  tela, caso de uso ou configuração.
- **A credencial do Gemini é server-only.** Ela nunca entra no APK, no `BuildConfig`, em resource,
  em DataStore, no Git ou em teste.
- **Versionamento.** Mudou instrução ou formato de prompt, suba `PROMPT_VERSION` no backend.
  Mudou o contrato de conversa, suba `SCHEMA_VERSION` (app) e `AI_SCHEMA_VERSION` (backend)
  juntos, e ajuste schemas, contextos e validadores dos dois lados.
- **Identificadores.** `exerciseId` inexistente é sempre rejeitado; onde há candidate set, id fora
  dos candidatos daquela requisição também.
- **Escrita.** Geração é draft-first, adaptação é confirmation-first, `EXPLAIN_*` é read-only.
  Sessão concluída é imutável. A IA não altera XP, streak, conquistas, missões ou PRs.
- **Custo.** Nenhuma chamada por `init`, abertura de tela, recomposição, polling ou background.
  Ação explícita do usuário, uma chamada; toque repetido durante uma chamada não vira outra — e o
  servidor repete a proteção (uma chamada ativa por conta, dedupe por `clientRequestId`, quota
  diária por conta e global). Sem retry automático em nenhum dos dois lados.
- **Contexto.** O menor contexto suficiente, com limites explícitos em `AiModelConfig`. Nada de
  gamificação ou medidas corporais em análise, geração ou adaptação.
- **Texto do usuário é dado.** Ele nunca vira instrução de sistema, e a proteção efetiva contra
  injeção é o validador, não a redação do prompt.
- **Logs.** Só metadata técnica (`requestId`, tipo, modelo, versões, duração, resultado). Prompt,
  contexto, resposta, histórico, medidas e texto do usuário não vão para log — nem em debug.
- **App Check.** A escolha do provedor é por variante de build (`src/debug` / `src/release`), e
  quem o instala é o `FirebaseAuthGateway` — ele atesta o app perante o Firebase, que hoje serve à
  autenticação. Nenhum token de depuração no código, no Git ou no APK de release.
- **Rede.** Comunicação em texto claro é proibida em release. A exceção de desenvolvimento é
  nominal (`10.0.2.2`/`localhost`) e vive só no source set `debug`.
- **Testes.** Toda mudança no Coach roda a suíte de avaliação
  (`./gradlew :app:testDebugUnitTest --tests "com.example.domain.ai.eval.*"`) e, quando tocar o
  servidor, `npm test` em `backend/`. As duas são offline, usam dublês e não consomem cota.
  Avaliação com o caminho real (Firebase Auth → Spark Backend → Gemini) é opt-in e nunca entra no
  build padrão nem no CI.

## 13.1 Identidade global e Outbox (T16.3)

O Spark tem identidade global de dados e uma Outbox transacional. Nada disso envia dado — e as
regras abaixo são o que impede que ele comece a enviar por acidente.

- **`syncId` é imutável.** Ele nasce na criação da entidade (`SyncIds.random()`, valor padrão da
  entidade Room) e nunca é reescrito. Ao editar, use `copy()` sobre a linha lida do banco; se uma
  tela remontar a entidade do zero, o repositório preserva a identidade guardada.
- **Identidade canônica não ganha concorrente.** Exercício de catálogo é identificado por
  `canonicalId` e **não** recebe `syncId`. `localId` continua sendo a chave de todas as relações
  locais — não troque FK do Room por UUID.
- **Escrita de domínio + Outbox é atômica.** Registre mutação apenas dentro de
  `SyncMutationCoordinator.mutate { }`. Nunca insira na Outbox fora da transação da alteração
  correspondente, e nunca a partir da UI: Compose não conhece `SyncOutbox`, `SyncEntityType` nem o
  coordenador. Há teste estrutural sobre isso.
- **Mutação é por agregado, não por linha.** Alterar um exercício de treino registra `UPSERT` do
  **treino**. Uma operação em lote (reordenar, editar várias séries) é uma mutação só. Uma operação
  que não muda estado não registra nada.
- **Login não liga a nuvem.** O padrão é `CloudSyncScope.Disabled`, e nesse estado nenhuma entrada
  é produzida. Entrar, sair e trocar de conta não regeneram `syncId`, não dão dono a dado local e
  não criam mutação. A adoção é explícita, aconteceu na T16.4 e mora em `cloud_data_binding`
  (Room) — ver §13.2.
- **Quem consome a Outbox.** O backup da T16.4 a usa apenas para marcar o que um snapshot completo
  já cobriu, **depois** da confirmação do servidor. Desde a **T16.6** ela também é a fila do push
  incremental — que igualmente só a libera com confirmação. O que continua proibido é o que a
  regra sempre quis dizer: trabalho **periódico**, polling e retry automático de recusa. Ver §13.4.
- **Migrations.** Room é `version = 35` (34 na T16.6, 33 na T16.5) com schema exportado versionado
  em `app/schemas`. Toda mudança de schema precisa de migration explícita e teste com banco da
  versão anterior; `fallbackToDestructiveMigration` é proibido.
- **Logs.** Nada de payload de Outbox em log. Metadata técnica apenas (tipo, operação, id
  abreviado).

## 13.2 Backup estruturado (T16.4)

O Spark envia um **snapshot completo** do estado pessoal ao Spark Backend. Ele **não** sincroniza,
não baixa e não restaura. As regras abaixo são o que impede o backup de virar sync por acidente —
ou de virar perda de dado.

- **Login não adota.** Entrar na conta não associa nada. A adoção exige toque em "Ativar backup"
  **mais** confirmação explícita, e é ela que grava `cloud_data_binding`. Nenhum outro caminho pode
  criar vínculo — nem `LaunchedEffect`, nem listener de login, nem abertura de tela.
- **O escopo é o vínculo, não o `FirebaseUser` atual.** Depois da adoção, mutações e backups usam
  `CloudDataBinding.ownerUid`. Sair da conta não remove o vínculo; entrar com outra conta não o
  transfere — produz descompasso, que bloqueia **só** a nuvem. Treino, execução e histórico
  continuam.
- **O dono sai do token.** O contrato de backup não tem `ownerUid`. Não adicione um "para validar".
- **A tentativa é imutável.** `clientBackupId` + payload congelado nascem juntos, em transação, e
  um retry manda os mesmos bytes. Precisar de estado mais novo é **outra** tentativa.
- **A Outbox só é liberada depois da confirmação do servidor**, e só até `coveredOutboxSequence`.
  Alteração feita durante o upload permanece pendente. Limpar antes é perda de dado sem conserto.
- **Backup só lê.** Nada de corrigir PR, recalcular XP, normalizar sessão ou salvar template no
  caminho da serialização. Sessão concluída continua imutável.
- **Fora do snapshot:** dado derivado (XP, conquistas, PRs, streak — o restore os recalcula),
  catálogo e conteúdo premium,
  preferências de aparelho, estado do timer, `deviceId`, estado da nuvem, credenciais, a Outbox e
  **mídia local** (`content://` não é referência portátil, e Base64 não é a saída).
- **Nada automático.** Sem `WorkManager`, agendador, polling, retry automático ou backup em
  background. Ação explícita do usuário, uma operação — e toque repetido não vira operação nova.
- **Versionamento.** Mudou o envelope, suba `BackupContract.SCHEMA_VERSION` **e**
  `BACKUP_SCHEMA_VERSION` no backend. Mudou o payload de um agregado, suba a
  `entitySchemaVersion` dele nos dois lados e atualize as fixtures em `contracts/backup/v1/`.
- **Forma canônica.** O hash é SHA-256 sobre texto canônico com tokens escalares copiados
  verbatim. Não "melhore" isso reserializando a partir do valor: Kotlin e TypeScript formatam
  ponto flutuante de formas diferentes, e o hash deixaria de fechar.
- **Logs.** `requestId`, prefixo de uid, `clientBackupId`, `itemCount`, `sizeBytes`, duração e
  status. Nunca corpo, payload, nome de treino, nota, medida ou token.
- **Testes.** Toda mudança no backup roda
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.backup.*"` e `npm test` em
  `backend/`. As duas são offline e não dependem de Firebase, VPS ou internet.

## 13.3 Restore seguro (T16.5)

O Spark **baixa** um snapshot da Conta Spark e substitui o dataset local por ele. As regras abaixo
são o que impede o restore de virar perda de dado — ou de virar sync por acidente.

- **Restore é substituição, não merge.** `REPLACE LOCAL DATASET WITH BACKUP`, e só isso. *Keep
  both*, *last write wins* e merge por campo continuam sendo T16.7 — a T16.6 detecta conflito e
  preserva os dois lados sem escolher. Introduzir qualquer um deles aqui faz o usuário perder dado
  achando que ganhou. Há teste estrutural.
- **A ordem não muda.** `download → hash → versão → schema → semântica → plano → preview →
  confirmação → snapshot de segurança → transação`. Nada local pode ser alterado antes da
  confirmação, e nenhuma etapa pode ser pulada "porque o servidor já validou".
- **Hash é obrigatório.** HTTPS protege o transporte e não substitui a verificação: divergência é
  sempre recusa, sempre antes de qualquer escrita.
- **Versão desconhecida não é interpretada.** `RestoreContract.SUPPORTED_BACKUP_SCHEMA_VERSIONS` é
  a lista, `BackupMigrator` é a fronteira, e migração de versão que ainda não existe não é
  inventada.
- **Nada parcial.** Um agregado inválido recusa o snapshot inteiro. Item desconhecido não é
  ignorado; identidade que não bate não é "aproximada".
- **O snapshot de segurança vem antes da mutação**, mora no armazenamento **privado** do app e
  **nunca** é enviado ao servidor.
- **Uma transação.** Limpeza e inserção do dataset pessoal têm um commit e um rollback. O vínculo
  com a conta, o baseline da Outbox e — desde a T16.6 — a revision conhecida, o cursor e os
  conflitos fazem parte do mesmo commit, nunca antes dele.
- **A Outbox não é replay.** Restaurar não gera mutação por item; a fila anterior só é substituída
  dentro do commit. Limpar antes é perda de dado sem conserto.
- **Catálogo canônico e conteúdo premium não são apagados.** O restore substitui dado pessoal.
- **Identidade.** `syncId` é preservado, `localId` é novo, relações são reconstruídas por
  identidade portátil. Nada pode depender do `localId` do aparelho de origem.
- **Histórico não é recalculado.** Sessão concluída volta como estava — duração, carga, repetições
  e horários. Restore é reconstrução de um snapshot histórico, não permissão para editar histórico.
- **Sem efeito colateral.** Restaurar não dá XP, não desbloqueia conquista, não cria recorde e não
  notifica. Gamificação é derivada e é reconstruída pelas reconciliações que já existem.
- **Conta.** Dataset sem dono pode receber restore da conta atual; dataset de A com a conta B
  conectada é bloqueio (`ACCOUNT_MISMATCH`), nunca reatribuição. A conta é revalidada imediatamente
  antes da aplicação.
- **Recuperação.** Uma tentativa interrompida é resolvida na abertura do app, antes de qualquer
  outra escrita, e enquanto isso nenhuma tela diz "restaurado".
- **Nada automático.** Sem restore no login, na abertura, ao abrir o Perfil ou ao listar backups.
  Sem `WorkManager`, polling ou retry automático. A recuperação não é exceção: ela só conclui o que
  o usuário já confirmou.
- **Logs.** O pacote de restore não registra nada hoje, e a ausência é testada. Se algum log for
  adicionado, ele carrega metadata técnica — nunca snapshot, medida, histórico ou token.
- **Testes.** Toda mudança no restore roda
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.restore.*"` e `npm test` em
  `backend/`. As duas são offline e não dependem de Firebase, VPS ou internet.

## 13.4 Sincronização incremental multi-device (T16.6)

Dois aparelhos da mesma Conta Spark convergem sozinhos. As regras abaixo são o que impede o sync de
virar perda de dado — ou de virar backup, ou de virar tempo real.

- **Local primeiro, sempre.** `ação → domínio → Room → UI` acontece inteiro antes de qualquer
  requisição. `editar → esperar HTTP → salvar` é proibido, e nenhum caminho do app faz isso. Room é
  a autoridade **operacional**; o servidor é autoridade de **ordem** (`revision`, `serverSequence`),
  nunca de execução de treino.
- **Login não sincroniza.** Só um dataset adotado (T16.4) ou restaurado (T16.5) participa, e só com
  a conta dona. Sem vínculo, o ciclo nem consulta a sessão; com a conta errada, o resultado é
  descompasso — e o núcleo do Spark continua completo.
- **O dono sai do token.** O corpo do push não tem `ownerUid`, e o envelope é estrito: um campo
  desses recusa a requisição. `deviceId` é metadado de origem e não autoriza nada.
- **A Outbox só é liberada com confirmação.** Timeout, 503, 429 e resposta perdida deixam tudo
  pendente. Apagar antes é perda sem conserto; reenviar é seguro pelo `clientMutationId`.
- **`revision` decide, relógio não.** Escrita stale é detectada por `baseRevision` × `serverRevision`
  — nunca por `updatedAt`. E um `STALE` **não** atualiza a revision conhecida: fazer isso seria
  *last write wins* com outro nome. Há teste estrutural e de comportamento.
- **Conflito é detectado e preservado, não resolvido sozinho.** Sem *keep both*, sem merge por
  campo, sem "reenvia com a revision atual", sem retry automático de
  `STALE`/`INVALID`/`UNSUPPORTED`. O lado local fica no Room e na Outbox (`BLOCKED`), o remoto em
  `sync_conflicts`. Desde a **T16.7** a escolha existe — e é do usuário (§13.5).
- **Um conflito não bloqueia o app.** O isolamento é por agregado: um treino em conflito não impede
  medidas, check-ins e sessões de convergirem.
- **Histórico concluído é imutável.** `revision = 1` e nunca mais. Mesmo conteúdo é idempotente;
  conteúdo divergente é conflito de integridade — dos dois lados. E receber uma sessão concluída
  **não** dá XP, não desbloqueia conquista, não cria recorde e não notifica.
- **O apply remoto acontece fora do coordenador de mutações.** Um lugar só (`SyncRemoteApplier`), em
  transação, como a `RestoreTransaction`. Pelo coordenador, aplicar o que veio do servidor
  devolveria tudo para ele — um laço. Há teste estrutural.
- **O cursor só avança depois do apply**, na mesma transação. Mudança que este app não sabe ler
  pausa o sync naquele ponto; ela nunca é pulada. `canonicalId` que não resolve pausa também —
  *fuzzy matching* não existe.
- **Exclusão propaga desde a T16.7**, com tombstone no servidor (§13.5). O que a T16.6 estabeleceu e
  continua valendo é que a `UPSERT` anterior a um `DELETE` **nunca** é enviada em lugar dele: isso
  ressuscitaria no servidor o que o usuário apagou.
- **Nada é periódico.** Um trabalho **único** do `WorkManager`, com restrição de rede e backoff
  exponencial, agendado por alteração local; foreground conservador (15 min); toque manual. Sem
  `PeriodicWorkRequest`, sem polling, sem WebSocket, sem push do servidor.
- **Treino em execução não muda por baixo.** Uma alteração remota no template que está sendo
  executado é **adiada** até a sessão terminar — o motor lê configuração de série do template
  durante o treino. Plano e histórico continuam separados: `exercise_sessions` e `set_logs` têm os
  próprios snapshots, e mudar o template nunca reescreve uma sessão já criada.
- **A tela não fala protocolo.** `cursor`, `revision`, `baseRevision` e `serverSequence` não entram
  em UI. O usuário lê "Atualizado", "3 alterações aguardando envio" e "1 item precisa de atenção".
- **Sync não substitui backup.** O snapshot completo da T16.4 continua sendo o mecanismo de cópia
  histórica, e um ciclo de sync nunca cria um. Há teste estrutural nos dois sentidos.
- **Versionamento.** Mudou o payload de um agregado, suba a `entitySchemaVersion` dele **nos dois
  lados** e atualize as fixtures. Mudou o protocolo, suba `SyncProtocol.VERSION` e
  `SYNC_PROTOCOL_VERSION` juntos.
- **Logs.** No servidor: `requestId`, prefixo de uid, prefixo de `deviceId`, contagens por desfecho,
  cursor, duração. No Android: **nada** — o pacote de sync não registra log, e a ausência é testada.
  Nunca payload, nome de treino, nota, medida, `syncId` ou `Authorization`.
- **Testes.** Toda mudança no sync roda
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.sync.*"` e `npm test` em `backend/`.
  As duas são offline e não dependem de Firebase, VPS ou internet.

## 13.5 Conflitos, exclusão e tombstones (T16.7)

Dois aparelhos que discordam agora **resolvem** — e é o usuário quem resolve. As regras abaixo são
o que impede a resolução de virar perda de dado, e a exclusão de virar ressurreição.

- **Conflito não é erro; é um estado legítimo.** Ele é detectado, isolado e **preservado até uma
  decisão segura**. Continua proibido resolver por *last write wins*, por `updatedAt`, por "device
  mais recente", por "server sempre vence" ou "local sempre vence" — em nenhum lugar, em nenhum
  agregado, sem política explícita.
- **A política é por agregado, e mora em um lugar só.** `SyncEntityPolicies` (app) e
  `SyncEntityPolicyRegistry` (`sync.policy.ts`) declaram mutabilidade, se a exclusão remota é
  permitida e a estratégia de conflito. Não existe `default`, e um `if (entityType == ...)` espalhado
  é exatamente o que eles existem para impedir. `LAST_WRITE_WINS_ALLOWED` **existe como valor e
  nenhum agregado o usa** — há teste dos dois lados.
- **Escolher o local gera mutação nova.** Novo `clientMutationId`, `baseRevision` igual à revision
  remota que o usuário viu e recusou, e a tentativa anterior é descartada. Reaproveitar o
  `clientMutationId` recusado faria o servidor devolver o resultado antigo do ledger. Se o servidor
  tiver andado de novo no meio, o resultado é `STALE` e o conflito **reabre** — isso é o desenho, e
  não um defeito a contornar.
- **Escolher o remoto não gera mutação, e desde a T16.7.1 confirma antes.** A cópia remota guardada
  foi validada **quando chegou**; antes de sobrescrever o local, o app pergunta ao servidor qual é o
  estado de **agora** (`GET /v1/sync/entities/...`). Revision igual: aplica no Room, descarta a
  tentativa local, grava a revision e fecha o conflito — sem mutação, porque uma aqui devolveria ao
  servidor o que veio dele. Revision diferente: o conflito é **atualizado** e o usuário decide de
  novo. Ver §13.6.
- **Resolução é transacional e idempotente.** Uma transação por decisão, e a idempotência é uma
  escrita condicional no banco (`status = PENDING`), não uma flag em memória: dois toques rápidos
  produzem **uma** mutação, e a decisão sobrevive ao processo morrer.
- **Histórico concluído não recebe escolha.** Divergência de conteúdo é conflito de integridade:
  informa-se, registra-se, e nenhuma versão é sobrescrita. Excluir continua permitido — apagar não
  é reescrever.
- **A exclusão viaja, e ela é uma mudança.** `DELETE` sobe da Outbox, o servidor cria tombstone em
  `sync_entities` (`deleted = 1`), gasta uma `revision` e anexa a mudança ao change log. Exclusão
  stale é `STALE`, nunca exclusão silenciosa por cima de algo mais novo.
- **Tombstone impede ressurreição.** `UPSERT` contra tombstone é `REMOTE_DELETED`, sempre — inclusive
  com a `baseRevision` do próprio tombstone. Não existe `force`, `overwrite` nem endpoint paralelo:
  a garantia é do banco (`AND sync_entities.deleted = 0`), não da disciplina do serviço.
- **Recriar é criar.** Manter um item que a nuvem apagou produz **`syncId` novo**; a identidade
  morta continua morta. Recriar não é oferecido para programa nem para exercício pessoal — outros
  agregados os referenciam por `localId`, e recriá-los exigiria reescrever os dependentes.
- **Aplicar exclusão remota não gera Outbox**, pelo mesmo motivo de sempre: seria devolver ao
  servidor o que acabou de vir dele. E ela respeita as guardas do domínio — `ON DELETE RESTRICT` de
  exercício, cascade de programa com alteração pendente dentro, e treino em execução (adiado).
- **Tombstone não é apagado.** `SYNC_TOMBSTONE_RETENTION_DAYS` declara a retenção pretendida e
  **nada** a executa: um aparelho que ficou meses offline precisa receber o delete quando voltar.
  Um cursor anterior ao que o servidor guarda é `CURSOR_EXPIRED` e exige rebaseline explícito —
  nunca `cursor = 0` em silêncio.
- **Merge por campo continua fora.** Sem CRDT, sem OT, sem event sourcing, sem consenso
  distribuído. Revision + escolha explícita basta para a escala do Spark.
- **Logs.** No servidor: contagens por desfecho, prefixo de uid e de `deviceId`, cursor, duração. No
  Android: **nada** — o pacote de sync continua sem log, e a ausência é testada. Nunca payload, nome
  de treino, nota, medida ou `Authorization`.
- **Testes.** Toda mudança em conflito/exclusão roda
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.sync.*"` e `npm test` em `backend/`.
  As duas são offline.

## 13.6 Confirmação do estado remoto e CI (T16.7.1)

A T16.7 deu ao usuário a escolha. A T16.7.1 garante que ela seja tomada sobre um estado que ainda é
verdade — e põe o Android no CI. As regras abaixo são o que impede a correção de virar um app
dependente de rede.

- **Só "usar a versão da nuvem" pergunta ao servidor.** Ela é a única escolha que grava conteúdo
  remoto por cima do local. `KEEP_LOCAL`, `CONFIRM_LOCAL_DELETE`, `CONFIRM_REMOTE_DELETE` e
  `KEEP_LOCAL_AS_NEW` **continuam funcionando offline**, e transformá-las em operação online "para
  padronizar" quebraria o local-first que a T16.6 e a T16.7 assumem. `CONFIRM_REMOTE_DELETE` é o
  caso interessante: ela depende de um tombstone, e tombstone é **terminal** — `deleted = 1` não
  volta atrás em revision nenhuma, e a garantia é do banco. Há teste estrutural sobre a lista.
- **Falha de rede nunca é fallback.** Sem confirmação, o snapshot guardado **não** é aplicado: Room,
  Outbox e conflito ficam exatamente como estavam. Aplicar "porque é melhor que nada" é a perda que
  a confirmação existe para impedir.
- **Estado remoto novo não é aceito automaticamente.** O conflito é atualizado, volta para `PENDING`
  e o usuário decide de novo. "Ele já escolheu remoto, então usa a revision nova" aplicaria conteúdo
  que ninguém conferiu — e o conteúdo pode ter mudado de um jeito que muda a decisão. Se a nuvem
  passou a ter tombstone, o conflito vira o caso de exclusão e as escolhas mudam junto.
- **A conta é revalidada depois da resposta.** O `ownerUid` que o servidor autenticou, o dono do
  dataset (`cloud_data_binding`) e a sessão do Firebase **neste instante** precisam ser a mesma
  conta. O `uid` capturado antes da requisição não serve: a conta pode trocar durante o voo.
- **A leitura é estritamente somente leitura.** `GET /v1/sync/entities/...` não gasta `revision`,
  não anexa linha a `sync_changes`, não escreve em `sync_mutations`, não altera tombstone e não move
  cursor. Ownership vem do token; identidade de outra conta é `404`, indistinguível de inexistente.
- **Onde cada coisa mora.** O `SyncConflictResolver` continua sendo **só transação** e não conhece
  `SyncApi` — há teste estrutural. Quem faz a pergunta e decide se a decisão vira escrita é o
  `SyncRepository`; a regra de comparação é pura (`SyncRemotePreflight`).
- **Um toque, uma operação.** Três camadas: `isBusy` na tela, `Mutex` no repositório (impede duas
  consultas remotas simultâneas para a mesma decisão) e escrita condicional no banco — que é a única
  que sobrevive ao processo morrer.
- **CI.** `backend.yml` e `android.yml` são gates de verdade: sem `continue-on-error`, sem `|| true`,
  sem `ignoreFailures`. O Android roda a suíte **inteira** de uma vez (dividir em shards esconderia
  interferência entre classes, que foi um defeito real em 23d3779) mais `:app:assembleDebug`.
  Nenhum dos dois usa Firebase real, Gemini real, VPS ou segredo.
- **`google-services.json` real continua fora do Git.** O CI gera um **sintético e inerte**
  (`CI_ONLY`, `ci-only-not-a-real-firebase-project`) com o `applicationId` real e identificadores
  obviamente falsos, e um passo do job falha se o arquivo real for versionado. É **proibido**
  enfraquecer a produção para o CI passar: nada de `if (CI)`, de remover o plugin Google Services ou
  de criar uma variante especial. O que o runner compila é estruturalmente o mesmo debug de sempre.
- **Testes.** Toda mudança na confirmação remota roda
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.sync.*"` e `npm test` em `backend/`.
  As duas são offline.

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
