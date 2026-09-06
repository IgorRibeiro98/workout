# Contrato de backup do Spark — v1 (T16.4)

- **Tarefa:** T16.4 — Backup estruturado no Spark Backend.
- **Escopo:** o formato do *snapshot completo* que o Spark Android envia ao Spark Backend, a
  representação canônica usada no hash e as fixtures que os testes dos **dois** lados consomem.
- **Fora de escopo:** restore (T16.5), sync incremental (T16.6), conflitos/tombstones (T16.7).

Este diretório existe para que Kotlin e TypeScript não tenham duas definições independentes do
mesmo formato. As fixtures em [`fixtures/`](./fixtures) são lidas pelo teste do Android
(`BackupContractFixturesTest`) e pelo teste do backend (`backup-contract.spec.ts`); mudar o
formato sem mudar as fixtures faz os dois lados falharem juntos, que é o objetivo.

---

## 1. Backup não é sincronização

```text
T16.4   Android ──full snapshot──▶ Spark Backend        (existe)
        Android ◀──────────────── Spark Backend         (NÃO existe)
```

Um backup é uma cópia **completa e autocontida** do estado pessoal atual. Ele não depende de
backup anterior, de delta, de Outbox nem de servidor antigo. Não há download de conteúdo, merge,
convergência multi-dispositivo nem resolução de conflito nesta fase.

## 2. Envelope do snapshot

```json
{
  "backupSchemaVersion": 1,
  "capturedAt": 1788700000000,
  "clientBackupId": "3f2a1c88-0e1b-4a52-9c31-6b7d5e2f0a11",
  "deviceId": "b1d4a6f2-7c33-4a51-9f0e-2d5c8b7a1e44",
  "items": [
    {
      "entitySchemaVersion": 1,
      "entityType": "WORKOUT_TEMPLATE",
      "payload": { "...": "..." },
      "syncId": "e93a1b57-0d64-4c28-91f5-7a2e8c6b3d10"
    }
  ],
  "source": { "appVersionCode": 1, "appVersionName": "1.0", "databaseVersion": 32 }
}
```

| Campo | Papel |
| --- | --- |
| `backupSchemaVersion` | versão **deste formato de backup**. Não é a versão do Room, nem `/v1`, nem `entitySchemaVersion`, nem a versão do app |
| `clientBackupId` | identidade da **tentativa lógica**, UUID gerado pelo Android e estável entre reenvios |
| `deviceId` | qual instalação produziu o snapshot (T16.3). Metadado — não autoriza nada |
| `capturedAt` | epoch millis UTC do relógio do aparelho. **Informativo.** Quem ordena backups é o servidor |
| `source` | diagnóstico: versão do app e do banco local que produziram o snapshot |
| `items` | os agregados. Ordenados pelo Android por `(entityType, syncId)` |

### O que **não** existe no contrato

`ownerUid` **não é campo**. O dono do backup é derivado do Firebase ID Token verificado
(`AuthenticatedPrincipal.uid`); um `ownerUid` no corpo não seria validado, seria ignorado — e
nem existir é melhor do que existir e ser ignorado.

Também não existem: token, credencial, prompt/contexto/resposta de IA, entrada de Outbox,
`localId` do Room, caminho de arquivo local, `content://`, binário em Base64.

## 3. Item

```text
entityType             o agregado (registry fechado — ver §4)
entitySchemaVersion    versão do payload daquele agregado
syncId                 identidade portátil do agregado (ver §5)
payload                o conteúdo, na forma daquele entityType
```

`entitySchemaVersion` é por agregado justamente para que o formato de um treino evolua sem
depender do número da tabela local nem da versão do envelope.

## 4. Registry de agregados (v1)

Registry **fechado**: `entityType` fora desta lista é recusado, e `payload` é validado contra o
schema estrito daquele tipo — campo desconhecido é erro, não é ignorado.

