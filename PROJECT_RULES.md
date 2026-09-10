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

## 13.7 Produção: hardening, backup do servidor e observabilidade (T16.8)

A arquitetura online da T16 virou infraestrutura operável. As regras abaixo são o que impede o
endurecimento de virar dependência de rede — e a operação de virar perda de dado.

- **O núcleo não paga nada por isso.** Com VPS fora, Firebase fora, Gemini fora, backup falhando e
  sync pausado, o usuário continua abrindo o app, executando treino, registrando série, concluindo
  e consultando histórico. Nenhum interruptor, limite, timeout ou modo de manutenção pode mudar
  isso. Se uma mudança de infraestrutura tornar o app dependente do servidor, ela está errada —
  não o app.
- **Produção é HTTPS, e o backend não fica exposto.** Quem escuta 80/443 é o Caddy;
  `docker-compose.prod.yml` **não publica** a porta do backend, e há gate de CI sobre isso.
  `http://IP:8080` como endpoint de release é proibido.
- **O endereço de release é HTTPS em host público.** `SparkBackendEndpoint` recusa em runtime, e
  `build.gradle.kts` falha o build de release antes de o APK existir. Vazio continua sendo válido
  e significa "nuvem desligada" — nunca fallback para `localhost`, `10.0.2.2` ou rede privada.
- **O snapshot do banco é um `pg_dump` consistente, nunca uma cópia de arquivo** (T18.0.2). O
  banco é o PostgreSQL de `DATABASE_URL` desde a T18.0; não existe arquivo de banco na VPS. O
  snapshot é `pg_dump --format=custom` (uma transação de leitura: ponto único no tempo, sem
  bloquear escritores), verificado por `pg_restore --list` e pela presença de `schema_migrations`
  **sobre o arquivo** — é ele que vai para o off-site, e é ele que precisa provar que serve. As
  ferramentas rodam por container (`SPARK_PG_TOOLS_IMAGE`), com major ≥ a do servidor, e a
  connection string nunca é impressa nem passa pela linha de comando do host.
- **Uma cópia na mesma VPS não é backup.** O destino é off-site e criptografado (restic), e a
  senha existe fora da VPS. Backup criptografado cuja senha só existe na máquina perdida é backup
  perdido.
- **Backup nunca é validado por exit code.** Só está validado o que foi restaurado:
  `ops/verify-backup.sh` restaura o dump num banco **descartável** (`SPARK_DRILL_DATABASE_URL`,
  nunca produção — o script recusa) e **sobe o backend real sobre ele** exigindo `/health/ready`.
  `ops/restore.sh --install` preserva um dump do estado atual antes de restaurar, e restaura em
  transação única: ou tudo entra, ou nada muda.
- **Migration de produção não roda sem ponto de recuperação.** `ops/deploy.sh` faz backup antes, e
  aborta se ele falhar. E **rollback de aplicação não desfaz migration**: mudança incompatível
  segue *expand → deploy → contract em release posterior*.
- **A imagem é identificável.** Tag por SHA do commit, nunca `latest` em produção, e nunca deploy
  com árvore suja — a tag precisa descrever exatamente o que sobe.
- **Durabilidade é do PostgreSQL, com `synchronous_commit` no default (`on`).** O aparelho só
  libera a Outbox com confirmação do servidor: uma transação confirmada e depois perdida é dado que
  o cliente considera salvo e que ninguém vai reenviar. Baixar `synchronous_commit` é decisão
  explícita, nunca default herdado. (Até a T18.0 isto era o `synchronous = FULL` do SQLite.)
- **Readiness é sobre servir, não sobre terceiros.** `/health/ready` verifica configuração,
  PostgreSQL (`SELECT 1`) e migrations. Ele **não** consulta Firebase nem Gemini, e não vai
  consultar: o Coach fora não pode derrubar backup e sync. `/health/live` prova só que o processo
  está vivo. E é o **único** lugar em que "banco indisponível" vira valor (`reachable: false`):
  em todo o resto, `PostgresService.query()` e `transaction()` **lançam** com o pool ausente ou
  encerrando — nunca devolvem `[]`. Um repositório não pode confundir "não achou" com "não havia
  banco" (T18.0.2).
- **Interruptor é pausa, nunca perda.** `AI_ENABLED`, `SYNC_WRITE_ENABLED` e `MAINTENANCE_MODE`
  respondem `503` — que o Android já trata como indisponibilidade recuperável desde a T16.2, sem
  APK novo. Com o push pausado, a Outbox **permanece pendente** e nada é apagado. Um interruptor
  que responda `4xx` faria o aparelho tratar a tentativa como recusada: isso seria perda.
- **Limite não pode parar restore legítimo.** Os tetos são por `uid` autenticado, nunca por IP
  (rede móvel e NAT compartilham endereço, e o Caddy à frente faria todo mundo parecer o mesmo
  cliente). Eles existem para conter laço, e são calibrados ordens de grandeza acima do uso real.
- **Log continua sem conteúdo.** `Authorization`, corpo, payload de backup, payload de sync,
  prompt e resposta do modelo não vão para log — nem do backend, nem do Caddy. `DATABASE_URL`
  também não: nem no log, nem em `backup-status.json`, nem em argumento de processo. Rotação é
  obrigatória: log não pode ser causa provável de disco cheio, e disco cheio derruba a mídia, o
  ledger e o backup.
- **Nada é apagado para liberar espaço.** Disco cheio é incidente, e a resposta nunca é remover
  backup, ledger, mídia ou o dump `pre-restore-*`. Um banco que o provedor reporta como corrompido
  é **preservado** (PITR/branch do provedor primeiro, dump off-site depois) — nunca descartado.
- **Tombstone, change log e ledger continuam intocados.** A T16.8 não limpa nenhum dos três
  (§13.5 continua valendo): tombstone apagado cedo demais é ressurreição, e change log compactado
  força rebaseline sem necessidade.
- **Dependência se atualiza deliberadamente.** `npm audit fix --force` é proibido. O gate de CI é
  `npm audit --omit=dev --audit-level=high`; vulnerabilidade aceita precisa de justificativa
  **verificável** — a de hoje é um teste que prova que a cadeia não é alcançável.
- **Documentação distingue estado.** `IMPLEMENTED`, `MANUAL SETUP REQUIRED`, `VERIFIED`,
  `NOT VERIFIED`. Nada em `docs/operations/` pode dizer que produção está verificada enquanto não
  houver VPS, DNS, TLS, credencial e backup off-site reais.
- **Testes.** Toda mudança de hardening roda `npm test` em `backend/` e
  `shellcheck ops/*.sh`. O CI normal continua sem Firebase real, sem Gemini, sem VPS e sem
  credencial de storage — mas **com** um PostgreSQL real de serviço: o ensaio de backup
  (`backup-drill`) e a topologia de produção rodam contra ele.

## 13.8 PostgreSQL como único runtime de persistência (T18.0 → T18.0.3)

- **Só existe um modelo de persistência no servidor: PostgreSQL.** `DATABASE_URL` é obrigatória e
  sem default; `DATABASE_URL_DIRECT` é opcional e **vazio significa ausente** (é como o Compose
  representa "não definido"), caindo em `DATABASE_URL`. Não existe `DATABASE_PATH`, `spark.db`,
  `better-sqlite3` nem `VACUUM INTO` em runtime, imagem, compose ou script operacional ativo.
  SQLite sobrevive em dois lugares, e só neles: o Room do Android (§13, autoridade local) e os
  testes históricos das migrations legadas (`backend/test/support/legacy-sqlite-migration-runner.ts`,
  `better-sqlite3` como devDependency).
- **Migrations são serializadas por advisory lock, e o primeiro boot concorrente está coberto.**
  Dois runners sobre um schema vazio aplicam a baseline exatamente uma vez, sem `CREATE` duplicado,
  sem migration parcial e sem lock esquecido — e é isso que o teste prova, não dois runners sobre um
  banco já migrado.
- **O runner de migrations devolve a conexão ao pool exatamente como a recebeu (T18.0.3).**
  `statement_timeout`/`lock_timeout` são lidos com `SHOW` antes de qualquer alteração e restaurados
  com `set_config` num `finally` — sucesso ou erro, com ou sem advisory lock adquirido — **antes**
  de o client voltar ao pool. Sem isto, quando o client de migration é o próprio pool principal
  (sem `DATABASE_URL_DIRECT`), a próxima requisição HTTP a reutilizá-lo herdaria os timeouts de
  migration silenciosamente.
- **Toda mudança de estado relacional de um par de contas passa pelo mesmo lock — inclusive
  bloqueio.** Enviar, aceitar, rejeitar e cancelar pedido de amizade, desfazer amizade, bloquear e
  desbloquear tomam `pg_advisory_xact_lock` sobre o par canônico `(min(uid), max(uid))`
  (`lockRelationshipPair`, exportado de `friendship.repository.ts`) e **releem o estado depois** do
  lock. Um `UPDATE ... WHERE status = 'PENDING'` que afeta 0 linhas aborta a transação — nunca segue
  criando amizade de um pedido que não foi aceito. Bloquear e limpar relações compartilhadas
  (`BlockRepository.blockAndCleanup`, T18.0.3) são a **mesma** transação, sob o mesmo lock — nunca
  duas chamadas separadas. `sendRequest` também relê `social_blocks` sob o lock: um bloqueio que já
  commitou (e cuja limpeza já rodou, sem nada para limpar) precisa ser visível para quem tenta
  enviar um pedido depois, ou a corrida cria um `PENDING` que nenhuma limpeza futura alcança. O
  invariante testado em corrida real: nunca existe pedido `REJECTED`/`CANCELLED` **e** amizade
  nascida daquele pedido; e um par bloqueado nunca termina com amizade ativa **nem** pedido
  `PENDING` criado pela mesma corrida.
- **Indisponibilidade do banco é erro, nunca resultado vazio.** `PostgresService.query()`,
  `transaction()` e `appliedVersions()` lançam `PostgresUnavailableError` com o pool ausente,
  encerrado ou encerrando. `checkHealth()` é a única exceção, e é o readiness.
- **O ensaio de restauração identifica o banco de produção pelo nome, não só pela string da URL
  (T18.0.3).** `ops/lib.sh#same_postgres_database` compara o pathname (nome do banco) de
  `DATABASE_URL` e `SPARK_DRILL_DATABASE_URL` além da string inteira — um endpoint Neon pooled e o
  direto do mesmo projeto são hosts diferentes com o mesmo banco, e a checagem antiga (só string)
  não pegava isso. A defesa é deliberadamente conservadora: nomes de banco iguais são recusados
  mesmo com hosts diferentes.
- **Connection strings PostgreSQL não passam pelo argv de `docker run`.** `ops/lib.sh#pg_run` e os
  `docker run` de `ops/restore.sh`/`ops/verify-backup.sh` usam `-e NOME` (sem `=valor`) com o valor
  vindo do ambiente do próprio comando (`DATABASE_URL="$url" docker run ... -e DATABASE_URL`), nunca
  `-e "NOME=${url}"` — a segunda forma grava a senha no argv do processo `docker`, visível a
  qualquer `ps` da máquina.

