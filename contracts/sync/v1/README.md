# Contrato de sincronização incremental do Spark — v1

- **Tarefa:** T16.6. Conflitos, deletes e tombstones são **T16.7**.
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

Política por agregado:

| Agregado | Política |
| --- | --- |
| `WORKOUT_SESSION` (só `COMPLETED`) | `IMMUTABLE_HISTORY` |
| os outros cinco | `MUTABLE_SNAPSHOT` |

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
| `UNSUPPORTED` | o servidor entende e não suporta (hoje: `DELETE`, tipo/versão desconhecidos) | bloqueia |
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
✗ tombstone, deletedAt, propagação de exclusão      → T16.7
✗ resolução de conflito e merge                     → T16.7
✗ WebSocket, SSE, push do servidor                  → não planejado
✗ compactação/retenção do change log + CURSOR_EXPIRED → T16.7/T16.8
✗ registro de dispositivos no servidor              → T16.8, se houver revogação por aparelho
```
