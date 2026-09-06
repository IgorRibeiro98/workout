# Protocolo de sincronização do Spark — contrato futuro

- **Tarefa:** T16.0 (documentação) — implementação em **T16.3** a **T16.7**.
- **Status (verificado em 2026-09-06):**
  - **implementado na T16.3:** a **Outbox transacional** no Android (`sync_outbox`), `syncId`,
    `clientMutationId`, `deviceId` e os DTOs de agregado;
  - **implementado na T16.4:** o **backup completo**, que não é sync — `POST /v1/backups` sobe um
    snapshot autocontido e `GET /v1/backups/latest` devolve metadata. Nada desce;
  - **não implementado:** o protocolo de sync. Não existe push incremental, pull, worker, ack,
    `revision`, `cursor`, tombstone remoto nem tabela de mudanças no servidor. O teste
    automatizado do backend continua garantindo que `/v1/sync/push` e `/v1/sync/pull` respondem 404.

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
| `status` | `PENDING` — o único estado que existe hoje |
| `createdAt` | epoch millis UTC, metadado de auditoria |
| `attemptCount` / `lastAttemptAt` | metadado mínimo de tentativa, zerado enquanto não houver transporte |

Não existe `SYNCED`: seria um estado mentindo sobre dado que nunca saiu do aparelho. `IN_FLIGHT` e
`FAILED` nascem junto com o worker, na T16.6.

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
- não toca em entrada fora de `PENDING`. Quando existir envio em andamento (T16.6), reaproveitar
  uma entrada já despachada quebraria a idempotência que o `clientMutationId` garante;
- não some com nada por idade. Limpeza depende de acknowledgment real do servidor (T16.6).

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

Consequência para a T16.6: quando o push incremental existir, ele precisa **partir do estado
coberto pelo último backup**, e não da instalação do app. E três agregados que hoje entram só no
snapshot completo — `EXERCISE_OVERRIDE`, `WEEKLY_GOAL`, `USER_PREFERENCES` — precisarão de mutação
própria na Outbox.

## Push incremental (T16.6)

```text
ação do usuário
      ↓
domínio → Room            ← a escrita local acontece primeiro, e é o que a UI observa
      ↓
Outbox (Room)             ← fila durável de mutações pendentes
      ↓
POST /v1/sync/push
```

Cada item enviado carrega:

```text
clientMutationId   UUID da tentativa                        [T16.3 — existe]
entityType         WORKOUT_TEMPLATE | WORKOUT_SESSION | ...  [T16.3 — existe]
syncId             identidade global da entidade             [T16.3 — existe]
operation          UPSERT | DELETE                           [T16.3 — existe]
deviceId           origem                                    [T16.3 — existe]
payload            snapshot do agregado + schemaVersion      [T16.3 — montável]
baseRevision       revision conhecida pelo cliente (0 = criação)   [T16.6]
```

`UPSERT` em vez de `CREATE`/`UPDATE`: o cliente não sabe — e não deveria precisar saber — se o
servidor já viu aquele `syncId`. Quem distingue criação de atualização é o servidor, pela
`revision`. Duas operações bastam.

O servidor precisa conseguir distinguir quatro situações:

| Situação | Como o servidor detecta | Resposta |
| --- | --- | --- |
| **Reenvio** | `clientMutationId` já registrado | devolve o resultado original, sem aplicar de novo |
| **Duplicidade** | mesmo `syncId` já criado | trata como update, não cria segunda entidade |
| **Versão stale** | `baseRevision` < `revision` atual | rejeita com a `revision` atual |
| **Conflito** | stale + conteúdo incompatível | conflito explícito para resolução (T16.7) |

O `Outbox` só remove uma mutação depois da confirmação do servidor. Uma resposta perdida deixa a
mutação na fila, e o reenvio é seguro — é exatamente o que a idempotência garante.

Nada disso existe hoje: não há worker, push por mutação, retry, ack nem `revision`. A T16.3
entregou a fila durável e o montador de payload, a T16.4 entregou o transporte autenticado e o
snapshot completo; o push incremental é da T16.6.

### Versão de payload

