# Treino em dupla à distância — `DUO_REMOTE`

- **Tarefa:** T19.5.
- **Status (verificado em 2026-09-16):** implementado no código, com testes dos dois lados.
  Validação em dois aparelhos reais, Cloud Run real e reconexão real: **NOT VERIFIED** neste
  ambiente (sem aparelho, sem `gcloud`). Room `version = 40` (`MIGRATION_39_40`); PostgreSQL
  `0007_multiplayer_rooms.sql`.
- **O que este documento é:** o contrato do modo remoto — o que o servidor coordena, o que o
  aparelho decide, e o que **nunca** atravessa a rede. Ele complementa
  [`duo-local-execution.md`](duo-local-execution.md) (T19.4); os dois modos de dupla convivem, e
  nenhum deles muda o solo.

## 1. Os três modos

```text
SOLO         uma pessoa,   um aparelho,   uma sessão
DUO_LOCAL    duas pessoas, um aparelho,   uma sessão (do dono; o convidado é local, sem conta)   [T19.4]
DUO_REMOTE   duas pessoas, dois aparelhos, DUAS sessões (uma em cada aparelho, cada uma do seu dono) [T19.5]
```

`DUO_REMOTE` **não** é uma segunda engine nem uma segunda máquina de estados. A execução de uma
sessão remota neste aparelho é, linha por linha, a execução solo: mesmas `set_logs`, mesmo
descanso, mesmo PR, mesmo XP, mesmo histórico, mesmo sync, mesmo backup. Nenhuma tabela de
participante é lida. O que se soma é um **vínculo** com uma sala no Spark Backend e um
**observador** que publica o que já aconteceu no Room e mostra o que o outro aparelho publicou.

## 2. O princípio

```text
execução local (Room)  →  evento canônico  →  sala (servidor)  →  outro aparelho  →  tela dele
```

e nunca:

```text
servidor  →  estado do Workout  →  Room apenas espelha
```

A T19.5 está correta quando o multiplayer pode desaparecer por uma falha de rede e o treino local
dos dois **continua íntegro**. Isso é testado: `MultiplayerSessionCoordinatorTest` corta a rede
no meio, conclui uma série, e prova que a série está gravada, a sessão continua `IN_PROGRESS`, o
evento fica pendente e a volta da rede o publica uma vez só.

## 3. Autoridades

| Recurso | Fonte de verdade |
| --- | --- |
| `WorkoutSession`, séries, peso, reps, descanso | Room, deste aparelho, via `WorkoutEngine` — como em solo |
| PR, XP, conquistas, histórico | domínio local, deste aparelho |
| Sala, membership, status, presença | Spark Backend (`multiplayer_rooms`, `multiplayer_room_members`) |
| Ordem dos eventos | Spark Backend (`multiplayer_room_events.sequence`, por sala, sob `SELECT … FOR UPDATE`) |
| O que o peer já fez | **derivado** no aparelho, do log da sala (`MultiplayerEventLog`), só em memória |
| Identidade do membro | `socialId` (T17.0); Firebase UID nunca sai em DTO |
| Autorização do convite | amizade ativa (T17.1); bloqueio veta (T17.6) |
| Auth | Firebase Auth, token por requisição (T16.1) |

```text
MultiplayerRoom   ≠   WorkoutSession
backend           ≠   autoridade do Workout
```

## 4. Transporte: HTTP long-polling, sem WebSocket e sem FCM

Não existia infraestrutura realtime antes da T19.5 (nenhum WebSocket, SSE, rooms ou presence —
o "Nostr-inspired" citado em `ARCHITECTURE.md §9` nunca teve código). O que existe é HTTP
autenticado sobre `SparkBackendClient` (um cliente, um interceptor, um lugar montando
`Authorization: Bearer`), NestJS em Cloud Run e PostgreSQL.

Escolha: `GET /v1/multiplayer/rooms/:id/events?after=<seq>&wait=<ms>`. O servidor segura a
resposta até haver evento novo ou o prazo vencer (teto 20 s; o aparelho pede 15 s com leitura de
40 s). Por quê:

- o PostgreSQL já é o ponto único de coordenação; um long-poll funciona igual com uma ou dez
  instâncias do Cloud Run, sem afinidade, sem fan-out entre instâncias, sem cleanup de conexão;
