# Auditoria geral do Spark — 2026-09-12

Varredura completa de `app/` (Android) e `backend/` + `ops/` + CI, feita em cima de `main` (`3ed6627`).
Somente leitura: nenhum arquivo de código foi alterado. Todo achado abaixo foi lido no código-fonte;
os marcados com ✔ foram re-conferidos manualmente numa segunda passada.

## 0. Estado mecânico (o que roda hoje)

| Verificação | Resultado |
|---|---|
| Backend `tsc --noEmit` | OK |
| Backend `eslint` | OK |
| Backend `jest` (PostgreSQL 17 em Docker, igual ao CI) | **1463 testes, 0 falhas** (90 suítes, 14 min) |
| Android `:app:testDebugUnitTest --rerun-tasks` | **1318 testes, 0 falhas** (138 suítes) |
| Android `:app:lintDebug` | **FALHA: 217 erros `NewApi`**, 119 warnings |

Nota sobre o ambiente do backend: `test/support/postgres-sync-db.ts` exige `psql` no PATH e, sem ele,
cai num `docker exec` para o container fixo `spark-postgres-dev`. Sem um dos dois, 24 suítes falham com
`container ... is not running` e o Jest **não encerra** (pools vazados pelo `afterEach` que falha antes
do `postgres.close()`). Vale documentar no `backend/README.md` ou trocar o helper por `pg` direto.

O `lintDebug` **não roda no CI** (`android.yml` só executa `testDebugUnitTest`, `assembleDebug`,
`assembleRelease`/`lintVital`). É por isso que os 217 erros nunca apareceram.

---

## 1. Críticos

### 1.1 ✔ `java.time` com `minSdk 24` e sem desugaring → crash em Android 7.0/7.1
- `app/build.gradle.kts:275` (`minSdk = 24`), `compileOptions` sem `isCoreLibraryDesugaringEnabled`.
- 217 chamadas em 14 arquivos: `ConsistencyCalculator.kt` (73), `ConsistencyRepositoryImpl.kt` (21),
  `MissionEvaluator.kt` (19), `MissionsScreen.kt` (18), `WorkoutCalendarCard.kt` (18),
  `ChallengeViewModel.kt` (16), `HistoryViewModel.kt` (12), `TodayHighlightCalculator.kt` (10),
  `TodayViewModel.kt` (8), `ConsistencyMilestoneEvaluator.kt` (8), etc.
- `ConsistencyRepositoryImpl` é chamado no `MainApplication.onCreate` → `NoClassDefFoundError` na
  **primeira abertura** em API 24/25. Nenhum teste pega: todos os `@Config` Robolectric usam TIRAMISU.
- **Fix:** `isCoreLibraryDesugaringEnabled = true` + `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")`,
  ou subir `minSdk` para 26 (decisão de produto). Adicionar `:app:lintDebug` ao CI.

### 1.2 ✔ PR gravado em `completeSet` é permanente e usa regra diferente do fim do treino
- `ExecutionViewModel.kt:456-464` grava `MAX_WEIGHT` para qualquer série (inclusive `WARMUP` e
  séries por tempo) e emite XP/conquista; `uncompleteSet` (`:478-482`) não reverte.
  `finishSession.evaluatePersonalRecords` (`WorkoutEngine.kt:628-658`) exclui warm-up e duração.
- Cenário: roda para em 120 kg por acidente, "Concluir", desfaz → PR e XP ficam para sempre.
- **Fix:** registrar PR só em `finishSession` (regra única), ou PR provisório vinculado ao `setLog.id`
  removido em `uncompleteSet`.

### 1.3 ✔ "Trocar exercício / Máquina ocupada" nunca abre o sheet (funcionalidade morta)
- `ExecutionScreen.kt:486-492`: `activeSheet = null; viewModel.loadAlternatives()`. Nenhum lugar
  atribui `WorkoutSheet.Alternatives` (única ocorrência é o branch do `when` em `:642`).
- Efeito colateral: `alternatives` fica não-vazio e o `BackHandler` (`:471`) engole o primeiro "Voltar".
- **Fix:** `activeSheet = WorkoutSheet.Alternatives` no `onClick`.

### 1.4 ✔ Edição feita durante um push em voo é perdida pela Outbox
- `SyncMutationCoordinator.kt:157-178` coalesce na entrada `PENDING` existente; `SyncRepository.kt:481`
  faz `acknowledge(entryIds)` apagando exatamente essa entrada. Não há estado em voo (decisão
  documentada em `SyncContract.kt:52-55`) e `lastSyncedPayloadHash` é gravado mas **nunca lido**.
