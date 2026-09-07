# Contrato de sincronização incremental do Spark — v1

- **Tarefa:** T16.6. Conflitos, deletes e tombstones foram **implementados na T16.7** — o
  envelope de mutação passou a aceitar `operation = "DELETE"` com `payload` nulo, e uma mudança de
  `operation = "DELETE"` no pull vem com `payload: null`. A **T16.7.1** acrescentou uma terceira
  rota, somente leitura: `GET /v1/sync/entities/{entityType}/{entitySyncId}` (§5.1).
- **Implementações:**
  - Android — `com.example.data.sync.SyncProtocol` e `com.example.data.sync.*`
  - Backend — `backend/src/modules/sync/sync.contract.ts` e `backend/src/modules/sync/*`
- **Documento de decisão:** [`docs/architecture/sync-protocol.md`](../../../docs/architecture/sync-protocol.md)

Este arquivo é a definição legível do que os dois lados precisam concordar. Ele existe pelo mesmo
motivo que [`contracts/backup/v1/`](../../backup/v1/README.md): um formato que muda de um lado só
deve quebrar os dois, e não produzir um bug silencioso no aparelho de alguém.

---

## 1. Sync não é backup

```text
POST /v1/backups            snapshot completo, imutável, sob demanda        (T16.4)
GET  /v1/backups/{id}/…     o mesmo snapshot de volta, sob demanda          (T16.5)
POST /v1/sync/push          mudanças deste aparelho                         (T16.6)
GET  /v1/sync/pull          mudanças dos outros aparelhos                   (T16.6)
GET  /v1/sync/entities/…    o estado ATUAL de um agregado, por identidade   (T16.7.1)
```

Os quatro coexistem. Um snapshot é um ponto no tempo ao qual dá para voltar; o sync converge cópias
vivas. Nenhum substitui o outro.

## 2. Identidades

| Peça | O que é | Quem gera |
| --- | --- | --- |
| `ownerUid` | a conta | **o servidor**, a partir do Firebase ID Token verificado |
| `entitySyncId` | qual agregado (`syncId` da T16.3) | dispositivo, offline |
| `clientMutationId` | qual tentativa de mutação | dispositivo, por mutação |
| `deviceId` | qual instalação originou a mudança | dispositivo |
| `serverRevision` | versão da entidade no servidor | **servidor**, por entidade |
| `baseRevision` | a revision sobre a qual o cliente construiu a mudança | dispositivo |
| `serverSequence` | posição no change log da conta | **servidor**, global e crescente |
| `cursor` | até onde o cliente já leu | dispositivo, a partir de `serverSequence` |

**`ownerUid` não existe no corpo de nenhuma requisição.** O envelope do push é estrito: um campo de
dono recusa a requisição inteira. `deviceId` é metadado e não autoriza nada.

## 3. Agregados

Os seis `SyncEntityType` da T16.3:

```text
WORKOUT_PROGRAM   WORKOUT_TEMPLATE   WORKOUT_SESSION
CUSTOM_EXERCISE   BODY_MEASUREMENT   CHECK_IN
```

Os payloads são **os mesmos** do backup, validados pelo **mesmo** registry
(`BackupEntityRegistry`): não existe um segundo schema de treino no projeto.

`EXERCISE_OVERRIDE`, `WEEKLY_GOAL` e `USER_PREFERENCES` existem no backup e **não** no sync
incremental — o servidor os recusa com `UNSUPPORTED`. Motivo em `sync-protocol.md`.

Política por agregado (`SyncEntityPolicies` no app, `sync.policy.ts` no servidor — os dois precisam
concordar, e nenhum tem `default`):

| Agregado | Mutabilidade | Delete remoto | Conflito |
| --- | --- | --- | --- |
| `WORKOUT_PROGRAM` | `MUTABLE_SNAPSHOT` | sim | `USER_CHOICE` |
| `WORKOUT_TEMPLATE` | `MUTABLE_SNAPSHOT` | sim | `USER_CHOICE` |
| `CUSTOM_EXERCISE` | `MUTABLE_SNAPSHOT` | sim | `USER_CHOICE` |
| `BODY_MEASUREMENT` | `MUTABLE_SNAPSHOT` | sim | `USER_CHOICE` |
| `CHECK_IN` | `MUTABLE_SNAPSHOT` | **não** | `USER_CHOICE` |
| `WORKOUT_SESSION` (só `COMPLETED`) | `IMMUTABLE_HISTORY` | sim | `IMMUTABLE_CONFLICT` |

