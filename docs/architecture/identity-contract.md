# Contrato de identidade global do Spark

- **Tarefa:** T16.0 (documentação) — implementação em **T16.1** (identidade da conta), **T16.3**
  (identidade dos dados), **T16.6** (identidade em movimento), **T16.7** (identidade de uma
  exclusão) e **T16.7.1** (revalidação de conta depois de uma resposta remota).
- **Status (verificado em 2026-09-07):**
  - **implementado na T16.1:** a identidade da **conta**. O Firebase UID chega ao servidor por
    Firebase ID Token, é verificado pelo Admin SDK e vira `AuthenticatedPrincipal { uid }`;
  - **implementado na T16.3:** a identidade dos **dados**. `syncId` nas raízes de agregado
    pessoais (Room `version = 31`), `deviceId` no DataStore e `clientMutationId` por mutação na
    Outbox;
  - **implementado na T16.4:** o `ownerUid` persistido. O conjunto de dados local ganha dono por
    adoção explícita (`cloud_data_binding`), e o servidor guarda snapshots sob o `uid` do token
    verificado (`backup_snapshots`);
  - **implementado na T16.5:** o caminho de volta, por ação explícita. Um snapshot escolhido pelo
    usuário substitui o dataset local (Room `version = 33`), preservando `syncId` e **regenerando**
    `localId` — as relações são reconstruídas por identidade portátil, e nada depende do `localId`
    do aparelho de origem. Um restore bem-sucedido também dá dono a um dataset que não tinha, e
    **nunca** transfere um dataset de uma conta para outra;
  - **implementado na T16.6:** a identidade em movimento. `clientMutationId` passou a ser a chave
    de idempotência do servidor (`sync_mutations`), `deviceId` viaja como metadado de origem e não
    autoriza nada, `syncId` é a identidade do agregado em `sync_entities` e no change log, e
    `ownerUid` continua saindo **só** do token verificado — o corpo do push não tem campo de dono,
    e um campo desses recusa a requisição inteira. Room `version = 34`;
  - **implementado na T16.7:** o tombstone e a identidade de uma exclusão — a exclusão é uma
    mudança versionada da mesma identidade (`sync_entities.deleted`), e recriar usa `syncId` novo.
    Room `version = 35`;
  - **implementado na T17.0:** a identidade **pública**. `socialId` (UUID v4) e `friendCode`
    (`SPK-` + 8 símbolos de CSPRNG) nascem no servidor, são únicos por constraint de banco e são
    imutáveis — inclusive através de desativar/reativar. Eles não derivam do Firebase UID, do
    e-mail nem do nome, e o cliente não os propõe: um `ownerUid`/`socialId`/`friendCode` no corpo
    recusa a requisição inteira. Ver [`social-domain.md`](./social-domain.md);
  - **implementado na T16.7.1:** a identidade **revalidada depois da resposta**. Uma leitura de
    estado atual (`GET /v1/sync/entities/...`) devolve o `ownerUid` que o servidor autenticou
    naquela requisição, e o aparelho só grava quando ele, o dono do dataset local
    (`cloud_data_binding`) e a sessão do Firebase **neste instante** são a mesma conta. O `uid`
    capturado antes da chamada HTTP não basta: a conta pode trocar durante o voo.

---

## Os cinco identificadores

Nenhum substitui outro, e confundir dois deles é o defeito que este documento existe para evitar:

```text
Firebase UID          → quem é o usuário          (conta online)        PRIVADO
socialId              → quem é o usuário no social (T17.0)              PÚBLICO
friendCode            → como convidar essa pessoa (T17.0)               COMPARTILHÁVEL
deviceId              → qual instalação do app    (aparelho)            PRIVADO
syncId                → qual entidade             (dado pessoal, global) PRIVADO
clientMutationId      → qual alteração            (uma tentativa de mutação)
clientBackupId        → qual tentativa de backup  (T16.4)
localId               → qual linha no Room        (só dentro deste aparelho)
canonicalExerciseId   → qual exercício de catálogo (conteúdo versionado)
```

**O Firebase UID nunca é identidade pública.** Desde a T17.0 essa distinção tem consequência
concreta: quando existe uma superfície pública, ela usa `socialId` — e `email`, `localId`,
`deviceId` e `syncId` são igualmente proibidos como identificador social.

### `clientBackupId` — identidade de uma tentativa de backup (T16.4)

UUID por tentativa lógica, gerado no aparelho e **estável entre reenvios**. Não é `syncId` (que é
"qual entidade") nem `clientMutationId` (que é "qual alteração"): é "qual tentativa de proteger o
estado inteiro".