- Cenário: `prepare()` monta payload do treino X, HTTP sai, usuário edita X → nada é inserido →
  servidor responde `APPLIED` → entrada apagada. A edição está no Room e nunca mais sobe até a
  próxima edição de X.
- **Fix:** no ramo `APPLIED`, dentro da mesma transação, recalcular o snapshot e, se o hash divergir
  de `mutation.payloadHash`, inserir novo `UPSERT PENDING`.

---

## 2. Altos

### Android — execução
- **Pular descanso dispara o alarme de "descanso finalizado"** — `ExecutionScreen.kt:324` + `:1461-1484`.
  `timerTarget` vira `null` no fade-out do `Crossfade`, `FocusedRestView` recebe `now`, o
  `LaunchedEffect` vê `remaining <= 0` e chama `notifyTimerFinished` + segundo `onSkip()`.
  Fix: aceitar `Long?` e não iniciar o loop quando `null`.
- **Pager para na página errada em salto não adjacente** — `ExecutionScreen.kt:165-175`. Dois
  `LaunchedEffect` bidirecionais se cancelam durante `animateScrollToPage`. Fix: `snapshotFlow { settledPage }`.
- **`startSession` não é transacional nem serializado** — `WorkoutEngine.kt:456-569` + `TodayViewModel.kt:237-241`.
  Morte do processo deixa sessão `IN_PROGRESS` vazia ("Treino Vazio" sem saída); duplo toque cria
  duas sessões. Fix: `withTransaction` + `Mutex`.
- **Regra de descanso entre exercícios duplicada e divergente** — `ExecutionScreen.kt:434-449` vs
  `WorkoutEngine.kt:359-368`; `?: 90` hardcoded em 4 lugares (viola PROJECT_RULES §6).
- **Cronômetro de série por tempo perdido em rotação** — `ExecutionScreen.kt:887-926` usa `remember`
  sem `Saveable` e nada no engine.

### Android — UI
- ✔ **Fila de conquistas trava no segundo item** — `MainScreen.kt:819-853` + `AchievementUnlockFeedback.kt:35-42`
  (e `MissionCompletionFeedback`). `unlockQueue.first()` sem `key()`; o componente reaproveita o slot
  com `isVisible=false` e `LaunchedEffect(Unit)` já consumido. Dois desbloqueios no mesmo treino →
  só o primeiro aparece, e missões param de aparecer até o processo morrer. Fix: `key(id) { ... }`.
- **XP "fantasma"** — `TodayScreen.kt:588-596` + `XpGainAnimation.kt:33-40`, mesmo padrão; resíduo
  permanente em `activeXpGains`.
- **Flows recriados a cada recomposição** — `ExerciseDetailsScreen.kt:73-81`, `MainScreen.kt:795`.
  `viewModel.getX(id).collectAsState()` cria Flow novo → recoleta + 8 queries por recomposição.
  Fix: `remember(id) { ... }` ou `StateFlow` no VM.
- **`SettingsScreen` sem ViewModel constrói `AppDatabase`, `ExportEngine`, importadores e um segundo
  `WorkoutEngine` em composição** — `SettingsScreen.kt:85-107`; operações longas em `rememberCoroutineScope`
  são canceladas ao sair da tela.
- **Histórico recalcula volume/séries incluindo warm-up** (VM exclui) — `HistoryScreen.kt:83-85, 138-140, 908-909`
  vs `HistoryViewModel.kt:119`; números diferentes na mesma tela.

### Android — infra
- **`targetSdk = 35`** — política do Play exige 36 desde 31/08/2026 para updates. `app/build.gradle.kts:276`.
- **Regras de backup excluem arquivos com nome errado** — `backup_rules.xml:25,28`, `data_extraction_rules.xml`.
  Os SDKs usam `"com.google.firebase.auth.api.Store." + persistenceKey` e `"PersistedInstallation." + key + ".json"`;
  regras não aceitam curinga → sessão do Firebase Auth e FID **viajam** no Auto Backup, o oposto do
  que o comentário do arquivo afirma.
- **`device_id` do sync no DataStore `settings`, que entra no backup** — `SettingsManager.kt:53`,
  `DeviceId.kt:51-53`. Transferência de aparelho → dois devices com mesmo id, cursor e vínculo.
- **18 ViewModels na raiz do `MainScreen`** (`:120-152`), 29 com `init { launch }` → dezenas de queries
  Room no cold start para telas nunca abertas.
- **Release sem R8** (`isMinifyEnabled = false`, `proguard-rules.pro` vazio), APK 18,5 MB com
  `material-icons-extended` inteiro (85 ícones usados). Risco latente: Moshi por reflexão
  (`ExportEngine.kt:20`, `ExerciseDbCatalogCache.kt:7`) quebra em release quando alguém ligar o R8.

