# Spark Social — visão geral do domínio

- **Tarefas:** T17.0 → T17.13 (fechamento do Social V2).
- **Status (verificado em 2026-09-09):** implementado e auditado. Migrations `0007`–`0020`,
  módulo `backend/src/modules/social/`, módulo `backend/src/modules/account-deletion/`, pacotes
  `com.example.data.social`, `com.example.domain.social`, `com.example.presentation.friends` e
  `com.example.service` (push) no Android.
- **O que este documento é:** o mapa. Ele explica como as peças se encaixam e **para onde ir**
  quando a pergunta é um contrato específico. Ele não repete os contratos — cada um tem o seu
  documento, e duplicá-los aqui criaria duas versões que divergem no primeiro ajuste.

## Índice de contratos

| Assunto | Documento |
| --- | --- |
| Identidade pública, privacidade, ativação | [`social-domain.md`](./social-domain.md) |
| Identidade de infraestrutura (Firebase UID, ownership) | [`identity-contract.md`](./identity-contract.md) |
| Amizade, pedidos, `friendCode`, QR | [`friendship-contract.md`](./friendship-contract.md) |
| Perfil social e projeção de progresso | [`social-profile-contract.md`](./social-profile-contract.md) |
| Desafios, pontuação e ciclo de vida | [`challenge-domain.md`](./challenge-domain.md) |
| Atividade e rankings | [`social-activity-ranking.md`](./social-activity-ranking.md) |
| Notificações push | [`social-notifications.md`](./social-notifications.md) |
| Squads privados, posse, convites e feed de grupo | [`social-groups.md`](./social-groups.md) |
| Audiência de interação (`FRIEND` / `GROUP`) | [`social-interaction-audience.md`](./social-interaction-audience.md) |
| Classificação de dado por sensibilidade | [`data-classification-matrix.md`](./data-classification-matrix.md) |
| Arquitetura online (por que existe backend) | [`ADR-0001-spark-online-architecture.md`](./ADR-0001-spark-online-architecture.md) |
| Sync do treino (T16) | [`sync-protocol.md`](./sync-protocol.md) |

Bloqueio, denúncia, exclusão de conta (T17.6), compartilhamento de treino (T17.7), check-ins e
Feed (T17.8) e conteúdo do check-in (T17.9) **não têm documento próprio**: as regras normativas
deles estão em `PROJECT_RULES.md` §13.14 e §13.15 e nos comentários dos arquivos citados abaixo.
Este documento é o índice para eles.

### O que mudou no Social V2 (T17.11 / T17.12)

O Social V1 (T17.0–T17.10) tinha **uma** audiência: a relação direta. O V2 acrescenta a segunda —
o Squad — e a torna explícita nas interações:

```text
WorkoutCheckIn                    ← continua sendo UMA publicação, nunca duplicada
   ├── FRIEND    → reações e comentários FRIEND
   ├── GROUP(X)  → reações e comentários GROUP(X)
   └── GROUP(Y)  → reações e comentários GROUP(Y)
```

Nenhuma interação atravessa audiências, e nenhuma audiência é concedida pelo cliente. As duas
seções novas deste documento são a §14 (Squads) e a §15 (audiências); o detalhe normativo está nos
dois documentos indexados acima.

---

## 1. As duas autoridades

A frase que define o sistema inteiro:

```text
TREINO                                    SOCIAL
local-first                               server-authoritative

Room (autoridade)                         Spark Backend (autoridade)
  ↓                                         ↑
Outbox / sync_entities (T16)              HTTP, a cada leitura
  ↓                                         ↑
VPS                                       cache em memória, nunca Room
```

O treino funciona **inteiro** sem rede, sem conta e sem Social: abrir, executar, registrar séries,
concluir, histórico, evolução e gamificação. O Social é opcional em todas as camadas — uma mudança
que torne o Social necessário para qualquer uma dessas coisas está errada.

Na direção oposta, o Social **nunca** é autoridade de treino, e nada dele entra no protocolo de
sync. As quatro coisas que isso proíbe, e que são bloqueantes se aparecerem:

- um banco Room de dados sociais;
- uma Outbox social no Android;
- estado social persistido como "cache canônico";
- `WorkoutShare` ou `CheckIn` dentro de `sync_entities`.