`LAST_WRITE_WINS_ALLOWED` existe como estratégia declarável e **nenhum agregado a usa** — há teste
dos dois lados. Ela nunca é padrão.

## 4. `POST /v1/sync/push`

```http
POST /v1/sync/push
Authorization: Bearer <Firebase ID Token>
Content-Type: application/json
```

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

`baseRevision` ausente, `null` ou `0` significa criação.

### Resposta — `200`, resultado por item

```json
{
  "results": [
    { "clientMutationId": "…", "status": "APPLIED", "serverRevision": 5, "serverSequence": 1842 }
  ]
}
```

| `status` | Significado | O cliente faz |
| --- | --- | --- |
| `APPLIED` | aplicada agora | confirma a Outbox, grava a revision |
| `ALREADY_APPLIED` | reenvio, ou conteúdo já idêntico | idem — nenhuma revision foi gasta |
| `STALE` | `baseRevision` desatualizada; `currentRevision` vem junto | bloqueia a entrada, registra conflito, **não** grava a revision |
| `INVALID` | fora do contrato | bloqueia; reenviar produziria o mesmo |
| `UNSUPPORTED` | o servidor entende e não suporta (tipo/versão desconhecidos, `DELETE` de agregado com `deleteAllowed = false`) | bloqueia |
| `REMOTE_DELETED` | a entidade tem tombstone: este `UPSERT` a recriaria (T16.7) | bloqueia, registra conflito de exclusão |
| `IMMUTABLE_HISTORY_CONFLICT` | mesma sessão concluída, conteúdo divergente | bloqueia, registra conflito |
| `IDEMPOTENCY_CONFLICT` | mesmo `clientMutationId`, conteúdo ou alvo diferente | bloqueia |

Um `status` desconhecido — de um servidor mais novo — **não** confirma nada: a entrada continua
pendente.

### Erros de requisição inteira

| HTTP | `code` |
| --- | --- |
| 400 | `INVALID_SYNC_REQUEST` |
| 400 | `INVALID_CURSOR` (no pull) |
| 401 | `UNAUTHENTICATED` |
| 404 | `SYNC_ENTITY_NOT_FOUND` (na leitura de estado atual) |
| 413 | `SYNC_PAYLOAD_TOO_LARGE` |
| 429 | `SYNC_RATE_LIMITED` |

## 5. `GET /v1/sync/pull`

```http
GET /v1/sync/pull?cursor=1840&limit=100
Authorization: Bearer <Firebase ID Token>
```

```json
{
  "changes": [
    {
      "serverSequence": 1841,
      "entityType": "WORKOUT_TEMPLATE",
      "entitySyncId": "…",
      "entitySchemaVersion": 1,
      "serverRevision": 5,
      "operation": "UPSERT",
      "payloadHash": "…",
      "originDeviceId": "…",
      "createdAt": 1788700000000,
      "payload": { }
    }
  ],
  "nextCursor": 1841,
  "hasMore": false
}
```

- ordem estável por `serverSequence`;
- `nextCursor` é a última sequência devolvida — ou o cursor pedido, quando nada veio;
- cursor negativo, não numérico ou além do que o servidor emitiu é `INVALID_CURSOR`. **Nunca** um
  reset silencioso para zero;
- uma conta jamais recebe o change log de outra.

## 5.1 `GET /v1/sync/entities/{entityType}/{entitySyncId}` (T16.7.1)

```http
GET /v1/sync/entities/WORKOUT_TEMPLATE/2f0c…
Authorization: Bearer <Firebase ID Token>
```

```json
{
  "ownerUid": "…",
  "entityType": "WORKOUT_TEMPLATE",
  "entitySyncId": "…",
  "entitySchemaVersion": 1,
  "serverRevision": 6,
  "deleted": false,
  "payloadHash": "…",
  "payload": { }
}
```

Num tombstone:

```json
{
  "ownerUid": "…",
  "entityType": "WORKOUT_TEMPLATE",
  "entitySyncId": "…",
  "entitySchemaVersion": 1,
  "serverRevision": 6,
  "deleted": true,
  "payloadHash": null,
  "payload": null
}
```

Ela responde uma pergunta que o pull não responde. O pull entrega **mudanças em sequência**, e uma
vez que o cursor passou de uma sequência aquela versão não é mais pedível; esta rota entrega **o
estado de agora**, por identidade. O cliente a usa antes de aplicar "usar a versão da nuvem" numa
resolução de conflito.

