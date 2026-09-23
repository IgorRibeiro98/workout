# Compartilhamento de treino e de programa — Workout Share e Program Share

- **Tarefas:** T17.7 (treino avulso) · **T19.3** (programa completo) · **T19.H2** (snapshot V2:
  treino vazio e exercício CUSTOM portátil).
- **Status (verificado em 2026-09-23):** implementado. Backend `workout-share.*` em
  `backend/src/modules/social/`, migrations `0001` (baseline, tabela `workout_shares`) e `0006`
  (`share_type`); Android `com.example.domain.social.WorkoutShare*`, `data/social/WorkoutShare*`,
  `data/repository/WorkoutShareSnapshotBuilder` e `WorkoutShareImporter`, Room `version = 38`
  (`MIGRATION_37_38`). A T19.H2 **não** exigiu migration em nenhum dos dois lados: o snapshot já
  era conteúdo versionado gravado verbatim, e o CUSTOM importado reusa `exercises`.
- **O que este documento é:** o contrato normativo do compartilhamento. Até a T19.3 as regras
  viviam só em `social-domain.md` §12 e nos comentários do código; ele passa a ser o lugar único, e
  os dois documentos apontam para cá.
- **A fixture é a amarra:** [`contracts/social/v1/workout-share-snapshot.json`](../../contracts/social/v1/workout-share-snapshot.json)
  carrega as faixas, as formas e os casos aceitos/recusados, e é lida pelo teste dos **dois** lados
  (`WorkoutShareContractTest` no Android, `workout-share-contract.spec.ts` no backend).

## 1. O princípio: cópia, nunca vínculo

```text
A possui um treino / um programa
        │  toque explícito em "Compartilhar"
        ▼
snapshot portátil e IMUTÁVEL  ──▶  Spark Backend (oferta)  ──▶  B vê a oferta
                                                                    │  toque em "Adicionar"
                                                                    ▼
                                                     B passa a possuir a PRÓPRIA cópia
```

Depois do aceite, o treino/programa de A e o de B são **objetos diferentes**, com `localId` e
`syncId` diferentes, em bancos diferentes. Não existe — e é bloqueante se aparecer — qualquer
mecanismo pelo qual uma alteração de um lado alcance o outro:

| A faz | B |
| --- | --- |
| edita o original | não muda |
| apaga o original | não muda |
| desfaz a amizade | não muda |
| bloqueia B | a cópia permanece; só ofertas `PENDING`/`ACCEPTED` são canceladas |
| exclui a conta | a cópia permanece; a oferta é purgada no servidor |

O servidor **não sabe que a cópia existe**. `complete-import` marca a oferta como `IMPORTED` para
que o remetente veja "Adicionado"; ele não registra o que foi criado, onde, nem com que identidade.
É essa ignorância que torna a cópia independente por construção, e não por disciplina.

## 2. As duas autoridades

| Recurso | Autoridade |
| --- | --- |
| A oferta (existência, estado, quem pode vê-la) | **Spark Backend** — `workout_shares` |
| O snapshot (o conteúdo da oferta) | contrato Social; gravado verbatim, nunca reinterpretado |
| Friendship / Block / exclusão de conta | Spark Backend (T17.1 / T17.6) |
| Se o conteúdo pode sair do aparelho (faixas, forma, catálogo) | **Android** — `WorkoutShareSnapshotBuilder`, fail-closed |
| O exercício `CUSTOM` importado | **Room do destinatário** — criado na transação da cópia, com `syncId` novo |
| A cópia importada | **Room** — `WorkoutRepository`, como qualquer treino criado à mão |
| Qual programa é o atual | o usuário, pela tela de Treinos (`setCurrentProgram`) |

O Social transporta; o Workout local cria. O backend Social não é autoridade operacional de nada
depois do import — e nada do compartilhamento entra em `sync_entities`, na Outbox social (que não
existe) ou no backup como oferta. O que entra na Outbox de **sync** é a cópia, como qualquer treino
ou programa criado no aparelho (T16.3).

## 3. Dois tipos, uma oferta

```text
share_type          snapshot_json                                                            criado por
WORKOUT_TEMPLATE    { snapshotVersion, name, shortIdentifier?, customExercises?, exercises[] }  T17.7 / T19.H2
WORKOUT_PROGRAM     { snapshotVersion, name, description?, customExercises?, templates[] }      T19.3 / T19.H2
```