## 13.8.1 Object Storage: PostgreSQL guarda metadata, o bucket guarda bytes (T18.1)

A T18.1 tirou do PostgreSQL o que nunca deveria ter pesado nele: as fotos dos check-ins e o
documento canônico de cada backup. As regras abaixo são o que impede a divisão de virar perda de
dado, bucket público ou um segundo domínio.

- **Duas autoridades, por natureza do dado.** O PostgreSQL / Neon continua sendo a única autoridade
  de metadata, ownership, hashes, índices e estado transacional. O Object Storage guarda bytes:
  `social/checkins/xx/yy/<uuid>.webp` e `backups/xx/yy/<backupId>.json`, num único bucket privado
  (`GCS_BUCKET_NAME`), separados por prefixo. Nenhuma tabela nova, nenhum bucket a mais.
- **Um único ponto escolhe o provider.** `OBJECT_STORAGE_PROVIDER=local|gcs` é lido por
  `object-storage.factory.ts`, e só por ele. `SocialModule`, `BackupModule`, os services, os
  repositórios e os três comandos operacionais recebem um `ObjectStorageClient` pronto — nenhum
  deles sabe qual é, e há teste estrutural. `gcs` sem `GCS_BUCKET_NAME` é falha de startup; um
  bucket default no código e um fallback silencioso para o disco são os dois erros que essa falha
  existe para impedir.
- **Credencial é ADC, e só.** `new Storage()` sem `credentials`, sem `keyFilename`, sem chave
  privada em variável, sem JSON de service account no Git, na imagem ou no `.env`. Em Cloud Run
  (T18.2) a identidade é a service account anexada; na máquina do operador é o
  `gcloud auth application-default login`. Há teste estrutural contra `GCS_PRIVATE_KEY`,
  `GCS_CLIENT_EMAIL`, `GCS_SERVICE_ACCOUNT_JSON` e afins.
- **O Android nunca fala com o bucket.** Sem credencial GCS no aparelho, sem URL pública, sem
  `getSignedUrl`, sem `makePublic`, sem ACL — há teste estrutural sobre o código. Todo byte sai
  de uma rota autenticada do backend, depois da autorização em SQL, com o hash conferido.
- **Um nome é um objeto imutável.** A escrita é create-only (`ifGenerationMatch = 0` no GCS, `wx`
  no disco) e verificada pelo SDK (CRC32C). Chave já ocupada é erro barulhento, nunca sobrescrita
  — mesmo uma colisão improvável de UUID não pode substituir a foto ou o backup de outra pessoa.
- **A chave é do servidor e opaca.** `backupId` e `mediaId` nascem no servidor; a chave deriva
  deles e nunca de `clientBackupId`, `deviceId`, uid, `socialId`, `friendCode`, e-mail, nome,
  legenda ou nome de arquivo. Nenhuma chave vem do cliente, e nenhuma sai em DTO.
- **Objeto antes, metadata depois; metadata some antes, objeto depois.** Não há transação
  distribuída, então a ordem é o invariante: o backup grava o objeto e **só então** insere a
  metadata; a retenção, a exclusão de conta e a reconciliação de DR apagam linhas no commit e
  **só então** apagam objetos. O erro que a ordem impede é a linha apontando para um objeto que não
  existe. O erro que ela permite é um objeto sem linha — e esse tem nome: órfão.
- **Órfão só é órfão depois da carência.** `OBJECT_STORAGE_ORPHAN_GRACE_MS` (24 h): um objeto sem
  linha mais novo que isso pode ser um upload cuja transação ainda não commitou, e **nunca** é
  recolhido — a varredura antiga, que confiava na ordem "lê o banco, depois lista o disco", tinha
  exatamente essa janela. As duas coletas (`SocialMediaCleaner`, `BackupPayloadCleaner`) são
  paginadas por prefixo, bounded por varredura, com cursor entre varreduras, e consultam o banco só
  pelas chaves da página. Nada lista o bucket inteiro.
- **Backup novo não duplica o documento no banco.** `backup_snapshots.payload` e
  `backup_items.payload` são `NULL` em todo snapshot da T18.1; `backup_items` guarda identidade,
  versão de schema e `content_hash`. Há teste estrutural sobre o `INSERT` e de comportamento sobre
  a linha. Os snapshots anteriores continuam válidos e restauráveis a partir da coluna (`storage_key`
  ausente + `payload` presente), até o migrador movê-los.
- **O documento é byte a byte.** `canonicalText → UTF-8 → objeto → UTF-8 → o mesmo texto`. Nada de
  `JSON.parse`/`stringify`, pretty print ou normalização entre o upload e o restore; o controller
  devolve um `Buffer`. Antes de qualquer byte sair, `size_bytes` e `payload_hash` são conferidos
  contra a metadata: divergência é `410 BACKUP_CONTENT_UNAVAILABLE`, nunca um JSON pela metade.
- **Falha de infraestrutura não é ausência.** Só `404` vira `null`/`false`. Timeout, permissão,
  quota e rede sobem como `ObjectStorageUnavailableError` e viram `503`
  (`BACKUP_STORAGE_UNAVAILABLE`, `SOCIAL_UNAVAILABLE`) — que o Android já trata como "tente de
  novo", com a tentativa de backup pendente e o reenvio idempotente. Um bucket fora do ar
  escondido atrás de um `404` seria "este backup não pode ser restaurado" para quem mais precisa
  dele.
- **A migração legada é um comando, idempotente e fail closed.**
  `migrate-backup-payloads-to-object-storage` verifica o hash do texto legado, sobe o objeto,
  lê de volta, e só então esvazia `payload` nas duas tabelas — numa transação. Objeto já existente
  com o mesmo conteúdo converge; com conteúdo diferente, ou texto que não fecha com o próprio
  hash, é recusado e deixado como está. Nunca upload dentro de migration SQL.
- **Exclusão de conta e DR conhecem o bucket.** As chaves de mídia **e** de backup são lidas antes
  do purge e os objetos removidos depois do commit; uma falha temporária do bucket não ressuscita
  a conta, e o que resistir é recolhido como órfão. `reconcile-account-deletions` monta o provider
  pela mesma factory do runtime: com `gcs`, a reconciliação purga o bucket — um provider local
  instanciado à mão ali purgaria um diretório vazio e deixaria as fotos no ar.
- **Social continua sem importar Backup.** O bucket é o mesmo; as fronteiras são duas
  (`SocialMediaStore`, `BackupPayloadStore`) sobre uma camada neutra em `src/object-storage/`.
  Nenhum arquivo de `modules/social` importa de `modules/backup`, nem o inverso — há teste.
- **Liveness e readiness não tocam o bucket.** `/health/live` diz que o processo está vivo;
  `/health/ready` confere configuração, PostgreSQL e migrations. A configuração do Object Storage é
  validada no startup, e o bucket real é provado por um smoke explícito
  (`npm run smoke:object-storage`, sob `_smoke/`, com ADC, fora da suíte) — nunca por
  `bucket.exists()` a cada probe.
- **Logs.** `operation`, `provider`, `prefix`, `byteSize`, `durationMs`, `status`, contagens.
  Nunca conteúdo, imagem, uid completo, URL, credencial, connection string — e a chave completa do
  objeto também não aparece em log normal.
- **Layout local preservado.** Com o provider `local`, a mídia continua em
  `SOCIAL_MEDIA_ROOT/checkins/…` — é o diretório que `ops/restore.sh` e `ops/verify-backup.sh`
  reconhecem — e os documentos de backup entram em `SOCIAL_MEDIA_ROOT/backups/…`. A tradução
  `social/checkins/` ↔ `checkins/` mora no provider local, e só nele. Com `gcs`, nada disso vive em
  disco e o `restic` não os leva: a durabilidade dos objetos é a do bucket (proteção do bucket
  contra exclusão acidental é T18.3, junto com o DR do PostgreSQL gerenciado).
- **Testes.** `npm test` em `backend/` continua sem GCS, sem ADC, sem projeto GCP e sem internet:
  o provider `local` e o `InMemoryObjectStorageClient` (só em `test/`) cobrem a matriz de falhas.
  O smoke do bucket real é operacional e opt-in.

## 13.8.2 Endurecimento: fence de conta, migração de mídia legada e coleta honesta (T18.1.1)

A T18.1 dividiu corretamente metadata (PostgreSQL) de bytes (Object Storage). A T18.1.1 fecha seis
riscos que uma auditoria pós-T18.1 encontrou: nenhum deles muda a divisão acima — todos são sobre
**quando** uma escrita pode persistir e **quando** um objeto pode ser removido.

- **Existe um único Account Mutation Fence, e ele é transacional.** `src/database/account-mutation-fence.ts` exporta `lockAccountMutationFence`/`assertAccountMutable`/`fenceAccountMutation` —
  `pg_advisory_xact_lock(hashtext('account_mutation_fence'), hashtext(ownerUid))` seguido de uma
  releitura de `account_deletion_tombstones` **dentro** da mesma transação da escrita, na mesma
  convenção de dois `hashtext` que `lockRelationshipPair` (T18.0.1) e o lock de `ai_usage_daily`
  já usavam — nunca uma segunda convenção de lock. `AccountDeletionRepository.beginAccountDeletion`
  adquire o mesmo lock antes do tombstone; toda escrita account-scoped nova que sobreviveria à
  exclusão de forma visível o adquire antes de confirmar: `BackupRepository.insert`,
  `SyncRepository.executeAtomicMutation`, `SocialMediaRepository.create` e
  `WorkoutCheckInRepository.create` (via `WorkoutCheckInService.insertOrResolveRace`). **O
  `BearerAuthGuard` sozinho não basta** — ele só recusa uma requisição nova; uma requisição que já
  passou por ele antes do tombstone comitar precisa ser recusada de novo, dentro da própria
  transação de escrita. `ai_usage_daily` e mutações sociais de baixo risco (reação, comentário,
  amizade) foram investigadas e **deliberadamente não** ganharam o fence: nenhuma delas guarda
  conteúdo capaz de "ressuscitar" a conta aos olhos de outra pessoa, e o purge já as alcança.
- **`AccountMutationFencedError` sempre vira a mesma resposta que o guard já dava.**
  `accountDeletedException()` (`bearer-auth.guard.ts`) é o único lugar que monta
  `403 ACCOUNT_DELETED` — usado pelo guard na entrada e pelos serviços de escrita quando o fence
  recusa no meio de uma transação já em voo.
- **A mídia social legada tem um migrador explícito, manual e fail-closed.**
  `migrate-social-media-to-object-storage` (`npm run migrate:social-media`) parte de
  `social_checkin_media` (PostgreSQL, a autoridade) e do disco legado (`SOCIAL_MEDIA_ROOT`), nunca
  de uma varredura do diretório. Por linha: ler o arquivo local, conferir SHA-256 contra
  `content_hash`, checar o destino (ausente → upload create-only; idêntico → converge; diferente →
  recusa sem sobrescrever), reler o destino para confirmar. `storage_key` nunca muda — ela já era
  `checkins/xx/yy/<uuid>.webp` nos dois providers antes da T18.1.1, só os bytes se movem.
  **A origem nunca é apagada automaticamente**: a remoção do volume legado é uma etapa operacional
  posterior, depois do cutover validado. Idempotente e retomável por construção — não há cursor
  próprio a manter, porque reconferir o destino a cada execução já é barato e sempre correto.