**Garantias, e elas são o contrato:**

- **somente leitura.** Não gasta `revision`, não anexa linha a `sync_changes`, não escreve em
  `sync_mutations`, não altera tombstone e não move cursor. Há teste que conta as três tabelas
  antes e depois;
- **autenticada, e o dono sai do token.** Não existe `?ownerUid=` nem `?uid=`; um uid no query
  string não influencia a resposta;
- **isolamento por conta.** Uma identidade que existe para **outra** conta responde `404`
  `SYNC_ENTITY_NOT_FOUND` — a mesma resposta de uma que nunca existiu. Distinguir as duas
  transformaria a rota num oráculo de existência do dado alheio;
- **`ownerUid` na resposta é a conta que o servidor autenticou** nesta requisição, derivada do token
  verificado. Ele existe para que o aparelho possa provar, **depois** da resposta, que o estado que
  vai gravar pertence à mesma conta dona do dataset local. Ele continua não existindo em nenhum
  **corpo de requisição**;
- `entityType` fora do registry é `400 INVALID_SYNC_REQUEST` — a lista de agregados é contrato
  público e dizer "não conheço esse tipo" não revela dado nenhum. O que precisa ser indistinguível é
  a identidade, e essa continua sendo `404`;
- o mesmo teto de requisições por conta do push e do pull.

## 6. Hash canônico

`payloadHash` é o SHA-256 da **forma canônica do payload**, definida em
[`contracts/backup/v1/README.md` §7](../../backup/v1/README.md) — a mesma dos dois lados, com
tokens escalares copiados verbatim. Ele responde "é o mesmo conteúdo?", que é uma pergunta
**diferente** de "é a mesma versão?" (`serverRevision`). Um não substitui o outro.

O payload devolvido no pull é JSON estruturado: o cliente confere o `payloadHash` que o servidor
informa, e **não** recalcula um hash sobre o texto recebido — a reserialização do transporte pode
mudar a forma do número sem mudar o valor.

## 7. Tetos

| Teto | Valor |
| --- | --- |
| mutações por push | 50 |
| bytes por payload de mutação (canônico) | 256 KiB |
| corpo total do push | 2 MiB |
| página de pull | 100 padrão, 200 máximo |
| requisições de sync por conta | 60 por minuto |

## 8. Versionamento

- `entitySchemaVersion` é a versão **daquele agregado**. Mudou o payload, suba nos dois lados.
- `SyncProtocol.VERSION` / `SYNC_PROTOCOL_VERSION` é a versão do protocolo. Mudou o envelope ou o
  vocabulário de status, suba os dois juntos.
- Nenhuma das duas é o `/v1` da URL, o `AppDatabase.version` ou o `BACKUP_SCHEMA_VERSION`.

Evolução é **controlada, não tolerante**: tipo desconhecido, versão futura, campo a mais e
identidade que não bate são recusados dos dois lados — nunca preenchidos com padrão.

## 9. O que este contrato deliberadamente não tem

```text
✓ tombstone, propagação de exclusão, prevenção de ressurreição   → T16.7
✓ resolução explícita de conflito (escolha do usuário)           → T16.7
✓ CURSOR_EXPIRED                                                 → T16.7
✓ leitura do estado atual de um agregado                         → T16.7.1
✗ merge por campo, CRDT, edição colaborativa       → deliberadamente fora
✗ WebSocket, SSE, push do servidor                 → não planejado
✗ compactação do change log / limpeza de tombstone → deliberadamente não feita
✗ registro de dispositivos no servidor             → T16.8, se houver revogação por aparelho
```

### O que a T16.7 acrescentou ao envelope

```jsonc
// mutação de exclusão: sem payload
{
  "clientMutationId": "…",
  "entityType": "WORKOUT_TEMPLATE",
  "entitySyncId": "…",
  "entitySchemaVersion": 1,
  "operation": "DELETE",
  "baseRevision": 5,
  "payload": null
}

// mudança de exclusão, no pull
{
  "serverSequence": 42,
  "entityType": "WORKOUT_TEMPLATE",
  "entitySyncId": "…",
  "serverRevision": 6,
  "operation": "DELETE",
  "payloadHash": "",
  "payload": null
}
```

Um tombstone não afirma conteúdo: identidade e `serverRevision` são tudo que o outro aparelho
precisa, e devolver o que foi apagado só duplicaria dado pessoal.