A mesma tabela, o mesmo ciclo de vida (`PENDING → ACCEPTED → IMPORTED | DECLINED | CANCELLED |
EXPIRED`), a mesma idempotência de criação (`UNIQUE (sender_uid, client_request_id)`), as mesmas
transições CAS, o mesmo outbox de notificação (`WORKOUT_SHARE_RECEIVED`), o mesmo cancelamento por
bloqueio e a mesma linha do inventário de purge. Uma tabela paralela duplicaria seis lugares para
divergir na primeira correção — por isso a T19.3 é uma **coluna** (`0006_workout_share_type.sql`),
não um módulo.

### O que distingue um Program Share de um Workout Share

| | Workout Share (T17.7) | Program Share (T19.3) |
| --- | --- | --- |
| Ação | ícone Compartilhar na tela do treino | ícone Compartilhar na tela do programa |
| Corpo da criação | `snapshot` | `programSnapshot` |
| O que a cópia cria | 1 `WorkoutTemplate` no programa **atual** do destinatário | 1 `WorkoutProgram` novo + N `WorkoutTemplate` + exercícios |
| Posição | próximo `orderInProgram` do programa atual | `orderInProgram` do snapshot, normalizado 0..n-1 |
| `isCurrent` | n/a | **sempre `false`** — receber não troca o programa atual |
| Recibo local | `importedTemplateLocalId` | `importedProgramLocalId` |
| Teto do snapshot | 64 KiB, 1..30 exercícios | 256 KiB, 1..30 treinos, 1..30 exercícios cada |

### O discriminador é o campo presente

`POST /v1/social/workout-shares` aceita `snapshot` **ou** `programSnapshot` — nunca os dois, nunca
nenhum. Não existe `shareType` no corpo: seria uma segunda afirmação sobre o mesmo fato, e as duas
poderiam discordar. Nas respostas o tipo é explícito (`shareType`), e o conteúdo volta **no campo
do seu tipo**: um cliente anterior à T19.3 lê `snapshot` ausente numa oferta de programa e não tem
o que importar — em vez de decodificar um programa como um treino sem exercícios.

## 4. O snapshot

### O que viaja

```text
programa   name, description, customExercises?                              (V2)
treino     name, shortIdentifier, orderInProgram, scheduledDays, customExercises?  (V2, no treino avulso)
exercício  canonicalExerciseId | customExerciseRef, sortOrder, targetSets, minReps, maxReps, restDurationSeconds
CUSTOM     ref, name, primaryMuscle?, equipment?, description?               (V2 — ver §5)
```

`sortOrder` viaja **normalizado** para `0..n-1`. O valor guardado no Room do remetente pode ter
buracos (remover um exercício não renumera os outros) e o servidor limita `sortOrder` a `0..30` — o
que transformava um treino legítimo numa recusa genérica. O que a oferta transporta é a ordem, não
os números.

`scheduledDays` (T19.8) são os 0..N dias da semana do treino, como nomes canônicos de
`java.time.DayOfWeek` (`["MONDAY", "THURSDAY"]`; `[]` é "sem dia fixo"). Um app anterior à
T19.8 ainda manda `dayOfWeek` (um dia, como rótulo); o servidor aceita **uma** das duas formas por
treino, e o app lê as duas — ver `docs/architecture/workout-scheduling.md`.

### O que nunca viaja — em nenhum nível

```text
plannedWeight   machineLabel   notes            ← pessoais: são do treino de quem compartilha
localId  id     syncId         programId  templateId   ← identidade do remetente
isCurrent       externalId     contentVersion            ← estado do dono
canonicalId     slug           isUserCreated    origin   ← do catálogo/do dono, num CUSTOM (T19.H2)
customPhotoUri  mediaUrl       gifUrl   externalExerciseId  ← arquivo local e mídia (T19.H2)
WorkoutSession  PR  cargas realizadas  repetições realizadas  histórico  XP  medidas  gamificação
Firebase UID    e-mail   deviceId
```

O servidor recusa **por nome** (`FORBIDDEN_SNAPSHOT_KEYS` + allowlist estrutural em
`workout-share.validator.ts`) e o teste de privacidade varre o detalhe de uma oferta de programa com
allowlist por nível (`social-privacy-sweep.spec.ts`). No Android, `WorkoutShareSnapshotBuilderTest`
prova que o JSON que sai não contém nenhum desses nomes nem valores.