Com o `ownerUid`, é a chave de idempotência do servidor: mesma dupla + mesmo conteúdo devolve o
backup que já existe; mesma dupla + conteúdo diferente é `409`. É isso que faz uma resposta perdida
ser resolvida por reenvio em vez de virar dois backups.

Alguns agregados do backup não têm UUID e usam a identidade portátil que já lhes pertence:
`canonical:<canonicalId>` ou `custom:<syncId>` para a customização de exercício,
`week:<epochDay>` para a meta semanal, e `preferences` para as preferências do atleta. Nenhuma
delas é um `localId` disfarçado — ver [a matriz](./data-classification-matrix.md#matriz-de-backup-t164).

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

É o que permite ao servidor distinguir "reenviaram porque a resposta se perdeu" de "mudaram de
novo". Tem índice `UNIQUE` no Room, e desde a T16.6 também no servidor: `sync_mutations` tem índice
único em `(owner_uid, client_mutation_id)`, e é constraint de banco — não verificação em código —
porque duas requisições simultâneas passariam por qualquer verificação.

O contrato completo, com os desfechos possíveis, está em
[`sync-protocol.md`](./sync-protocol.md#push-incremental-t166--implementado).

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

### `socialId` e `friendCode` — identidade pública (T17.0)

As **únicas** identidades do Spark que podem aparecer para outra pessoa. As duas nascem no
servidor, e a razão é a mesma que faz o social ser server-authoritative: unicidade global é uma
pergunta que um aparelho offline não pode responder.

- **`socialId`** — UUID v4 (`crypto.randomUUID`), opaco, imutável, `UNIQUE` no banco. Não é
  sequencial: um id sequencial vazaria de graça quando o perfil foi criado e quantos existem.
- **`friendCode`** — `SPK-` + 8 símbolos de `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (31^8 ≈ 8,5 × 10^11),
  sorteados por `crypto.randomInt` (com rejeição de amostra — `% 31` enviesaria os primeiros
  símbolos). Alfabeto sem `0`, `O`, `1`, `I` e `L`, porque um código é lido em voz alta e digitado
  de uma foto. Único por `UNIQUE`; colisão é retry limitado, e esgotar é `503`, nunca um perfil sem
  código. Normalização canônica única (`normalizeFriendCode`), **reusada** pelo lookup da T17.1 — e
  amarrada à cópia de formato do Android pela fixture compartilhada
  `contracts/social/v1/friend-code-normalization.json`, lida pelos testes dos dois lados.
- **Nenhum dos dois deriva do uid, do e-mail ou do nome — nem por hash.** Derivar permitiria
  confirmar um palpite ("o e-mail X tem o código Y?"), que é a informação que eles existem para não
  carregar.
- **Nenhum dos dois autentica.** Conhecer um `socialId` ou um `friendCode` não concede permissão
  nenhuma: ownership continua saindo do Firebase ID Token verificado. O `friendCode` é um convite,
  não uma credencial.
- **Imutáveis, inclusive através do interruptor.** `disable` → `enable` devolve o mesmo par. Gerar
  identidade nova a cada toque invalidaria o código que a pessoa já distribuiu.
- **`displayName` não é identidade.** Ele não é único, e dois "Igor" podem coexistir.

### `deviceId` — identidade da instalação

Identificador da instalação do Spark. Desde a T16.6 ele viaja no push e volta no pull como
`originDeviceId`, para diagnóstico e supressão de eco.

**Ele não autoriza nada.** Conhecer o `deviceId` de outro aparelho não dá acesso a conta nenhuma:
ownership vem do Firebase UID do token verificado, e só dele. O cursor de sync, por sua vez, é por
**conta e banco local** (`sync_cursor` tem `ownerUid` como chave) — não por `deviceId`, porque o que
ele descreve é a posição **deste banco** no change log daquela conta.

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
ownership de tudo que a requisição escreve ou lê   [T16.4+ — implementado]
```

O caminho até `AuthenticatedPrincipal` existe e é testado: `BearerAuthGuard` recusa token ausente,
malformado ou inválido com 401, distingue "não consegui verificar" com 503, e nenhum `uid` vindo de
query string, header ou corpo influencia a resposta.

O que vem depois **também** existe desde a T16.4, e cada rota de dado pessoal filtra por `ownerUid`
do principal: backup (`POST /v1/backups`, `GET /v1/backups`, `/{id}`, `/{id}/content`), sync
(`POST /v1/sync/push`, `GET /v1/sync/pull`) e, desde a **T16.7.1**, a leitura de estado atual
(`GET /v1/sync/entities/{entityType}/{entitySyncId}`). Nenhuma delas tem parâmetro de usuário.

Se o corpo da requisição trouxer um `ownerUid`, ele é **ignorado**, não validado — aceitar um
`ownerUid` "conferindo se bate com o token" já seria um caminho a mais para errar. O único `uid` que
existe no servidor é o que veio do token.

Consequências, **valendo desde a T16.4**, quando passou a existir dado pessoal remoto:

- toda leitura é filtrada por `ownerUid` do principal — `GET /v1/backups/latest` não tem parâmetro
  de usuário, e não há como pedir o backup de outra conta. O mesmo vale para o change log
  (`GET /v1/sync/pull`) e para o estado atual de um agregado (`GET /v1/sync/entities/...`,
  T16.7.1), onde uma identidade que existe para **outra** conta responde `404` — indistinguível de
  inexistente, para que a rota não vire um oráculo de existência do dado alheio;
- toda escrita grava o `ownerUid` do principal;
- uma requisição sem token válido não acessa dado pessoal nenhum;
- o contrato de backup **não tem** campo `ownerUid`. Um campo desconhecido no corpo é recusado, e
  não ignorado — não existir é melhor do que existir e ser ignorado.

Há teste que tenta as três portas dos fundos: `ownerUid` no corpo, `uid` na query string e token de
outra conta.

### Adoção explícita — o contrato `LOCAL_UNOWNED` (T16.3)

O estado padrão de todo dado local é **`LOCAL_UNOWNED`**: ele existe, tem `syncId`, e **não tem
dono remoto**. Isso é representado no código por `CloudSyncScope.Disabled`, que é o valor quando
nada foi gravado — ou seja, sempre, até a T16.4.

```text
padrão (todo aparelho nasce assim)        T16.4 — adoção explícita, implementada
CloudSyncScope.Disabled                   usuário autenticado
dado local = LOCAL_UNOWNED                      ↓ toca "Ativar backup"
Outbox não registra nada                  Spark mostra QUANTO será associado
login não muda nada                             ↓ confirma
                                          Preparing(uid) → snapshot completo sobe
                                                ↓ o servidor confirma
                                          Enabled(uid) → ownership remoto estabelecido
                                                ↓
                                          Outbox passa a registrar no escopo daquela conta
```

**Onde esse estado mora, desde a T16.4:** no Room, em `cloud_data_binding` — uma linha, um dono.
Na T16.3 ele era uma preferência do DataStore, e nunca chegou a ser gravado lá porque a adoção não
existia. Ele mudou de lugar para que estabelecer o vínculo, capturar o snapshot, ler o corte da
Outbox e criar a tentativa de backup caibam na **mesma transação**.

**O vínculo é do conjunto de dados, e é persistente.** Sair da conta não o remove, reiniciar não o
remove, e entrar com outra conta não o transfere: o resultado é um descompasso em que só os
recursos de nuvem ficam indisponíveis.

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
| `ownerUid` gravado em dado pessoal remoto | T16.0 | **T16.4 — feito** |
| Adoção real (primeiro backup) | T16.0 | **T16.4 — feito** |
| `clientBackupId` (identidade de uma tentativa de backup) | T16.4 | **T16.4 — feito** |
| `syncId` como identidade do agregado remoto (`sync_entities`) | T16.0 | **T16.6 — feito** |
| `clientMutationId` como chave de idempotência do servidor | T16.0 | **T16.6 — feito** |
| `deviceId` como origem de uma mudança (`originDeviceId`) | T16.0 | **T16.6 — feito** |
| Cursor por conta e por banco local (`sync_cursor`) | T16.0 | **T16.6 — feito** |
| Registro de dispositivos no servidor | T16.0 | não implementado — o `deviceId` é metadado, e uma tabela de dispositivos só passa a valer a pena com revogação por aparelho (T16.8) |
| Identidade de uma exclusão (tombstone) | T16.0 | **implementado na T16.7** |
| Revalidação de conta depois de uma resposta remota | T16.7.1 | **T16.7.1 — feito** |
| `socialId` — identidade social pública | T17.0 | **T17.0 — feito** |
| `friendCode` — convite/descoberta controlada | T17.0 | **T17.0 — feito** |
| Lookup por `friendCode` (match exato, rate limited) | T17.0 (documentado) | **T17.1 — feito** |
| `requestId` — identificador público de um pedido de amizade (UUID v4, opaco) | T17.1 | **T17.1 — feito** |