| `entityType` | v | Origem local | Identidade portátil |
| --- | --- | --- | --- |
| `WORKOUT_PROGRAM` | 1 | `workout_programs` | `syncId` (UUID) |
| `WORKOUT_TEMPLATE` | 1 | `workout_templates` + `workout_template_exercises` | `syncId` (UUID) |
| `WORKOUT_SESSION` | 1 | `workout_sessions` + `exercise_sessions` + `set_logs`, **só `COMPLETED`** | `syncId` (UUID) |
| `CUSTOM_EXERCISE` | 1 | `exercises` com `isUserCreated = 1` | `syncId` (UUID) |
| `BODY_MEASUREMENT` | 1 | `body_measurements` | `syncId` (UUID) |
| `CHECK_IN` | 1 | `check_ins` | `syncId` (UUID) |
| `EXERCISE_OVERRIDE` | 1 | `exercise_user_overrides` | `canonical:<canonicalId>` ou `custom:<uuid>` |
| `WEEKLY_GOAL` | 1 | `weekly_goal_history` | `week:<epochDay>` |
| `USER_PREFERENCES` | 1 | DataStore (`SettingsManager`) | `preferences` (singleton) |

Os seis primeiros são exatamente os `SyncEntityType` da T16.3 e reusam
`SyncAggregateSnapshotBuilder` — não existe um segundo serializador de treino no Spark.

Os três últimos **não** produzem entrada de Outbox hoje (a matriz da T16.3 já os marcava como
"T16.4"): eles entram no snapshot completo, e não no incremental, que não existe. Quando a T16.6
trouxer push incremental, eles precisam ganhar mutação própria — está registrado como pendência.

## 5. Identidade portátil

Nenhum `localId`, `rowid`, caminho ou URI atravessa a fronteira. Para os agregados cuja raiz não
tem UUID, a identidade é a que a
[matriz de dados](../../../docs/architecture/data-classification-matrix.md) já definia:

- `EXERCISE_OVERRIDE` — a identidade é a **do exercício customizado**, não uma nova. Dois
  aparelhos que customizam o mesmo supino estão falando da mesma coisa;
- `WEEKLY_GOAL` — a PK natural (`effectiveFromWeekStartEpochDay`) já é global;
- `USER_PREFERENCES` — singleton por conta.

O servidor valida o formato de cada identidade e exige que ela **case com o payload**: um item
`WORKOUT_TEMPLATE` cujo `syncId` difere de `payload.syncId` é recusado. Sem *fuzzy matching*.

## 6. Validação relacional (o que é exigido e o que não é)

Exigido, porque vale por construção no Android e uma violação indicaria dado corrompido:

1. `WORKOUT_TEMPLATE.exercises[].exercise` com `kind = CUSTOM` → aquele `CUSTOM_EXERCISE` precisa
   estar no snapshot;
2. `WORKOUT_TEMPLATE.programSyncId`, quando presente → aquele `WORKOUT_PROGRAM` precisa estar no
   snapshot;
3. `EXERCISE_OVERRIDE` com identidade `custom:` → aquele `CUSTOM_EXERCISE` precisa estar no
   snapshot.

**Não** exigido, de propósito:

- `WORKOUT_SESSION.templateSyncId` — histórico sobrevive ao treino que o originou. Exigir isso
  recusaria o backup de quem apagou um template antigo;
- referências de exercício **dentro** de uma sessão — a sessão carrega
  `exerciseNameSnapshot`/`machineLabelSnapshot`, que é o que preserva o passado quando o exercício
  é renomeado ou apagado;
- `CHECK_IN.sessionSyncId` — pode apontar para uma sessão não concluída, que o backup não inclui.

Duplicidade `(entityType, syncId)` no mesmo snapshot é sempre recusada.

## 7. Representação canônica e hash

```text
payloadHash = SHA-256( canonical(bytes do corpo recebido) ), hex minúsculo
```

`canonical(...)` é uma reemissão do **texto** JSON com:

1. nenhum espaço insignificante;
2. membros de objeto ordenados pelo token da chave (comparação por unidade de código UTF-16);
3. arrays na ordem em que chegaram — inclusive `items`;
4. todo token escalar (string com aspas e escapes, número, `true`, `false`, `null`) copiado
   **verbatim** da origem;
5. chave repetida no mesmo objeto: erro.