### Identidade de exercício

Um exercício **do catálogo** é identificado só pelo `canonicalId`. O nome nunca é identidade: o
importador resolve `canonicalExerciseId → ExerciseEntity.id` neste aparelho, e um id que não
resolve recusa a importação inteira (`MissingExercises`) antes de qualquer escrita. *Fuzzy matching*
não existe.

Um exercício **CUSTOM** (V2) não se resolve: ele é criado. Ver §5.

### Imutável

O snapshot é gravado verbatim na criação e devolvido verbatim no detalhe e no aceite. Não há rota
de edição. Editar o programa depois de compartilhar não altera a oferta; compartilhar de novo é
**outra** oferta, com outro `shareId`. O `snapshot_hash` (SHA-256 do texto) é o que a idempotência
de criação compara.

## 5. As duas versões de snapshot (T19.H2)

```text
V1   1..30 exercícios por treino, todos com canonicalExerciseId
V2   0..30 exercícios por treino  +  customExercises / customExerciseRef
```

### O que a V2 acrescenta

| | V1 | V2 |
| --- | --- | --- |
| treino sem exercícios | recusado | **aceito** — é um estado do Workout local, não um erro |
| exercício CUSTOM | bloqueado antes da oferta | **viaja como cópia** |
| treino vazio dentro de um programa | bloqueia o programa | aceito; o programa continua exigindo ≥ 1 treino |

A V1 **não muda**. Uma oferta `snapshotVersion: 1` é validada exatamente como sempre foi, e um
`customExercises` ou um `customExerciseRef` dentro dela recusa a oferta: a versão descreve a forma,
e uma forma que se contradiz é defeito, não flexibilidade.

### O app escreve a versão mínima

`WorkoutShareSnapshotBuilder` escolhe pela necessidade: **V2 só quando a oferta tem CUSTOM ou algum
treino vazio; V1 em todo o resto.** Não é conservadorismo gratuito — uma oferta V1 é aceita por
qualquer Spark Backend já publicado, então o caminho que já funcionava não passa a depender de um
servidor novo, e a V1 continua sendo exercitada de verdade em vez de virar um ramo morto.

### CUSTOM é snapshot, nunca referência viva

```text
remetente                          oferta                        destinatário
Exercise localId=42 syncId=X  ──▶  customExercises[0]        ──▶  Exercise localId=907 syncId=Y
isUserCreated = true               { ref: "custom-1",              isUserCreated = true
                                     name, primaryMuscle?,         canonicalId = null
                                     equipment?, description? }
```

O que viaja é **só** isso. Ficam de fora, por allowlist e por recusa nomeada: `localId`, `syncId`,
`canonicalId`, `slug`, `customPhotoUri`, `mediaUrl`, `gifUrl`, `externalExerciseId`,
`contentVersion`, `isUserCreated`, `origin` — e tudo o que já ficava de fora antes (carga, nota,
máquina, histórico). `isBodyweight` também fica: nenhum caminho do app permite marcá-lo num
exercício criado pelo usuário, então enviá-lo seria transportar uma constante.

Depois do aceite, **editar o original não alcança a cópia** — é a mesma regra do §1, agora também
para o exercício.

### `customExerciseRef` é escopado ao snapshot

`custom-1`, `custom-2`: a chave existe **dentro daquela oferta** e em nenhum outro lugar. Ela não é
derivada do `localId` nem do `syncId` do remetente, e nenhum dos dois atravessa a rede.

Ela existe por um motivo único: o mesmo CUSTOM usado em três treinos do mesmo programa precisa
chegar ao destinatário como **uma** cópia referenciada três vezes, e não como três exercícios
iguais. Por isso `customExercises` mora na raiz do snapshot — do treino ou do **programa inteiro** —
e nunca dentro de um treino.

O formato diz em voz alta que ela não é identidade global: não é UUID, não é slug, e o servidor
recusa qualquer outra forma (`^custom-[0-9]{1,3}$`).

### Exatamente uma identidade por exercício

