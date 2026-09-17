# Compartilhamento de treino e de programa — Workout Share e Program Share

- **Tarefas:** T17.7 (treino avulso) · **T19.3** (programa completo).
- **Status (verificado em 2026-09-16):** implementado. Backend `workout-share.*` em
  `backend/src/modules/social/`, migrations `0001` (baseline, tabela `workout_shares`) e `0006`
  (`share_type`); Android `com.example.domain.social.WorkoutShare*`, `data/social/WorkoutShare*`,
  `data/repository/WorkoutShareSnapshotBuilder` e `WorkoutShareImporter`, Room `version = 38`
  (`MIGRATION_37_38`).
- **O que este documento é:** o contrato normativo do compartilhamento. Até a T19.3 as regras
  viviam só em `social-domain.md` §12 e nos comentários do código; ele passa a ser o lugar único, e
  os dois documentos apontam para cá.

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
| Se o conteúdo pode sair do aparelho (CUSTOM) | **Android** — `WorkoutShareSnapshotBuilder` |
| A cópia importada | **Room** — `WorkoutRepository`, como qualquer treino criado à mão |
| Qual programa é o atual | o usuário, pela tela de Treinos (`setCurrentProgram`) |

O Social transporta; o Workout local cria. O backend Social não é autoridade operacional de nada
depois do import — e nada do compartilhamento entra em `sync_entities`, na Outbox social (que não
existe) ou no backup como oferta. O que entra na Outbox de **sync** é a cópia, como qualquer treino
ou programa criado no aparelho (T16.3).

## 3. Dois tipos, uma oferta

```text
share_type          snapshot_json                                            criado por
WORKOUT_TEMPLATE    { snapshotVersion, name, shortIdentifier?, exercises[] }  T17.7
WORKOUT_PROGRAM     { snapshotVersion, name, description?, templates[] }      T19.3
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
programa   name, description
treino     name, shortIdentifier, orderInProgram, scheduledDays
exercício  canonicalExerciseId, sortOrder, targetSets, minReps, maxReps, restDurationSeconds
```

`scheduledDays` (T19.8) são os 0..N dias da semana do treino, como nomes canônicos de
`java.time.DayOfWeek` (`["MONDAY", "THURSDAY"]`; `[]` é "sem dia fixo"). Um app anterior à
T19.8 ainda manda `dayOfWeek` (um dia, como rótulo); o servidor aceita **uma** das duas formas por
treino, e o app lê as duas — ver `docs/architecture/workout-scheduling.md`.

### O que nunca viaja — em nenhum nível

```text
plannedWeight   machineLabel   notes            ← pessoais: são do treino de quem compartilha
localId  id     syncId         programId  templateId   ← identidade do remetente
isCurrent       externalId     contentVersion            ← estado do dono
WorkoutSession  PR  cargas realizadas  repetições realizadas  histórico  XP  medidas  gamificação
Firebase UID    e-mail   deviceId
```

O servidor recusa **por nome** (`FORBIDDEN_SNAPSHOT_KEYS` + allowlist estrutural em
`workout-share.validator.ts`) e o teste de privacidade varre o detalhe de uma oferta de programa com
allowlist por nível (`social-privacy-sweep.spec.ts`). No Android, `WorkoutShareSnapshotBuilderTest`
prova que o JSON que sai não contém nenhum desses nomes nem valores.

### Identidade de exercício

Um exercício é identificado **só** pelo `canonicalId` do catálogo. O nome nunca é identidade: o
importador resolve `canonicalExerciseId → ExerciseEntity.id` neste aparelho, e um id que não
resolve recusa a importação inteira (`MissingExercises`) antes de qualquer escrita. *Fuzzy matching*
não existe.

### Imutável

O snapshot é gravado verbatim na criação e devolvido verbatim no detalhe e no aceite. Não há rota
de edição. Editar o programa depois de compartilhar não altera a oferta; compartilhar de novo é
**outra** oferta, com outro `shareId`. O `snapshot_hash` (SHA-256 do texto) é o que a idempotência
de criação compara.

## 5. CUSTOM bloqueia, antes da oferta

Um exercício `isUserCreated` ou sem `canonicalId` bloqueia o compartilhamento — do treino, e, na
T19.3, do **programa inteiro**, nomeando o treino e o exercício. A decisão é do aparelho
(`WorkoutShareSnapshotBuilder`), fail-closed, antes de existir requisição: o servidor não conhece o
catálogo e só valida a forma do identificador. Um treino sem exercícios também bloqueia o programa.

Portabilidade de exercício CUSTOM é tarefa futura, com contrato próprio. Não é resolvida aqui.

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

## 7. Identidade da cópia

```text
remetente                      destinatário
Program  syncId = P1     ──▶   Program  syncId = P9   (novo, gerado no aparelho de B)
Template syncId = T1     ──▶   Template syncId = T8
Template syncId = T2     ──▶   Template syncId = T10
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
- **Um tipo que esta versão não conhece:** conteúdo `null`, prévia diz "atualize o app", botão de
  importar escondido.
- `POST :shareId/accept` devolve o detalhe (`WorkoutShareDetailDto`) desde a T19.3; antes devolvia
  o snapshot de treino cru. Nenhum cliente publicado consumia a resposta.

## 11. Logs

Servidor: `shareId`, `shareType`, `snapshotVersion`, `templateCount`, `exerciseCount`, evento e
desfecho. Nunca nome de programa, de treino, `displayName`, `socialId`, uid completo ou o
snapshot. Android: nada — o pacote social não registra log, e a ausência é testada.

## 12. Fora de escopo — e por quê

Live sync entre cópias, colaboração, edição compartilhada, marketplace, busca pública, programas
oficiais, ratings, comentários em programa, fork graph, versionamento colaborativo, portabilidade de
CUSTOM, compartilhamento de histórico/PR/cargas, mudança automática de programa atual, treino em
dupla. Cada um deles quebraria a frase do §1: **compartilhar não é compartilhar propriedade viva**.

## 13. Onde cada coisa mora

```text
backend/src/modules/social/
  workout-share.contract.ts     tipos, WORKOUT_SHARE_TYPES, DTOs
  workout-share.validator.ts    envelope estrito: snapshot XOR programSnapshot, allowlists
  workout-share.service.ts      regras semânticas por tipo, aceite idempotente, tetos
  workout-share.repository.ts   share_type, listagens derivadas por tipo
backend/migrations/postgres/0006_workout_share_type.sql
backend/test/program-share.spec.ts · workout-share.spec.ts · workout-share-atomicity.spec.ts

app/src/main/java/com/example/
  domain/social/WorkoutShare.kt                 SharedProgramSnapshot, WorkoutShareContent, WorkoutShareKind
  data/social/WorkoutShareDtos.kt               snapshot XOR programSnapshot; conteúdo lido pelo shareType
  data/repository/WorkoutShareSnapshotBuilder   buildSnapshot / buildProgramSnapshot (CUSTOM fail-closed)
  data/repository/WorkoutShareImporter          acceptAndImport → importShare / importProgramShare
  data/repository/WorkoutRepository             addTemplateWithExercises / addProgramWithTemplates
  data/local/WorkoutShareImportReceiptEntity    importedTemplateLocalId | importedProgramLocalId
  presentation/workouts/ProgramDetailsViewModel prepareShare (monta o snapshot a partir do Room)
  presentation/friends/ShareWorkoutDialog       o mesmo diálogo para treino e programa
  presentation/friends/SharedWorkoutsViewModel  aceite servidor-primeiro, escopo de conta
```