### Android — dados
- **Exceção não prevista no sync derruba o processo** — `MainApplication.kt:643` (`CoroutineScope(Dispatchers.Default)`
  sem `SupervisorJob`/handler), `SyncRemoteApplier.kt:154` só captura `SyncRemoteApplyException`.
  Cenário concreto: `writeProgram` (`:573-584`) não consulta `externalId`; dois aparelhos que importaram
  o mesmo programa antes da nuvem → `UNIQUE index_workout_programs_externalId` → crash em
  `MainActivity.onStart()` a cada abertura, cursor nunca avança.

### Backend
- ✔ **Bloquear alguém cancela TODAS as notificações pendentes dos dois usuários, inclusive as de
  terceiros** — `block.repository.ts:210-226`: `WHERE status='PENDING' AND (recipient_uid = blocker OR
  recipient_uid = blocked)` sem filtro de entidade do par. O `UPDATE` de deliveries seguinte varre todos
  os eventos `CANCELLED` da tabela. Fix: restringir aos `entity_id` do par, ou remover o passo (o
  `checkRelevance` do dispatcher já suprime).
- **Migrations pelo endpoint pooled do Neon usam lock de sessão e `set_config` de sessão** —
  `postgres.service.ts:134-146`, `postgres-migration-runner.ts:543-556, 625-631`. Sem `DATABASE_URL_DIRECT`
  cai na pooled; `pg_advisory_lock` + `statement_timeout=0` numa conexão PgBouncer em modo transação
  é a mesma classe do incidente real do lock preso (T18.3). Fix: recusar `apply` em URL pooled.

---

## 3. Médios (seleção)

### Android
- **Regra de tempo estimado na Composable** — `TodayScreen.kt:236` (`count * 8 + 10 else 45`), viola §3/§6.
- **Nomes com espaço chegam como `João+Silva`** — `Screen.kt:62-66, 86-90` usa `URLEncoder.encode`;
  o helper correto (`encode()` com `%20`) já existe em `:171`.
- **Navegação duplicada em toque rápido** — só `MyEvolution` usa `launchSingleTop`; toque duplo em
  Perfil cria 6 ViewModels em dobro (`MainScreen.kt:307-694`).
- **Semana "congelada"** — `TodayViewModel.kt:85-99` calcula `startOfWeek` no `init` de um VM que
  vive o processo inteiro.
- **Campo da chave API perde caracteres** — `SettingsScreen.kt:276-283` (`remember(key)` + grava a cada tecla).
- **Restore grava `ROOM_APPLIED` fora da transação** — `RestoreRepository.kt:284-298`; morte entre o
  commit e o `updateStatus` faz `runRecovery()` marcar `ABANDONED` sobre um Room já restaurado.
- **Backup recusado (413/409/404) fica `PENDING` para sempre** — `BackupRepository.kt:139-141, 246-257`;
  não há caminho que abandone a tentativa.
- **Troca de programa atual não gera mutação de sync** — `WorkoutRepository.kt:89-92`; outro aparelho
  reativa o programa antigo. ✔ E `addProgram` (`:84`) compara `Flow` com `null` — condição sempre falsa,
  primeiro programa nunca vira atual.
- **Importação de treino compartilhado não atômica** — `WorkoutShareImporter.kt:79-111`.
- **`runBlocking` sem timeout no interceptor + sem `callTimeout`** — `SparkAuthInterceptor.kt:31`,
  `SparkBackendClient.kt:42-49`; token travado → `Mutex` do sync preso até o processo morrer.
  E 401 nunca tenta `forceRefresh` (`FirebaseAuthGateway.kt:206` existe, `:31` sempre `false`).
- **Histórico completo observado por ~12 collectors independentes** — `WorkoutDao.kt:306-309`
  (`@Transaction` + `@Relation` de tudo), sem `distinctUntilChanged`; cada série concluída recarrega
  o histórico N vezes.
- **DataStore sem `distinctUntilChanged`** — `SettingsManager.kt:147-171`; `setRestTimerState` faz
  `showGifsFlow` emitir → `resolveAll` do catálogo inteiro a cada descanso.
- **Índices ausentes** — `Entities.kt:125-128, 148-152, 189-198`: `workout_sessions(status, finishedAt)`,
  `exercise_sessions(actualExerciseId)`, `(plannedExerciseId)`, `check_ins(checkInTime)`.
- **~2,4 MB de JSON parseados em todo cold start antes de checar versão** — `MainApplication.kt:766-770`,
  `ManifestImporter.kt:58→237`, `PremiumManifestImporter.kt:34-51` (via `org.json`).