- **`ObjectStorageClient.write` tem uma única semântica, documentada na interface: create-or-confirm-identical.** Nome livre grava; nome ocupado com os mesmos bytes converge **sem** regravar
  (retry seguro); nome ocupado com bytes diferentes é `ObjectAlreadyExistsError`. `local`, `gcs` e o
  `InMemoryObjectStorageClient` de teste seguem exatamente essa tabela — nenhuma diferença
  silenciosa entre providers. No GCS, a decisão de um `412` mora em `resolvePreconditionConflict`
  (função pura, testável sem o SDK): ela **nunca** troca uma falha de leitura por uma ausência —
  antes disso acontecia (`.catch(() => null)`), e uma falha de infraestrutura durante a releitura
  virava colisão em vez de `ObjectStorageUnavailableError`.
- **Idade desconhecida nunca é idade zero.** `StoredObjectSummary.createdAt` é `number | null`;
  `parseObjectCreatedAt` (GCS) devolve `null` quando `timeCreated` está ausente ou é ilegível, nunca
  `0`. Os dois coletores de órfãos (`BackupPayloadCleaner`, `SocialMediaCleaner`) tratam
  `createdAt === null` como "não provado, não remover" — nunca como "nasceu em 1970, remover
  primeiro".
- **Os coletores só contam remoção que de fato aconteceu.** `removed`/`failed` são contadores
  separados nos dois coletores; uma falha de `remove()` não incrementa `removed` e não interrompe o
  lote — o próximo objeto do mesmo lote continua sendo tentado, e o que falhou converge na próxima
  varredura.
- **A reivindicação de mídia `PENDING` expirada é um `UPDATE` atômico, não um `SELECT` seguido de
  remoção.** `SocialMediaRepository.claimCollectable` seleciona e transiciona para `DELETED` numa
  única instrução (`UPDATE ... WHERE id IN (SELECT ... FOR UPDATE)`); sob READ COMMITTED, um
  `attach()` concorrente que dispute a mesma linha reavalia sua própria cláusula `WHERE` contra o
  estado pós-commit ao desbloquear — não há janela em que o coletor apague uma mídia que acabou de
  ser publicada, e nenhum lock consultivo adicional foi necessário para isso.
- **Testes.** `npm test` em `backend/` cobre o fence com PostgreSQL real (`account-mutation-fence.spec.ts`), a migração de mídia (`social-media-migration.spec.ts`), a decisão pura do GCS
  (`gcs-object-storage-client.spec.ts`) e a reivindicação atômica + contagem honesta
  (`social-media-cleanup-hardening.spec.ts`, `backup-object-storage.spec.ts`).

## 13.8 Domínio social: identidade pública e privacidade (T17.0)

O Spark ganhou identidade **pública**. As regras abaixo são o que impede essa identidade de
carregar a privada — e o que impede o social de virar dependência do núcleo.

- **O núcleo não paga nada por isso.** Social é **opcional**. Sem conta, sem perfil social, com o
  servidor fora do ar: treinar, histórico, criação, execução, gamificação, backup, restore, sync e
  Coach continuam exatamente como estavam. Uma mudança que torne Social necessário para qualquer
  uma dessas coisas está errada — não o app.
- **O Firebase UID é identidade privada de infraestrutura, e nunca identidade pública.** É proibido
  mostrar, compartilhar ou aceitar como identificador social: Firebase UID, e-mail, `localId`,
  `deviceId` e `syncId`. `owner_uid` não aparece em DTO nenhum, e há teste que varre as respostas
  reais e os DTOs do Android atrás de `uid`, `ownerUid`, `firebaseUid` e e-mail.
- **Login não ativa Social.** `GET /v1/social/me` de quem nunca ativou responde
  `{ enabled: false }` **sem escrever uma linha**. Não existe criação preguiçosa, `LaunchedEffect`
  que ative, nem listener de login que crie perfil. A ativação exige toque explícito **mais**
  confirmação.
- **A identidade é do servidor, e é imutável.** `socialId` (UUID v4) e `friendCode` (CSPRNG,
  `SPK-` + 8 de `ABCDEFGHJKMNPQRSTUVWXYZ23456789`) nascem no servidor e não derivam do uid, do
  e-mail nem do nome — **nem por hash**. O cliente não propõe nenhum dos dois: um `ownerUid`,
  `socialId`, `friendCode`, `status` ou `createdAt` no corpo **recusa a requisição inteira**.
  `friendCode` não é customizável, e rotação está fora de escopo.
- **Unicidade é do banco.** Colisão de `friendCode` é retry limitado sobre a `UNIQUE`, nunca um
  `SELECT` antes do `INSERT`. Esgotar as tentativas responde `503 SOCIAL_UNAVAILABLE` — nunca um
  `500` e nunca um perfil sem código.
- **Desativar não apaga nada.** Nem Conta Spark, nem conta Firebase, nem backup, nem sync, nem
  treino, nem histórico, nem medida, nem gamificação. `DISABLED` é estado persistido — é ele que
  faz reativar devolver o **mesmo** `socialId` e o **mesmo** `friendCode`. Exclusão completa de
  conta continua sendo outro assunto (pré-release).
- **Social é server-authoritative; treino continua local-first.** Sem Outbox social, sem Room como
  autoridade de perfil, sem `sync_entities`, sem tombstone, sem `syncId` social. Offline, uma
  edição **não acontece**: ela não fica pendente e não é reenviada depois — e a tela diz isso. Um
  cache local é permitido, desde que seja cache: em memória, e invalidado **antes** de a
  requisição da conta nova sair.
- **`CloudDataBinding` não é identidade social.** Ele protege o dataset de treino; o social
  pertence à conta autenticada. Reusá-lo amarraria duas coisas que precisam poder divergir.
- **Nada de treino entra no perfil.** É proibido gravar XP, streak, contagem de treinos, último
  treino, peso ou PR em `social_profiles`, mesmo "para facilitar a UI". O caminho para progresso
  social é a projeção (`SOCIAL_PROJECTION_RULES` em `social.projection.ts`), e as regras dela
  valem desde já: `OWNER_SCOPED`, `CONSENT_REQUIRED`, `DERIVED_NEVER_RAW`, `NO_BACKUP_READ`,
  `AGGREGATE_ONLY` e `SINGLE_AUTHORITY` (as três últimas substituíram, na T17.2, o
  `NO_CROSS_DOMAIN_READ` absoluto — ver §13.10). O módulo social **não importa** backup, sync nem
  IA — há teste sobre os imports dos dois lados.
- **Privacidade nasce restrita.** `discoverability = FRIEND_CODE_ONLY`,
  `friendRequestsEnabled = true`, `activitySharingEnabled = false`. Não existe `PUBLIC_SEARCH`,
  `GLOBAL_PROFILE` nem `ACTIVITY_PUBLIC` — nem como default, nem como valor. E não existe busca
  por nome, busca por e-mail, listagem global nem lookup público.
- **Uma política, um lugar.** `SocialAccessPolicy` responde `canDiscover`/`canViewProfile`/
  `canViewActivity`/`canReceiveFriendRequest`. Um `if (privacy...)` espalhado por controllers é
  exatamente o que ela existe para impedir.
- **Ativar/desativar não é evento de domínio.** Não dá XP, não desbloqueia conquista, não move
  missão e não altera `WorkoutTemplate`, `WorkoutSession`, `PersonalRecord` nem `BodyMeasurement`.
- **Logs.** No servidor: `requestId`, prefixo de uid, evento, status, duração, tentativas de
  geração de código e **quais chaves** de privacidade mudaram. No Android: **nada** — o pacote
  social não registra log, e a ausência é testada. Nunca `Authorization`, uid completo, e-mail,
  `displayName`, `friendCode` ou `socialId`.
- **Testes.** Toda mudança no social roda `npm test` em `backend/` e
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.social.*" --tests
  "com.example.presentation.account.Social*"`. As duas são offline e usam dublê de autenticação.

## 13.9 Grafo social: amizade, pedidos e descoberta por código (T17.1)

O Spark ganhou **relações entre contas**. As regras abaixo são o que impede uma relação de virar
acesso ao domínio privado — e a descoberta de virar enumeração.

- **O núcleo não paga nada por isso.** Amigos é opcional, como todo o social. Sem conta, sem perfil,
  com o servidor fora: treinar, histórico, criação, execução, gamificação, backup, restore, sync e
  Coach continuam exatamente como estavam.
- **Descoberta é `friendCode` exato, e nada mais.** Um mecanismo só:
  `POST /v1/social/friends/lookup`, sobre a forma normalizada da T17.0, com igualdade sobre índice
  único. É **proibido** `LIKE`, prefixo, *contains*, Levenshtein, sugestão, busca por nome, busca
  por e-mail, listagem global e "pessoas que talvez você conheça". Código malformado, inexistente e
  de perfil desativado respondem **a mesma coisa** — distinguir qualquer um transforma a rota num
  oráculo de existência.
- **Uma normalização, e uma fixture que a amarra.** A regra canônica é
  `social.identity.ts#normalizeFriendCode`. A cópia do Android existe só para resposta imediata na
  tela (habilitar o botão, recusar um QR), nunca para decidir um lookup, e as duas são presas por
  `contracts/social/v1/friend-code-normalization.json`, lida pelos testes dos dois lados. Mudou o
  formato de um lado, atualize a fixture e os dois lados no mesmo commit.
- **Amizade é um par, e a garantia é do banco.** `min(uid), max(uid)` com chave primária e
  `CHECK (user_a_uid < user_b_uid)`. Duplicata A-B, duplicata B-A e amizade consigo mesmo são
  irrepresentáveis. Não "resolva" isso em código — se um caminho novo precisar de outra forma de
  escrever o par, ele está errado.
- **Composta é transacional.** Aceitar (marcar `ACCEPTED` + criar amizade) e o pedido cruzado
  (aceitar o inverso + criar amizade) acontecem em **uma** transação. Nunca amizade com pedido
  pendente, nunca pedido cancelado com amizade escondida.
- **Idempotência é escrita condicional no banco**, e não flag em memória:
  `UPDATE ... WHERE request_id = ? AND status = 'PENDING'`, e o `changes` decide. Enviar, aceitar,
  recusar e cancelar de novo são **sucesso** — é o retry depois de resposta perdida e o toque duplo.
- **Estado terminal é terminal.** `ACCEPTED`/`REJECTED`/`CANCELLED` não voltam para `PENDING`, e a
  linha não é apagada na transição. Pedir de novo é uma operação nova, com `requestId` novo.