O Social **lê** o domínio canônico de treino por um adapter estreito e único —
`CanonicalTrainingSource` — que devolve **escalares**, nunca `payload`. Contar sessões concluídas
é responder uma pergunta; ler o payload seria abrir uma porta.

## 2. Identidade — três coisas diferentes

```text
Firebase UID   →  identidade privada de infraestrutura. Nunca sai em DTO. Nunca em log completo.
socialId       →  identidade pública (UUID v4, do servidor). É o que circula entre amigos.
friendCode     →  descoberta controlada (SPK- + 8 chars). Só do dono, e só na tela dele.
```

Nenhuma deriva das outras, nem por hash. O cliente não propõe nenhuma. Não existe busca por nome,
por e-mail, listagem global, feed público nem ranking global — a única descoberta é igualdade
exata de `friendCode` sobre índice único, com teto próprio por conta.

## 3. Política de acesso — um lugar só

Duas classes decidem visibilidade, e nenhum controller consulta `friendships` ou `social_blocks`
por conta própria (há teste estrutural que varre os controllers atrás de `SELECT`):

- `SocialAccessPolicy` — descoberta, perfil, atividade, recebimento de pedido;
- `workout-checkin.access-policy.ts` — Feed, detalhe, mídia, reações, comentários e denúncia.

A segunda é **SQL**, e de propósito: a audiência é uma CTE na cláusula `FROM`, então não existe
caminho em que uma linha inelegível chegue a ser materializada em JavaScript.

```text
autores visíveis = { viewer } ∪ { amigos diretos atuais ∧ perfil ACTIVE ∧ ¬bloqueado }
```

Avaliada **a cada leitura**. É isso que faz `unfriend`, bloqueio e desativação serem revogações
imediatas: não há cache para invalidar, porque não há cache.

Desde a T17.11/T17.12 a mesma classe exporta os fragmentos que descrevem o caminho de **Squad**, e
Feed de grupo, detalhe, mídia, reações, comentários e denúncia usam literalmente os mesmos — nunca
uma cópia:

| Fragmento | Responde |
| --- | --- |
| `VIEWER_SCOPE_CTE` | quem o viewer alcança por relação direta |
| `interactionVisibleSql` | a interação `FRIEND` é visível para este viewer? |
| `groupShareVisibleSql` | este check-in chegou ao viewer por um Squad (share + ambos membros + ¬bloqueio)? |
| `viewerInActiveGroupSql` | o viewer é membro ativo **deste** Squad? |
| `groupInteractionVisibleSql` | a interação `GROUP` é visível para este viewer? |

```text
canView = SELF ∨ FRIEND ∨ GROUP        (grant nesta ordem de precedência)
GROUP   = Squad ACTIVE ∧ share explícito ∧ viewer membro ∧ autor membro ∧ ¬bloqueio
```

`WorkoutCheckInContextResolver` é o **único** lugar que transforma o contexto proposto pela tela na
audiência autorizada. Um `groupId` que não confere é `404` — nunca cai para `FRIEND` (fail-closed).

### Matriz de acesso

| Superfície | Próprio | Amigo | Não-amigo | Bloqueado | Social desativado |
| --- | --- | --- | --- | --- | --- |
| `GET /social/me` | completo (inclui `friendCode`) | — | — | — | `{ enabled: false }` |
| Perfil do amigo | — | campos consentidos | 404 | 404 | 404 |
| Atividade (14 d) | — | se `activitySharingEnabled` | ausente | ausente | ausente |
| Ranking (7 d) | se participa | se ambos participam | ausente | ausente | ausente |
| Desafio | criador/participante | por convite | 404 | política T17.6 | participação encerrada |
| Workout Share | remetente | destinatário | 404 | bloqueado | 404 |
| Feed / check-in | sempre | sim | 404 | 404 | some dos outros |
| Mídia (`/media/{id}`) | sim | sim | 404 | 404 | 404 |
| Reação / comentário | sim | sim | 404 | invisível ao par | 404 |
| Denúncia | não sobre si | sobre alvo visível | 404 | 404 | 404 |

### Matriz de acesso do Social V2 — por contexto

Quatro papéis distintos, e a diferença entre as duas colunas do meio é a fase inteira:

