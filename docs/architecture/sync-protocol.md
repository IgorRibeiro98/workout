# Protocolo de sincronização do Spark

- **Tarefa:** T16.0 (documentação) — **implementado na T16.6**; conflitos e deletes em **T16.7**.
- **Status (verificado em 2026-09-07):**
  - **implementado na T16.3:** a **Outbox transacional** no Android (`sync_outbox`), `syncId`,
    `clientMutationId`, `deviceId` e os DTOs de agregado;
  - **implementado na T16.4:** o **backup completo** — `POST /v1/backups` sobe um snapshot
    autocontido e `GET /v1/backups/latest` devolve metadata. Não é sync;
  - **implementado na T16.5:** o **restore** — `GET /v1/backups`, `GET /v1/backups/{id}` e
    `GET /v1/backups/{id}/content` deixam o usuário **escolher** um snapshot e substituir o
    dataset local por ele. Um snapshot completo, por ação explícita, sem delta e sem merge.
    Também não é sync;
  - **implementado na T16.6:** o **sync incremental**. `POST /v1/sync/push` e
    `GET /v1/sync/pull`, estado remoto por agregado (`sync_entities`), `serverRevision` por
    entidade, ledger de idempotência (`sync_mutations`), change log append-only (`sync_changes`)
    com sequência global, cursor durável por conta no Android (`sync_cursor`), revision conhecida
    por agregado (`sync_entity_metadata`), conflitos preservados (`sync_conflicts`) e um trabalho
    único de `WorkManager` com restrição de rede;
  - **não implementado (T16.7):** resolução de conflito, tombstone, propagação de exclusão,
    prevenção de ressurreição e política avançada de consistência offline. Um push de `DELETE` é
    recusado explicitamente com `DELETE_NOT_SUPPORTED` e a intenção continua pendente no aparelho.

> **Backup/restore ≠ sync.** Os dois movem um snapshot **inteiro**, em uma direção, quando o
> usuário manda. O protocolo abaixo move **mudanças**, nos dois sentidos, sozinho — e é por isso
> que ele precisa de versão por entidade, sequência do servidor e política de conflito, que
> backup e restore não precisam ter.

Este documento existe para que as decisões difíceis do sync estejam tomadas antes de a primeira
linha de sync ser escrita.

---

## Por que não "quem tem o timestamp maior vence"

A saída óbvia — comparar `updatedAt` e deixar o mais recente vencer — falha por um motivo simples:
**relógios de dispositivos divergem**. Fuso trocado, relógio manual, bateria, NTP atrasado.

Com *last write wins* baseado em relógio do cliente, um aparelho com o relógio adiantado uma hora
sobrescreve silenciosamente tudo que os outros fizerem naquela hora. O usuário não recebe erro
nenhum: só perde dado.

O protocolo do Spark usa, em vez disso, **versão por entidade** e **sequência controlada pelo
servidor**. Timestamps continuam existindo como metadado informativo, nunca como árbitro.

---

## Peças do protocolo

| Peça | O que é | Quem gera |
| --- | --- | --- |
| `syncId` | identidade global da entidade (UUID) | dispositivo, offline |
| `clientMutationId` | identidade de **uma tentativa de mutação** (UUID) | dispositivo, por mutação |
| `revision` | versão da entidade no servidor, inteiro crescente | servidor |
| `baseRevision` | a `revision` sobre a qual o cliente construiu a mudança | dispositivo |
| `changeSeq` | sequência global e monotônica de mudanças da conta | servidor |
| `cursor` | posição do cliente na sequência do servidor | servidor |
| `deviceId` | qual instalação originou a mudança | dispositivo |
| `deletedAt` | marcação de tombstone | servidor |

---

## A Outbox no Android (T16.3 — implementado)

```text
UI
 ↓
ViewModel / UseCase
 ↓
Repository
 ↓
┌──────────────────────────────────────────────┐
│ Transação Room                               │
│                                              │
│ dado de domínio                              │
│ +                                            │
│ SyncOutboxEntry  [só se a nuvem estiver      │
│                   associada a uma conta]     │
└──────────────────────────────────────────────┘
 ↓
COMMIT — Room continua sendo a autoridade local
```

### Atomicidade

O padrão é *Transactional Outbox*, na **mesma base Room** — não em arquivo JSON paralelo, não em
`MutableList`, não em `StateFlow`. A fronteira é `SyncMutationCoordinator.mutate { }`:

```text
salva o treino → app morre → sem entrada de Outbox      ← impossível
cria a entrada → salvar o treino falha                  ← impossível
regra de domínio rejeita → entrada registrada mesmo assim ← impossível
```