- **`USE_EXACT_ALARM` + `SCHEDULE_EXACT_ALARM`** — `AndroidManifest.xml:10-11`; a primeira é restrita
  pelo Play a despertadores/calendários (risco de rejeição). `FOREGROUND_SERVICE` declarada sem nenhum FGS.
- **Token de debug do App Check gravado no arquivo errado** (debug only) — `src/debug/.../SparkAppCheck.kt:71-74`.
- **Falhas de startup engolidas com `printStackTrace`** — `MainApplication.kt:727,733,761,783`
  (inclui `restoreRepository.recover()`).
- **Três stacks de JSON** (kotlinx, Moshi, org.json) + `moshi-kotlin-codegen` no KSP com zero `@JsonClass`.
- **Efeitos colaterais dentro do `combine` do `ExecutionViewModel`** (`:180-244`) e pipeline de estado
  quente para sempre com `resolveAll` do catálogo (`:260-289`, `WorkoutEngine.kt:43-50`).
- **`finishSession` carrega todo o histórico** para achar a própria sessão — `WorkoutEngine.kt:605-606`.
- **Ordem dos sets vem de `@Relation` sem `ORDER BY`** — `WorkoutDao.kt:614-621`; funciona por rowid.
- **Regras por substring de nome em Composables** — `ExecutionScreen.kt:874-882, 1987-1989`
  ("prancha", "plank", "flexão"...) quando `executionMode`/`isBodyweight` já existem.

### Backend
- **`POST /v1/social/workout-shares` sem parser estrutural** — `workout-share.controller.ts:31`; corpo
  `null`/tipos errados → 500; chaves desconhecidas do snapshot persistidas (mass-assignment em 64 KB).
- **Convite de Squad: evento fora da transação** — `social-group.service.ts:275`; único `enqueue*`
  sem `client` (`notification.service.ts:220`).
- **Push do sync: 50 transações sequenciais × ~8 round-trips** — `sync.service.ts:95`; ~400 RTT
  contra Neon por lote. Fix: uma transação por push com SAVEPOINT por mutação.
- **Backup: até 5.000 INSERTs sequenciais** — `backup.repository.ts:196`. Fix: `UNNEST`.
- **Tetos check-then-act sem lock** — `social-group.service.ts:93, 245, 371`, `challenge.repository.ts:483`;
  20 aceites simultâneos ultrapassam o máximo de membros.
- **Bloquear sem perfil social → 500 (FK)** — `block.service.ts:23` não chama `requireActiveProfile`;
  corpo não validado.
- **Varreduras de expiração como escrita em toda leitura** — `social-group.service.ts:293`,
  `workout-share.repository.ts:230,283`.
- **Pull sem teto em bytes** — `sync.limits.ts:30,39` (200 × 256 KiB).
- **Sem índice em `social_notification_events(entity_id)`** — `notification.repository.ts:341`.
- **`statement_timeout` provavelmente ignorado atrás do pooler** — `postgres.service.ts:100` envia como
  parâmetro de startup. Verificar com `SHOW statement_timeout` na URL pooled.
- **Cold start Neon × `DATABASE_CONNECTION_TIMEOUT_MS=5000` sem retry** — `env.schema.ts:78`.
- **Jobs de backup/audit recebem digest novo antes do gate de validação; rollback não os cobre** —
  `ops/gcp/deploy-cloud-run.sh:152-185`, `rollback-cloud-run.sh`.
- **`restore.sh --install` falha aberto** quando não consegue consultar o Compose — `ops/restore.sh:159`, `ops/lib.sh:157-164`.
- **`ENV SOCIAL_MEDIA_ROOT=/media` na imagem anula o portão de produção** — `Dockerfile:33-35`.
- **Job de backup carrega o dump duas vezes em memória** com teto padrão (768 MiB) acima da memória
  do Job (1 GiB, tmpfs conta) — `db-backup.runner.ts:136-142`.
- **Deploy não exige CI verde** — `deploy-cloud-run.sh:84-88`, `deploy.sh:107-114`.

---

## 4. Baixos (lista curta)

Android: `itemsIndexed`/`LazyColumn` sem `key` (11 lugares, `contentType` nunca usado); sem
`CoroutineExceptionHandler` nos `viewModelScope.launch` do `ExecutionViewModel`; `WorkoutEngine.coroutineScope`
sem `SupervisorJob`; `insertExercise` com `REPLACE` sobre tabela com `UNIQUE(syncId)` e 8 filhos `CASCADE`;
restore apaga `customPhotoUri` locais (`RestoreTransaction.kt:134, 382-391`); seed "ABCDE Hipertrofia"
com `syncId` sobe como dado pessoal e duplica a cada aparelho novo (`AppDatabase.kt:805-825`);
`SocialMediaCache` remove o `Mutex` após liberar (`:95-118`); tempo relativo congelado no feed;
`catch (e: Exception) {}` em 5 cards premium; `Locale("pt","BR")` fixo em ~15 arquivos; ~716 strings
hardcoded vs 116 em `strings.xml`; `HttpLoggingInterceptor` ativo em release; `FakeFcmTokenProvider`
em `main`; `isReturnDefaultValues = true`.