| Viewer | Friend Feed | Squad Feed (X) | Mídia | Comentar | Reagir | Moderar |
| --- | --- | --- | --- | --- | --- | --- |
| Autor | sim | sim (se compartilhou) | sim | ambas audiências | ambas audiências | própria publicação |
| Amigo direto | sim | só se for membro | sim | `FRIEND` | `FRIEND` | não |
| Membro de X **sem** amizade | **não** | sim | sim, via `GROUP(X)` | `GROUP(X)` | `GROUP(X)` | não |
| Não-membro / não-amigo | não | 404 | 404 | 404 | 404 | não |
| Bloqueado (qualquer papel) | não | conteúdo do par some | 404 | 404 | 404 | não |
| Social desativado | não | 404 | 404 | 404 | 404 | não |
| Dono do Squad X | como amigo | sim | sim | `GROUP(X)` | `GROUP(X)` | **só** `GROUP(X)` |

O dono de X não modera comentário `FRIEND` nem comentário de `GROUP(Y)` — privilégio de grupo é
contextual, e não atravessa audiência.

"404" é sempre a **mesma** resposta que "não existe". Distinguir transformaria a rota num oráculo
de existência.

## 4. Bloqueio — autorização por viewer, nunca exclusão global

Bloquear remove a amizade, cancela pedidos pendentes e convites de desafio, e retira participações
abertas — tudo em uma transação. Desbloquear **não restaura** nada disso: amizade, pedido,
participação e compartilhamento não voltam sozinhos.

O caso que define o desenho é o post de terceiro. A e B são amigos de C; A bloqueia B; os dois
comentaram no post de C:

- **C continua vendo os dois.** Nada foi apagado.
- **A não vê a interação de B**, e vice-versa — inclusive nas **contagens**. Um `COUNT(*)` global
  vazaria a participação de quem o bloqueio existe para esconder: "🔥 5" contra "🔥 4" é
  informação sobre quem está no mesmo lugar que quem.

O Android **não recalcula contagem nenhuma**. Toda contagem chega pronta, já filtrada para aquele
viewer.

## 5. Denúncia

`targetType ∈ USER | CHECKIN | COMMENT`. Não existe `MEDIA`: a foto pertence ao check-in.

O cliente diz **o quê**; o servidor descobre **de quem** — `reportedUid` é recusado por nome.
Denunciar exige conseguir ver o alvo, não permite o próprio conteúdo, não pune, não oculta e não
notifica ninguém. Há anti-duplicata por alvo e teto de 5 por dia por conta.

Retenção: a linha de denúncia **sobrevive** à exclusão do conteúdo denunciado (não há FK para o
alvo) e é removida com a exclusão de conta de qualquer uma das duas partes. É deliberado: uma
denúncia cujo conteúdo o autor apagou é justamente a que a revisão precisa ver.

## 6. Desafios

- O cliente **nunca** envia `score`, `progress`, `rank`, `winner`, `points` — recusados por nome.
- A pontuação é **derivada na leitura** de `sync_entities`, por `ChallengeProgressSource`. Não
  existe coluna de placar nem contador incremental: um treino do período pode chegar **depois** do
  fim, e um contador exigiria correção retroativa.
- `startedAt`, nunca `finishedAt` — a atribuição de treino a dia é a do `ConsistencyCalculator`.
- Ciclo de vida **derivado** do relógio contra a janela, sem cron.
- Empate é empate: *competition ranking* (1, 1, 3).

Detalhes em [`challenge-domain.md`](./challenge-domain.md).

## 7. Atividade e ranking

Projeção efêmera em tempo de leitura, sem tabela de placar. Consentimentos **independentes** entre
si e independentes do Feed:

| Interruptor | Controla | Não controla |
| --- | --- | --- |
| `activitySharingEnabled` | dias de treino visíveis a amigos | ranking, Feed |
| `friendRankingParticipationEnabled` | participação no ranking (recíproca) | atividade, Feed |
| (nenhum) | Feed: o consentimento é **por publicação** | atividade, ranking |

Um usuário com os dois desligados continua podendo publicar um check-in; ligar os dois não publica
nada. O usuário autenticado nunca desaparece da resposta de ranking só por estar fora do Top N.

## 8. Notificações

Push é sinal **best-effort**, jamais fonte de verdade. Payload data-only e mínimo:
`v`, `eventId`, `type`, `recipientSocialId`, `entityId` — e nada mais. Nenhum uid, e-mail,
`friendCode`, `displayName`, legenda, comentário, URL de foto ou dado de treino. Os textos são
gerados **no Android**, de `strings.xml`.