- **Autorização por participante, em um lugar só.** `FriendshipAccessPolicy` (participação) e
  `SocialAccessPolicy` (visibilidade e `friendRequestsEnabled`). Um `if (request.recipient === uid)`
  espalhado por controller é exatamente o que elas existem para impedir. Terceiro recebe "não
  existe", nunca "não é seu".
- **O Firebase UID nunca sai, e o `friendCode` não circula depois da descoberta.** O preview é
  `socialId` + `displayName`, e a lista de amigos não devolve código de convite de ninguém. Há
  teste que varre as respostas reais e os DTOs dos dois lados.
- **Teto próprio para lookup e envio**, por `uid` autenticado e nunca por IP. O teto geral de
  600/min não serve: 600 tentativas de código por minuto **é** uma varredura.
- **Desativar suspende; não apaga.** Perfil desativado some das listas dos dois lados, para de ser
  acionável e volta inteiro ao reativar. Transformar "desativar" em "desfazer amizades" é perda de
  dado sem conserto. `friendRequestsEnabled = false` recusa **novos** pedidos e não mexe nos
  pendentes.
- **Desfazer amizade remove uma linha, e só.** Não apaga treino, histórico, backup, sync, medida
  nem gamificação; não altera perfil; **não bloqueia** (bloqueio é T17.6, pendente); e não impede
  uma nova amizade depois.
- **Nada disso entra na Outbox, no `sync_entities`, no backup, no restore ou na gamificação.**
  Offline, a ação **não acontece** — não fica pendente, não é reenviada, e a tela diz que nada foi
  enviado. Adicionar amigo não dá XP, conquista nem missão.
- **QR carrega só `spark://friend/v1/<friendCode>`.** É proibido incluir uid, e-mail, token,
  `socialId`, `deviceId` ou endereço de servidor. Ele é gerado no aparelho, e o scanner **nunca**
  navega: nada de `Intent`, `startActivity`, `WebView` ou `Uri.parse` sobre o que a câmera leu. Há
  teste estrutural. A área de transferência é só de escrita — o app nunca lê o clipboard.
- **A tela não usa um `isLoading`.** A ocupação é por alvo (`requestId`, `socialId`): aceitar o
  pedido de um não pode bloquear a resposta ao de outro. Troca de conta invalida o estado **antes**
  de a requisição da conta nova sair, e a resposta de uma requisição da conta anterior é descartada.
- **Logs.** No servidor: `requestId`, prefixo de uid, evento, desfecho, contagens. No Android:
  **nada**. Nunca `friendCode`, `socialId`, `displayName`, e-mail, uid completo ou corpo.
- **Testes.** Toda mudança no grafo roda `npm test` em `backend/` e
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.social.*" --tests
  "com.example.presentation.account.Friends*" --tests "com.example.presentation.friends.*"`. As
  duas são offline e usam dublê de autenticação.

## 13.10 Perfil social: projeção de progresso e compartilhamento controlado (T17.2)

O Spark passou a **publicar progresso** para amigos. As regras abaixo são o que impede essa
publicação de virar uma segunda autoridade de progresso, uma vitrine do que o cliente digita sobre
si, ou uma porta lateral para o domínio privado.

- **O núcleo não paga nada por isso.** Perfil social é opcional, como todo o social. Sem conta, sem
  perfil, com o servidor fora: treinar, histórico, gamificação, backup, restore, sync e Coach
  continuam exatamente como estavam — e o próprio nível, a própria sequência e os próprios treinos
  da semana continuam sendo calculados **no aparelho**, pelas autoridades de sempre.
- **O Social projeta; ele nunca calcula.** É **proibido** recriar `XpCalculatorService`,
  `ConsistencyCalculator` ou `AchievementEvaluator` — em TypeScript ou em qualquer lugar do domínio
  social — "só para o Social funcionar". Duas implementações da mesma regra divergem no primeiro
  ajuste, e a divergência aparece como um perfil social afirmando um nível que o aparelho da própria
  pessoa não reconhece. Uma métrica sem autoridade **remota** responde `UNSUPPORTED`.
- **O servidor nunca confia no cliente sobre progresso.** Não existe — e não pode existir — rota que
  aceite `level`, `streak`, `weeklyWorkoutCount`, `totalXp` ou lista de conquistas vinda do
  aparelho. Os campos são recusados **por nome**, invalidando a requisição inteira. O que o cliente
  envia é **preferência** (o que compartilhar) e o fuso da própria semana.
- **Ausência de dado não vira zero.** Quem nunca sincronizou uma sessão concluída recebe campo
  **ausente** — nunca "0 treinos", "nível 1" ou "sem conquistas". Zero só é publicado quando é fato
  comprovado. Na tela do dono, "ligado, ainda não disponível" é um estado que precisa existir e ser
  dito.
- **Privacidade é aplicada no servidor.** Um campo desligado **não está** no JSON; esconder no
  Compose não é controle de acesso, porque um cliente modificado pediria o mesmo endpoint. Escondido
  e indisponível produzem a **mesma** ausência para o amigo — distinguir contaria a ele a
  configuração de privacidade de outra pessoa. Só o dono distingue os dois.
- **Defaults são privados.** `shareLevel`, `shareConsistencyStreak`, `shareWeeklyWorkoutCount` e
  `shareHighlightedAchievements` nascem `false`, e a migration os grava assim para quem já tinha
  perfil. Subir uma versão nova não publica nada de ninguém.
- **Amizade ativa é a única porta, verificada a cada leitura.** Pedido `PENDING` não concede acesso;
  terceiro não acessa; `unfriend` revoga na requisição seguinte; o alvo desativado some; e o
  visitante desativado para de consumir perfil social. Alvo inexistente, alvo desativado, "não somos
  amigos" e "só há pedido pendente" respondem **a mesma coisa** (`404 FRIEND_PROFILE_NOT_FOUND`).
- **Autorizar vem antes de projetar.** Carregar o progresso de alguém para depois descobrir que quem
  perguntou não tem direito a ele é o desenho que, no dia de um bug, responde o dado.
- **Uma consulta de amizade, em um lugar só.** `SocialAccessPolicy.canViewFriendProfile` decide, e
  `FriendshipRepository.areFriends` responde. Um `SELECT` de `friendships` em controller é
  exatamente o que elas existem para impedir.
- **O adapter é estreito, e só sai agregado.** `SocialProgressSource` devolve **um escalar por
  método** — nunca payload, linha, sessão, série ou timestamp de treino. Ler `sync_entities` para
  `COUNT(*)` é responder uma pergunta; `SELECT payload` seria abrir uma porta. Backup
  (`backup_snapshots`, `backup_items`, `backup_payloads`) continua **inalcançável em qualquer
  forma**, e `SocialModule` continua sem importar `SyncModule`, `BackupModule` e `AiModule`.
- **A semana é a canônica do Spark.** `ConsistencyCalculator.weekStart` (segunda-feira, na data
  **local** do dono), janela `[segunda 00:00, próxima segunda 00:00)` — e não "início + 7×24h", que
  erra por uma hora nas semanas de horário de verão. Só sessões `COMPLETED` contam. As duas
  implementações são presas por `contracts/social/v1/weekly-window.json`, lida pelos testes dos dois
  lados.
- **Sem fuso declarado, não há contagem.** `weekTimeZone` ausente responde `UNAVAILABLE`; supor UTC
  produziria um número plausível e errado.
- **Nada de progresso de terceiros no Room.** Não existe `FriendProgressEntity`, e não deve existir:
  uma cópia local continuaria mostrando o que a outra pessoa desligou. O perfil lido vive em
  memória, é descartado na troca de conta **antes** de a requisição da conta nova sair, e a resposta
  de uma requisição da conta anterior é descartada.
- **Offline não finge, e não há atualização otimista.** Sem servidor, alterar o compartilhamento
  **não acontece**: não vai para a Outbox, não fica pendente, o interruptor não se move, e a tela
  diz que nada foi alterado. Numa tela de privacidade, um "salvo" que não salvou é o pior desfecho
  possível.
- **O perfil carrega on demand, e nunca em lote.** Um perfil é lido no **toque** sobre um amigo. A
  lista de amigos continua leve, e **não existe** rota que devolva o progresso de vários amigos de
  uma vez — um endpoint de colheita é a diferença entre "meu amigo vê meu progresso" e "qualquer um
  baixa o progresso de todo mundo".
- **Nada disso é evento de domínio.** Ver perfil, alterar privacidade e pré-visualizar não dão XP,
  não desbloqueiam conquista, não movem missão e não escrevem no domínio de treino.
- **Perfil ≠ pontuação de desafio.** Os quatro interruptores desta seção **não** decidem progresso
  de Challenge. A T17.3 terá autorização própria e dados canônicos próprios.
- **Logs.** No servidor: `requestId`, prefixo de uid, evento, desfecho e **quantidade** de campos.
  Nunca nível, sequência, contagem de treinos, lista de conquistas, `displayName`, `socialId`,
  `friendCode`, e-mail ou uid completo. No Android: **nada**.
- **Testes.** Toda mudança no perfil social roda `npm test` em `backend/` e
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.social.*" --tests
  "com.example.presentation.account.SocialProfile*" --tests
  "com.example.presentation.friends.*"`. As duas são offline e usam dublê de autenticação.

## 13.11 Desafios entre amigos: pontuação canônica e consentimento próprio (T17.3)

O Spark passou a **comparar** duas pessoas. As regras abaixo são o que impede essa comparação de
virar um placar que o cliente escreve, uma porta para o histórico de treino alheio, ou uma punição
para quem treinou offline.

- **O núcleo não paga nada por isso.** Desafio é opcional, como todo o social. Sem conta, sem
  perfil, com o servidor fora: treinar, histórico, gamificação, backup, restore, sync e Coach
  continuam exatamente como estavam — inclusive **durante** um desafio. A sessão concluída offline
  sobe no próximo sync e passa a contar pelo instante em que aconteceu.
- **O celular nunca informa a própria pontuação.** `score`, `progress`, `rank`, `winner`,
  `goalReached`, `points`, `count`, `leaderboard` e `participants` são recusados **por nome** no
  corpo, invalidando a requisição inteira. Não é filtragem silenciosa: um corpo com `progress: 8`
  descreve um cliente que se acha autoridade de pontuação, e atender o resto dele seria concordar
  em parte. `creatorUid` e `participantUids` caem pela mesma regra da T17.0 — o dono sai do token.
- **A pontuação é derivada na leitura, dos dados canônicos.** `ChallengeProgressSource` responde
  `COUNT(*)` sobre `sync_entities`; `ChallengeScoringService` ordena e classifica. **Não existe** —
  e não pode existir — coluna de placar, contador incremental ou `challenge_progress`. O motivo é
  concreto: um treino do período pode chegar **depois** do fim, e um contador precisaria de uma
  correção retroativa que ninguém escreveu.
- **O perfil da T17.2 não é autoridade de pontuação.** `SocialProgressProjection` é para exibição.
  O desafio tem fonte própria (`ChallengeProgressSource`), janela própria e política própria — as
  duas leem os mesmos dados canônicos por caminhos separados. Ligar uma na outra é proibido.