Backend: guard consulta tombstone antes do rate-limit (`bearer-auth.guard.ts:146` vs `:168`);
`stream.pipe(response)` sem handler de erro (`social-media.controller.ts:126`, provider local →
`process.exit`); cursor malformado → 500 (`friendship.repository.ts:502`, `challenge.repository.ts:370,427`);
pull expõe sequência global (`sync.service.ts:126`); aceite concorrente de squad → 500 (23505 não tratado);
quota de bytes de mídia check-then-write; oráculo 404×403 em `report.service.ts:187`; dispatcher sem
`FOR UPDATE SKIP LOCKED` (seguro só por `max-instances=1`); logs pino sem `severity` (Cloud Logging
vê tudo como DEFAULT); `headersTimeout > requestTimeout` (`main.ts:105-106`); `spark-maintenance`
fecha pool em paralelo com o dreno HTTP (`maintenance-main.ts:113`); Postgres 17 no CI vs Neon 18;
`postgresql-client-18` sem versão pinada no Dockerfile; 4 `docker build` idênticos por push sem cache;
`backend.yml` sem `concurrency`/`timeout-minutes`.

---

## 5. Código morto (zero referências em `app/src/main`)

`feature/evolution/components/PerformanceCard.kt` (164 linhas), `.../performance/VolumeCard.kt` (156),
`.../body/BodyMetricSummaryCard.kt` (132), `domain/exercise/import/ExerciseCanonicalMapper.kt` (132),
`domain/provider/ExerciseDbProvider.kt` (81, só testado), `gamification/components/LevelBadge.kt` (45),
`domain/provider/YoutubeProvider.kt`, `.../body/BodyMeasurementComparisonCard.kt`,
`domain/evolution/model/BodyMetric.kt`, `components/workout/execution/ExerciseTargetCard.kt`,
`FakeFcmTokenProvider`. Assets nunca referenciados (~540 KB): `exercises-premium.v2.json`,
`exercise-content-manifest.v1.json`, `premium-library-audit.json`. 53 recursos não usados (lint).
`ARCHITECTURE.md §4` cita motores legados que já não existem.

**Não é morto** (poderia parecer): `AiCoachResponseValidator` (dupla validação intencional; mas os
limites espelham `ai-coach.limits.ts` sem contrato compartilhado).

---

## 6. Dependências

Android (`libs.versions.toml`): camada de build atual (AGP 9.1.1, Kotlin 2.2.10, KSP 2.3.5), camada de
libs parada em 2024: `composeBom 2024.09.00` → 2026.09.00; `lifecycle 2.8.7` → 2.11.0; `navigation 2.8.9` → 2.10.1;
`activityCompose 1.9.3` → 1.13.0; `coreKtx 1.13.1` → 1.19.0; `room 2.7.0` → 2.8.5; `okhttp 4.10.0` (2022) → 4.12+/5.5;
`workRuntime 2.9.1` → 2.11.2; `datastore 1.1.7` → 1.2.1; `compileSdk 35` → 36. `lifecycle-runtime-compose`
já é dependência mas `collectAsStateWithLifecycle` tem **0 usos** contra 66 `collectAsState()`.

Backend: `firebase-admin 14.3.0 → 14.4.0` resolve 6 vulnerabilidades moderate (`uuid` GHSA-w5hq-g745-h8pq,
CVSS 7.5, alcançável via `gaxios`/GCS — o gate `--audit-level=high` não pega porque o npm rotula moderate).
Majors disponíveis sem urgência: NestJS 12, pino 10, eslint 10, TypeScript 7. `@types/pg` e `@eslint/js`
com caret enquanto tudo é pinado.

---

## 7. Lacunas de teste mais relevantes

- **Nenhum teste instancia `WorkoutEngine`**; `WorkoutExecutionFlowQATest` e `ExecutionIntelligenceTest`
  reimplementam a lógica inline em vez de chamar o código de produção.