Sete tipos, e só sete: `FRIEND_REQUEST_RECEIVED`, `FRIEND_REQUEST_ACCEPTED`,
`CHALLENGE_INVITATION_RECEIVED`, `CHALLENGE_STARTING_SOON`, `CHALLENGE_ENDED`,
`WORKOUT_SHARE_RECEIVED` e `GROUP_INVITATION_RECEIVED` (T17.11). **Feed, reações e comentários não
geram push** — decisão da T17.9 —, e o Squad também não notifica entrada, saída, remoção, posse
transferida, publicação nova nem exclusão de grupo: um Squad de 20 pessoas que avisasse cada
movimento seria um chat com outro nome. A única categoria de Squad é o **convite**, e ela é
suprimida se houver bloqueio, se o convite deixou de estar `PENDING`, se expirou ou se o Squad foi
excluído — tudo revalidado no instante do despacho.

Outbox transacional: o evento é persistido **dentro** da transação de negócio; o FCM fica **fora**
dela. Uma falha de entrega nunca reverte a operação social. A relevância é revalidada no despacho,
então um pedido já aceito, um convite já recusado ou um par que se bloqueou não geram push tardio.

`POST_NOTIFICATIONS` **não** é pedida no arranque — só no opt-in explícito, na tela de preferências.
Deep links usam allowlist fechada (`SocialNotificationNavigationResolver`); o servidor nunca envia
rota. A tela aberta consulta o estado atual — o push não é snapshot de autoridade.

Isolamento de conta: `recipientSocialId` diferente do `socialId` ativo (ou app deslogado) faz o push
ser descartado em silêncio.

## 9. Compartilhamento de treino (T17.7)

Snapshot **versionado e imutável** (`snapshotVersion: 1`), contendo apenas o que é portável:

```text
name, shortIdentifier, exercises[ canonicalExerciseId, sortOrder,
                                  targetSets, minReps, maxReps, restDurationSeconds ]
```

Nunca: carga, histórico, notas, número de máquina, `localId`, `syncId`, uid. Exercício **CUSTOM**
bloqueia o compartilhamento (fail-closed, decidido no aparelho — o servidor não conhece o
catálogo, e valida a **forma** do identificador).

A importação cria um `WorkoutTemplate` **novo**, com `localId` e `syncId` novos, e é idempotente
por recibo local (`WorkoutShareImportReceiptEntity`). Depois de importada, a cópia é do
destinatário: o remetente editar, apagar o template, desfazer a amizade, bloquear ou excluir a
conta **não** a alcança.

## 10. Feed, check-ins e mídia (T17.8 / T17.9)

Concluir um treino **não publica nada**. O primeiro toque no CTA abre um preview que lista o que
vai e o que não vai; só a confirmação publica. Um treino, no máximo um check-in — garantia do banco
(`UNIQUE (author_uid, source_session_sync_id)`).

O servidor exige sessão canônica sincronizada, `COMPLETED`, do dono, dentro da janela. O cliente
não declara conclusão: `completed`, `status`, `publishedAt`, `authorUid` são recusados por nome.

O DTO é o contrato inteiro:

```text
type, checkInId, author { socialId, displayName }, publishedAt, isCurrentUser,
caption, media { mediaId, width, height }, reactions, currentUserReaction, commentCount
```

Nada de `sessionSyncId`, nome do treino, exercício, série, carga, duração, volume, PR, nota, medida
ou horário do treino. `publishedAt` é quando **publicou**, nunca quando treinou.

Feed: `FRIENDS_ONLY`, janela de 30 dias, `limit` padrão 20 e teto 50, ordenação determinística
(`created_at DESC, id DESC`). Custo fixo em consultas — as agregações recebem a página inteira, e
um feed de 20 itens custa as mesmas consultas que um de 2.

### Pipeline de mídia

```text
bytes recebidos
   ↓  decode real (sharp/libvips) — o Content-Type do cliente não participa
formato + dimensões
   ↓  recusa animação, depois formato; valida pixels e arestas ANTES de alocar
   ↓  rotate()  — a orientação EXIF vira geometria, e a tag morre
   ↓  resize inside 1600 px, sem ampliar
   ↓  re-encode WebP SEM withMetadata()  ← é esta ausência que remove GPS, aparelho e data
   ↓  cabe em 1,5 MB? senão, próxima qualidade
arquivo sanitizado → SocialMediaStore (chave opaca, fora do SQLite)
```

