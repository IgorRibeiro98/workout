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
- **`cp spark.db` com o banco ativo é proibido.** Em WAL, o arquivo principal não contém o que
  ainda está no `-wal`. O snapshot é `VACUUM INTO` a partir de uma conexão **somente leitura**,
  seguido de `integrity_check` e `foreign_key_check` **sobre a cópia** — é ela que vai para o
  off-site, e é ela que precisa provar que serve.
- **Uma cópia na mesma VPS não é backup.** O destino é off-site e criptografado (restic), e a
  senha existe fora da VPS. Backup criptografado cuja senha só existe na máquina perdida é backup
  perdido.
- **Backup nunca é validado por exit code.** Só está validado o que foi restaurado:
  `ops/verify-backup.sh` restaura, verifica integridade e **sobe o backend real sobre a cópia**
  exigindo `/health/ready`.
- **Migration de produção não roda sem ponto de recuperação.** `ops/deploy.sh` faz backup antes, e
  aborta se ele falhar. E **rollback de aplicação não desfaz migration**: mudança incompatível
  segue *expand → deploy → contract em release posterior*.
- **A imagem é identificável.** Tag por SHA do commit, nunca `latest` em produção, e nunca deploy
  com árvore suja — a tag precisa descrever exatamente o que sobe.
- **`synchronous = FULL` é a escolha, não o default herdado.** O aparelho só libera a Outbox com
  confirmação do servidor: uma transação confirmada e depois perdida é dado que o cliente
  considera salvo e que ninguém vai reenviar. `NORMAL` existe como configuração; trocar é decisão
  explícita.
- **Readiness é sobre servir, não sobre terceiros.** `/health/ready` verifica configuração, SQLite
  e migrations. Ele **não** consulta Firebase nem Gemini, e não vai consultar: o Coach fora não
  pode derrubar backup e sync. `/health/live` prova só que o processo está vivo.
- **Interruptor é pausa, nunca perda.** `AI_ENABLED`, `SYNC_WRITE_ENABLED` e `MAINTENANCE_MODE`
  respondem `503` — que o Android já trata como indisponibilidade recuperável desde a T16.2, sem
  APK novo. Com o push pausado, a Outbox **permanece pendente** e nada é apagado. Um interruptor
  que responda `4xx` faria o aparelho tratar a tentativa como recusada: isso seria perda.
- **Limite não pode parar restore legítimo.** Os tetos são por `uid` autenticado, nunca por IP
  (rede móvel e NAT compartilham endereço, e o Caddy à frente faria todo mundo parecer o mesmo
  cliente). Eles existem para conter laço, e são calibrados ordens de grandeza acima do uso real.
- **Log continua sem conteúdo.** `Authorization`, corpo, payload de backup, payload de sync,
  prompt e resposta do modelo não vão para log — nem do backend, nem do Caddy. Rotação é
  obrigatória: log não pode ser causa provável de disco cheio, e disco cheio derruba o SQLite.
- **Nada é apagado para liberar espaço.** Disco cheio é incidente, e a resposta nunca é remover
  banco, backup ou o arquivo corrompido. `integrity_check` falhando significa **preservar** o
  arquivo e restaurar por cima de uma cópia — nunca `rm spark.db`.
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
  credencial de storage.

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
