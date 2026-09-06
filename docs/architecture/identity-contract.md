# Contrato de identidade global do Spark

- **Tarefa:** T16.0 (documentação) — implementação em **T16.1** (identidade da conta) e **T16.3**
  (identidade dos dados).
- **Status (verificado em 2026-09-06):**
  - **implementado na T16.1:** a identidade da **conta**. O Firebase UID chega ao servidor por
    Firebase ID Token, é verificado pelo Admin SDK e vira `AuthenticatedPrincipal { uid }`;
  - **implementado na T16.3:** a identidade dos **dados**. `syncId` nas raízes de agregado
    pessoais (Room `version = 31`), `deviceId` no DataStore e `clientMutationId` por mutação na
    Outbox;
  - **não implementado:** `ownerUid` persistido. Não existe tabela de ownership no servidor,
    nenhum dado local tem dono, e nada sobe ou desce.

---

## Os cinco identificadores

Nenhum substitui outro, e confundir dois deles é o defeito que este documento existe para evitar:

```text
Firebase UID          → quem é o usuário          (conta online)
deviceId              → qual instalação do app    (aparelho)
syncId                → qual entidade             (dado pessoal, global)
clientMutationId      → qual alteração            (uma tentativa de mutação)
localId               → qual linha no Room        (só dentro deste aparelho)
canonicalExerciseId   → qual exercício de catálogo (conteúdo versionado)
```

### `localId` — identidade dentro do aparelho

O que o Room já usa hoje: `@PrimaryKey(autoGenerate = true) val id: Long`.

Continua existindo e continua sendo a chave de todos os relacionamentos locais (`templateId`,
`sessionId`, `exerciseSessionId`, ...). A T16.3 **não** troca as chaves estrangeiras do Room por
identificadores globais — isso reescreveria o schema inteiro sem ganho.

`localId` **nunca** é identidade cross-device: dois aparelhos geram `1`, `2`, `3` para coisas
completamente diferentes.

### `syncId` — identidade global estável

Identificador global de uma entidade pessoal, estável para sempre.

- **Formato:** UUID aleatório (v4), como `String`.
- **Gerado por:** o dispositivo, **offline**, no momento em que a entidade é criada. Não depende de
  rede, de servidor nem de conta — um treino criado em modo avião já nasce com `syncId`.