Cada item de `exercises` traz `canonicalExerciseId` **ou** `customExerciseRef` — nunca os dois,
nunca nenhum. Os dois juntos seriam duas afirmações sobre o mesmo exercício; nenhum deixaria quem
recebe sem saber o que criar. Toda `ref` usada precisa existir em `customExercises`, e toda entrada
de `customExercises` precisa ser usada por alguém: conteúdo que ninguém referencia é peso escolhido
pelo remetente dentro da folga do teto.

### O que ainda bloqueia antes da oferta

`WorkoutShareSnapshotBuilder` continua sendo fail-closed, e agora cobre **todas** as regras do
servidor — nome, sigla, quantidade de exercícios, faixa de `sortOrder`, forma do id canônico, além
das faixas de série/repetição/descanso da H1.2. Cada recusa nomeia o treino, o exercício e o campo,
porque "o servidor recusou o conteúdo" não diz o que corrigir.

Uma linha do catálogo **sem** `canonicalId` continua bloqueando, com o nome do exercício: ela não é
CUSTOM (ninguém a criou), é uma anomalia, e transformá-la em cópia reclassificaria o dado de alguém
em silêncio.

### O que a sala de treino em dupla não herdou

`MultiplayerWorkoutStarter` usa o mesmo construtor de snapshot, mas o contrato da sala (T19.5)
transporta **só** referência canônica: o outro aparelho resolve cada exercício no catálogo dele. Um
treino com CUSTOM — ou vazio — é recusado ali, com o motivo. Adivinhar criaria um treino em dupla
com um exercício que só existe de um lado.

## 6. O fluxo de aceite

```text
recibo local?  ──sim──▶  AlreadyImported            (funciona offline)
      │ não
      ▼
POST :shareId/accept     ← o servidor revalida bloqueio, cancelamento, expiração, amizade,
      │                    transiciona PENDING → ACCEPTED (CAS) e devolve o conteúdo IMUTÁVEL
      ▼
transação Room           ← programa/treino + exercícios + recibo: tudo, ou nada
      │
      ▼
POST :shareId/complete-import   (best effort)
```

Três pontos que a T19.3 estabeleceu e que valem para os dois tipos:

- **O aceite é servidor-primeiro.** Até a T19.3 o app importava a partir do `GET :shareId` e nunca
  chamava `accept`: o estado no servidor ficava `PENDING` para sempre, `complete-import` respondia
  400 em silêncio, e um cancelamento ou bloqueio posterior à leitura do detalhe não impedia a
  importação. Agora é o servidor quem decide se a oferta ainda existe para este destinatário — e a
  cópia é construída sobre o conteúdo que **ele** devolve, nunca sobre o que a tela tinha.
- **`accept` é idempotente em `ACCEPTED` e em `IMPORTED`** e devolve o detalhe inteiro. Toque
  duplo, retry depois de resposta perdida e reabrir uma oferta cuja importação local falhou
  (exercício ausente no catálogo, por exemplo) passam pelo aceite de novo e recebem o mesmo
  conteúdo. Quem impede a segunda cópia é o recibo local.
- **A importação é uma transação** (`WorkoutRepository.addProgramWithTemplates` /
  `addTemplateWithExercises`): o recibo é gravado **dentro** dela, e lançar dali desfaz tudo. Nunca
  "programa criado + 2 de 4 treinos". `WorkoutShareImporterTest` injeta a falha no recibo e afirma
  o estado do banco.

### Idempotência, em três camadas

| Camada | Garantia |
| --- | --- |
| tela | `importingShareId`: um toque repetido durante a importação não faz nada |
| servidor | `accept` repetível; `UNIQUE (sender_uid, client_request_id)` na criação |
| Room | `workout_share_import_receipts.shareId` é chave primária; o recibo nasce na mesma transação da cópia |

Só a terceira sobrevive ao processo morrer — e é a que decide.

Desde a T19.H2 os exercícios CUSTOM criados entram **na mesma transação**, pelo mesmo motivo: um
retry depois de um crash não pode deixar duas cópias do mesmo exercício. Dentro da transação, a
`ref` é a chave de deduplicação — o mesmo CUSTOM referenciado por três treinos do programa produz
**uma** linha em `exercises`, e as três posições apontam para ela.

## 7. Identidade da cópia