- **`startedAt`, e não `finishedAt`.** O Spark **não tem** `completedAt`: o agregado canônico tem
  `startedAt` (obrigatório) e `finishedAt` (nulável). A atribuição de um treino a um dia é a do
  `ConsistencyCalculator`, congelada em `contracts/social/v1/weekly-window.json`. Usar `finishedAt`
  criaria uma **segunda** regra de conclusão de treino — um treino de 23h30 cairia em dias
  diferentes no desafio e na tela da própria pessoa — e perderia em silêncio toda sessão com ele
  nulo.
- **Elegibilidade é o instante do treino, nunca o da chegada.** Nenhuma consulta olha `created_at`,
  `updated_at` ou `last_server_sequence`. `ENDED` significa "a janela fechou", e **não** "o
  resultado é final": a resposta carrega `resultMayStillChange` e a tela diz isso. Prometer
  irrevogabilidade que o servidor não pode verificar é mentir sobre a única coisa que o desafio
  afirma. *Settlement* é tarefa própria.
- **Só agregado sai.** Cada método da fonte devolve **um número**. `json_extract` só na cláusula
  `WHERE`: carga, exercício, nota e horário nunca são materializados em JavaScript. Participar dá
  acesso ao **placar**, e não aos dados que o produziram — e nenhum DTO carrega `sessionId`,
  `syncId`, exercício, série, carga, repetição, nota, horário, medida, uid, e-mail ou `friendCode`.
- **Ler o placar é read-only.** Não escreve treino, XP, conquista, missão, Outbox, `sync_entities`
  nem coluna nenhuma. Abrir a tela de um desafio não dá XP, e atualizar não dá de novo.
- **O ciclo de vida é derivado, não agendado.** O banco guarda `lifecycle` (`OPEN`/`CANCELLED`) — a
  única parte que alguém escreve. `UPCOMING`, `ACTIVE`, `ENDED` e `VOID` saem do relógio do
  servidor contra a janela, e `EXPIRED`/`CANCELLED` de convite saem do mesmo lugar. Um cron que não
  roda é um desafio que nunca começa, em silêncio.
- **O fuso é do desafio, e é um só.** Com um fuso por participante, "dia 8" seria um dia diferente
  para cada um. O Android sugere `ZoneId.systemDefault().id`; o servidor valida contra o ICU.
  Nenhum fuso hardcoded. A janela em instantes é **gravada na criação**: as regras que os
  participantes aceitaram não mudam se o país mudar o horário de verão no meio.
- **Um dia não é 24 horas.** Cada limite é `localMidnightToInstant`, em `social-time.ts` —
  compartilhado com a semana canônica da T17.2. Duas implementações de "meia-noite local"
  divergiriam, e a divergência apareceria como perfil e desafio discordando sobre o dia de um
  treino.
- **As regras são imutáveis depois da criação.** Não existe rota de edição, e isso é o que torna
  *bait-and-switch* impossível: quem aceitou "5 treinos" não acorda em "30 treinos". Para mudar,
  cancela e cria outro.
- **Amizade permite convidar; aceitar permite compartilhar a pontuação daquele desafio.** São
  autorizações distintas. Revalidar a amizade acontece na criação **e** no aceite; depois do aceite
  ela deixa de decidir — `unfriend` revoga o perfil da T17.2 e **não** remove ninguém do desafio.
  Os quatro interruptores da T17.2 não decidem nada aqui, e participar não altera nenhum deles.
- **Desativar o Social encerra a participação ativa, em uma transação** com a mudança de status:
  convites pendentes viram `DECLINED`, participações abertas viram `WITHDRAWN`, e desafios abertos
  criados pela conta são **cancelados** (um desafio sem criador ativo é um desafio que ninguém pode
  encerrar). Encerrados são intocados: o resultado é histórico de **outras** pessoas.
- **Empate permanece empate.** *Competition ranking* (`1, 1, 3`). Desempatar por ordem de chegada
  ao servidor puniria quem sincronizou depois; por `createdAt` da sessão inventaria um critério que
  ninguém combinou. `score` pode ultrapassar o `target` — é a barra que limita, não o número — e
  `goalReached` é separado de liderar.
- **Nada disso entra na Outbox, no `sync_entities`, no backup ou no restore.** Não existe
  `entityType` `CHALLENGE`; um push que tente declarar um responde `UNSUPPORTED`. Offline, a ação
  **não acontece** — não fica pendente, não é reenviada, e a tela diz isso.
- **Sem tempo real.** Sem WebSocket, sem SSE, sem FCM, sem polling. Abrir ou atualizar a tela é o
  que busca o placar, e abrir um desafio não dispara sincronização de treino.
- **Anti-fraude, honestamente.** Esta fase garante que o cliente não envia pontuação e que ela
  deriva do domínio canônico. Ela **não** torna o desafio à prova de fraude: um cliente
  comprometido que fabrique `WorkoutSession` canônicas produziria pontuação correspondente — e esse
  é um problema de integridade do dado de treino, que existiria sem desafio nenhum. Dizer o
  contrário na documentação seria falso.
- **Logs.** No servidor: `requestId`, prefixo de uid, evento, desfecho, **tipo** de desafio, status
  e contagens. Nunca nome do desafio, `displayName`, `socialId`, `friendCode`, e-mail, uid completo
  — e nunca **pontuação individual**. No Android: nada.
- **Testes.** Toda mudança nos desafios roda `npm test` em `backend/` e
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.social.*" --tests
  "com.example.presentation.account.Challenge*" --tests
  "com.example.presentation.friends.Challenge*"`. As duas são offline, usam dublê de autenticação,
  e o tempo é **injetado** — nenhum teste dorme.

## 13.12 Atividade dos amigos e rankings contextuais (T17.4)

- **Princípio:** O Spark não é uma rede social aberta — nada de feed público, curtidas, reações ou ranking perpétuo.
- **Projeção efêmera em tempo de leitura:** O feed de atividade (14 dias civis) e o ranking semanal (últimos 7 dias móveis) são computados dinamicamente no backend a partir das sessões de treino canônicas finalizadas (`sync_entities` com status `COMPLETED`). Nenhuma tabela secundária de placar no banco.
- **Consentimento e Reciprocidade:**
  - `activitySharingEnabled` controla a visibilidade dos dias de treino para amigos diretos.
  - `friendRankingParticipationEnabled` exige reciprocidade: o usuário só vê o ranking semanal se tiver optado por participar.
- **Isolamento de dados:** O cliente Android mantém o feed e o ranking exclusivamente em memória (`StateFlow`/ViewModel), sem salvar em Room, DataStore ou Outbox.

## 13.13 Notificações sociais com Firebase Cloud Messaging (T17.5)

- **Push é sinal best-effort, nunca fonte da verdade:** O push convida o usuário a abrir o app; o estado canônico é sempre consultado e sincronizado a partir das rotas do Spark Backend.
- **Payload FCM estritamente data-only e minimalista:**
  - Versão `v = '1'`.
  - Campos limitados a: `v`, `eventId`, `type`, `recipientSocialId`, `entityId`.
  - Proibido incluir dados pessoais (UID, e-mail, foto, nome) ou de treino (exercícios, séries, repetições, cargas, pontuações de desafios, XP ou histórico) no payload push.
  - Textos de notificação são gerados localmente no Android a partir de strings de recursos (`strings.xml`).
- **Isolamento de conta:** Se o `recipientSocialId` recebido no push for diferente do `socialId` do usuário ativo no app (ou se o app estiver deslogado), a notificação é silenciosamente descartada.
- **Deduplicação e Desacoplamento:** O cliente deduplica eventos por `eventId` em cache LRU em memória. Falhas de FCM ou descarte de push nunca revertem nem bloqueiam transações de negócios no backend (Transactional Outbox).
- **Preferências e Ciclo de Vida:** O usuário tem controle master (`pushEnabled`) e switches por categoria. Logout ou desativação social desregistra imediatamente os dispositivos no backend e limpa o escopo local de push.

## 13.14 Check-ins de treino e Feed Social (T17.8)

O Spark passou a ter uma **publicação social explícita**. As regras abaixo são o que impede essa
publicação de virar postagem automática, de virar um segundo caminho de upload de treino, ou de
virar uma vitrine do que o histórico tem de íntimo.

- **O núcleo não paga nada por isso.** Check-in é opcional, como todo o social. Sem conta, sem
  perfil, com o servidor fora: treinar, concluir, consultar histórico, gamificação, backup, restore,
  sync e Coach continuam exatamente como estavam. Falha ao publicar **nunca** reverte conclusão de
  treino, XP, PR ou streak — o treino já está salvo antes de o CTA existir na tela.
- **Concluir um treino não publica nada.** Não existe gatilho no fim da sessão, no ciclo de sync, na
  abertura de tela ou em background. A ordem é `commit COMPLETED → resultado salvo → opção social`,
  nunca o inverso. E o **primeiro toque no CTA não faz requisição**: ele abre o preview, que lista o
  que vai e o que **não** vai. Só a confirmação publica.
- **O consentimento é por sessão, e é independente de todos os outros.**
  `activitySharingEnabled = false` não impede publicar; `= true` não publica nada.
  `friendRankingParticipationEnabled`, Challenge (T17.3) e Workout Share (T17.7) não interferem no
  Feed em nenhuma direção, e publicar não altera nenhum deles.
- **O cliente não declara conclusão.** `completed`, `status`, `ownerUid`, `authorUid`, `publishedAt`,
  `checkInId`, `caption`, `photoUrl` e `audience` são recusados **por nome**, invalidando a
  requisição inteira. O corpo tem duas strings: `sessionSyncId` e `clientRequestId`. O dono sai do
  token verificado; o estado da sessão sai da fonte canônica; o instante sai do `Clock` do servidor.
- **A fonte canônica é a da T17.4.1, e não ganha concorrente.**
  `CanonicalTrainingSource.findSessionForCheckIn` é uma operação **estreita** no mesmo adapter que
  responde perfil, desafio e atividade. Nenhum arquivo de check-in consulta `sync_entities`,
  `json_extract` ou `'WORKOUT_SESSION'` por conta própria — há teste estrutural. Só saem cinco
  escalares; `payload` nunca é materializado.
- **O instante é `COALESCE(finishedAt, startedAt)`, e não um significado novo.** `finishedAt` é o
  fim do treino e tem um escritor só, mas é nulável no contrato; o fallback é o instante canônico já
  usado pela semana da T17.2 e pelo dia da T17.3. Como `startedAt ≤ finishedAt`, ele só encurta a
  janela — nunca a alarga. A atribuição de treino a **dia** (§13.11) continua intocada.
- **Não existe segundo uploader de sessão.** Quando o servidor não conhece a sessão, o app pede um
  ciclo do **Sync T16** e tenta de novo com o mesmo `clientRequestId`. É proibido montar push,
  escrever na Outbox ou falar com `sync_entities` a partir do caminho social — há teste estrutural.
- **Adoção de nuvem continua explícita.** Sem `cloud_data_binding`, ou com dataset de outra conta, a
  publicação não acontece e a tela explica. Criar o vínculo em silêncio para o Feed funcionar seria
  associar o dataset inteiro de alguém a uma conta sem que a pessoa tivesse pedido.
- **Um treino, no máximo um check-in — e a garantia é do banco.**
  `UNIQUE (author_uid, source_session_sync_id)` e `UNIQUE (author_uid, client_request_id)`. Mesmo
  `clientRequestId` em outra sessão é conflito; excluir é definitivo para aquela sessão, porque a
  linha permanece e a `UNIQUE` continua valendo.
- **Sem Outbox social, e sem fila.** Offline, publicar **não acontece**: não fica pendente, não é
  reenviado e a tela diz isso. O retry é ação nova do usuário, pelo Histórico, dentro da janela.
- **O Feed é `FRIENDS_ONLY`, e a autorização mora na consulta.**
  `{ viewer } ∪ { amigos diretos atuais ∧ perfil ACTIVE ∧ ¬bloqueado }`, avaliado **a cada
  leitura**. Pedido pendente não concede; `unfriend` revoga; bloqueio revoga nas duas direções;
  Social desativado some dos outros e volta ao reativar. Filtrar amizade no Android não é controle
  de acesso. Não existe `?users=`, não existe rota pública por `socialId`, e não existe feed
  infinito: 30 dias, `limit` padrão 20 e teto 50, ordenado por publicação com desempate por
  `checkInId`.
- **O DTO é o contrato inteiro**: `type`, `checkInId`, `author { socialId, displayName }`,
  `publishedAt`, `isCurrentUser`. `publishedAt` é quando **publicou**, nunca quando treinou. Não
  cruzam a fronteira: uid, e-mail, `friendCode`, `sessionSyncId`, `startedAt`, `finishedAt`,
  `templateId`, nome do treino, exercício, série, repetição, carga, duração, volume, PR, caloria,
  nota, medida e horário do treino. Há teste que varre a resposta real atrás de todos.
- **Sem conteúdo livre nesta fase.** Sem legenda, foto, vídeo, comentário, reação ou curtida — a
  ausência evita trazer moderação de UGC, denúncia de post, edição e sanitização junto. A T17.9
  expande **este** agregado; ela não cria um segundo feed.
- **Nada disso é evento de domínio.** Publicar e ler não dão XP, conquista, missão, streak nem
  pontuação, não escrevem em `sync_entities`, na Outbox, no backup ou no restore, e **não disparam
  push** — `WORKOUT_CHECK_IN` não tem notificação, e nenhuma preferência nova foi criada.
- **Excluir a publicação ≠ excluir o treino.** Só o autor exclui (outro recebe a mesma resposta de
  "não existe"), é idempotente, não toca a sessão — e excluir a sessão **não** apaga a publicação. A
  tela de exclusão do Histórico diz isso, porque prometer o contrário seria a ambiguidade mais cara
  daquela tela.
- **Cache do Feed é memória, e só.** Sem Room, sem DataStore, account-scoped, limpo **antes** de a
  requisição da conta nova sair, e a resposta de uma requisição da conta anterior é descartada.
  Logout e desativação limpam na hora. Sem polling, sem WebSocket, sem SSE.
- **Logs.** No servidor: `requestId`, prefixo de uid, evento, status e `returnedCount`. Nunca
  `sessionSyncId`, `displayName`, `socialId`, uid completo, `friendCode` ou payload de treino. No
  Android: nada.
- **Anti-fraude, honestamente.** O servidor exige sessão canônica sincronizada e `COMPLETED`, e não
  aceita `completed = true`. Isso **não** torna o check-in à prova de fraude: um cliente
  comprometido que fabrique dados canônicos válidos produziria um check-in correspondente — problema
  de integridade do dado de treino, que existiria sem o Feed. Attestation avançada está fora de
  escopo.
- **Testes.** Toda mudança no check-in roda `npm test` em `backend/` e
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.social.*" --tests
  "com.example.data.repository.WorkoutCheckIn*" --tests
  "com.example.presentation.friends.SocialFeed*" --tests
  "com.example.presentation.friends.WorkoutCheckIn*"`. As duas são offline e usam dublê de
  autenticação.