Os três são cobertos por teste com Room de verdade: falha na Outbox reverte a escrita de domínio,
exceção no domínio reverte a entrada, e uma operação recusada por regra de negócio não registra
nada.

A UI não conhece a Outbox — há teste estrutural que falha se `presentation/` ou `ui/` citar
`SyncOutbox`, `SyncMutationCoordinator` ou `SyncEntityType`.

### Estrutura da entrada

| Campo | Papel |
| --- | --- |
| `id` | ordem de intenção, local. O push processa por `id` crescente |
| `clientMutationId` | identidade da alteração (UUID, `UNIQUE`) |
| `ownerUid` | a conta que poderá enviá-la. `NOT NULL` — nenhuma entrada tem dono ambíguo |
| `entityType` | o agregado (`WORKOUT_TEMPLATE`, `WORKOUT_SESSION`, ...) |
| `entitySyncId` | identidade global da entidade |
| `operation` | `UPSERT` ou `DELETE` |
| `status` | `PENDING` ou `BLOCKED` (T16.6) — ver abaixo |
| `createdAt` | epoch millis UTC, metadado de auditoria |
| `attemptCount` / `lastAttemptAt` | metadado mínimo de tentativa, zerado enquanto não houver transporte |

Não existe `SYNCED`: seria um estado mentindo sobre dado que nunca saiu do aparelho — uma entrada
confirmada é **removida**, porque a intenção foi cumprida.

E não existe `IN_FLIGHT`, mesmo depois da T16.6. Uma entrada despachada permanece `PENDING` até o
servidor confirmar, e é isso que torna uma resposta perdida recuperável: o reenvio carrega o mesmo
`clientMutationId` e volta como `ALREADY_APPLIED`. Um estado "em voo" durável só criaria uma linha
que ninguém sabe destravar depois de um crash.

A T16.6 acrescentou **um** estado: `BLOCKED`, para a entrada que o servidor recusou de um jeito que
reenviar não resolve — escrita stale, conflito de histórico imutável, payload inválido, operação
não suportada. Ela não é apagada (é a alteração do usuário), sai da fila de envio e passa a esperar
a T16.7. O lado remoto correspondente vive em `sync_conflicts`, e `blockedReason` guarda o
vocabulário técnico do motivo — nunca conteúdo.

### Referência, não snapshot

A entrada guarda **o que mudou**, não **o conteúdo**. O payload é montado a partir do Room no
momento do envio, por `SyncAggregateSnapshotBuilder`.

A alternativa — congelar o payload dentro da entrada — foi recusada por quatro motivos:

1. **segunda autoridade** — uma cópia congelada começa a divergir do Room no instante seguinte;
2. **estado errado no ar** — três edições seguidas enviariam versões intermediárias que o usuário
   já abandonou;
3. **idempotência** — reenviar passa a ser "reler o estado atual", sem reconciliar payloads velhos;
4. **volume** — uma sessão concluída inteira duplicada na fila a cada alteração multiplicaria o
   banco local.

O preço assumido: um `DELETE` não monta payload — e não precisa, porque uma exclusão carrega
apenas tipo e identidade.

### Coalescência

Três edições do mesmo treino antes de qualquer envio viram **uma** `UPSERT`:

```text
template ABC alterado
template ABC alterado   →   1 × UPSERT ABC
template ABC alterado
```

É seguro justamente porque o payload é referência: as três resolveriam para o mesmo conteúdo.

O que a coalescência **não** faz:

- não atravessa operações: um `DELETE` nunca absorve o `UPSERT` anterior, nem o contrário — a
  ordem de intenção é preservada por `id`;
- não toca em entrada fora de `PENDING`: reaproveitar uma entrada bloqueada por conflito quebraria
  a idempotência que o `clientMutationId` garante;
- não some com nada por idade. Limpeza depende de acknowledgment real do servidor.

**No envio (T16.6)** a mesma regra é aplicada de novo, por `SyncPushBuilder`: as `UPSERT` pendentes
do mesmo agregado viram um envio só, e a confirmação dele libera todas. É correto pelo mesmo motivo
— o payload é montado do Room na hora, então todas resolveriam para o mesmo conteúdo. O que é
enviado leva o `clientMutationId` da última; as outras nunca saíram do aparelho, então nenhuma
idempotência é quebrada. E um agregado com `DELETE` pendente **não envia nada**: mandar a `UPSERT`
anterior a ele ressuscitaria no servidor o que o usuário apagou aqui.