- Sync: mutação local durante push em voo; `SQLiteException` no applier; escopo sobrevive a exceção.
- Restore: "commit do Room feito, `ROOM_APPLIED` não gravado". Backup: recusa definitiva 413/409.
- Migrações 1→28 sem teste; `MIGRATION_1_2`…`5_6` vazias (`AppDatabase.kt:616-636`).
- Backend: bloqueio preservando notificações de terceiros; atomicidade convite-de-squad + evento;
  corpos malformados em `workout-shares`/`blocks`; concorrência em tetos; cursor não numérico.
- `androidTest` praticamente vazio: nenhum teste Room em SQLite real, nenhum teste Compose de execução.

---

## 8. Ordem sugerida de ataque

1. Desugaring (1.1) + `lintDebug` no CI + `targetSdk 36`. Um commit, destrava Android 7 e o Play.
2. Os 3 bugs de execução visíveis ao usuário: PR permanente (1.2), sheet morto (1.3), alarme no skip.
3. Fila de conquistas/XP com `key()` (2 linhas cada).
4. Outbox em voo (1.4) + `SupervisorJob` no escopo do sync + `externalId` no `writeProgram`.
5. Bloqueio cancelando notificações de terceiros (backend, 1 query).
6. Regras de backup do Android (nomes de arquivo) + `device_id` fora do backup.
7. Performance: `collectAsStateWithLifecycle`, `distinctUntilChanged` no DataStore, fonte única do
   histórico, índices Room, parse de manifesto só quando versão mudar, VMs por rota.
8. R8 + ícones + limpeza de código morto e das 3 stacks de JSON.

---

## 9. Aplicação — 2026-09-12 (mesmo dia)

Tudo abaixo foi aplicado em `main`, validado com build e testes reais, e está no commit que
acompanha esta seção. Marcas: **✔ aplicado**, **◐ aplicado com desvio** (o que e por quê),
**✖ não aplicado** (com o motivo).

### Estado mecânico depois da aplicação

| Verificação | Antes | Depois |
|---|---|---|
| Backend `tsc` + `eslint` + `prettier` | OK | OK |
| Backend `jest` (PostgreSQL 18.6) | 1463 testes | **1474 testes, 0 falhas** |
| Android `testDebugUnitTest` | 1318 testes | **1372 testes, 0 falhas** |
| Android `lintDebug` | 217 erros | **0 erros** (114 warnings, sem `NewApi`) |
| Android `assembleRelease` | sem R8, 18,5 MB | **R8 + shrink, 5,1 MB** |
| `shellcheck ops/` + testes de `ops/tests/` | OK | OK |

### Críticos
- ✔ 1.1 desugaring (`desugar_jdk_libs 2.1.5`) + `compileSdk`/`targetSdk` 36 + `lintDebug` no CI.
- ✔ 1.2 PR só em `finishSession`; o bloco de `completeSet` saiu.
- ✔ 1.3 `activeSheet = WorkoutSheet.Alternatives`.
- ✔ 1.4 no `APPLIED`, `requeueIfChangedInFlight` recalcula o hash do agregado e reenfileira um `UPSERT` novo; sem estado `IN_FLIGHT`, como a decisão original pede.

### Altos
- ✔ alarme no skip (`FocusedRestView(targetTime: Long?)` + só alerta quando cruzou o zero observando).
- ✔ pager por `settledPage`.
- ✔ `startSession` transacional + `Mutex` de ciclo de vida (também em `finishSession`/`cancelSession`).
- ✔ regra única de descanso: `WorkoutEngine.resolveRestRecommendation`, exposta em `ExecutionState`; os `?: 90` saíram.
- ✔ cronômetro por tempo em `rememberSaveable`.
- ✔ fila de conquistas/missões e XP com `key()`.
- ✔ flows por `remember(id)`; `SummaryViewModel` consulta por id.
- ✔ `SettingsViewModel`; nenhum engine construído em composição; motor de mídia único (`MainApplication.exerciseMediaEngine`).
- ◐ histórico: volume/séries excluem aquecimento em todo lugar; **"parcial" continua contando aquecimento** — é a regra vigente e mudar é decisão de produto.
- ✔ `targetSdk 36`.
- ◐ regras de backup: viraram **lista de inclusão** (`workout_database` + `-wal`/`-journal` + `settings`), porque os nomes dos arquivos do Firebase dependem do `applicationId` e não podem ser escritos; a chave RapidAPI continua em `settings` (decisão de produto pendente).
- ✔ `device_id` em `datastore/sync_device` (fora do backup por omissão), com adoção do valor legado.
- ◐ ViewModels na raiz do `MainScreen`: 18 → 5. Ficam `Friends`, `Challenge`, `SocialProfile` (compartilhadas), `BodyEvolution` (formulário atravessa rotas) e `Execution` (treino em andamento sobrevive a sair da tela).
- ✔ R8 + `shrinkResources`, regras para Moshi por reflexão, enums persistidos e `keep.xml` para `default_web_client_id`. `moshi-kotlin-codegen` removido do KSP. **Não validado em aparelho.**
- ✔ `SupervisorJob` + handler no escopo da aplicação (um só, `applicationScope`), `SQLiteException` capturada no applier, `writeProgram` resolve por `externalId`.
- ✔ bloqueio cancela só notificações do par (CTE com `RETURNING`).
- ✔ migração em endpoint pooled é recusada em `apply` (API e Job).