## 13.15 Check-ins ricos: foto, legenda, reações e comentários (T17.9)

O Feed passou a carregar **conteúdo gerado por usuário**. As regras abaixo são o que impede esse
conteúdo de virar uma segunda publicação, de virar um vazamento de localização, e de virar um
canal em que o bloqueio deixa de proteger.

- **O núcleo não paga nada por isso.** Foto e legenda são opcionais dentro de uma publicação que já
  era opcional. Sem conta, sem perfil, com o servidor fora: treinar, concluir, histórico,
  gamificação, backup, restore, sync e Coach continuam exatamente como estavam. Falha ao enviar
  foto **nunca** reverte conclusão de treino, XP, PR ou streak.
- **Um agregado, e nenhum segundo Feed.** A T17.9 expande o `WorkoutCheckIn` da T17.8: `caption`,
  no máximo **uma** `media`, `reactions` e `comments`. Não existe `SocialPost`, não existe
  `/v1/social/posts` e não existe uma segunda rota de leitura. Publicação da T17.8 continua válida
  sem backfill — `caption = null`, `media = null`, `reactions = {}`, `commentCount = 0`.
- **Concluir um treino continua não publicando nada** (§13.14), e escolher uma foto também não: o
  primeiro toque abre o compositor, a leitura da imagem acontece no aparelho, e só a confirmação
  envia alguma coisa.
- **A legenda nasce do teclado, nunca de uma nota.** É proibido preencher `caption` a partir de
  `WorkoutSession.notes`, `WorkoutTemplate.notes` ou do nome do treino. Publicar o que a pessoa
  escreveu para si mesma é o vazamento mais fácil desta fase.
- **Texto é texto.** `0..280` (legenda) e `1..300` (comentário) em **code points**, NFC, sem
  controle C0/C1, sem zero-width, sem override bidirecional, com quebras de linha bounded. HTML,
  Markdown e JavaScript **não são interpretados e não são escapados**: o Android desenha com `Text`
  de Compose, e escapar corromperia o texto da pessoa para se defender de um risco que este caminho
  não tem. URL, `@menção` e `#hashtag` são caracteres — não existe detecção, link clicável nem
  preview.
- **O `Content-Type` não decide nada.** O servidor **decodifica de verdade** (`sharp`/libvips),
  recusa animação antes de recusar formato, valida pixels e arestas antes de alocar, aplica a
  orientação EXIF aos **pixels** e re-encoda em WebP **sem** `withMetadata()`. É a ausência dessa
  chamada que remove GPS, modelo do aparelho e data original — a foto de um treino tirada em casa
  carrega a coordenada da casa da pessoa. O original nunca encosta no disco.
- **A imagem não entra no banco, e a chave é do servidor.** Metadata em `social_checkin_media`,
  bytes em `SocialMediaStore` sob `SOCIAL_MEDIA_ROOT`. A chave é opaca
  (`checkins/xx/yy/<uuid>.webp`), nunca deriva de uid, `socialId`, `friendCode`, `displayName` ou
  nome de arquivo, e **nunca** vem do cliente. Path traversal tem duas barreiras: allowlist de
  forma e confinamento na raiz.
- **Produção sem volume de mídia não sobe.** `SOCIAL_MEDIA_ROOT` é obrigatória quando
  `NODE_ENV=production` (`AppConfig.missingRequirements`). Um default derivado cairia na camada
  efêmera do container, e um deploy que não montasse a mídia perderia todas as fotos na primeira
  recriação de container — em silêncio.
- **Upload exige sessão elegível.** `POST /v1/social/checkin-media` não é armazenamento genérico:
  ele exige `sessionSyncId` de uma sessão canônica, `COMPLETED`, do dono autenticado, dentro da
  janela — a mesma verificação do check-in, pela mesma `CanonicalTrainingSource`. `mediaId` é
  owner-scoped, session-scoped, usado **uma vez**, e o retry converge pelo `clientUploadId`.
- **A foto que falha não some em silêncio.** A publicação **para**, e a decisão entre "tentar de
  novo" e "publicar sem foto" é do usuário. Um caminho que seguisse em frente a tomaria por ele —
  com o resultado no Feed dos amigos antes de ele perceber.
- **Uma política de visibilidade, em um lugar.** `workout-checkin.access-policy.ts` define
  `eligible_authors` e é usada por Feed, detalhe, mídia, reações, comentários e denúncia. Um
  controller que consulte `friendships` ou `social_blocks` por conta própria é bloqueante
  arquitetural — há teste que varre os controllers atrás de `SELECT`.
- **Bloqueio é autorização por viewer, nunca exclusão global.** No post de um terceiro, A e B
  deixam de ver a interação um do outro **sem** que nada seja apagado para o dono do post. E as
  contagens são **do viewer**: um `COUNT(*)` global vazaria a participação de quem o bloqueio
  esconde. O Android não recalcula contagem nenhuma.
- **Conhecer o `mediaId` não concede acesso.** `GET /v1/social/media/{id}` exige token e passa pela
  mesma política; não-amigo, par bloqueado, autor desativado e post excluído recebem `404`. Não
  existe URL pública, diretório estático, URL assinada, `ETag` ou `Cache-Control: public`.
- **Cache de mídia é memória, e é da conta.** Sem disco, sem Coil com `diskCache`, sem arquivo em
  `cacheDir`. O cache é trocado **antes** de a primeira requisição da conta nova sair. A foto de A
  reaparecendo para B é bloqueante.
- **Photo Picker oficial, e nenhuma permissão nova.** `ActivityResultContracts.PickVisualMedia`;
  `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE` e `CAMERA` continuam fora do manifesto, e há teste
  que lê o manifesto.
- **Reação é otimista; comentário não é.** A reação é reversível — a tela responde na hora e faz
  rollback para o estado que veio do servidor. O comentário espera a resposta, e o rascunho
  permanece quando o envio falha: um comentário que aparece e some faz quem escreveu acreditar que
  a outra pessoa leu.
- **Enum fechado para reação.** `FIRE`/`MUSCLE`/`CLAP` no contrato e no `CHECK` do banco. O cliente
  não envia emoji; qual desenhar é decisão da tela.
- **Denúncia: o cliente diz o quê, o servidor diz de quem.** `targetType` ∈ `USER|CHECKIN|COMMENT`
  — **não existe `MEDIA`**, porque a foto pertence ao check-in. `reportedUid` é recusado por nome.
  Denunciar exige ver o alvo, não permite o próprio conteúdo, não pune, não oculta e não notifica.
- **Nada disso é evento de domínio.** Foto, legenda, reação e comentário não dão XP, não
  desbloqueiam conquista, não movem missão, não alteram streak, ranking ou desafio, **não geram
  push** e não aparecem na Activity da T17.4. Nenhuma preferência de notificação foi criada.
- **Exclusão leva tudo junto.** Excluir a publicação revoga foto, reações e comentários na hora
  (o arquivo sai no cleanup) e **não** toca a sessão de treino. Excluir a conta remove posts,
  legendas, mídia (banco **e** arquivos), comentários — inclusive os feitos em posts alheios — e
  reações. As chaves de armazenamento são lidas **antes** do purge: depois dele não há o que ler.