```text
remetente                      destinatário
Program  syncId = P1     ──▶   Program  syncId = P9   (novo, gerado no aparelho de B)
Template syncId = T1     ──▶   Template syncId = T8
Template syncId = T2     ──▶   Template syncId = T10
Exercise syncId = X (CUSTOM)  ──▶  Exercise syncId = Y   (T19.H2 — um por `ref`, não por posição)
```

`localId` é o que o Room atribuir; `syncId` é `SyncIds.random()` na construção da entidade — os
valores do remetente nunca chegam ao aparelho (o snapshot não os carrega) e nunca são reutilizados.
Se a nuvem estiver ativa (T16.4), a cópia entra na Outbox de sync como criação local — o programa
**antes** dos treinos, na mesma mutação, porque o outro aparelho precisa do programa para aplicar
os treinos que o referenciam.

## 8. O que o bloqueio, a exclusão de conta e a troca de conta fazem

- **Bloqueio antes do aceite:** o servidor cancela ofertas `PENDING`/`ACCEPTED` entre o par (T17.6)
  e responde `404` para detalhe e aceite; a lista não as mostra. `importShare` recebe `Rejected` e
  nada é escrito.
- **Bloqueio depois do aceite:** a cópia permanece. Uma oferta `IMPORTED` não é tocada pelo
  bloqueio, e mesmo uma `ACCEPTED` cancelada por ele não alcança o Room de ninguém.
- **Exclusão da conta do remetente:** a oferta é purgada com a conta (`account-uid-inventory.ts`);
  a cópia do destinatário é dele.
- **Troca de conta:** ofertas são account-scoped. `SharedWorkoutsViewModel` limpa a tela **antes**
  de a leitura da conta nova sair e descarta a resposta de qualquer requisição — lista, detalhe,
  importação, recusa, cancelamento — iniciada pela conta anterior. O programa importado pertence ao
  dataset local, como qualquer programa criado no aparelho (T16.4 decide de quem é o dataset).

## 8.1 O corpo que sai do aparelho (T19.H2 / H2.6)

Até a T19.H2, **todo** compartilhamento de treino e de programa era recusado com
`INVALID_SNAPSHOT`, e nenhum teste ficava vermelho.

```text
o que o app mandava                       o que o servidor lia
{"recipientSocialId":…,"clientRequestId":…,   snapshot.snapshotVersion === undefined
 "snapshot":{"name":…,"exercises":[…]}}       → "Versão do snapshot não suportada: undefined."
```

A causa não estava em nenhum DTO: `kotlinx.serialization` **não escreve** um campo cujo valor é
igual ao default declarado na `data class`. `snapshotVersion` valia `1` e o default era `1` — o
campo simplesmente não existia no JSON. Os testes do Android afirmavam sobre objetos Kotlin; os do
backend montavam o corpo à mão em TypeScript. Ninguém confrontava o **texto**.

A correção tem três partes, e a terceira é a que impede o retorno:

1. `WorkoutShareWireFormat` (`encodeDefaults = true`) é o único lugar onde o JSON da rede é
   configurado, com o motivo escrito ao lado;
2. `WorkoutShareSnapshotBuilder` passou a checar **todas** as regras do servidor antes da
   requisição, nomeando o campo — a H1.2 tinha feito isso só para série/repetição/descanso;
3. `WorkoutShareWireFormatTest` afirma o **texto exato** do corpo, e
   `workout-share-contract.spec.ts` + `WorkoutShareContractTest` leem a mesma fixture dos dois
   lados.

`explicitNulls = false` continua desligado, e isso é contrato: o campo ausente de um par exclusivo
(`snapshot`/`programSnapshot`, `canonicalExerciseId`/`customExerciseRef`) não pode virar `null` no
JSON, porque o servidor lê "presente" por nome, e não por valor.

## 9. Offline

| Ação | Sem rede |
| --- | --- |
| criar oferta | falha clara ("Sem conexão. O programa não foi enviado"); nada fica pendente |
| aceitar | falha clara; nada é escrito; a prévia continua aberta para tentar de novo |
| já importado | responde pelo recibo local, sem rede |
| usar o programa importado | funciona inteiro — é um programa como outro qualquer |

Não existe Outbox social. Uma oferta não é enfileirada para "enviar depois".

## 10. Compatibilidade