O original **nunca** encosta no disco. A chave é `checkins/xx/yy/<uuid>.webp`, gerada pelo servidor,
com duas barreiras contra path traversal (allowlist de forma + confinamento na raiz). Conhecer o
`mediaId` não concede acesso: a rota exige token e passa pela mesma política do check-in. Não
existe URL pública, diretório estático, URL assinada nem `Cache-Control: public`.

Quota por conta, TTL de mídia `PENDING`, e um `setInterval` bounded (`SocialMediaCleaner`) recolhe
pendências expiradas, mídia `DELETED` e órfãos.

No Android o cache de foto é **memória e só memória** — sem Coil `diskCache`, sem `cacheDir` —, com
escopo de conta trocado **antes** da primeira requisição da conta nova.

### Texto

Legenda `0..280`, comentário `1..300`, em code points, NFC, sem controle C0/C1, sem zero-width, sem
override bidirecional, quebras de linha bounded. HTML e Markdown **não são interpretados e não são
escapados**: o Android desenha com `Text` de Compose, e escapar corromperia o texto da pessoa para
se defender de um risco que este caminho não tem.

Reação: enum fechado `FIRE | MUSCLE | CLAP`, uma por pessoa por post **por audiência** — desde a
T17.12 a garantia não é mais a chave primária, e sim dois índices únicos **parciais** (§15).
Otimista na tela com rollback. Comentário **não** é otimista: espera a resposta, e o rascunho
permanece se falhar.

Nada disso é evento de domínio: não dá XP, não move missão, não altera streak, ranking ou desafio,
e não gera push.

## 11. Exclusão de conta e DR

`DELETE /v1/account` ≠ limpar o Room. **O treino local permanece.** O que sai é o que estava na
nuvem.

### Matriz de exclusão

| Recurso | Ação |
| --- | --- |
| `social_profiles` e privacidade/progresso | apagado |
| `friendships`, `friend_requests` | apagado (nos dois papéis) |
| `social_blocks`, `social_reports` | apagado (nos dois papéis) |
| `challenges` (criados), invitations, participations | apagado (cascade a partir do perfil) |
| `social_notification_*`, `social_push_devices` | apagado |
| `workout_shares` (enviados e recebidos) | apagado |
| `social_workout_checkins` e `social_checkin_media` (linhas) | apagado |
| arquivos de mídia no `SocialMediaStore` | apagado (chaves lidas **antes** do purge) |
| `social_checkin_comments` / `_reactions` — próprios e **em posts alheios**, nas duas audiências | apagado |
| `social_groups` de que a conta era dona (e todo o contexto deles) | apagado — o Squad vai junto |
| `social_group_memberships` em Squads de terceiros | apagado — o Squad **permanece** |
| `social_group_invitations` (enviados e recebidos) | apagado |
| `social_group_checkin_shares` da conta | apagado |
| interações `GROUP` de **terceiros** dentro dos Squads da conta | apagado (cascade de `group_id`) |
| `sync_entities`, `sync_changes`, `sync_mutations` | apagado |
| `backup_snapshots`, `backup_items` | apagado |
| `ai_usage_daily` | apagado |
| Conta no Firebase Auth | apagada (com job de retry durável) |
| `account_deletion_tombstones` | **criado** (HMAC do uid) |
| Room no aparelho | **preservado** |
| Dados de B e C | **preservados** |

Cópia de treino que B já importou de A **não** é apagada: ela é de B.

### Anti-ressurreição

O tombstone guarda `HMAC(uid)` com `ACCOUNT_DELETION_HMAC_KEY` — obrigatória em produção, e o
processo não sobe com o default de desenvolvimento. Duas razões: com uma chave conhecida qualquer
pessoa confirma um uid a partir da tabela; e **trocar a chave depois** faria todos os tombstones
existentes deixarem de casar — conta excluída voltando a passar pelo guard, e a reconciliação de DR
deixando de reconhecê-la.

O guard de autenticação recusa qualquer rota (exceto `/v1/account`) para um uid com tombstone,
comparando o **caminho** da requisição — nunca a URL com query string.

Ciclo de DR:

```text
A existe, com conteúdo social e mídia
   ↓  backup (restic: spark.db + $SPARK_MEDIA_DIR + tombstones)
A exclui a conta            →  linhas apagadas, arquivos apagados, tombstone gravado
   ↓  restore de um snapshot ANTERIOR  →  linhas e arquivos de A ressuscitam fisicamente
   ↓  reconciliação de tombstones      →  purga banco E arquivos de novo
A não ressuscita
```

O arquivo de tombstones é append-only e **precisa** estar no backup: sem ele, um restore antigo não
sabe quais contas já haviam sido excluídas.

## 12. Operação

| Recurso | Backup | Restore | Reconciliação de exclusão |
| --- | --- | --- | --- |
| `spark.db` | `VACUUM INTO` → restic | `ops/restore.sh` | purga por uid |
| `$SPARK_MEDIA_DIR` | restic, no mesmo snapshot | `--media-from` / `--install` | arquivos apagados por chave |
| tombstones (`.tsv`) | junto do banco | junto do banco | é a **fonte** da reconciliação |

O restore tolera incoerência entre banco e mídia nos dois sentidos: metadata apontando para arquivo
ausente devolve 404 (nunca derruba o processo), e arquivo sem linha é recolhido como órfão.

### Tetos

| Camada | Teto |
| --- | --- |
| Caddy (`request_body`) | 12 MB — acima do maior teto do backend, para que o **backend** responda o erro |
| JSON (backup/sync/social) | 4 MiB |
| Upload de imagem | `SOCIAL_MEDIA_MAX_UPLOAD_BYTES` (10 MiB por padrão) |
| Imagem armazenada | 1,5 MB, 1600 px na maior aresta |
| Quota de mídia por conta | `SOCIAL_MEDIA_MAX_USER_BYTES` |

### Rate limits — todos por conta autenticada, nunca por IP

| Operação | Teto |
| --- | --- |
| Geral (qualquer rota autenticada) | 600 / min |
| Lookup por `friendCode` | 20 / min |
| Envio de pedido de amizade | 15 / min |
| Criar desafio | 5 / min |
| Responder convite de desafio | 30 / min |
| Criar check-in | 30 / janela |
| Upload de mídia | 20 / hora |
| Comentário | 30 / 10 min (**e** 10 por check-in / 10 min) |
| Reação | 60 / min |
| Compartilhar treino | 20 / dia |
| Criar Squad | 10 / min (o teto real é de domínio: 5 Squads ativos por conta) |
| Convidar para Squad | 30 / min |
| Participação em Squad (aceitar, recusar, sair, remover, transferir) | 30 / min |
| Compartilhar check-in em Squad | 20 / min (o teto real é de domínio: 5 Squads por check-in) |
| Denúncia | 5 / dia |
| Bloquear, registrar push, excluir conta | teto geral (operações idempotentes e terminais) |

### Logs

Servidor: `requestId`, **prefixo** de uid, evento, status, duração, contagens, tipo de reação,
comprimento do comentário, prefixo de `mediaId`. **Nunca** uid completo, e-mail, `displayName`,
`socialId`, `friendCode`, token FCM, legenda, corpo de comentário, caminho de arquivo, nome
original ou payload de treino.

Android: o pacote social **não registra log nenhum**, e a ausência é testada. O pacote de push
registra evento e `eventId` opaco — nunca token, `socialId` ou uid.

## 13. Disciplina do cliente

Todo estado social no Android é **memória com escopo de conta**. A troca de conta limpa **antes** de
a primeira requisição da conta nova sair, e toda resposta confere o `uid` de origem antes de tocar
no estado — uma resposta iniciada como A e concluída depois do login de B é descartada.

Desde a T17.12 a conta não é a única dimensão do escopo: todo estado por publicação — comentários
carregados, reação em voo, contagens — é indexado por `(checkInId, audiência)`, nunca pelo
`checkInId` sozinho. `interactionKey(checkInId, context)` é essa chave. Indexar só pelo check-in é
o defeito que faz a mesma publicação aberta em dois Squads compartilhar uma conversa só:

```text
conta  +  checkInId  +  audiência        ← a chave de qualquer cache de interação
```

O `InteractionContext` viaja como **parâmetro** — da rota para a ViewModel, e da ViewModel para o
gateway. Um "contexto atual" em singleton faria a tela aberta a partir do Squad A herdar o contexto
de quem abriu o Squad B um instante antes, e a interação nasceria na audiência errada sem nenhum
sintoma visível. O app **nunca** lê a audiência de uma resposta do servidor: ele a monta a partir de
onde o usuário estava quando tocou.