- **Backup e DR levam a mídia.** `ops/backup.sh` manda `$SPARK_MEDIA_DIR` no mesmo snapshot restic;
  `ops/restore.sh` restaura e instala os dois; `ops/verify-backup.sh` lê os bytes de dentro do
  container. Depois de um restore antigo, a reconciliação de tombstones purga banco **e** arquivos.
- **Nada é periódico além do necessário.** Um `setInterval` bounded (`SocialMediaCleaner`) recolhe
  `PENDING` expirada, mídia `DELETED` e órfãos, em lotes fixos. Sem fila externa, sem scheduler
  novo, sem polling no Android.
- **Logs.** No servidor: evento, `requestId`, prefixo de uid, prefixo de `mediaId`, bytes
  processados, dimensões, formato de origem, status, tipo de reação, comprimento do comentário.
  **Nunca** legenda, corpo de comentário, bytes, caminho de arquivo, nome original, `displayName`,
  `socialId`, `friendCode` ou uid completo. No Android: nada.
- **Dependência.** O pipeline de imagem é `sharp` (libvips), pinado em versão exata, e ele é a
  **única** biblioteca de imagem da árvore de produção — há teste. `docker build` é gate: se o
  binário nativo não vier, a imagem não sai. Multipart continua **fora** do caminho de execução, e
  há teste que garante isso.
- **Testes.** Toda mudança nesta fase roda `npm test` em `backend/` e
  `./gradlew :app:testDebugUnitTest --tests "com.example.data.social.*" --tests
  "com.example.data.media.*" --tests "com.example.presentation.friends.CheckIn*" --tests
  "com.example.presentation.friends.SocialFeed*"`. As duas são offline e usam dublê de
  autenticação.

## 13.16 Fechamento do Social: o que a auditoria travou (T17.10)

A T17.10 não acrescentou funcionalidade. Ela auditou T17.0–T17.9 como um sistema só e corrigiu o
que encontrou. As regras abaixo são as que **não existiam explicitamente** antes dela — cada uma
nasceu de um defeito real, e cada uma tem teste.

- **O tombstone é verificado sobre o caminho, nunca sobre a URL.** O guard isentava a rota de
  conta com um `includes('/v1/account')` sobre `originalUrl` — que carrega a query string. Uma
  conta excluída voltava a **escrever** com `?x=/v1/account` em qualquer rota: reativava o perfil,
  publicava, comentava. A comparação agora é de caminho e por segmento, e `/v1/accounts` ou
  `/v1/account-recovery` não herdam a isenção.
- **A chave de tombstone é obrigatória em produção.** `ACCOUNT_DELETION_HMAC_KEY` tinha default no
  repositório e `missingRequirements()` não a exigia. Duas falhas ao mesmo tempo: com a chave
  conhecida, qualquer pessoa com o banco confirma um uid; e **trocá-la depois** faz todos os
  tombstones existentes deixarem de casar — conta excluída voltando a passar pelo guard, e a
  reconciliação de DR deixando de reconhecê-la. Produção com o default de desenvolvimento **não
  sobe**. Ela nunca deve ser rotacionada sem plano de migração.
- **Migration aplicada não muda de conteúdo.** O runner recusava um arquivo renomeado e aceitava
  um arquivo **editado**. Agora `schema_migrations` guarda o SHA-256 do texto, e uma edição
  retroativa derruba o startup. Banco anterior à coluna é adotado no primeiro arranque (não há como
  saber retroativamente o que foi aplicado) e protegido a partir dali.
- **Uma varredura que não varre passa vazia.** Toda suíte que percorre superfícies precisa afirmar
  que **exercitou** cada uma. Um caminho errado numa lista de rotas deixou o ranking fora da
  varredura de privacidade sem nada falhar. O mesmo vale para a inspeção estrutural do Android: as
  telas da T17.4, T17.5, T17.7 e T17.9 estavam fora da lista, e a lista é o teste.
- **Tela social não segura entidade de Room.** `ShareWorkoutDialog` recebia `WorkoutTemplateEntity`
  e a lista de exercícios. O snapshot passou a ser montado de onde a entidade legitimamente mora, e
  o diálogo recebe só o resultado portável — a fronteira do social é não conhecer Room, e uma tela
  social com a entidade de treino na mão é onde um campo privado entra sem que ninguém decida.
- **Toda ViewModel social tem escopo de conta.** Limpar vem **antes** de a requisição da conta nova
  sair, e toda resposta confere o `uid` de origem antes de tocar no estado.
  `NotificationPreferencesViewModel` era a única sem nenhuma das duas coisas.
- **Estado técnico de instalação não entra no backup do Android.** Token FCM, `socialId` registrado,
  InstanceID e sessão do Firebase Auth saem do Auto Backup **e** da transferência de aparelho. Dois
  aparelhos anunciando o mesmo token ao backend é notificação entregue no lugar errado; estado de
  instalação se reconquista no primeiro registro, nunca se restaura.
- **O teto do proxy fica acima do maior teto do backend.** São dois tetos — JSON (4 MiB) e imagem
  (10 MiB) — e o Caddy estava em 5 MB, de quando só existia o primeiro. Ele cortava foto legítima
  antes do backend, com 413 genérico, em vez de deixar o servidor responder `MEDIA_TOO_LARGE`.
- **O volume de mídia entra nas checagens de disco.** Ele é o que **cresce**, e em produção costuma
  ser uma partição separada. Vigiar só o diretório do banco deixava a partição das fotos sem alarme.
- **Log não carrega `socialId`.** A regra já valia desde a T17.0; `social.block.removed` a violava.
  Prefixo de uid correlaciona no suporte sem registrar o identificador com que a pessoa é
  encontrável.
- **Identificador vindo do cliente tem forma declarada.** `canonicalExerciseId` só exigia "string
  não vazia": com 30 exercícios e 4 MiB de JSON, era um canal de texto livre que o servidor
  guardava e devolvia. A política de exercício CUSTOM continua fail-closed **no aparelho** — o
  servidor não conhece o catálogo e valida a forma, não a existência.
- **O que a auditoria confirmou e não mudou:** a autoridade continua dividida (treino local-first,
  social server-authoritative), o bloqueio é aplicado no servidor e por viewer inclusive nas
  contagens, o Feed é bounded em linhas **e** em consultas, o EXIF/GPS não sobrevive ao re-encode,
  o original nunca toca o disco, e excluir a conta não toca o Room.

## 13.17 Squads privados e feed de grupo (T17.11)

A T17.11 abre o Social V2: pequenos grupos privados, formados por convite, com um feed onde os
membros trazem **explicitamente** check-ins que já publicaram. As regras abaixo são as invariantes
da fase — cada uma tem teste, e cada uma existe porque a alternativa produz um defeito nomeável.

- **Um Squad é privado, e a privacidade é estrutural.** Não existe busca, listagem pública, link de
  convite, QR ou código de entrada. Uma pessoa conhece um Squad porque está dentro dele ou porque
  recebeu um convite, e não há terceira porta. Conhecer o `groupId` não concede nada: quem não é
  membro recebe o mesmo `404` de "não existe", em toda superfície do grupo.
- **A T17.11 não cria um segundo modelo de publicação.** O feed do Squad é o **mesmo**
  `WorkoutCheckIn` da T17.8/T17.9 lido por outra audiência. `social_group_checkin_shares` é uma
  **aresta**, e não um post: excluir o Squad, sair dele ou desfazer o compartilhamento apagam a
  aresta e nunca a publicação. A montagem do card virou um provider (`CheckInProjector`) justamente
  porque passou a ter duas superfícies — duas cópias divergiriam no primeiro campo novo.
- **Nada entra sozinho.** Publicar um check-in não o coloca em Squad nenhum, e entrar num Squad não
  traz check-ins antigos de ninguém. Só o **autor** traz o próprio check-in, por um toque, com
  confirmação. O teto é 5 Squads por check-in.
- **Participação e amizade são consentimentos diferentes, nos dois sentidos.** A amizade é
  revalidada no **convite** e no **aceite**, porque é ali que ela é a autorização; depois disso a
  participação é um consentimento próprio, e desfazer a amizade não remove ninguém. O inverso
  também vale: estar no mesmo Squad não cria amizade e não concede perfil de amigo.
- **Bloqueio continua soberano, e não destrói participação.** Ele corta a visibilidade entre o par —
  no feed, na mídia, na lista de membros — e mantém as duas participações de pé. Destruí-las
  contaria a todos os outros membros que houve um bloqueio, e daria a qualquer um o poder de
  expulsar outro de um grupo que não é dele. Na lista de membros o par bloqueado vira uma entrada
  **opaca** com `membershipId` e nenhuma identidade: é o que permite ao dono administrar o grupo sem
  receber o `socialId` de quem o bloqueou. A contagem de membros não muda — ela não é identidade.
- **Exatamente um `OWNER`, e a garantia é do banco.** Um índice único parcial sobre
  `(group_id) WHERE role = 'OWNER'` torna zero ou dois donos **irrepresentáveis**, em vez de
  improváveis: a transferência de posse é duas escritas, e uma transação que falhasse no meio
  deixaria um estado visível para outras pessoas que não se corrige sozinho.
- **Relação puramente de grupo é read-only nesta fase.** Reagir e comentar continuam exigindo
  relação direta (autor ou amigo). Um mesmo check-in em dois Squads e no Feed de amigos passaria a
  ter uma conversa com três audiências sobrepostas, e resolver isso exige comentários cientes de
  audiência — que a T17.11 não introduz em silêncio. O DTO carrega `canInteract`, decidido no
  servidor; a tela usa o booleano para não desenhar o que não funciona, e o servidor recusa de
  qualquer forma.
- **A mídia ganhou o terceiro caminho, com a mesma definição.** `WorkoutCheckInAccessPolicy`
  responde `self ∨ amizade direta ∨ Squad compartilhado`, sempre sob perfil ativo e ¬bloqueio, e o
  predicado de Squad é exportado como **texto** para que o feed, o detalhe e os bytes da foto usem
  literalmente a mesma regra. O `mediaId` continua não concedendo nada.
- **Desativar o Social pode recusar; excluir a conta nunca.** Ser dono de um Squad com outras
  pessoas bloqueia a desativação com `GROUP_OWNERSHIP_REQUIRES_ACTION` e uma **contagem** — nunca
  dados de membro. A alternativa seria escolher um novo dono por ordenação arbitrária, que é
  entregar um grupo de gente real a quem não pediu. Na exclusão de conta não há como recusar, e por
  isso a política é outra: o Squad do dono excluído vai junto, sem substituto silencioso, e os
  check-ins, treinos e templates dos outros membros continuam intactos.
- **Um push, e só um.** `GROUP_INVITATION_RECEIVED`, data-only, com `entityId = invitationId`.
  Nunca o nome do Squad, o de quem convidou ou o dos membros. Não existe push para "entrou", "saiu",
  "foi removido", "posse transferida", "check-in compartilhado" nem "Squad excluído": um grupo de 20
  pessoas que notificasse cada movimento seria um chat com outro nome. O deep link abre a **lista de
  convites**, e nunca o detalhe do grupo — quem ainda não aceitou não é membro dele.