- **Cliente anterior à T19.3 contra o servidor novo:** uma oferta de programa aparece na lista com
  o nome do programa em `templateName` e a soma dos exercícios em `exerciseCount`; o detalhe chega
  sem `snapshot`, então a prévia mostra zero exercícios e o botão "Adicionar" não faz nada. Recusar
  funciona. Nada errado é criado.
- **Cliente novo contra um servidor anterior à T19.3:** `shareType` ausente é lido como
  `WORKOUT_TEMPLATE`; o app não oferece compartilhar programa se o servidor recusar
  `programSnapshot` (400 → mensagem de recusa).
- **Cliente T19.H2 contra um servidor anterior a ela:** uma oferta sem CUSTOM e sem treino vazio é
  escrita em **V1** e funciona igual. Uma oferta V2 é recusada com `INVALID_SNAPSHOT` ("versão do
  snapshot não suportada") até o backend ser publicado — ordem de deploy: **backend antes do app**
  para as capacidades novas; o resto não depende disso.
- **Ofertas V1 pendentes continuam importáveis**, para sempre. Nada foi reescrito.
- **Um tipo que esta versão não conhece:** conteúdo `null`, prévia diz "atualize o app", botão de
  importar escondido.
- `POST :shareId/accept` devolve o detalhe (`WorkoutShareDetailDto`) desde a T19.3; antes devolvia
  o snapshot de treino cru. Nenhum cliente publicado consumia a resposta.

## 11. Logs

Servidor: `shareId`, `shareType`, `snapshotVersion`, `templateCount`, `exerciseCount`, evento e
desfecho. Nunca nome de programa, de treino, de exercício CUSTOM, `displayName`, `socialId`, uid
completo ou o snapshot. Android: nada — o pacote social não registra log, e a ausência é testada.

## 12. Fora de escopo — e por quê

Live sync entre cópias, colaboração, edição compartilhada, marketplace, busca pública, programas
oficiais, ratings, comentários em programa, fork graph, versionamento colaborativo, foto de
exercício CUSTOM na nuvem, compartilhamento de histórico/PR/cargas, mudança automática de programa
atual, treino em dupla. Cada um deles quebraria a frase do §1: **compartilhar não é compartilhar
propriedade viva**.

A portabilidade de CUSTOM saiu desta lista na T19.H2 — e saiu **como cópia** (§5), que é a única
forma que não a quebra.

## 13. Onde cada coisa mora

```text
contracts/social/v1/workout-share-snapshot.json   a fixture que os dois lados leem

backend/src/modules/social/
  workout-share.contract.ts     tipos, WORKOUT_SHARE_TYPES, SharedCustomExerciseV2, DTOs
  workout-share.validator.ts    envelope estrito: snapshot XOR programSnapshot, allowlists
  workout-share.service.ts      regras semânticas por versão e por tipo, aceite idempotente, tetos
  workout-share.repository.ts   share_type, listagens derivadas por tipo
backend/migrations/postgres/0006_workout_share_type.sql
backend/test/program-share.spec.ts · workout-share.spec.ts · workout-share-atomicity.spec.ts
         · workout-share-contract.spec.ts   (a fixture compartilhada)

app/src/main/java/com/example/
  domain/social/WorkoutShare.kt                 SharedProgramSnapshot, SharedCustomExerciseSnapshot, WorkoutShareContent
  data/social/WorkoutShareSnapshotLimits.kt     as faixas e as formas + WorkoutShareWireFormat (o Json da rede)
  data/social/WorkoutShareDtos.kt               snapshot XOR programSnapshot; conteúdo lido pelo shareType
  data/repository/WorkoutShareSnapshotBuilder   buildSnapshot / buildProgramSnapshot (V1 quando basta, V2 quando precisa)
  data/repository/WorkoutShareImporter          acceptAndImport → importShare / importProgramShare (+ CUSTOM)
  data/repository/WorkoutRepository             addTemplateWithExercises / addProgramWithTemplates / NewCustomExercise
  data/local/WorkoutShareImportReceiptEntity    importedTemplateLocalId | importedProgramLocalId
  presentation/workouts/ProgramDetailsViewModel prepareShare (monta o snapshot a partir do Room)
  presentation/friends/ShareWorkoutDialog       o mesmo diálogo para treino e programa
  presentation/friends/SharedWorkoutsViewModel  aceite servidor-primeiro, escopo de conta
```