O que é persistido localmente, e por quê:

| O que | Onde | Por quê | Entra no backup Android? |
| --- | --- | --- | --- |
| Token FCM + `socialId` registrado | DataStore `social_push_scope` | estado técnico da instalação | **não** (excluído) |
| Recibo de importação de share | Room `WorkoutShareImportReceiptEntity` | evita importar o mesmo share duas vezes num retry | sim (é dado do dono) |

Nada disso é autoridade social. Não existe Outbox social, não existe `FriendProgressEntity`, e
nenhum agregado social entra em `sync_entities`.

## 14. Squads privados (T17.11)

Um Squad é um grupo **privado e por convite**. Não existe busca, listagem pública, diretório, link
de convite nem QR de grupo: conhecer o UUID não concede nada, e quem não é membro recebe o mesmo
`404` de "não existe".

```text
Squad (social_groups)
  ├── Membership  (social_group_memberships)   quem está dentro, e com qual papel
  ├── Invitation  (social_group_invitations)   quem foi chamado, e em que estado
  └── CheckInShare(social_group_checkin_shares) ARESTA para um check-in que já existe
```

A quarta tabela é o desenho da fase: ela é uma **referência**, não um post. Não existe
`social_group_posts`, não existe segundo modelo de CheckIn e não existe segundo Feed — o feed do
Squad é a mesma publicação da T17.8/T17.9 lida por outra audiência.

### Friendship ≠ Membership

As duas relações são independentes, e a independência vale nos dois sentidos:

| Estar no mesmo Squad **não** concede | Desfazer a amizade **não** remove |
| --- | --- |
| perfil de amigo, Feed de amigos | a participação no Squad |
| Workout Share, desafio, ranking | os compartilhamentos já feitos ali |
| progresso privado da T17.2 | o acesso `GROUP` |

Convidar exige amizade **atual**; aceitar revalida amizade, bloqueio, capacidade e status do Squad
no instante do aceite. Um convite emitido antes de um bloqueio ou de um `unfriend` não entra.

### Invariantes de posse e composição

- **exatamente um `OWNER`** enquanto o Squad existe — garantido por índice único parcial no banco,
  e não por verificação no serviço: uma transferência que falhasse no meio deixaria zero ou dois
  donos, e nenhum dos dois estados se corrige sozinho;
- o dono **não sai** pela porta comum: ele transfere a posse ou exclui o Squad. Escolher um
  substituto por ordenação entregaria um grupo de pessoas reais a quem não pediu;
- tetos **todos no servidor**: 20 membros, 5 Squads criados por conta, 20 participações por conta,
  5 Squads por check-in, 20 convites pendentes por Squad.

### Ciclo de vida do conteúdo

```text
sair / ser removido do Squad X  →  os shares da pessoa em X, as interações GROUP(X) dela,
                                   e as conversas nos posts que ela trouxe  →  removidos
voltar depois (rejoin)          →  nada ressuscita
desfazer o share de X           →  as interações GROUP(X) daquele check-in  →  removidas
excluir o Squad X               →  só o contexto de X. Nunca o WorkoutCheckIn, nunca FRIEND,
                                   nunca outro Squad, nunca treino
```

Desativar o Social resolve as participações, mas **recusa** se a conta for dona de um Squad com
outras pessoas (`GROUP_OWNERSHIP_REQUIRES_ACTION`): transfira ou exclua antes. A exclusão de conta
é diferente — ela nunca pode ser bloqueada, e por isso o Squad de quem sai vai junto (§11).

### Identidade dentro do Squad

A administração usa `membershipId` — um UUID próprio —, e não o uid nem o `socialId`. É isso que
permite ao dono remover um participante bloqueado sem que a tela receba a identidade dele: um
membro em bloqueio aparece como entrada opaca ("Participante indisponível"), sem `socialId`, sem
`displayName` e sem navegação de perfil.

Detalhes em [`social-groups.md`](./social-groups.md).

## 15. Audiências de interação (T17.12)

Até a T17.11, reagir e comentar exigiam relação **direta**, e quem alcançava o post só por um Squad
tinha acesso somente de leitura. Isso era um contorno, não a solução: a causa era que a interação
pertencia ao **check-in**, e não ao lugar onde a conversa acontecia. A T17.12 resolve a causa.