- **Nada de Squad mora no Room.** Sem entidade, sem DAO, sem Outbox, e Squad **não** é
  `sync_entity` da T16. Cache de memória com escopo de conta, trocado antes de a requisição da conta
  nova sair. Sem backend configurado, a área de Squads some — e todo o núcleo de treino continua
  funcionando offline.
- **O rate limit fica acima do limite de domínio, não igual a ele.** Criar Squad tem teto de domínio
  de 5 e teto de requisição de 10/min de propósito: iguais, o sexto pedido devolveria `429` em vez
  de `GROUP_OWNED_LIMIT_REACHED` — uma mensagem que não explica nada e que some sozinha depois de um
  minuto, ensinando a pessoa a tentar de novo em vez de a entender o limite.

## 13.18 Interações contextuais: reações e comentários por audiência (T17.12)

A T17.11 deixou reagir e comentar como privilégio de relação direta, e o motivo não era
desconfiança do grupo: o mesmo check-in pode estar no Feed de amigos e em vários Squads ao mesmo
tempo, e uma interação sem audiência vazaria de um lugar para o outro. A T17.12 resolve o problema
em vez de contorná-lo — **a interação passa a pertencer a uma audiência explícita** — e por isso a
restrição caiu.

- **Uma publicação, várias audiências, uma conversa em cada.** O `WorkoutCheckIn` continua sendo um
  objeto só: não existe post duplicado por Squad, não existe segundo modelo de publicação. O que
  passa a existir é a audiência da interação: `FRIEND` (o Feed de amigos) e `GROUP(groupId)` (um
  Squad específico). Não existe `PUBLIC`, `FOLLOWERS`, `CUSTOM` nem `MULTI_GROUP`, e `SELF` não é
  audiência persistida — o autor enxerga as audiências em que a própria publicação existe.
- **O contexto é uma proposta do cliente, e nunca uma concessão.** A tela diz de onde veio; o
  servidor revalida tudo, contra as tabelas, a cada requisição: Squad ativo, check-in explicitamente
  compartilhado ali, participação ativa do requisitante **e** do autor, e ausência de bloqueio. Um
  `groupId` sozinho não abre nada — é a mesma regra que faz um `checkInId` ou um `mediaId` vazado
  não valer nada desde a T17.9.
- **Fail-closed, sempre.** Um contexto de grupo inválido — Squad errado, sem compartilhamento, sem
  participação — é `404`, e **nunca** um rebaixamento silencioso para `FRIEND`. O caminho contrário
  publicaria no Feed de amigos algo que a pessoa escreveu achando que estava dentro de um Squad.
  Um contexto malformado (`GROUP` sem `groupId`) é `400`: o pedido está errado, e dizer "não
  encontrado" mandaria o cliente procurar o defeito no lugar errado.
- **Participação autoriza dentro do Squad, e só dentro dele.** Um membro sem amizade nenhuma reage e
  comenta na audiência daquele Squad. Isso **não** cria amizade, não concede perfil de amigo, não
  abre o Feed de amigos, não habilita compartilhamento de treino nem desafio, e não autoriza
  interagir com a mesma publicação no Feed de amigos.
- **Uma reação por pessoa, por publicação, por audiência.** A mesma pessoa pode ter 🔥 no Feed de
  amigos e 💪 no Squad X sobre o mesmo check-in: são interações independentes, e trocar ou remover
  uma não toca a outra. A garantia é do banco, por **dois índices únicos parciais** — um por
  partição de audiência. Uma `UNIQUE` comum não serviria: em SQL (SQLite e PostgreSQL) cada `NULL`
  é distinto de qualquer outro, e duas reações `FRIEND` da mesma pessoa (as duas com
  `group_id IS NULL`) passariam sem conflito.
- **Contagens e listas são por audiência _e_ por viewer.** As duas filtragens são independentes: a
  primeira impede que a conversa do Squad X apareça em Y ou no Feed de amigos; a segunda mantém o
  bloqueio viewer-safe da T17.9 — quem está em bloqueio não transparece nem como número, e some para
  o par sem ser apagado para os outros.
- **Participação é o consentimento que sustenta a audiência.** Sair do Squad, ser removido ou
  desativar o Social encerram as interações daquela pessoa **naquele** Squad — e também as que os
  outros deixaram nas publicações dela ali, porque os compartilhamentos dela saem junto e a conversa
  perde o objeto. Voltar não ressuscita nada. Desfazer o compartilhamento revoga a audiência daquele
  Squad; excluir o Squad apaga a audiência dele. Nenhuma dessas operações alcança o Feed de amigos,
  outro Squad ou a publicação — e desfazer a **amizade** não alcança audiência de Squad nenhuma.
- **Moderação segue a audiência.** Podem apagar um comentário: o autor dele, o autor do check-in e —
  **só** quando a audiência é `GROUP` daquele Squad — o dono do Squad. O privilégio de dono não
  atravessa para o Feed de amigos, nem quando dono e autor são amigos; não alcança outro Squad; e
  não vira exceção de privacidade: um comentário que o bloqueio já esconde do dono não se torna
  visível para ser moderado. Denunciar continua exigindo enxergar o conteúdo **naquela** audiência.
- **A audiência é uma propriedade do comentário, não do pedido.** Por isso apagar um comentário não
  recebe contexto: o servidor lê de qual audiência ele é. Aceitar um contexto ali deixaria a tela
  declarar em que audiência ela acha que está — que é exatamente o que não pode decidir moderação.
- **No Android o contexto pertence à tela, e toda chave de cache o inclui.** Nunca estado global.
  Uma chave por `checkInId` sozinha mostraria os comentários do Squad X dentro do Squad Y só por
  cache, sem que o servidor tivesse errado nada.
- **O que a T17.12 continua não fazendo.** Nenhum push de reação ou comentário, nenhum XP, missão,
  conquista, ranking ou desafio, nenhum evento de Activity, nenhuma alteração de ordenação de feed,
  nenhum contador de não lidos, nada de realtime, e nada no Room nem no Outbox. O teto de
  comentários por publicação atravessa as audiências de propósito: contá-lo por audiência daria a
  quem quisesse floodar um multiplicador pelo número de Squads em que o post está.

## 13.19 Integridade da exclusão de conta e da recuperação de desastre (T17.13.1)

Fechamento pós-auditoria do Social. Nenhuma funcionalidade nova; sete regras que passam a valer.

- **Exclusão de conta é uma transação.** Tombstone, job e purge das tabelas account-scoped entram no
  mesmo `BEGIN`/`COMMIT`. Um `DELETE` que falhe no meio faz `ROLLBACK` de tudo. Não existe estado
  "conta bloqueada com dados pela metade" — ele não seria visível para ninguém e nada saberia
  interpretá-lo. **A mídia fica fora da transação**: as chaves são lidas antes, os arquivos saem
  depois do commit, e I/O de sistema de arquivos nunca segura uma transação do banco.
- **Depois do commit do purge, os dados nunca voltam.** Falha de arquivo, de ledger ou do Firebase
  não desfazem a exclusão. O que elas adiam é a *declaração* de término.
- **O registro anti-ressurreição é obrigatório, e a falha dele é visível.** `deletion_tombstones.tsv`
  é escrito com `append` + `fsync`; se a escrita falhar, a resposta é `DELETION_PENDING` — nunca
  `DELETED`. Dizer que terminou sem esse registro é prometer o que o servidor não pode cumprir: é
  exatamente o restore seguinte que traria a conta de volta. O que falta tem nome durável em
  `account_deletion_jobs.phase` e sobrevive a restart, porque é uma linha do banco.
- **Recuperação de desastre não termina em lembrete.** A reconciliação é um comando
  (`dist/cli/reconcile-account-deletions.js`) e **uma** autoridade — não há reconciliação no startup
  nem gatilho no readiness. `ops/restore.sh --install` o executa antes de declarar a restauração
  completa. Ledger ausente ou malformado **falha fechado**: nunca "nenhuma conta excluída", porque
  as duas leituras não apagam nada e uma delas ressuscita contas.
- **O inventário de colunas de uid é declarado, e um teste o defende.** Ele vive em
  `account-uid-inventory.ts` e é confrontado com o schema real do banco. Uma tabela nova com coluna
  de uid não passa em silêncio: ela reprova o teste até alguém declarar a política — reconciliar, ou
  justificar por que não. O sufixo `_uid` é a heurística que **encontra**, nunca a que decide.
- **Verificação de estado de conta falha fechada.** Não conseguir avaliar o tombstone responde
  `503`, e não "conta ativa". `false` naquele ponto significa "pode entrar", e devolver isso quando
  a consulta falhou é declarar ativa uma conta que pode estar excluída — justamente durante o
  incidente em que ninguém está olhando.
- **Estado derivado do relógio não substitui estado gravado quando o banco precisa dele.** `EXPIRED`
  de convite de Squad era derivado na leitura, e um índice único parcial em `WHERE status =
  'PENDING'` não consulta o relógio: o convite vencido segurava a vaga do par para sempre. Derivar é
  suficiente para *exibir*; o que participa de índice, quota ou unicidade precisa ser **gravado**.
- **Idempotência é sobre a intenção, não sobre a chave.** A conferência acontece depois da
  autorização e **antes** do rate limit — um retry legítimo não pode virar `429` por uma janela que
  a primeira tentativa consumiu. Ela compara o payload canônico: mesma chave com payload diferente é
  `409`, e nunca o resultado antigo. Devolver o resultado antigo faz o cliente acreditar que fez o
  que pediu agora. Replay **não** pula autorização: perfil ativo, posse e participação são
  revalidados sempre.
- **Idempotência de upload compara a entrada, não a saída.** `input_content_hash` é o SHA-256 dos
  bytes que chegaram e responde "é a mesma requisição?"; `content_hash` é o da imagem sanitizada e
  responde "é a mesma imagem armazenada?". Duas entradas diferentes podem convergir para a mesma
  saída depois do processamento, e como requisições continuam sendo duas. Registros anteriores à
  coluna não são reprocessados para preenchê-la.
- **Um efeito durável obrigatório entra na transação de quem o exige.** `workout_shares` e o evento
  de notificação nascem juntos: uma oferta sem o aviso que a torna visível é pior que nenhuma
  oferta, porque é silenciosa. O envio ao FCM continua fora — uma chamada de rede dentro de um
  `BEGIN` seguraria o banco pelo timeout do provedor.
- **Transição de estado é condicional ao estado esperado.** `UPDATE ... WHERE id = ? AND status = ?`,
  conferindo `changes`. Ler, decidir em memória e escrever deixa uma requisição inteira de janela, e
  duas transições incompatíveis simultâneas ambas passam. Quem perde a corrida **relê** e responde a
  partir do estado real. Isso não é uma segunda máquina de estados: a tabela continua sendo a
  autoridade.

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