- são dois participantes e uma série dura dezenas de segundos — latência de ~1 s é irrelevante;
- **presença é o próprio poll**: `last_seen_at` do membro é o último `GET`; "conectado" é
  derivado (`now − last_seen_at < 45 s`), nunca gravado. Não há heartbeat separado;
- entre uma checagem e outra o servidor não segura conexão do pool (uma consulta curta por
  segundo).

FCM: **não usado** (N/A). Um push seria só sinal; não há sinal que o poll já não dê.

## 5. A sala

`POST /v1/multiplayer/rooms` `{ clientRequestId, inviteeSocialId, workout }` — o host convida
**um** amigo com o treino de hoje. `workout` é o mesmo snapshot portável do compartilhamento
(`WorkoutTemplateShareSnapshotV1`, T17.7): só catálogo canônico, sem carga, sem nota, sem máquina.

- `status`: `WAITING` (convidado ainda não entrou) → `ACTIVE` → `CLOSED` | `EXPIRED`.
- **Dois membros, por schema:** `UNIQUE (room_id, role)` com `role IN ('HOST','GUEST')`. Não há
  terceiro papel, logo não há terceiro membro. TRIO é outra migration.
- **Uma sala aberta por host:** criar outra encerra a anterior (`SUPERSEDED`).
- **Idempotência:** `UNIQUE (host_uid, client_request_id)`; replay com o mesmo convidado e o mesmo
  treino devolve a mesma sala, divergência é `409`.
- **Expiração preguiçosa:** `WAITING` expira em 30 min, `ACTIVE` em 6 h desde a criação. Sem cron
  — a transição acontece no primeiro acesso depois do prazo e gera um `ROOM_CLOSED`.
- **Quem não é membro recebe `404`** em toda rota — nunca "esta sala é de outra pessoa".
- Membership: `INVITED → ACTIVE → FINISHED | LEFT`. `join` de quem já é `ACTIVE` é rejoin
  (mesma linha, nenhum evento); `LEFT` é definitivo (`409 MULTIPLAYER_MEMBER_LEFT`) — sair é
  decisão, e reconexão automática não a desfaz.
- Fecha sozinha quando: o convidado recusa (`INVITE_DECLINED`), o host sai antes de alguém entrar
  (`HOST_LEFT_WAITING`), os dois saíram (`ALL_LEFT`), o host encerra (`HOST_CLOSED`), o par se
  bloqueia (`UNAVAILABLE`, na mesma transação do bloqueio) ou uma conta é excluída
  (`UNAVAILABLE`, no purge).

## 6. Eventos

`POST /v1/multiplayer/rooms/:id/events` `{ events: [{ eventId, type, payload }] }` (1–50 por lote).

| Tipo | Quem produz | Payload |
| --- | --- | --- |
| `WORKOUT_STARTED` | aparelho | `{ exerciseCount }` |
| `SET_COMPLETED` | aparelho | `{ canonicalExerciseId?, exercisePosition, setNumber, setCount, completedAt }` |
| `MEMBER_FINISHED` | aparelho | `{}` — e marca a membership `FINISHED` |
| `MEMBER_JOINED` | servidor, no join | `{}` |
| `MEMBER_LEFT` | servidor, no leave | `{}` |
| `ROOM_CLOSED` | servidor, no fechamento/expiração | `{ reason }` |

- **Identidade estável:** `eventId` é do cliente, `UNIQUE (room_id, event_id)`. O mesmo id
  reenviado recebe a sequence que já tinha — nunca uma segunda linha.
- **Ordem determinística:** `sequence` por sala, atribuída pelo servidor sob lock da linha da sala;
  `PRIMARY KEY (room_id, sequence)`. O relógio do aparelho (`completedAt`) é informativo.
- **Privacidade por allowlist:** o validador recusa **por nome** `weight`, `reps`, `repetitions`,
  `rpe`, `rir`, `notes`, `pr`, `xp`, `uid`, `syncId`, `sessionId`… e qualquer chave fora da lista
  do tipo. Há teste que tenta cada uma. Os DTOs do Android não têm campo para nenhuma delas.
- Sala `CLOSED`/`EXPIRED` recusa evento (`409`); `INVITED` não publica (`403`); `LEFT` não
  publica (`409`). `FINISHED` ainda pode reenviar (dedupe) e ler.
- Teto de 2 000 eventos por sala.

### 6.1 Eventos derivados do estado, não do toque