- **Onde vive (T16.3, implementado):** coluna `syncId` em `workout_programs`,
  `workout_templates`, `workout_sessions`, `body_measurements` e `check_ins` — `NOT NULL`, com
  índice `UNIQUE` —, e em `exercises` como coluna **anulável** preenchida apenas quando
  `isUserCreated = 1`. Filhos de agregado (`workout_template_exercises`, `exercise_sessions`,
  `set_logs`) **não** têm coluna: eles viajam no snapshot da raiz. Ver
  [a matriz](./data-classification-matrix.md#agregados-de-sincronização).
- **Onde é gerado:** `SyncIds.random()`, valor padrão da entidade Room. Um treino criado em modo
  avião já nasce com identidade.
- **Unicidade é do banco:** índice `UNIQUE`, não a confiança em "UUID nunca colide".
- **Imutável:** editar um template não muda seu `syncId`. É isso que permite ao servidor reconhecer
  "esta é a mesma entidade" em vez de criar uma cópia.

Proibido usar como identidade cross-device, porque nenhum destes é estável nem único entre
aparelhos:

- `displayName` ou qualquer nome exibido;
- nome do exercício;
- timestamp isolado (`createdAt`, `startedAt`);
- `id` autoincrement do Room.

**Backfill (T16.3 — feito):** a migração 30 → 31 é aditiva. Para cada tabela: `ADD COLUMN` →
`UPDATE` gerando um UUID v4 por linha em SQL (`randomblob()` é reavaliado por linha) → índice
`UNIQUE` **por último**, para que uma colisão faça a migração falhar em vez de corromper. Nenhuma
tabela é recriada, nenhuma chave estrangeira é substituída, nenhum `DROP TABLE` acontece.

Não é exigido que dois bancos migrados em universos separados gerem os mesmos UUIDs. O que é
exigido é que, **uma vez atribuído, o `syncId` nunca mude** — e isso é garantido por: a coluna só é
escrita na criação, todo caminho de edição usa `copy()` sobre a linha lida do banco, e o único
lugar onde a UI remontava a entidade do zero (edição de medida corporal) preserva a identidade
guardada no repositório.

### `clientMutationId` — identidade de uma alteração

UUID por mutação registrada na Outbox. **Não é** o `syncId`:

```text
entitySyncId      → o treino ABC
clientMutationId  → a edição XYZ feita naquele treino
```

É o que permite ao servidor (T16.6) distinguir "reenviaram porque a resposta se perdeu" de
"mudaram de novo". Tem índice `UNIQUE` no Room.

### `canonicalExerciseId` — identidade de conteúdo, que já existe

O catálogo canônico **já tem** identidade estável: `ExerciseEntity.canonicalId`, vinda do manifesto
versionado, com `slug` e `contentVersion`.

**Essa identidade não muda e não é substituída.** Um exercício canônico é o mesmo exercício em todo
aparelho porque veio do mesmo manifesto — ele não precisa de `syncId` e não vai ganhar um.

O `syncId` existe para **entidades pessoais criadas pelo usuário**. Ele não reinventa identidade de
catálogo.

Consequência prática para o sync: quando uma entidade pessoal referencia um exercício, ela
referencia:

- `canonicalId`, quando o exercício é do catálogo;
- o `syncId` do exercício pessoal, quando `isUserCreated = 1`.

Nunca o `localId` do exercício — ele não significa nada no outro aparelho.

### `deviceId` — identidade da instalação

Identificador da instalação do Spark, necessário para o multi-device (T16.6) saber de onde veio uma
mudança e para o cursor de sync ser por dispositivo.

- **Formato:** UUID aleatório gerado pelo próprio Spark, guardado no DataStore (`DEVICE_ID`).
  Implementado na T16.3 em `DeviceIdProvider`.
- **Quando nasce:** na primeira vez que alguém pede — não na abertura do app. O Spark é
  local-first e não cria identidade que ninguém usou.
- **Escopo:** a instalação. Desinstalar e reinstalar gera outro — e isso é correto, é outra cópia
  local. Não há tentativa de sobreviver à reinstalação: fabricar isso seria o rastreamento que a
  regra abaixo proíbe.

**Proibido** derivar `deviceId` de IMEI, número de série, MAC, Android ID ou qualquer *fingerprint*
de hardware. São identificadores persistentes de aparelho: usá-los transformaria um detalhe de sync
em rastreamento, e vários são inacessíveis ou instáveis nas versões atuais do Android.

`deviceId` não é identidade de usuário e não autoriza nada.

---

## Ownership

Todo dado pessoal remoto tem um dono autenticado, representado por `ownerUid` (o `uid` do Firebase
Auth).

### A regra crítica

> O servidor **nunca** confia em `ownerUid` recebido no payload.

O caminho é sempre:

```text
Authorization: Bearer <Firebase ID Token>
        ↓
backend verifica a assinatura do token   [T16.1 — implementado]
        ↓
uid confiável extraído do token verificado
        ↓
AuthenticatedPrincipal { uid }           [T16.1 — implementado]
        ↓
ownership de tudo que a requisição escreve ou lê   [T16.4+ — não implementado]
```

O caminho até `AuthenticatedPrincipal` existe e é testado: `BearerAuthGuard` recusa token ausente,
malformado ou inválido com 401, distingue "não consegui verificar" com 503, e nenhum `uid` vindo de
query string, header ou corpo influencia a resposta. O que ainda **não** existe é o que vem depois:
não há dado pessoal no servidor para filtrar por `ownerUid`.

Se o corpo da requisição trouxer um `ownerUid`, ele é **ignorado**, não validado — aceitar um
`ownerUid` "conferindo se bate com o token" já seria um caminho a mais para errar. O único `uid` que
existe no servidor é o que veio do token.

Consequências, a partir da T16.4 (quando existir dado pessoal remoto):

- toda leitura é filtrada por `ownerUid` do principal;
- toda escrita grava o `ownerUid` do principal;
- uma requisição sem token válido não acessa dado pessoal nenhum.

Na T16.1 a última regra já vale de forma trivial: `/v1/auth/me` é a única rota sob `/v1`, exige
token e devolve apenas o `uid` derivado dele.

### Adoção explícita — o contrato `LOCAL_UNOWNED` (T16.3)

O estado padrão de todo dado local é **`LOCAL_UNOWNED`**: ele existe, tem `syncId`, e **não tem
dono remoto**. Isso é representado no código por `CloudSyncScope.Disabled`, que é o valor quando
nada foi gravado — ou seja, sempre, até a T16.4.

```text
T16.3 (hoje)                              T16.4 (adoção explícita)
CloudSyncScope.Disabled                   usuário autenticado
dado local = LOCAL_UNOWNED                      ↓ ativa backup
Outbox não registra nada                  Spark mostra o que será associado
login não muda nada                             ↓ confirma
                                          Preparing(uid) → snapshot inicial sobe
                                                ↓
                                          Enabled(uid) → ownership remoto estabelecido
                                                ↓
                                          Outbox passa a registrar no escopo daquela conta
```

Consequências que valem hoje e são cobertas por teste:

- **login não liga a nuvem.** `SettingsCloudSyncScopeProvider` não observa `AuthState`; ele lê uma
  preferência que só a ativação explícita escreve. Um usuário pode estar autenticado o dia inteiro
  para usar o Coach IA com o backup desligado;
- **nenhuma entrada de Outbox sem dono.** `ownerUid` é `NOT NULL` na tabela. Uma fila criada antes
  da adoção não teria a quem pertencer, e adotá-la na primeira conta que entrasse seria dar a ela
  dados de outra pessoa;
- **mudanças anteriores ao backup não se perdem.** O primeiro backup é um *snapshot completo do
  estado atual*, não a reprodução de uma fila histórica. Não é preciso reconstruir mutações desde
  a instalação do app.

### Login não associa dados locais a uma conta

Registrado explicitamente para que a T16.3+ não faça isso em silêncio: na T16.1, entrar, sair e
**trocar de conta** deixam Room e DataStore exatamente como estavam. Nenhuma linha ganha dono,
nenhum `syncId` é gerado, nada sobe e nada desce.

O cenário que isso protege:

```text
usuário A entra  →  sai  →  usuário B entra
        ↓
dados locais permanecem exatamente como estavam — de ninguém
```

Associar o banco existente ao usuário B automaticamente seria dar a ele o histórico do A.

**Continua valendo na T16.3, agora com identidade global no meio.** Entrar, sair e trocar de conta
não regeneram `syncId`, não gravam dono e não produzem entrada de Outbox — há teste que faz o ciclo
A → sair → B com Room de verdade e compara a identidade de cada linha antes e depois. A adoção real
é da **T16.4**.

### Sem autenticação própria

O Spark **não** terá:

- usuário/senha próprios;
- JWT emitido pelo Spark;
- refresh token próprio.

A identidade é do Firebase Auth. O backend é um *verificador* de token, não um emissor. Reduz
superfície de ataque e não há nada aqui que justifique manter um sistema de credenciais.

---

## Conta continua opcional

Nada neste contrato torna a conta obrigatória.

| Sem conta | Com conta |
| --- | --- |
| Room funciona | tudo da coluna anterior |
| treino, execução, histórico funcionam | + backup |
| templates e catálogo funcionam | + restore |
| gamificação funciona | + sync multi-device |
| `syncId` é gerado normalmente e fica guardado localmente | + features online |
| dado local é `LOCAL_UNOWNED` | + adoção explícita (T16.4) |

O `syncId` é gerado mesmo sem conta. Isso é deliberado: quando o usuário eventualmente criar uma
conta, os dados que já existem no aparelho **já têm identidade global** e podem ser enviados sem
precisar reconciliar nada.

---

## Cronograma

| Conceito | Documentado | Implementado |
| --- | --- | --- |
| `localId` | — (já existe) | já existe |
| `canonicalExerciseId` | — (já existe) | já existe |
| `AuthenticatedPrincipal` (uid do token verificado) | T16.0 | **T16.1 — feito** |
| `syncId` nas entidades Room | T16.0 | **T16.3 — feito** |
| `deviceId` | T16.0 | **T16.3 — feito** |
| `clientMutationId` | T16.0 | **T16.3 — feito** |
| Contrato `LOCAL_UNOWNED` / adoção explícita | T16.0 | **T16.3 — feito (contrato)** |
| `ownerUid` gravado em dado pessoal remoto | T16.0 | T16.4 |
| Adoção real (primeiro backup) | T16.0 | T16.4 |
| Registro de dispositivos no servidor | T16.0 | T16.6 |