```text
FRIEND    → group_id IS NULL
GROUP(X)  → group_id = X
GROUP(Y)  → group_id = Y
```

Três garantias, e as três são bloqueantes se falharem:

1. `GROUP(X)` nunca aparece em `GROUP(Y)` nem no Feed de amigos;
2. `FRIEND` nunca aparece em Squad nenhum;
3. contexto inválido falha **fechado** — `404`, nunca rebaixamento para `FRIEND`.

### Contexto é proposta; audiência é decisão do servidor

A tela envia `context: { type, groupId? }` a partir de **onde o usuário estava**. O servidor
revalida Squad ativo, share naquele Squad, participação do viewer, participação do autor e ausência
de bloqueio — a cada requisição, contra as tabelas. Um corpo **sem** `context` resolve para
`FRIEND`, que é a autorização que essas rotas sempre exigiram: clientes anteriores à T17.12
continuam funcionando sem mudança.

`DELETE .../comments/{id}` não recebe contexto de propósito: a audiência é propriedade **do
comentário**, e é o servidor que a lê — aceitar um contexto ali deixaria a tela decidir moderação.

### A unicidade de reação, e a armadilha do `NULL`

Uma `UNIQUE (checkin_id, reactor_uid, group_id)` **não** resolveria o problema: no SQLite cada
`NULL` é distinto de qualquer outro numa `UNIQUE`, então duas reações `FRIEND` da mesma pessoa no
mesmo post passariam sem conflito — a regra falharia justamente na audiência mais usada. A solução
são dois índices únicos **parciais**, um por partição:

```text
idx_checkin_reactions_friend_unique  UNIQUE (checkin_id, reactor_uid)            WHERE audience_type = 'FRIEND'
idx_checkin_reactions_group_unique   UNIQUE (checkin_id, reactor_uid, group_id)  WHERE audience_type = 'GROUP'
```

Um `CHECK` cruzado nas duas tabelas garante que `FRIEND` nunca carregue `group_id` e que `GROUP`
sempre carregue.

### Contagens por viewer **e** por audiência

`commentCount` e as contagens de reação nunca são globais e nunca somam audiências. São duas
filtragens independentes, cada uma por um motivo:

```text
por audiência → a conversa do Squad X não vaza para o Y nem para o Feed de amigos
por viewer    → a participação de quem está em bloqueio não transparece nem como número
```

A exceção deliberada é o teto anti-enxurrada por publicação, que **atravessa** as audiências:
contá-lo por audiência daria a quem quisesse floodar um multiplicador pelo número de Squads.

### Moderação

Três autoridades sobre um comentário, nesta ordem: o **autor do comentário**, o **autor do
check-in** e o **dono do Squad** — este último só quando a audiência é `GROUP` daquele Squad, e
nunca sobre um comentário que o bloqueio já esconde dele. Qualquer outra pessoa recebe `404`.

Detalhes em [`social-interaction-audience.md`](./social-interaction-audience.md).

---

## Onde o código mora

```text
backend/src/modules/social/
  social.*                     identidade, privacidade, ativação        (T17.0)
  friendship.*                 amizade, pedidos, lookup                 (T17.1)
  social-profile.*             projeção de progresso                    (T17.2)
  challenge.*                  desafios e pontuação                     (T17.3)
  social-activity.*            atividade e ranking                      (T17.4)
  notification.*, *push*       eventos, outbox e FCM                    (T17.5)
  block.*, report.*            bloqueio e denúncia                      (T17.6)
  workout-share.*              compartilhamento de treino               (T17.7)
  workout-checkin.*            check-in, Feed e política de acesso      (T17.8/T17.9)
  checkin-interaction.*        reações e comentários, por audiência     (T17.9/T17.12)
  social-media.*               pipeline, armazenamento e limpeza        (T17.9)
  social-group.*               Squads, posse, convites e feed de grupo  (T17.11)
  checkin.projector.ts         o ÚNICO montador de card dos dois feeds  (T17.11)
  workout-checkin-context.resolver.ts  contexto proposto → audiência autorizada  (T17.12)
  canonical-training.source.ts a ÚNICA porta para o domínio de treino

backend/src/modules/account-deletion/   exclusão, tombstone e reconciliação  (T17.6)
```