No aparelho, `LocalMultiplayerEvents.derive(roomId, sessionId, exercícios)` produz a lista
inteira — o início e cada série concluída — **a partir do Room**. O `eventId` é
`sha256(roomId | fato)`: a série 1 do exercício X tem sempre o mesmo id neste aparelho. Assim:

- uma morte de processo entre gravar a série e publicar o evento se resolve na reabertura;
- depois de qualquer reconexão o coordenador **reenvia tudo** que a sessão diz sobre si, e o
  servidor deduplica — o custo é uma requisição, o ganho é nunca depender de um outbox em memória;
- um "desfazer série" local não gera evento (o fato continua aplicado no peer como concluído; é
  visão de progresso, não espelho).

### 6.2 Ordering e dedupe no aparelho — `MultiplayerEventLog`

Puro e síncrono. Um evento só é aplicado quando é o próximo (`sequence == applied + 1`); um que
chegue à frente fica no buffer e a lacuna pede **resync** (`after = applied`) — nunca "aplica
assim mesmo"; um que chegue atrás é descartado; `eventId` já visto avança a sequence sem mudar o
estado. O progresso do peer sai só dos eventos cujo `actorSocialId` não é o meu: os meus voltam
pelo mesmo log e são ignorados — o meu estado é o Room.

## 7. O aparelho — `MultiplayerSessionCoordinator`

Vive no processo (`applicationScope`), começa no `onCreate` e observa `(uid atual, vínculo da
sessão ativa)`:

```text
sessão ativa DUO_REMOTE da conta atual
  → resync (after = 0)  → publica o que o Room já diz  → long-poll  → aplica em ordem
  → rede caiu: RECONNECTING, backoff 2/4/8/16/30 s, resync integral ao voltar
  → sala fechou/expirou/saí: ENDED (o treino local não percebe)
```

- **Escopo por conta:** o laço é relançado por `collectLatest` a cada mudança de `(uid, vínculo)`.
  Trocar de conta cancela o laço anterior **inclusive o `GET` em voo** (o long-poll usa
  `SparkBackendClient.getJsonCancellable`, `enqueue` + `call.cancel()` na cancelação). A conta nova
  encontra um vínculo de outra conta e mostra `OTHER_ACCOUNT` sem fazer chamada nenhuma. Nenhum
  evento, cursor, sala ou buffer da conta anterior chega à nova: tudo isso vive dentro do laço
  cancelado.
- **Nunca escreve no Room por causa do servidor.** Há teste estrutural
  (`MultiplayerBoundaryInspectionTest`): os pacotes de multiplayer não referenciam `updateSetLog`,
  `completeSet`, `startRestTimer`, `finishSession`, PR, XP, Outbox, sync ou backup, e não registram
  log.
- **Fim da sessão:** concluir publica `MEMBER_FINISHED`; cancelar sai da sala. Se o processo
  morrer antes do aviso, `finishedNotifiedAt` nulo no vínculo faz o aviso sair na próxima passagem
  (abertura do app, troca de conta, fim de outra sessão).
- **Sair da sala** (`leaveRoom`) marca o vínculo como encerrado: sem reconexão automática, e o
  treino segue como `DUO_REMOTE` sem sala.

## 8. Persistência

**Room (v40):** `workout_session_multiplayer_links (sessionId PK → workout_sessions cascade,
roomId, accountUid, role, peerDisplayName, createdAt, finishedNotifiedAt)`, `UNIQUE (roomId,
accountUid)`. É o mínimo para recovery: qual sala, de qual conta. **Nada do estado remoto é
persistido** — membros, eventos, cursor e progresso do peer são relidos do servidor a cada
reconexão. `workout_sessions.executionMode` ganhou o valor `DUO_REMOTE` sem coluna nova. A tabela
não entra em sync, backup, restore (é apagada com as sessões), PR, XP nem histórico. O `accountUid`
aqui é armazenamento privado do aparelho, como o de `CloudDataBinding`; nunca sai em DTO.

**PostgreSQL (0007):** `multiplayer_rooms`, `multiplayer_room_members`, `multiplayer_room_events`.
FKs para `social_profiles(owner_uid)` com cascade; as três colunas de uid estão no inventário de
purge (`ACCOUNT_UID_COLUMNS`) e o purge fecha as salas em que a conta era membro antes de apagar o
rastro dela.

## 9. Fluxos