### Médios
- ✔ ETA no `TodayState`; `encodeRouteArg`; `pushOnce()` com `launchSingleTop` nos 47 pontos; `weekStartFlow`; chave API com estado local; `ROOM_APPLIED` dentro do commit; backup recusado vira `ABANDONED`; `setCurrentProgram` registra os dois programas e `addProgram` define o primeiro como atual; importação de share em uma transação; `withTimeout` no interceptor + `callTimeout` + retry único em 401 com `forceRefresh`; `distinctUntilChanged` em todas as preferências; índices (5) + migração 36→37; atalho de versão dos manifestos (`CatalogAssetVersion`); `printStackTrace` → `Log.e` com tag; `combine` puro + pipeline frio no `ExecutionViewModel`; `finishSession` por id; `sortedSets`; `isBodyweight`/`executionMode` no domínio.
- ◐ histórico observado por N collectors: projeções de timestamps + `distinctUntilChanged` nos repositórios; o `shareIn` único ficou de fora (exigiria escopo no construtor dos repositórios).
- ◐ `USE_EXACT_ALARM` saiu e `FOREGROUND_SERVICE` saiu; **`SCHEDULE_EXACT_ALARM` ficou**. A auditoria sugeria `setAlarmClock` como saída sem permissão — errado: ele exige a mesma permissão desde o Android 12 (lint `MissingPermission`). O que entrou foi o atalho em Configurações para o usuário conceder o alarme exato; até lá o alarme é inexato.
- ✔ token de debug do App Check no arquivo com `persistenceKey`.
- ◐ três stacks de JSON: só o codegen morto saiu; migrar Moshi/Retrofit para kotlinx é tarefa própria.
- ✔ backend: parser de `workout-shares`; evento de convite na transação; push em **uma** transação com `SAVEPOINT` por mutação; `UNNEST` no backup; locks de aviso por grupo/desafio antes de contar; bloqueio sem perfil → 404; pull com teto de 1 MiB; índice `(entity_id, status)` (migration 0003); rate-limit antes do tombstone; `pipeline()` no download; cursor inválido → 400; cursor por conta; 23505 no aceite → idempotente; quota de mídia dentro da transação; 403 indistinguível em report/share; `statement_timeout` via `SET LOCAL`; timeout de conexão 15 s + retry único; `SHUTDOWN_TIMEOUT_MS` 8 s; `DATABASE_URL` validada em produção; Jobs de backup/audit atualizados **depois** da troca de tráfego e revertidos no rollback; `restore.sh --install` aborta em `unknown`; `ENV SOCIAL_MEDIA_ROOT` fora da imagem; teto do dump 256 MiB + hash em stream; portão de CI verde no deploy (`ops/lib.deploy-gate.sh`, override `SPARK_DEPLOY_ALLOW_UNVERIFIED=1`); `headersTimeout` corrigido; `maintenance-main` fecha em ordem; `claimDue` devolve a janela em falha; Postgres 18.6 no CI e no compose; `postgresql-client-18` pinado; tags pinadas; `verify-backup` em destino limpo; shutdown determinístico no CI; imagem construída **uma** vez por push; `concurrency`/`timeout-minutes`; rejeição não tratada reprova o Jest.
- ✖ squads: varredura de expiração continua materializando `EXPIRED` **no escopo da operação** (não mais na tabela inteira) — a materialização é decisão testada da T17.13.1 por causa do índice parcial.
- ✖ `FOR UPDATE SKIP LOCKED` no dispatcher: em autocommit não protege nada; a correção real é um *lease* por evento, tarefa própria. Continua seguro por `max-instances=1`.
- ✖ "nunca rodou" no caminho travado da manutenção: contradiz teste explícito da T18.3 e a decisão tem mérito (após restore, `successAt` volta nulo).
- ✖ severidade do pino: ficou de fora por fronteira de agente; segue como pendência.