Uma operação que altera dezenas de linhas do mesmo agregado — reordenar exercícios, editar em lote
— produz **uma** mutação daquele agregado, não dezenas. E uma operação que não muda estado (fazer
check-in quando já existe um aberto) não produz nenhuma.

### Escopo de conta

Com a nuvem desligada — o padrão, e o comportamento ao final da T16.3 — **nenhuma entrada é
produzida**. Alterações locais continuam normais e não existe mutação remota pendente. Isso é
deliberado: uma fila criada antes da adoção não teria dono, e adotá-la na primeira conta que
entrasse seria dar a ela dados de outra pessoa. Ver
[`identity-contract.md`](./identity-contract.md#adoção-explícita--o-contrato-local_unowned-t163).

Mudanças anteriores à ativação da nuvem **não se perdem**: o primeiro backup é um snapshot completo
do estado atual, não a reprodução de uma fila histórica desde a instalação.

---

## Backup completo (T16.4) — o que existe hoje

Antes do push incremental veio o backup, e ele **não** consome a Outbox como fila de envio:

```text
Room transaction
 ├── vincula o dataset (adoção explícita)
 ├── captura o snapshot completo
 ├── lê coveredOutboxSequence = MAX(sync_outbox.id)
 └── cria a BackupAttempt (payload congelado)
COMMIT  →  POST /v1/backups  →  o servidor confirma
 ↓
Room transaction: entradas com id <= coveredOutboxSequence são liberadas
```

O corte é o ponto: uma alteração feita **durante** o upload recebe `id` maior e continua pendente,
porque o snapshot não a contém. Falha no upload não libera nada. Formato, identidades, hash,
idempotência e erros em [`contracts/backup/v1/README.md`](../../contracts/backup/v1/README.md).

### Baseline: como o sync incremental começa (T16.6)

A pergunta que a T16.4 e a T16.5 deixaram em aberto era "de qual posição do change log um aparelho
recém-adotado ou recém-restaurado deve começar a puxar?". A investigação da T16.6 confirmou que
**nenhuma das duas guardou essa informação**: `backup_snapshots` não tem sequência de sync, e a
`restore_attempts` também não.

A T16.6 **não inventou** uma. O cursor inicial de qualquer aparelho é **zero**, e o restore zera o
cursor, a revision conhecida e os conflitos dentro do próprio commit que substitui o dataset. O
motivo é que a alternativa seria pior:

- um dataset adotado ou restaurado **não carrega revision nenhuma**. Herdar um cursor "adiantado"
  faria o aparelho nunca aprender as revisions do que ele tem — e a primeira edição de cada
  agregado viraria um conflito falso;
- reler o log desde o início é **idempotente e barato**: conteúdo idêntico é reconhecido pelo hash
  canônico, o domínio não é reescrito, e a única coisa que muda é a metadata técnica passar a
  saber a revision de cada agregado.

Ou seja: "cursor = 0" aqui não é rebaixar a conta, é a forma de **aprender** o que o servidor sabe.
Um baseline explícito só passa a valer a pena junto com retenção do change log — e retenção é
T16.7/T16.8, com `CURSOR_EXPIRED` e rebaseline, nunca com um recomeço silencioso.

O que o backup **não** faz, e continua não fazendo: ele não vira mudança no change log. Um snapshot
completo enviado não é interpretado como milhares de mudanças novas — há teste sobre isso.

### Três agregados ainda fora do incremental

`EXERCISE_OVERRIDE`, `WEEKLY_GOAL` e `USER_PREFERENCES` entram no snapshot completo e **não** têm
mutação de Outbox. A T16.6 os deixou fora do incremental de propósito, e o servidor os recusa
explicitamente com `UNSUPPORTED` em vez de aceitar pela metade:

- a customização de exercício é escrita hoje direto pelo DAO, a partir de um ViewModel — dar-lhe
  mutação exigiria mover essa escrita para um repositório, que é refatoração de outra tarefa;
- a meta semanal é **derivada** de uma preferência do DataStore por `ConsistencyRepositoryImpl`, e
  não uma entidade que o usuário edita diretamente;
- as preferências moram no DataStore, que **não** participa da transação Room — e a Outbox
  transacional é o que garante que intenção e alteração vivam ou morram juntas.

Consequência aceita e registrada: uma alteração nesses três propaga por **backup completo**, não por
sync incremental. Está em `ARCHITECTURE.md` como pendência da T16.7.

## Push incremental (T16.6 — implementado)

```text
ação do usuário
      ↓
domínio → Room            ← a escrita local acontece primeiro, e é o que a UI observa
      ↓
Outbox (Room)             ← fila durável de mutações pendentes
      ↓
SyncPushBuilder           ← coalesce por agregado, monta o payload do Room, lê a baseRevision
      ↓
POST /v1/sync/push        ← lotes de até 50 mutações, corpo canônico
```

O fluxo proibido — e que nenhum caminho do app faz — é o inverso:

```text
editar treino → esperar HTTP → salvar Room        ← PROIBIDO
```

Corpo da requisição:

```json
{
  "deviceId": "…",
  "mutations": [
    {
      "clientMutationId": "…",
      "entityType": "WORKOUT_TEMPLATE",
      "entitySyncId": "…",
      "entitySchemaVersion": 1,
      "operation": "UPSERT",
      "baseRevision": 4,
      "payload": { }
    }
  ]
}
```

**Não existe `ownerUid` no corpo, e isso é o contrato.** O dono sai do Firebase ID Token verificado;
o envelope do servidor é estrito, então um campo de dono não é ignorado em silêncio — ele recusa a
requisição inteira. O `deviceId` é metadado de diagnóstico e não autoriza nada: conhecer o
`deviceId` de outro aparelho não dá acesso a nada.

`UPSERT` em vez de `CREATE`/`UPDATE`: o cliente não sabe — e não deveria precisar saber — se o
servidor já viu aquele `syncId`. Quem distingue criação de atualização é o servidor, pela
`revision`.

### O que o servidor decide, mutação a mutação

A ordem das perguntas é o protocolo:

| # | Pergunta | Resposta |
| --- | --- | --- |
| 1 | `clientMutationId` já registrado, mesmo alvo e mesmo hash? | `ALREADY_APPLIED` com a `revision`/`sequence` originais |
| 2 | `clientMutationId` já registrado, conteúdo ou alvo diferente? | `IDEMPOTENCY_CONFLICT` — não reaplica |
| 3 | `operation` é `DELETE`? | `UNSUPPORTED` (`DELETE_NOT_SUPPORTED`) — T16.7 |
| 4 | `entityType` fora do registry, `entitySchemaVersion` desconhecida? | `UNSUPPORTED` |
| 5 | payload fora do schema, identidade que não bate, item grande demais? | `INVALID` |
| 6 | histórico imutável já gravado com **outro** conteúdo? | `IMMUTABLE_HISTORY_CONFLICT` |
| 7 | conteúdo idêntico ao que já está gravado? | `ALREADY_APPLIED` — nenhuma `revision` é gasta |
| 8 | `baseRevision` == `revision` atual? | `APPLIED`, `revision + 1` |
| 9 | `baseRevision` < `revision` atual, ou criação sobre entidade existente | `STALE` com `currentRevision` |
| 10 | `baseRevision` > `revision` atual | `INVALID` (`BASE_REVISION_AHEAD`) |

A idempotência é verificada **antes** da validação de conteúdo, de propósito: um reenvio precisa
devolver o resultado original mesmo que o servidor tenha ficado mais exigente entre as duas
tentativas — senão o aparelho reenviaria para sempre algo que o servidor já tem.

### Resultado por item, transação por item

Um lote **não** é atômico, e isso é decisão. As mutações da Outbox são de agregados diferentes e
independentes entre si; recusar quatro válidas porque a quinta ficou stale faria o aparelho
reenviar tudo para sempre. Cada mutação recebe o desfecho dela na resposta:

```json
{
  "results": [
    { "clientMutationId": "…", "status": "APPLIED", "serverRevision": 5, "serverSequence": 1842 },
    { "clientMutationId": "…", "status": "STALE", "currentRevision": 7 }
  ]
}
```

O que **é** atômico é cada aplicação: `sync_entities`, `sync_changes` e `sync_mutations` entram na
mesma transação SQLite. Atualizar a entidade e falhar ao anexar a mudança deixaria os outros
aparelhos sem nunca saber da alteração; gravar o ledger sem aplicar faria um reenvio devolver um
resultado que não existe. Há teste que derruba cada uma das três tabelas e confirma que nada sobra.

### O que o aparelho faz com cada desfecho

| Desfecho | Outbox | `sync_entity_metadata` | `sync_conflicts` |
| --- | --- | --- | --- |
| `APPLIED` / `ALREADY_APPLIED` | entradas do agregado **removidas** | grava `serverRevision` e o hash | limpa o conflito daquele agregado |
| `STALE` | `BLOCKED` (`STALE`) | **não** é atualizada | registra `STALE_LOCAL_CHANGE` com `baseRevision` e hash local |
| `IMMUTABLE_HISTORY_CONFLICT` | `BLOCKED` | não | registra `IMMUTABLE_HISTORY` |
| `IDEMPOTENCY_CONFLICT` | `BLOCKED` | não | registra `IDEMPOTENCY` |
| `INVALID` / `UNSUPPORTED` | `BLOCKED` | não | registra `REJECTED_BY_SERVER` |
| status desconhecido, ou resultado ausente | continua `PENDING` | não | não |
| falha de transporte (rede, 5xx, 429) | continua `PENDING` | não | não |

A linha mais importante é a do `STALE`: **a revision conhecida não é atualizada**. Gravar
`currentRevision` ali faria o próximo push nascer com a base do servidor e sobrescrever a alteração
do outro aparelho — que é exatamente o *last write wins* que este protocolo existe para impedir. Há
teste sobre isso.

### Versão de payload

Cada agregado carrega a própria `schemaVersion`, no envelope `SyncAggregateEnvelope`. Ela **não** é
a versão da API HTTP, nem a versão do banco Room, nem uma versão de formato de backup — é o
contrato daquele payload, para que o formato de um treino possa evoluir sem depender do número da
tabela local.

**Compatibilidade.** A evolução é controlada, não tolerante: um `entityType` desconhecido, um
`exerciseId` que não resolve ou um campo obrigatório ausente são **recusados**, não preenchidos com
padrão. Nos dois sentidos: o servidor recusa no push, e o cliente recusa no pull — `Json` estrito,
com `ignoreUnknownKeys = false`, para que uma versão antiga do app não aceite pela metade um
payload criado por uma versão nova e destrua o resto na escrita seguinte.

### Tetos

| Teto | Valor | Onde |
| --- | --- | --- |
| mutações por push | 50 | `SyncProtocol.PUSH_BATCH_SIZE` / `SYNC_LIMITS.maxMutations` |
| bytes por payload de mutação | 256 KiB | `SYNC_LIMITS.maxMutationPayloadBytes` |
| corpo total do push | 2 MiB | `MAX_SYNC_PUSH_BODY_BYTES` |
| página de pull | 100 pedidos, 200 no teto | `SYNC_LIMITS.defaultPullPageSize` / `maxPullPageSize` |
| requisições de sync por conta | 60/min | `SYNC_RATE_LIMIT` |

Os dois lados declaram os mesmos números. O do servidor é o que vale: ele não pode supor que só o
APK oficial faz requisições.

---

## Idempotência

> A mesma mutação reenviada pelo cliente precisa ser reconhecida como a mesma mutação.

Cenário que **precisa** funcionar:

```text
cliente envia   →   servidor grava   →   resposta se perde   →   cliente reenvia
```

O resultado obrigatório é **um** registro. O resultado proibido:

- duas sessões de treino;
- duas medições corporais;
- duas recompensas;
- dois exercícios personalizados.

O servidor mantém um registro de `clientMutationId` já aplicados (por conta) e, ao reencontrar um,
devolve o resultado original em vez de reprocessar.

Isso não é novidade no projeto: a gamificação já resolve o mesmo problema localmente com
`dedupeKey` único em `gamification_events` e `eventId` único em `xp_transactions`. O protocolo
remoto segue o mesmo princípio.

---

## Pull (T16.6 — implementado)

```text
GET /v1/sync/pull?cursor=1840&limit=100
      ↓
{ changes: [...], nextCursor: 1841, hasMore: false }
      ↓
validação                 ← payload do servidor é entrada não confiável
      ↓
┌─────────────────────────────────────────────┐
│ Transação Room                              │
│   domínio + sync_entity_metadata            │
│   + sync_conflicts + sync_cursor            │
└─────────────────────────────────────────────┘
      ↓
UI observa Room           ← a UI nunca lê a resposta HTTP diretamente
```

Cada mudança carrega `serverSequence`, `entityType`, `entitySyncId`, `entitySchemaVersion`,
`serverRevision`, `operation`, `payloadHash`, `originDeviceId`, `createdAt` e o **agregado inteiro**
como estava naquela sequência.

O `cursor` é posição em `sync_changes.server_sequence` — estado controlado pelo servidor, nunca do
relógio do aparelho. Propriedades exigidas, todas cobertas por teste:

- **monotônico** — mudanças aparecem em ordem estável;
- **retomável** — perder conexão no meio não obriga a recomeçar;
- **completo** — nenhuma mudança entre dois cursores é pulada;
- **por conta** — nunca entrega dado de outro `ownerUid`, e o cursor de uma conta não é reusado por
  outra (a chave de `sync_cursor` é o `ownerUid`);
- **verificável** — cursor negativo, não inteiro ou além do que o servidor já emitiu é
  `INVALID_CURSOR`, nunca um reset silencioso para zero.

### O change log guarda o snapshot, não uma referência

Cada linha de `sync_changes` guarda o payload daquela mudança, e não um ponteiro para o estado
atual da entidade. Duplica armazenamento; a alternativa custaria corretude, porque a sequência X
passaria a devolver um estado posterior a X assim que a entidade mudasse de novo. Na escala do
Spark — um app pessoal, uma VPS — alguns KB não valem uma classe inteira de bug de convergência.

E o log é **append-only**: uma linha criada nunca é editada. Retenção agressiva não existe nesta
fase, justamente porque um aparelho offline pode precisar de mudanças antigas; se um dia existir,
ela precisa vir junto com `CURSOR_EXPIRED` e um caminho de rebaseline — nunca com um recomeço
silencioso de outro lugar.

### O que acontece com cada mudança recebida

```text
já conheço uma revision >= esta?          → eco. Nada a escrever, nenhuma Outbox nova
o agregado tem alteração local pendente?
   └ sim, e o hash local == o remoto      → convergiu. Confirma a fila, grava a revision
   └ sim, e o conteúdo é outro            → CONFLITO. O Room não é tocado
   └ não                                  → aplica no Room e grava a revision
```

Uma sessão concluída que já existe localmente com conteúdo divergente é conflito de integridade —
nunca sobrescrita.

### O cursor só avança depois do apply

A transação cobre domínio, metadata, conflitos **e** o cursor. Gravar o cursor antes transformaria
uma falha de escrita em alteração remota perdida — e perder não tem conserto, enquanto reaplicar
tem, porque cada passo é idempotente.

Quando alguma coisa não pode ser tratada, o sync **para naquele ponto** em vez de pular:

| Situação | O que acontece |
| --- | --- |
| `entityType`/`entitySchemaVersion` que este app não conhece | a página é truncada antes dela; o cursor para ali; a tela pede atualização do app |
| payload fora do contrato, identidade que não bate | idem |
| `canonicalId` que não resolve neste aparelho | a transação inteira é desfeita; o cursor **não** anda; nada é escrito |
| exercício personalizado ainda ausente | idem — e a página seguinte o traz |

Não existe *fuzzy matching* em lugar nenhum: o app não escolhe "um exercício parecido" para
conseguir aplicar. Ele para e diz por quê.

### Treino em execução

Uma execução em andamento é o único estado que o pull **adia** em vez de aplicar.

```text
sessão IN_PROGRESS/PAUSED usando o treino X
      +
chega uma mudança remota do treino X
      ↓
a mudança fica no change log, o cursor para antes dela
      ↓
o ciclo seguinte, sem execução ativa, a aplica
```

O motivo é concreto: o motor de execução lê a configuração de série do template **durante** o
treino (`getTemplateExercise`) — alvo de séries, faixa de repetições e descanso. Substituir os
exercícios do template no meio de uma execução mudaria esses valores debaixo do usuário, ou
removeria a linha do exercício que ele está fazendo naquele instante.

Adiar, e não descartar: a mudança não se perde, e uma execução dura minutos. Só o treino **em
execução** é adiado — outro treino, uma medida ou uma sessão concluída que chegue antes dele na
sequência continua sendo aplicada.

O histórico já criado nunca é afetado por isso, em nenhum cenário: `exercise_sessions` e `set_logs`
carregam os próprios snapshots (`exerciseNameSnapshot`, `restDurationSecondsSnapshot`) desde a
T16.3, e é essa separação entre **plano** e **execução** que faz o passado não ser reescrito quando
o plano muda.

### Ordem de dependência dentro da página

A ordem incidental do JSON não é confiável. O prefixo aplicável de cada página é ordenado por
dependência antes de ser escrito — exercícios personalizados e programas, depois treinos, depois
sessões, depois check-ins, e medidas por último, que não dependem de nada. Entre mudanças **do
mesmo agregado** a ordem continua sendo a da sequência, então o estado final é idêntico ao de
aplicar uma a uma.

### Supressão de eco

Uma mudança originada neste próprio aparelho volta no pull, como qualquer outra. Ela é reconhecida
por `sync_entity_metadata` (a revision já é conhecida) e não produz escrita nem entrada nova de
Outbox. É isso que impede `push → change log → pull → push` de virar um laço.

### O apply remoto não gera Outbox

As escritas do pull acontecem **fora** do `SyncMutationCoordinator`, em um lugar só
(`SyncRemoteApplier`) — exatamente como a `RestoreTransaction` da T16.5. Pelo coordenador, aplicar
40 mudanças remotas registraria 40 `UPSERT` e o aparelho devolveria ao servidor o que acabou de
receber dele. Há teste estrutural sobre isso.

E nenhum efeito colateral de domínio: receber uma sessão concluída de outro aparelho não dá XP, não
desbloqueia conquista, não cria recorde e não notifica. Gamificação é derivada, e as reconciliações
que já existem a reconstroem.

---

## Ciclo, gatilhos e estado (T16.6)

```text
1. valida conta e vínculo      ← só dataset adotado sincroniza, e só com a conta dona
2. push da Outbox              ← em lotes, em ordem de intenção
3. processa ACKs e conflitos
4. pull do change log          ← página a página, a partir do cursor durável
5. aplica cada página          ← transação: domínio + metadata + cursor
6. repete até hasMore = false
```

**Push antes de pull.** A Outbox descreve o que este aparelho decidiu; mandá-la primeiro evita que
uma alteração local vire conflito por causa de uma mudança remota que chegaria no mesmo ciclo.
Quando o push encontra `STALE`, o conflito é registrado e o pull seguinte **traz o lado remoto** —
sem aplicá-lo por cima do local sujo.

Os gatilhos são três, e nenhum deles é um laço:

| Gatilho | Quando | Como |
| --- | --- | --- |
| manual | toque em "Sincronizar agora" | um ciclo; toque repetido não vira um segundo |
| foreground | `MainActivity.onStart` | só se houver pendência ou se a última sincronização tiver mais de 15 min |
| alteração local | depois do commit da mutação | agenda **um** trabalho único do `WorkManager`, com rede e backoff exponencial |

Não existe `PeriodicWorkRequest`, `AlarmManager` de repetição, polling nem WebSocket. O Spark não
precisa de tempo real, e a tela não promete o que não existe: ela diz "última sincronização", não
"sempre atualizado".

---

## Sessões por status

Identidade global foi dada a **todas** as sessões — `syncId` responde "qual sessão é esta", não
"esta sessão sincroniza". A política de envio é separada e está na
[matriz de dados](./data-classification-matrix.md#política-por-status-de-sessão). Resumo:

- `COMPLETED` — histórico, único status que registra mutação na T16.3;
- `IN_PROGRESS` / `PAUSED` — execução **neste aparelho**. Sincronizar como estado vivo faria dois
  aparelhos disputarem o mesmo cursor de execução; "retomar treino em outro aparelho" é decisão de
  produto e não foi tomada;
- `PLANNED` — derivável do template e da agenda;
- `CANCELLED` — continua **local**. A T16.6 manteve a decisão anterior: o registry de payload só
  aceita `COMPLETED`, e uma sessão cancelada não é histórico de treino. Propagá-la exigiria decidir
  o que ela significa no outro aparelho, e essa decisão continua sem dono.

## Histórico concluído

Esta regra é bloqueante e não pode ser enfraquecida por nenhuma fase da T16.

Uma `WorkoutSession` com status `COMPLETED` — e os `exercise_sessions` e `set_logs` que pertencem a
ela — registra **o que de fato aconteceu**. Não é um documento colaborativo.

```text
mesmo syncId + conteúdo histórico incompatível
      ↓
CONFLITO DE INTEGRIDADE
```

E **não**:

```text
mesmo syncId + conteúdo divergente → last write wins → o histórico do usuário é reescrito
```

Na prática, **implementado na T16.6**:

- uma sessão concluída chega ao servidor uma vez e nasce em `revision = 1`;
- o mesmo `syncId` com conteúdo **idêntico** é idempotente — nada é aplicado, nenhuma `revision` é
  gasta;
- o mesmo `syncId` com conteúdo **divergente** é `IMMUTABLE_HISTORY_CONFLICT`, nunca `revision++`;
- no pull, uma sessão que já existe localmente com conteúdo divergente vira conflito e o Room não é
  tocado;
- receber uma sessão concluída de outro aparelho **não** dispara XP, conquista, recorde nem
  notificação;
- a exceção legítima continua sendo o **tombstone**: o usuário pode apagar a própria sessão. Apagar
  não é reescrever — e o tombstone é T16.7.

Isso é a mesma invariante que o Coach IA já respeita hoje (`PROJECT_RULES` §13: "Sessão concluída é
imutável"). O sync não pode ser a porta dos fundos que ela não tem.

**Na T16.3:** dar `syncId` a uma sessão concluída **não** a torna editável. A migração 30 → 31 tem
teste que compara séries, cargas, repetições, ordem e notas de uma sessão `COMPLETED` antes e
depois — nada muda.

---

## Deletes e tombstones (T16.7 — o que a T16.6 deliberadamente não fez)

A T16.6 **não propaga exclusão**, e é explícita sobre isso em vez de silenciosa:

```text
push com operation = DELETE   →   UNSUPPORTED (DELETE_NOT_SUPPORTED)
entrada DELETE na Outbox      →   permanece PENDING, e bloqueia o agregado dela
```

O agregado é bloqueado — e não parcialmente enviado — porque mandar a `UPSERT` anterior a um
`DELETE` pendente ressuscitaria no servidor exatamente o que o usuário apagou aqui. Converter um
`DELETE` em `UPSERT` é o defeito que essa regra existe para tornar impossível.

A UI **não esconde** a exclusão por causa disso: o usuário continua apagando o que quiser,
localmente. O que a tela faz é dizer a verdade — "itens excluídos neste aparelho ainda não são
removidos nos outros".

Delete físico imediato não funciona em multi-device:

```text
aparelho A deleta o item
aparelho B está offline
servidor remove fisicamente
aparelho B reconecta com a cópia antiga
      ↓
o item ressuscita
```

O protocolo precisa suportar **tombstone**: a exclusão é uma mudança versionada como qualquer
outra, com `deletedAt`, e entra na sequência do servidor. O aparelho B recebe "isto foi apagado" em
vez de reenviar "isto existe".

Um tombstone participa de `revision` e de `changeSeq` normalmente; um push de UPDATE sobre uma
entidade com tombstone é conflito, não recriação silenciosa.

A política de retenção — por quanto tempo um tombstone é guardado antes da limpeza definitiva —
fica para a **T16.7**. Ela depende de uma decisão que ainda não foi tomada: quanto tempo um
dispositivo pode ficar offline e ainda convergir corretamente.

---

## Conflitos (T16.7)

A T16.6 **detecta, isola e preserva**. Ela não resolve — e não resolver foi a decisão, não uma
omissão: qualquer heurística de desempate apagaria em silêncio a alteração de um dos dois lados.

O que já existe:

| O que a T16.6 faz | Onde |
| --- | --- |
| detecta escrita stale por `revision`, nunca por relógio | servidor |
| detecta divergência de histórico imutável | servidor |
| guarda a alteração local intacta, fora da fila de envio | `sync_outbox` em `BLOCKED` |
| guarda o lado remoto — revision, hash e payload daquela sequência | `sync_conflicts` |
| isola: um agregado em conflito não impede os outros de convergirem | cliente |
| reconhece convergência quando os dois lados têm o mesmo hash canônico | cliente e servidor |

O que **não** existe, e é a T16.7:

| Tipo de entidade | Política prevista |
| --- | --- |
| Template, programa, exercício pessoal, customização | escolha explícita entre as duas versões, com histórico preservado no servidor |
| Sessão concluída e seus filhos | **imutável** — divergência é conflito de integridade, sem resolução automática |
| Medida corporal | mesma data com conteúdo divergente = conflito explícito |
| Tombstone vs. update | conflito — o delete não é desfeito silenciosamente |

E, explicitamente ausentes da T16.6: *last write wins*, desempate por `updatedAt`, merge por campo,
"reenvia com a revision atual" e retry automático de `STALE`/`INVALID`/`UNSUPPORTED`. Há teste
estrutural e testes de comportamento sobre cada um.

---

## Deletes locais (T16.3 → T16.6)

O delete local continua **físico**, exatamente como era. Apagar um treino ou uma sessão apaga a
linha, e a intenção fica registrada como `DELETE` do agregado — o suficiente para o servidor criar
o tombstone quando o tombstone existir.

A T16.6 não mudou isso, e a razão é a mesma de antes invertida: agora **há** algo consumindo a
fila, e é justamente por isso que a exclusão precisa de política antes de viajar. Adicionar linhas
"apagadas mas presentes" no banco de todo usuário sem a política de retenção e de prevenção de
ressurreição trocaria um problema conhecido por um pior.

---

## O que a T16.0 deixou pronto para isso

Nada do protocolo — mas a **fundação** que permitiu implementá-lo na T16.6 sem retrabalho, e cada
peça foi usada como estava:

- versionamento de API configurado — o `@Controller('sync')` da T16.6 responde em `/v1/sync` sem
  ninguém ter escrito o prefixo;
- migrations versionadas e transacionais, para o schema remoto nascer por fase;
- SQLite com `foreign_keys=ON`, para que as tabelas de sync possam recusar órfãos de verdade;
- envelope de erro e request ID, para que um conflito seja diagnosticável;
- fronteira de autenticação desenhada, para que ownership não seja retrofit.