**Host:** Hoje → "Treinar em dupla" → "Treinar com um amigo à distância" → lobby → escolhe o amigo →
`POST rooms` (sala `WAITING`) → "Iniciar treino" (pode começar antes de o convidado entrar) →
`WorkoutEngine.startSession(templateId, DUO_REMOTE, multiplayer = vínculo)` → execução.

**Convidado:** lobby → "Convites recebidos" → "Entrar" → `POST join` (o servidor revalida
bloqueio, encerramento, expiração) → cópia local do treino a partir do snapshot da sala (um
`WorkoutTemplate` novo no programa atual, como uma oferta aceita — T17.7) → `startSession(cópia,
DUO_REMOTE, vínculo)` → execução. Um exercício fora do catálogo local recusa **antes** de qualquer
escrita. Reentrar (toque duplo, reabrir o convite, app reaberto) retoma a sessão existente pela
`UNIQUE (roomId, accountUid)`: uma sessão, uma cópia.

**Execução:** a tela de sempre, com o chip "À DISTÂNCIA" e o `RemotePeerBanner` — estado da
conexão (CONECTANDO / EM DUPLA / AGUARDANDO / RECONECTANDO / ENCERRADA), quem é o peer, o que ele
já fez no exercício em foco, "N série(s) aguardando rede" e "Sair da sala". Nenhuma fase, série ou
descanso lê o banner. Toda mensagem de perda de sala diz "seu treino continua normalmente".

**Finish é individual:** quem termina publica `MEMBER_FINISHED` e a sala continua para o outro.
Não há finish simultâneo, e o host não finaliza a sessão de ninguém.

## 10. Concorrência (T19.5 §10)

| Corrida | Quem vence |
| --- | --- |
| join vs fechamento | a sala fechada (`409 ROOM_CLOSED`) |
| join vs bloqueio | o bloqueio (`404`, indistinguível de inexistente; a sala já fechou no bloqueio) |
| reconexão vs saída explícita | a saída (`409 MEMBER_LEFT`; o vínculo local está marcado) |
| evento vs fechamento | o fechamento (`409`) |
| exclusão de conta vs sala | a exclusão (sala `CLOSED`, membership e eventos apagados; a conta não volta pelo token) |
| dois eventos, mesmo fato | um só (`eventId` determinístico + `UNIQUE`) |
| troca de conta vs long-poll em voo | a troca (o `Call` é cancelado; a resposta nunca é lida) |

## 11. O que não existe

TRIO, salas públicas, matchmaking, espectadores, chat, voz, vídeo, placar ao vivo, XP de
multiplayer, PR compartilhado, sessão compartilhada entre aparelhos, sync direto entre aparelhos,
WebRTC, WebSocket, push de multiplayer, lock-step (um esperar o outro para avançar — a
coordenação é de **visibilidade**, cada um segue no próprio ritmo).

## 12. Testes

- **Backend:** `test/multiplayer.spec.ts` (25 casos): auth, amizade/anti-enumeração, criação
  idempotente e `SUPERSEDED`, validação do treino, join/rejoin, terceiro é `404`, dois membros por
  schema, bloqueio, recusa do convite, sequence/ordem/paginação, dedupe, payload proibido, INVITED
  e LEFT, finish individual e `ALL_LEFT`, fechamento pelo host, expiração (relógio do servidor),
  presença, long-poll real, isolamento entre contas, exclusão de conta (convidado e host), sweep
  de uid/e-mail nas respostas.
- **Android:** `MultiplayerEventLogTest` (ordem, lacuna, dedupe, eventos próprios ignorados),
  `LocalMultiplayerEventsTest` (ids determinísticos, ordem de derivação, payload sem peso),
  `MultiplayerSessionCoordinatorTest` (conecta/publica/derivado do Room, peer visto e não
  aplicado, rede fora → treino intacto → volta com dedupe, troca de conta cancela e não chama,
  sala fechada, sair, concluir → `MEMBER_FINISHED`, cancelar → leave, aviso reenviado após morte,
  solo não toca o gateway), `MultiplayerWorkoutStarterTest` (host, cópia do convidado, exercício
  ausente, recusa do servidor, treino em andamento), `MultiplayerLobbyViewModelTest`,
  `RemotePeerBannerTest`, `AppDatabaseMigration39To40Test`, `MultiplayerBoundaryInspectionTest`.