### Baixos
- ✔ keys em listas; `CoroutineExceptionHandler` no `ExecutionViewModel`; `SupervisorJob` no motor; `insertExercise` sem `REPLACE`; `customPhotoUri` sobrevive ao restore; seed com `externalId = seed:abcde-hipertrofia`; `Mutex` do `SocialMediaCache` fica no mapa; tempo relativo com tique (`ui/components/RelativeTime.kt`); `catch {}` dos cards logam; `Locale.ROOT` nas formatações; `HttpLoggingInterceptor` só em debug; `FakeFcmTokenProvider` em `test/`; `isReturnDefaultValues` desligado (um teste precisou de Robolectric); `!!` eliminados nos arquivos tocados; `Toast` → snackbar.
- ✖ `Locale("pt","BR")` fixo e ~716 strings hardcoded: internacionalização é tarefa própria.

### Código morto
- ✔ removidos: `PerformanceCard`, `VolumeCard`, `BodyMetricSummaryCard`, `BodyMeasurementComparisonCard`, `LevelBadge`, `ExerciseCanonicalMapper`, `ExerciseDbProvider` (+ seu teste), `YoutubeProvider`, `BodyMetric`, `ExerciseTargetCard`, 3 assets (~540 KB). `ARCHITECTURE.md §4` corrigido.
- ✖ 53 recursos não usados (lint `UnusedResources`): `shrinkResources` já os tira do APK; a limpeza da árvore fica para depois.

### Dependências
- ✔ Android (mesma geração): Compose BOM 2024.12.01, Room 2.7.2, OkHttp 4.12.0, WorkManager 2.10.1, core-ktx 1.15.0, Firebase BOM 34.19.0.
- ✖ salto de geração (Compose 1.8+, lifecycle 2.9+, navigation 2.9+, Coil 3): muda comportamento de `LazyColumn`/`Pager` e só se valida em aparelho.
- ✔ backend: `firebase-admin 14.4.0` (resolve 4 das 6; as 2 de `uuid` via `gaxios` só saem com `@google-cloud/storage` 9), `@google/genai`, `jest 30.5.1`, `ts-jest`, `supertest`, `@types/node`, carets removidos. `prettier` ficou em 3.8.2 (3.9 reformataria 7 arquivos alheios).

### Testes novos (82)
Android (54): `WorkoutEngineExecutionTest` (primeira suíte que instancia o motor), `ExecutionViewModelFocusTest`, `CatalogAssetVersionTest`, `ResolvedExerciseClassificationTest`, `ScreenRouteEncodingTest`, `SyncApplierResilienceTest`, `WorkoutRepositoryProgramTest`, mais casos em `SyncPushAckTest`, `RestoreRecoveryTest`, `RestoreTransactionTest`, `BackupAttemptDurabilityTest`, `WorkoutShareImporterTest`, `SparkAuthInterceptorTest`.
Backend (11): `social-audit-hardening.spec.ts` (9) e ajustes derivados em `dr-backup`/`dr-restore-drill` (schemaVersion lido das migrations).

### O que a aplicação descobriu que a auditoria não viu
- `updateCheckInDetails` era um `UPDATE` sobre `id = 0` quando a sessão não tinha check-in: gravava nada e registrava mutação de sync. Agora insere.
- O catálogo em `assets` **não declara** `isBodyweight` em nenhuma entrada; remover a heurística de nome (como a auditoria sugeria) faria 21 exercícios pedirem carga. A heurística ficou, no domínio, com uma cópia só.
- `setAlarmClock` exige `SCHEDULE_EXACT_ALARM` desde o Android 12; a auditoria o apresentou como saída sem permissão.
- Um exercício **sem séries** sempre contou como pendente; a versão por `COUNT` precisou preservar isso (`countPendingExerciseSessions`).
- Jest 30.5 aplica `moduleNameMapper` dentro de `require.resolve`; o shim de `better-sqlite3` passou a carregar a si mesmo e três suítes de migração SQLite quebraram. Corrigido com caminho absoluto.
- Em `ConsistencyRepositoryImpl`, métodos suspensos usam `COALESCE(finishedAt, startedAt)` e os flows usam `startedAt`. Divergência **preservada** (mudar altera números que o usuário vê); registrada como pendência.

### Pendências que ficam registradas
1. Validar o APK de release (R8) em aparelho real: login com Google, exportação de dados, sync.
2. Severidade do pino no Cloud Logging (`logger.ts`).
3. Lease por evento no dispatcher de notificações antes de qualquer `max-instances > 1`.
4. Migrar Moshi/Retrofit para kotlinx.serialization e aposentar duas stacks de JSON.
5. Salto de geração do Compose/lifecycle/navigation, com validação em aparelho.
6. Internacionalização (strings e `Locale`).
7. `COALESCE(finishedAt, startedAt)` vs `startedAt` em `ConsistencyRepositoryImpl`.
8. Rollback do Cloud Run não reverte o `spark-maintenance`.