A regra 4 é o ponto importante. Canonicalizar a partir de valores já *parseados* obrigaria os dois
lados a concordar na formatação de ponto flutuante — e `Float.toString()` do Kotlin e
`JSON.stringify` do JavaScript não concordam (um serializa `Float`, o outro `double`). Preservando
o token, o servidor reproduz exatamente os bytes que o cliente produziu, e os dois hashes fecham
sem que nenhum dos dois precise imitar o formatador do outro.

O servidor **calcula o seu próprio hash**. O cliente não declara hash no corpo: um hash declarado
só poderia ser ignorado ou confiado, e nenhuma das duas é boa.

O hash serve a quatro coisas: idempotência (§8), integridade, diagnóstico e a verificação que o
restore da T16.5 vai precisar fazer.

## 8. Idempotência

```text
POST clientBackupId=ABC  payload X  →  201  backup criado
POST clientBackupId=ABC  payload X  →  200  o mesmo backup, sem criar outro
POST clientBackupId=ABC  payload Y  →  409  BACKUP_IDEMPOTENCY_CONFLICT
```

A chave é `(ownerUid do token, clientBackupId)`, com `UNIQUE` no banco. Uma resposta perdida no
caminho de volta é resolvida por reenvio — é para isso que a tentativa é durável no Android e que
o payload dela é imutável.

## 9. Endpoints

### `POST /v1/backups`

```text
Authorization: Bearer <Firebase ID Token>
Content-Type: application/json
<envelope do §2>
```

`201 Created` (novo) ou `200 OK` (reenvio idêntico):

```json
{
  "backupId": "0e2b...",
  "clientBackupId": "3f2a1c88-0e1b-4a52-9c31-6b7d5e2f0a11",
  "backupSchemaVersion": 1,
  "createdAt": 1788700123456,
  "itemCount": 9,
  "sizeBytes": 4821,
  "payloadHash": "9f86d081..."
}
```

Nunca devolve o snapshot. Metadata é o suficiente nesta fase.

### `GET /v1/backups/latest`

Mesma metadata do backup mais recente **daquela conta**, ou `404 BACKUP_NOT_FOUND`.

Existe para preparar a T16.5 (descoberta) sem implementar restore: não há endpoint de conteúdo.

### Erros

| Código | HTTP | Quando |
| --- | --- | --- |
| `UNAUTHENTICATED` | 401 | sem token, token malformado ou recusado |
| `AUTH_UNAVAILABLE` | 503 | o servidor não consegue verificar tokens agora |
| `INVALID_BACKUP` | 400 | envelope, item, identidade ou relação inválida |
| `UNSUPPORTED_BACKUP_SCHEMA_VERSION` | 400 | `backupSchemaVersion` desconhecida |
| `UNSUPPORTED_ENTITY_SCHEMA_VERSION` | 400 | `entitySchemaVersion` desconhecida para o tipo |
| `BACKUP_IDEMPOTENCY_CONFLICT` | 409 | mesmo `clientBackupId`, conteúdo diferente |
| `BACKUP_TOO_LARGE` | 413 | corpo, número de itens ou item acima do teto |
| `BACKUP_NOT_FOUND` | 404 | `GET latest` sem backup para aquela conta |

Nenhuma mensagem de erro repete conteúdo do snapshot.

## 10. Tetos

| Teto | Valor |
| --- | --- |
| corpo da requisição | 4 MiB |
| itens por snapshot | 5 000 |
| bytes por item | 256 KiB |
| string em qualquer profundidade | 4 000 caracteres |
| coleção aninhada (exercícios, séries) | 500 elementos |

Server-side, configuráveis no código em um lugar só (`backup.limits.ts`), e repetidos como
proteção — o servidor não pode supor que só o APK oficial faz requisições.

## 11. Fixtures

| Arquivo | Para quê |
| --- | --- |
| `backup-v1-minimal.json` | envelope válido sem item nenhum |
| `backup-v1-complete.json` | um item de cada `entityType`, com as relações do §6 satisfeitas |
| `backup-v1-invalid-id.json` | `syncId` de item que não é identidade portátil |
| `backup-v1-duplicate-item.json` | `(entityType, syncId)` repetido |
| `backup-v1-unsupported-version.json` | `backupSchemaVersion` desconhecida |

As três últimas precisam ser **recusadas** pelos dois lados.