Cada agregado carrega a própria `schemaVersion`, no envelope `SyncAggregateEnvelope`. Ela **não** é
a versão da API HTTP, nem a versão do banco Room, nem uma versão de formato de backup — é o
contrato daquele payload, para que o formato de um treino possa evoluir sem depender do número da
tabela local.

**Compatibilidade.** A evolução é controlada, não tolerante: um `entityType` desconhecido, um
`exerciseId` que não resolve ou um campo obrigatório ausente são **recusados**, não preenchidos com
padrão. O montador de snapshot já segue essa regra — um exercício sem `canonicalId` nem `syncId`
faz o agregado inteiro não ser montado, em vez de subir incompleto e virar dado errado permanente
no servidor.

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

## Pull (T16.6)

```text
GET /v1/sync/pull?cursor=<opaco>
      ↓
{ changes: [...], nextCursor: "<opaco>", hasMore: true|false }
      ↓
validação                 ← payload do servidor é entrada não confiável
      ↓
Room                      ← escrita local
      ↓
UI observa Room           ← a UI nunca lê a resposta HTTP diretamente
```

O `cursor` é derivado de `changeSeq`, que é **estado controlado pelo servidor** — nunca do relógio
do aparelho. Ele é opaco para o cliente: o Android guarda e devolve, sem interpretar.

Propriedades exigidas:

- **monotônico** — mudanças aparecem em ordem estável;
- **retomável** — perder conexão no meio não obriga a recomeçar;
- **completo** — nenhuma mudança entre dois cursores é pulada;
- **por conta** — nunca entrega dado de outro `ownerUid`.

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
- `CANCELLED` — decisão adiada para a T16.6.

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

Na prática, a partir da T16.6:

- uma sessão concluída chega ao servidor uma vez e vira imutável;
- um push que tente alterar o conteúdo de uma sessão concluída é rejeitado, não aplicado;
- divergência é reportada como conflito de integridade e exige decisão explícita, nunca resolução
  automática;
- a exceção legítima é o **tombstone**: o usuário pode apagar a própria sessão. Apagar não é
  reescrever.

Isso é a mesma invariante que o Coach IA já respeita hoje (`PROJECT_RULES` §13: "Sessão concluída é
imutável"). O sync não pode ser a porta dos fundos que ela não tem.

**Na T16.3:** dar `syncId` a uma sessão concluída **não** a torna editável. A migração 30 → 31 tem
teste que compara séries, cargas, repetições, ordem e notas de uma sessão `COMPLETED` antes e
depois — nada muda.

---

## Deletes e tombstones (T16.7)

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

| Tipo de entidade | Política prevista |
| --- | --- |
| Template, programa, exercício pessoal, customização | última `revision` vence, com histórico preservado no servidor |
| Sessão concluída e seus filhos | **imutável** — divergência é conflito de integridade, sem resolução automática |
| Medida corporal | mesma data com conteúdo divergente = conflito explícito |
| Tombstone vs. update | conflito — o delete não é desfeito silenciosamente |

Nenhuma dessas políticas está implementada.

---

## Deletes locais na T16.3

O delete local continua **físico**, exatamente como era. A T16.3 não antecipou tombstone: apagar um
treino ou uma sessão apaga a linha, e a intenção fica registrada como `DELETE` do agregado — que é
o suficiente para o servidor criar o tombstone quando existir servidor.

Mudar o comportamento de exclusão agora, sem nada consumindo a fila, adicionaria linhas
"apagadas mas presentes" no banco de todo usuário em troca de nada.

---

## O que a T16.0 deixou pronto para isso

Nada do protocolo. O que existe é a **fundação** que permite implementá-lo sem retrabalho:

- versionamento de API configurado — um `@Controller('sync')` futuro responde em `/v1/sync`;
- migrations versionadas e transacionais, para o schema remoto nascer por fase;
- SQLite com `foreign_keys=ON`, para que as tabelas de sync possam recusar órfãos de verdade;
- envelope de erro e request ID, para que um conflito seja diagnosticável;
- fronteira de autenticação desenhada, para que ownership não seja retrofit.
