# Domínio social do Spark — identidade pública, autoridade e privacidade

- **Tarefa:** T17.0 — fundação social.
- **Status (verificado em 2026-09-07): implementado.** Migration `0007_social_foundation.sql`,
  módulo `backend/src/modules/social/`, gateway e seção de Perfil no Android.
- **Contrato:** [`contracts/social/v1/README.md`](../../contracts/social/v1/README.md)
- **Continuação:** [`friendship-contract.md`](./friendship-contract.md) — **T17.1**, o grafo social
  (amizade bilateral, pedidos, descoberta por código e QR Code), e
  [`social-profile-contract.md`](./social-profile-contract.md) — **T17.2**, o perfil enriquecido
  (projeção de progresso, privacidade por campo e freshness). Este documento descreve a identidade
  e a privacidade sobre as quais as duas foram construídas.
- **Relacionados:** [`identity-contract.md`](./identity-contract.md),
  [`ADR-0001-spark-online-architecture.md`](./ADR-0001-spark-online-architecture.md),
  [`data-classification-matrix.md`](./data-classification-matrix.md)

**Não existe** nesta fase: amizade, pedido de amizade, bloqueio, QR Code, compartilhamento de
código, lookup por `friendCode`, busca de usuários, desafios, ranking, feed, notificações sociais,
avatar/upload de mídia e exclusão completa de conta. **Desde a T17.1**, amizade, pedido, lookup
por código, compartilhamento e QR Code existem; **desde a T17.2**, o perfil enriquecido com
compartilhamento controlado de progresso — o restante continua fora.

---

## 1. As duas autoridades, e por que elas são diferentes

```text
TREINO — local-first                      SOCIAL — server-authoritative
                                          
UI                                        UI
 ↓                                         ↓
Domain                                    Spark Backend
 ↓                                         ↓
Room  ← autoridade operacional            resultado
 ↓                                         ↓
Outbox                                    UI / cache em memória
 ↓
Spark Backend ← autoridade de ordem
```

O treino é local-first porque **executar um treino não pode depender de rede**. A pessoa está na
academia, no subsolo, sem sinal, e precisa registrar uma série. Room é a autoridade operacional; o
servidor é autoridade apenas de *ordem* (`revision`, `serverSequence`).

O social é o oposto, e pela razão simétrica: **uma identidade pública não pode ser decidida por um
aparelho**. Dois celulares offline não podem inventar dois `friendCode` e depois "convergir" —
convergir aqui significaria escolher qual dos dois códigos a pessoa já tinha distribuído aos
amigos deixa de valer. Unicidade global é uma pergunta que só o servidor pode responder.

Disso decorre tudo o mais:

| | Treino | Social |
| --- | --- | --- |
| Autoridade | Room (operacional) | Spark Backend |
| Offline | funciona por completo | ação não acontece; a tela diz isso |
| Outbox | sim | **não** |
| Conflito | detectado e resolvido pelo usuário | não existe: uma autoridade só |
| Identidade | `syncId`, gerado offline | `socialId`/`friendCode`, gerados pelo servidor |
| Backup/restore | é o objeto do backup | fora do backup |
| Exige conta | não | **sim** |

**Isso não torna o Spark dependente de servidor.** Social é uma capacidade opcional: uma conta sem
perfil social continua treinando, consultando histórico, sincronizando, fazendo backup,
restaurando e usando o Coach IA. Um servidor fora do ar deixa a seção social indisponível e não
toca em mais nada.

## 2. As identidades

| Identidade | Função | Pública? |
| --- | --- | --- |
| Firebase UID | autenticação / ownership de conta | **Não** |
| `socialId` | identidade social | Sim |
| `friendCode` | convite / descoberta controlada | Compartilhável |
| `displayName` | nome exibido (não é único) | Sim |
| `deviceId` | instalação | Não |
| `syncId` | entidade de treino sincronizada | Não social |
| `clientMutationId` | uma mutação de sync | Não social |
| `clientBackupId` | uma tentativa de backup | Não social |

### O princípio central

> **O Firebase UID é identidade privada de infraestrutura e nunca identidade pública do usuário.**

É proibido mostrar, compartilhar ou aceitar como identificador social: Firebase UID, e-mail,
`localId`, `deviceId` e `syncId` de dados de treino. O domínio social tem identidade própria.

Onde isso é aplicado, e não apenas afirmado:

- `owner_uid` é chave primária de `social_profiles` e **não** aparece em nenhum DTO. A conversão
  para DTO (`toOwnerProfile`) é a fronteira onde o Firebase UID para de existir;
- há teste que varre todas as respostas sociais procurando `uid`, `ownerUid`, `firebaseUid` e o
  e-mail — e teste estrutural que varre `SocialDtos.kt` no Android;
- o validador do servidor **recusa a requisição inteira** se o corpo trouxer `ownerUid`, `uid`,
  `socialId`, `friendCode`, `status`, `createdAt`, `updatedAt` ou `email`.

### Nenhuma identidade deriva de outra

`socialId` é UUID v4 de CSPRNG; `friendCode` é sorteado por `crypto.randomInt`. Nenhum dos dois é
derivado do uid, do e-mail ou do nome — **nem por hash**. Um identificador derivado permite
confirmar um palpite ("o e-mail X tem o código Y?"), que é exatamente a informação que estes
identificadores existem para não carregar.

### `socialId` não é autenticação

Conhecer um `socialId` não concede permissão nenhuma. Toda autorização continua saindo do Firebase
ID Token verificado → `AuthenticatedPrincipal.uid`. O mesmo vale para o `friendCode`: ele é um
convite, não uma credencial.

## 3. Ativação é explícita

```text
PROIBIDO                              CORRETO
Google Sign-In                        Google Sign-In
     ↓                                     ↓
Firebase login                        Conta Spark autenticada
     ↓                                     ↓
cria SocialProfile automaticamente    Social = NOT_ENABLED
                                           ↓  (toque explícito + confirmação)
                                      SocialProfile
```

`GET /v1/social/me` de uma conta sem perfil responde `{ "enabled": false }` **sem escrever uma
linha**. Não há criação preguiçosa, não há "cria se não existir" escondido em uma leitura, e não
há `LaunchedEffect` que ative. Há teste que lê `/me` repetidamente e verifica que nada foi criado.

Antes de ativar, a tela informa, em três linhas:

```text
Será criado:
• um identificador social
• um código de amigo
• um nome social

Seu e-mail, treinos e medidas não ficam públicos.
```

## 4. Estados e ciclo de vida

```text
NOT_ENABLED  ──activate──▶  ACTIVE  ──disable──▶  DISABLED
(sem linha)                    ▲                      │
                               └───────enable─────────┘
                                (mesma identidade)
```

- **`NOT_ENABLED` é a ausência de linha.** É o estado padrão de toda conta.
- **`DISABLED` é um estado persistido**, e não a remoção da linha. É exatamente isso que permite
  reativar preservando `socialId` e `friendCode`.
- **Reativar preserva a identidade.** `socialId=A, friendCode=X → disable → enable → socialId=A,
  friendCode=X`. Gerar identidade nova a cada toque inviabilizaria reconstruir relações no futuro
  — e um toque acidental no interruptor invalidaria o código que a pessoa já distribuiu.
- **Perfil desativado não é descobrível.** A política está implementada
  (`SocialAccessPolicy.canDiscoverByFriendCode`), e o lookup da T17.1 responde "não encontrado"
  — a **mesma** resposta de um código que nunca existiu. Distinguir os dois transformaria a rota
  num oráculo: bastaria comparar as respostas para descobrir que um código existe mas está
  desligado, que é a informação que desativar deveria esconder.

### Desativar ≠ excluir

```text
Social disabled  ≠  Firebase account deleted
Social disabled  ≠  Spark account deleted
```

Desativar não apaga Conta Spark, conta Firebase, backups, sync, treinos, histórico, medidas nem
gamificação. **Exclusão completa de conta continua fora de escopo** e permanece registrada como
requisito **pré-release** para público externo (ARCHITECTURE §17): ela precisará coordenar
Firebase, Spark Backend, backup, mídia e social de uma vez.

## 5. Schema

```text
social_profiles                        social_privacy_settings
├── owner_uid    PK  (Firebase UID)    ├── owner_uid    PK, FK → social_profiles
├── social_id    UNIQUE                ├── discoverability
├── friend_code  UNIQUE                ├── friend_requests_enabled
├── display_name                       ├── activity_sharing_enabled
├── status       ACTIVE | DISABLED     └── updated_at
├── created_at
└── updated_at                         social_progress_settings   (T17.2)
                                       ├── owner_uid    PK, FK → social_profiles
                                       ├── share_level
                                       ├── share_consistency_streak
                                       ├── share_weekly_workout_count
                                       ├── share_highlighted_achievements
                                       ├── week_time_zone
                                       └── updated_at
```

Decisões:

- **`owner_uid` é PK**, porque "uma conta tem no máximo um perfil social" é a regra e a forma mais
  barata de garanti-la é o banco não conseguir representar o contrário;
- **unicidade é do banco**, não de um `SELECT` antes do `INSERT` — entre a consulta e a escrita
  cabe outra ativação;
- **sem índice em `status`**, deliberadamente: toda consulta chega por chave única e lê o status da
  linha encontrada. Não existe — e não pode existir — consulta que varra perfis por status, porque
  isso seria enumeração de usuários;
- **privacidade em tabela própria**, porque `social_profiles` responde *quem é* e
  `social_privacy_settings` responde *o que os outros podem*. Elas mudam por caminhos diferentes e
  evoluem em ritmos diferentes; misturá-las faria cada opção nova virar uma coluna no meio da
  identidade;
- **a migration é aditiva.** Ela só cria tabelas: nenhum `DROP`, `ALTER`, `DELETE` ou `UPDATE`, e
  nenhuma menção a tabela da T16. Há teste que sobe um banco T16 com dados, aplica a `0007` e
  compara linha a linha o que estava lá antes.

### Nenhum dado de treino entra no perfil

É proibido gravar XP, streak, contagem de treinos, último treino, peso corporal ou PR em
`social_profiles` — mesmo "só para facilitar a UI". Esses valores têm projeção própria, e a
fronteira já existia antes dela: `SocialProjection`.

**A T17.2 manteve a regra ao criar `social_progress_settings`:** aquela tabela guarda quatro
booleanos e um fuso — **consentimento**, e nenhum valor de progresso. O número que um amigo vê é
derivado na leitura, por `SocialProgressProjector`, e não existe coluna nenhuma no domínio social
com nível, sequência, contagem ou XP.

## 6. `SocialProjection` — a fronteira entre o privado e o social

```text
PROIBIDO                                    OBRIGATÓRIO
endpoint social                             WorkoutSession (privado)
  → SELECT payload de sync/backup                 ↓
  → "treinou hoje"                          SocialProjection
                                                  ↓
                                            "treinou hoje"
```

O caminho mais curto para "mostrar que o Igor treinou hoje" é um endpoint social lendo
`backup_items` ou o payload de um push de sync. Ele funciona no primeiro dia e é irreversível no
segundo: a partir daí, um campo novo no snapshot de treino vira campo novo na superfície social
sem que ninguém decida isso.

A T17.0 implementou **a fronteira e a política**, e nenhuma projeção. A **T17.2** escreveu a
primeira — `SocialProgressProjector`, descrita em
[`social-profile-contract.md`](./social-profile-contract.md). As regras que toda projeção obedece
estão declaradas em `social.projection.ts`:

- **`OWNER_SCOPED`** — uma projeção só pode usar dado que pertence ao **mesmo** `ownerUid` do
  `SocialProfile` que ela descreve. O cenário que isso impede já é possível hoje: o dataset local
  pode estar vinculado à conta A (`cloud_data_binding`, T16.4) enquanto a sessão do Firebase é a
  conta B; sem esta regra, o progresso de A seria publicado como sendo de B;
- **`CONSENT_REQUIRED`** — nenhum campo é publicado sem o dono ter ligado o interruptor
  correspondente, e todos nascem desligados;
- **`DERIVED_NEVER_RAW`** — a projeção produz um fato pequeno e escolhido, nunca o agregado bruto.
  Peso, medidas, cargas, notas, nomes de treino e histórico não têm forma social;
- **`NO_BACKUP_READ`** — snapshot e payload de backup nunca são fonte de projeção. Um snapshot é a
  conta inteira em um documento;
- **`AGGREGATE_ONLY`** — só escalar sai do adapter (`SocialProgressSource`): uma contagem, um
  nível, uma lista de identificadores canônicos. Nunca payload, linha ou sessão;
- **`SINGLE_AUTHORITY`** — a projeção não recalcula regra de domínio que já existe em outro lugar
  do Spark. Ou ela lê o estado canônico, ou faz a derivação que o próprio contrato canônico define,
  ou responde que não sabe.

**As duas últimas substituíram o `NO_CROSS_DOMAIN_READ` absoluto da T17.0**, que proibia ler
`sync_entities` quando nenhuma projeção existia. A T17.2 precisou da primeira, e o afrouxamento foi
declarado com substituto no lugar — o motivo completo está em
[`social-profile-contract.md` §13](./social-profile-contract.md).

Isso é sustentado por construção: `SocialModule` não importa `BackupModule`, `SyncModule` nem
`AiModule`, e há teste que varre os imports do módulo; `social-progress.source.ts` é o único
arquivo que menciona `sync_entities`, e há teste que verifica que ele só seleciona `COUNT(*)` e
`1`. Do lado Android, há teste estrutural que verifica que o pacote social não conhece Room, DAO,
Outbox, `CloudDataBinding`, backup, restore, treino, medida nem gamificação.

## 7. Privacidade

| Campo | Default | Por quê |
| --- | --- | --- |
| `discoverability` | `FRIEND_CODE_ONLY` | só quem já tem o código encontra; é o único valor que existe |
| `friendRequestsEnabled` | `true` | receber convite é o caminho pretendido; quem não quiser desliga **antes** de a T17.1 existir |
| `activitySharingEnabled` | `false` | um default `true` publicaria, no dia em que a feature nascesse, a atividade de quem nunca escolheu publicar |

Desde a T17.2, quatro preferências novas vivem em `social_progress_settings` — `shareLevel`,
`shareConsistencyStreak`, `shareWeeklyWorkoutCount`, `shareHighlightedAchievements` —, e as quatro
nascem `false` pela mesma razão. Elas ficam em tabela própria porque respondem a outra pergunta:
`social_privacy_settings` diz *quem pode me alcançar*, `social_progress_settings` diz *o que
aparece no meu perfil*.

Não existe `PUBLIC_SEARCH`, `GLOBAL_PROFILE` nem `ACTIVITY_PUBLIC` — nem como default, nem como
valor aceito. E não existe, neste servidor:

- busca pública por nome (`GET /social/users?q=`);
- busca por e-mail;
- listagem global de perfis;
- lookup por `friendCode` (T17.1).

A UI não oferece escolha de descoberta: com um comportamento real só, uma opção que não muda nada
seria uma promessa falsa de controle. O enum existe no contrato porque a T17.1 vai **ler** este
campo antes de responder um lookup.

### Segundo princípio

> **Ser amigo, no futuro, nunca significará ter acesso aos dados sincronizados da outra pessoa.**

O módulo social não concede acesso a `/v1/sync/*`, `/v1/backups/*`, ao estado remoto, a payloads de
backup, ao histórico bruto, a medidas corporais ou a notas. Amizade é uma relação no domínio
social; ela não é uma chave para o domínio privado.

## 8. Android

```text
ProfileScreen
    ↓
SocialViewModel  ← cache em memória, account-scoped
    ↓
SocialGateway (domain)
    ↓
SparkSocialGateway (data) → SparkBackendClient → /v1/social/*
```

- **Gateway, e não repositório.** "Repositório" no Spark significa um dono de dado local, com Room
  atrás e `Flow` na frente. O social não tem nada disso; chamá-lo de repositório sugeriria um
  cache durável que não existe, e o primeiro leitor tentaria adicioná-lo. É a mesma escolha do
  `AiCoachGateway`, pelo mesmo motivo.
- **Não fica dentro de `WorkoutRepository`.** Ele é a autoridade do treino local; o social não tem
  o que fazer lá.
- **Room não é autoridade social.** Não existe `SocialProfileEntity`, `FriendshipEntity` nem
  `SocialOutboxEntity`.
- **Cache ≠ autoridade.** O último perfil lido vive no `SocialUiState`, em memória. Ao trocar de
  conta ele é descartado **antes** de a requisição da conta nova sair — e a resposta de uma
  requisição iniciada pela conta anterior é descartada se a conta tiver mudado durante o voo (a
  mesma lição da T16.7.1: o `uid` capturado antes da chamada não vale depois dela).
- **Sem escrita offline.** Editar o nome sem rede **não** acontece: não vai para a Outbox, não
  fica pendente e não é reenviado depois. A tela diz "nada foi alterado". "Editei meu nome no
  avião e ele mudou sozinho três dias depois" seria pior do que "não deu, tente com internet".
- **`CloudDataBinding` não é reusado.** Ele protege o **dataset de treino** — responde "de quem
  são estes dados". O social pertence à conta autenticada, e ligar os dois amarraria coisas que
  precisam poder divergir: o dataset pode ser de A com B logado (descompasso que bloqueia a nuvem)
  sem que isso diga nada sobre o perfil social de B.
- **Nenhum log.** O pacote social não registra nada, e a ausência é testada — a mesma regra dos
  pacotes de sync e restore.

### Estados de tela

`SignedOut` · `Loading` · `NotEnabled` · `Activating` · `Active` · `Saving` · `Disabling` ·
`Offline` · `Error` — e `NotConfigured` quando o build não tem endereço de backend.

Não é um `isLoading`: "entre na conta", "ative os recursos sociais", "sem internet" e "este nome
não serve" são conselhos **opostos**, e um booleano colapsaria os quatro.

### Onde mora na navegação

Uma seção dentro do Perfil, depois de Conta / Backup / Restore / Sincronização. **Nenhum item novo
de bottom navigation**: a T17.0 cria identidade e privacidade, e não há tela social para navegar
até a T17.1 — um item de navegação levaria para uma tela vazia.

## 9. O que a T17.0 deliberadamente não faz

- não cria amizade, pedido, bloqueio, QR Code, compartilhamento, lookup, busca, desafio, ranking,
  feed ou notificação;
- não implementa rotação de `friendCode` (T17.1 decide, se o convite/privacidade exigir);
- não introduz avatar, upload ou storage de mídia (continua fora, inclusive depois da T17.2);
- não gera projeção de treino (a primeira nasceu na T17.2; atividade recente continua T17.4);
- não versiona payload social (`socialSchemaVersion`): `/v1` já é a versão do protocolo, e o
  documento é pequeno e estável. Versionar por hábito envelhece errado;
- não cria tabela genérica `social_objects(type, payload)`: o domínio social tem estrutura clara;
- não dá XP, conquista, missão ou evento de gamificação por ativar/desativar;
- não altera `WorkoutTemplate`, `WorkoutSession`, `PersonalRecord` nem `BodyMeasurement`;
- não entra no backup (T16.4), no restore (T16.5), na Outbox (T16.6) nem nos tombstones (T16.7).

## 10. O que a T17.1 herdou pronto — e o que ela construiu

```text
friendCode → normalizeFriendCode → match exato → SocialProfilePreview → FriendRequest → Friendship
```

**A T17.1 está implementada** (`0008_friend_graph.sql`, `friendship.*` no backend, telas de Amigos
e Solicitações no Android). O desenho completo dela vive em
[`friendship-contract.md`](./friendship-contract.md); o que segue é o que ela recebeu daqui:

- a normalização canônica única, já testada nos dois lados;
- `SocialAccessPolicy`, com `canDiscoverByFriendCode`, `canViewProfile`, `canViewActivity` e
  `canReceiveFriendRequest` — as regras que já podem ser verdadeiras já funcionam e são testadas;
- `SocialProfilePreviewDto`, com `socialId` e `displayName` e nada mais;
- `friendRequestsEnabled`, que ela precisa **respeitar**;
- a exigência de teto próprio de rate limit na rota de lookup;
- a regra de que match é exato, sem *fuzzy matching*, e que malformado e inexistente têm a mesma
  resposta.

---

## 11. T17.6 — Hardening Social, Bloqueio, Abuso, Exclusão de Conta e Fechamento da T17

### 11.1 Bloqueio Bilateral (`/v1/social/blocks`)
- **Server-Authoritative**: O bloqueio é registrado na tabela `social_blocks` com chave primária composta `(blocker_uid, blocked_uid)`.
- **Efeitos Imediatos em Transação Atômica**:
  1. Desfaz amizade ativa imediatamente (`friendships`);
  2. Cancela todas as solicitações de amizade pendentes em ambas as direções (`friend_requests`);
  3. Desinscreve o bloqueado ou o bloqueador de desafios ativos compartilhados (`challenge_participants`);
  4. Cancela e limpa mensagens pendentes na outbox de notificações transacionais entre os dois usuários;
  5. Pesquisas de `friendCode` respondem `NOT_FOUND` de forma indistinguível de código inexistente;
  6. Em rankings e histórico de desafios passados, o usuário bloqueado é projetado de forma segura ("Participante indisponível") sem expor nome ou dados sociais.
- **Desbloqueio**: Remove o bloqueio, mas **nunca restaura amizades** ou solicitações passadas.
- **Silencioso**: Nenhum push ou notificação é enviado informando sobre o bloqueio.

### 11.2 Denúncia de Abuso (`/v1/social/reports`)
- **Minimalista e sem texto livre**: Motivos restritos ao enum canônico (`SPAM`, `HARASSMENT`, `INAPPROPRIATE_BEHAVIOR`, `OTHER`).
- **Contexto Social Legítimo**: O servidor só aceita denúncia se houver interação social prévia comprovada (amizade ativa, solicitação de amizade pendente ou participação em desafio comum).
- **Proteção contra Abuso e Spam**:
  - Rate limit de 5 denúncias por dia por usuário denunciante;
  - Supressão de denúncias duplicadas contra o mesmo alvo no mesmo dia;
  - Sem auto-ban automático (auditoria humana / moderação interna);
  - Sem notificação ou alerta emitido ao usuário denunciado.

### 11.3 Exclusão de Conta Server-Authoritative (`DELETE /v1/account`)
- **Expurgo Cascata Completo**:
  Remove registros em todas as tabelas vinculadas ao `uid` do usuário. A lista canônica de colunas
  que carregam um uid de conta vive em `src/modules/account-deletion/account-uid-inventory.ts` (35
  colunas), e um teste a confronta com o schema real do banco — uma tabela nova com coluna de uid
  não passa sem política declarada. Desde a T17.13.1 o expurgo, o tombstone e o job de exclusão
  pertencem à **mesma transação**: uma falha no meio faz `ROLLBACK` de tudo.
- **Tombstones Criptográficos (HMAC-SHA256)**:
  Gera um hash HMAC com salt e segredo do servidor (`account_deletion_tombstones`) para impedir ressurreição ou reutilização da identidade, interceptando qualquer chamada subsequente no `BearerAuthGuard` com HTTP 403 `ACCOUNT_DELETED`.
- **Fila Assíncrona de Exclusão no Firebase Auth (`account_deletion_jobs`)**:
  Se a chamada ao Firebase Admin Auth (`deleteUser(uid)`) falhar por indisponibilidade transitória do serviço, um job de retentativa com backoff exponencial garante a exclusão final no provider.
- **Disaster Recovery Append-Only Log (`deletion_tombstones.tsv`)**:
  Cada exclusão registra um evento no arquivo de tombstones para recuperação de desastres. Desde a
  T17.13.1 a escrita é durável (`append` + `fsync`) e **obrigatória**: uma falha nela deixa a
  exclusão em `DELETION_PENDING`, com a conta já bloqueada, em vez de responder `DELETED` sem o
  registro anti-ressurreição. O arquivo entra no snapshot do `ops/backup.sh`, e a reconciliação
  pós-restore é um comando operacional (`dist/cli/reconcile-account-deletions.js`) que
  `ops/restore.sh --install` executa antes de declarar a restauração completa. Ver
  [`../runbooks/account-deletion-dr.md`](../runbooks/account-deletion-dr.md).

### 11.4 Preservação Local-First (Android)
- **Princípio Inviolável**: O banco de dados local do Room (sessões de treino, templates, histórico de exercícios, medidas corporais, recordes pessoais) **pertence ao dispositivo e nunca é apagado** durante a exclusão de conta na nuvem.
- **Desvinculação Local**: `cloudDataBindingDao.deleteBinding()` remove o vínculo do dataset local com a conta excluída, desregistra o token de push e efetua o logout no FirebaseAuth.
- **Confirmação Explícita de Alto Risco**: A UI exige confirmação em modal de dois passos com digitação explícita da palavra "EXCLUIR", deixando claro que os treinos no aparelho serão mantidos.

---

## 12. T17.7 — Compartilhamento Seguro de Treinos entre Amigos

### 12.1 Princípio Fundamental: Transferência por Cópia Independente
- **Cópia vs. Vínculo Vivo**: O compartilhamento transfere exclusivamente uma cópia estrutural instantânea (`SharedWorkoutSnapshot` V1).
- **Isolamento Total**: Nunca é criado qualquer link vivo, sincronizado ou dependente entre os usuários.
  - Se o autor edita o original → o template do destinatário não muda;
  - Se o autor apaga o original → o template do destinatário não muda;
  - Se a amizade é desfeita → as cópias já importadas permanecem intocadas;
  - Se um usuário bloqueia o outro → as cópias já importadas permanecem intocadas (apenas compartilhamentos pendentes são cancelados);
  - Se o autor exclui a conta → o template local do destinatário continua intacto.

### 12.2 Privacidade e Sanitização Estrita de Dados
- **Dados Omitidos**:
  - Cargas planejadas (`plannedWeight`);
  - Anotações pessoais (`notes`);
  - Números/identificadores de aparelhos e máquinas (`machineLabel`);
  - Identificadores locais (`id`, `templateId`, `exerciseId`);
  - Identificadores de sincronização (`syncId`);
  - Sessões executadas, histórico, PRs e XP.
- **Dados Preservados no Snapshot**:
  - Nome do treino (`name`) e identificador curto (`shortIdentifier`);
  - Lista de exercícios canônicos ordenados (`canonicalExerciseId`, `sortOrder`, `targetSets`, `minReps`, `maxReps`, `restDurationSeconds`).

### 12.3 Restrição de Catálogo Canônico
- Compartilhamentos são restritos a exercícios oficiais do catálogo Spark (`canonicalId` presente e estável, `isUserCreated == false`).
- A presença de qualquer exercício customizado bloqueia o compartilhamento com mensagem orientativa clara na UI.

### 12.4 Anti-Enumeração e Segurança
- O endpoint `/v1/social/workout-shares/:shareId` exige autenticação Bearer e verifica se o requisitante é o remetente ou o destinatário.
- Qualquer terceiro que tentar acessar ou consultar o `shareId` recebe HTTP 404 indistinguível de compartilhamento inexistente.
- Validação estrita de status: tentativas de aceitar ou recusar treinos não pendentes ou por não-participantes retornam erro específico de domínio.

### 12.5 Idempotência e Persistência Local
- **Backend**: Idempotência de envio garantida por `(sender_uid, client_request_id)` com constraint UNIQUE.
- **Android**: Tabela local `workout_share_import_receipts` (Room v36, `MIGRATION_35_36`) registra o `shareId` e o `importedTemplateLocalId`. Importações repetidas retornam o template existente sem duplicar registros no banco.
- **Sync Integration**: O novo `WorkoutTemplate` nasce com um novo `syncId` aleatório e é registrado na Outbox local do destinatário, como qualquer outro treino criado diretamente por ele.



---

## 13. T17.8 — Check-ins de treino e Feed Social

### 13.1 O princípio: concluir um treino não publica nada

```text
WorkoutSession COMPLETED
        │  nada automático
        ▼
"Compartilhar check-in?"          ← toque explícito, por sessão
        │  preview do que vai e do que NÃO vai
        ▼
confirmação
        ▼
validação canônica no servidor
        ▼
WorkoutCheckIn  ──▶  Feed dos amigos
```

Não existe gatilho no fim da sessão, no ciclo de sync, na abertura de tela nem em background. O
`WorkoutCheckInService` só é alcançado por um `POST` que o usuário disparou depois de confirmar um
preview, e o primeiro toque no CTA **não** faz requisição nenhuma — ele abre o preview.

### 13.2 Check-in ≠ Activity (T17.4)

São **duas superfícies com consentimentos diferentes**, e elas não se deduplicam:

| | T17.4 — Activity | T17.8 — CheckIn |
| --- | --- | --- |
| O que diz | "João treinou hoje" | "João publicou um check-in" |
| Consentimento | `activitySharingEnabled` (configuração) | ação explícita **por sessão** |
| Origem | projeção derivada na leitura | linha em `social_workout_checkins` |
| Existe sem a outra | sim | sim |

`activitySharingEnabled = false` **não** impede publicar check-in;
`activitySharingEnabled = true` **não** publica check-in nenhum. Da mesma forma,
`friendRankingParticipationEnabled`, participação em Challenge (T17.3) e compartilhamento de
treino (T17.7) não interferem no Feed em nenhuma direção.

### 13.3 A fonte canônica é a mesma de sempre

```text
Room (WorkoutSession COMPLETED)   ← autoridade operacional, local-first
        │
        ▼  Sync T16  ← o ÚNICO caminho de upload de sessão de treino
sync_entities
        │
        ▼  CanonicalTrainingSource.findSessionForCheckIn(ownerUid, sessionSyncId)
SocialModule
```

`CanonicalTrainingSource` é o mesmo adapter que responde perfil (T17.2), desafio (T17.3) e
atividade (T17.4). A T17.8 acrescentou **uma** operação estreita a ele, e não um quarto parser:
nenhum arquivo de check-in consulta `sync_entities`, `json_extract` ou `'WORKOUT_SESSION'` por
conta própria, e há teste estrutural sobre isso.

O adapter devolve cinco campos — `ownerUid`, `sessionSyncId`, `deleted`, `status`, `finishedAt` —
e nenhum deles é conteúdo de treino.

### 13.4 A fonte temporal

`COALESCE(finishedAt, startedAt)`, e **não** um significado novo de fim de treino:

- `finishedAt` é o fim do treino no domínio, com um único escritor no Android
  (`WorkoutEngine.finishSession()` grava `finishedAt` e `status = COMPLETED` no mesmo `copy()`);
- ele é **nulável** na coluna e no contrato de sync, então uma sessão vinda de restore ou de outro
  aparelho pode chegar `COMPLETED` sem ele;
- o fallback é `startedAt`, que já é o instante canônico da semana da T17.2 e do dia da T17.3;
- como `startedAt ≤ finishedAt`, o fallback só pode fazer a sessão parecer **mais velha** — ele
  nunca alarga a janela de elegibilidade.

A atribuição de treino a **dia** (T17.3) continua intocada: 48 horas é recência, não bucket de dia.

### 13.5 Elegibilidade

```text
owner correto ∧ entityType = WORKOUT_SESSION ∧ deleted = 0 ∧ status = COMPLETED ∧ dentro de 48h
```

O relógio é o `Clock` do servidor. O aparelho pode esconder o CTA por conveniência, mas ele não
decide: o backend revalida a cada publicação.

**Sessão ainda não sincronizada** responde `SESSION_NOT_FOUND` — a mesma resposta de "não existe",
"é de outra conta" e "tem tombstone". Distinguir transformaria a rota num oráculo de existência. O
Android, que sabe ter a sessão localmente, interpreta esse `404` como "falta sincronizar", pede um
ciclo normal do Sync T16 e tenta de novo com o **mesmo** `clientRequestId`. Se o ciclo não
convergir, o treino continua salvo e nada fica pendente — não existe fila social.

Se a conta nunca adotou a nuvem (T16.4), o app **não** cria vínculo nenhum: ele explica que os
treinos precisam estar sincronizados.

### 13.6 Idempotência

| Situação | Resultado |
| --- | --- |
| Mesmo `clientRequestId`, mesma sessão | o mesmo check-in |
| Outro `clientRequestId`, mesma sessão | o check-in existente |
| Mesmo `clientRequestId`, outra sessão | `CHECKIN_REQUEST_CONFLICT` (409) |
| Toque duplo | uma publicação |
| Sessão cujo check-in foi excluído | `CHECKIN_ALREADY_EXISTS` (409) |

As duas garantias são do **banco**: `UNIQUE (author_uid, source_session_sync_id)` e
`UNIQUE (author_uid, client_request_id)`. Duas requisições simultâneas passam pelas leituras e a
segunda falha no `INSERT`; o serviço relê e devolve a linha vencedora.

### 13.7 O DTO do Feed — campo a campo

```json
{
  "type": "WORKOUT_CHECK_IN",
  "checkInId": "…",
  "author": { "socialId": "…", "displayName": "Igor" },
  "publishedAt": 1788912345678,
  "isCurrentUser": false
}
```

`publishedAt` é **quando a pessoa publicou**, e nunca quando ela treinou.

**O que não cruza a fronteira:** Firebase UID, e-mail, `friendCode`, `sessionSyncId`, `startedAt`,
`finishedAt`, `workoutId`, `templateId`, nome do treino, exercícios, séries, repetições, cargas,
duração, volume, PRs, calorias, notas, medidas corporais, horário do treino e histórico. Nenhum
deles existe no DTO, e há teste que varre a resposta real atrás de todos.

### 13.8 Audiência do Feed

```text
eligible_authors = { viewer } ∪ { amigos diretos atuais ∧ perfil ACTIVE ∧ ¬bloqueado }
```

A autorização está **na consulta SQL**, e não depois dela. Janela de 30 dias, `limit` padrão 20 e
teto 50, ordenação `publishedAt DESC` com desempate por `checkInId`. Não existe cursor histórico,
não existe `?users=`, não existe rota pública por `socialId`.

### 13.9 Matriz de exclusão de conta (atualiza a §11.3)

| Tabela | Efeito de `DELETE /v1/account` |
| --- | --- |
| `social_workout_checkins` | **removida** — explicitamente no purge, e por `ON DELETE CASCADE` de `social_profiles` |

O Feed de um amigo simplesmente deixa de mostrar o autor: o `JOIN` com `social_profiles` é interno,
e nenhuma referência órfã sobra.

### 13.10 Matriz de bloqueio (atualiza a §11.1)

| Superfície | Efeito do bloqueio |
| --- | --- |
| Feed | par bloqueado invisível **nas duas direções**, revogado na leitura seguinte |

`A → B` e `B → A` produzem o mesmo resultado. O autor continua vendo as próprias publicações.
Desbloquear **não** devolve o Feed enquanto os dois não voltarem a ser amigos.

### 13.11 Matriz de denúncia (atualiza a §11.2)

O Feed é **contexto legítimo** para `Report USER`: a ação "Ver perfil" leva à tela da T17.2, onde
Bloquear e Denunciar já existem. Não existe `Report POST` nesta fase — um check-in não tem texto,
foto nem vídeo, então não há conteúdo a moderar além da própria pessoa. A T17.9 pode evoluir isso.

### 13.12 Matriz de consentimento

| Feature | Consentimento |
| --- | --- |
| Activity (T17.4) | configuração `activitySharingEnabled` |
| Ranking (T17.4) | configuração `friendRankingParticipationEnabled` |
| Workout Share (T17.7) | ação explícita por treino |
| **Workout CheckIn (T17.8)** | **ação explícita por sessão, com confirmação** |
| Challenge (T17.3) | participação explícita |

### 13.13 O que a T17.8 não faz

Não gera XP, conquista, missão, streak nem pontuação de desafio. Não altera nenhum consentimento.
Não compartilha `WorkoutTemplate`. Não dispara push (`WORKOUT_CHECK_IN` → **sem push**, e sem
preferência de notificação nova). Não faz polling, não usa WebSocket e não usa SSE. Não escreve em
`sync_entities`, na Outbox, no backup ou no restore. Excluir um check-in não apaga a sessão, e
excluir a sessão não apaga o check-in — depois de publicado, ele é um artefato social independente,
e a tela de exclusão do Histórico diz isso.

### 13.14 A fronteira anti-fraude, dita honestamente

O backend **não** aceita `score`, `completed = true` nem qualquer declaração de conclusão vinda do
aparelho: ele exige uma `WorkoutSession` canônica, sincronizada e `COMPLETED`, lida pela fonte
canônica. Isso **não** torna o check-in à prova de fraude — um cliente comprometido que consiga
fabricar dados canônicos válidos no protocolo de Sync produziria um check-in correspondente. Esse é
um problema de integridade do dado de treino, que existiria sem o Feed, e attestation avançada
continua fora de escopo.

---

## 14. T17.9 — Check-ins ricos: foto, legenda, reações e comentários

### 14.1 O princípio: o mesmo agregado, e nenhum segundo Feed

A T17.9 **expande** o `WorkoutCheckIn` da T17.8. Não existe `SocialPost`, não existe uma segunda
publicação e não existe uma segunda rota de leitura:

```text
WorkoutCheckIn
       ├── caption?      0..280 caracteres, texto puro
       ├── media?        no máximo 1 imagem
       ├── reactions     FIRE | MUSCLE | CLAP
       └── comments      1..300 caracteres, texto puro
```

O que a T17.8 estabeleceu continua valendo inteiro: concluir um treino **não publica nada**, o
consentimento é por sessão, e o Feed é `FRIENDS_ONLY` avaliado a cada leitura. Foto, legenda,
reação e comentário não alteram `WorkoutSession`, XP, streak, conquista, missão, ranking nem
desafio.

Uma publicação criada pela T17.8 continua válida sem backfill: `caption = null`, `media = null`,
`reactions = {}`, `commentCount = 0`.

### 14.2 A política de visibilidade virou um objeto

A T17.8 tinha uma superfície (o Feed) e a regra vivia dentro daquela consulta. A T17.9 acrescentou
cinco — detalhe, bytes da foto, reações, comentários e denúncia de conteúdo —, e reimplementar
`amigo ∧ ativo ∧ ¬bloqueado` em cada uma é o desenho em que, no dia de um ajuste, quatro mudam e a
quinta continua respondendo o dado de quem não devia.

`workout-checkin.access-policy.ts` é a definição única:

```sql
viewer_blocked    = quem o viewer bloqueou  ∪  quem bloqueou o viewer
viewer_friends    = amigos diretos atuais
eligible_authors  = { viewer }  ∪  ( viewer_friends \ viewer_blocked )
```

Todas as consultas desta fase começam com essa CTE. Há teste estrutural: a string
`eligible_authors AS (` existe em **um** arquivo, e nenhum controller do módulo social escreve
`SELECT`.

### 14.3 A pipeline da imagem

```text
Android Photo Picker (PickVisualMedia)     ← sem permissão ampla, sem CAMERA
        │
        ▼  redução local: 1920 px, JPEG    ← economia de banda, não privacidade
POST /v1/social/checkin-media?sessionSyncId=&clientUploadId=
        │   corpo = bytes; Content-Type NÃO decide nada
        ▼
decode real (sharp / libvips)
        ├── animado?           → recusa (antes do formato: a mensagem fala de animação)
        ├── formato aceito?    → JPEG, PNG, WebP estático
        ├── pixels ≤ 20 MP, aresta ≤ 20000 px
        ▼
rotate()                        ← a orientação EXIF vira geometria, e a tag some com o resto
resize inside 1600 px, sem ampliar
re-encode WebP (sem withMetadata) ← é a AUSÊNCIA dessa chamada que remove EXIF/GPS
qualidade em degraus até ≤ 1,5 MB
        ▼
SocialMediaStore.write(chave opaca)
        ▼
linha PENDING, prazo de 1 hora
```

O que sai do pipeline é sempre um WebP que **este processo** produziu a partir de pixels que ele
mesmo decodificou. Não existe caminho em que os bytes recebidos sejam os bytes armazenados, e o
original nunca encosta no disco.

**Prova de privacidade:** `test/social-media.spec.ts` envia um JPEG com `Make`, `Model`,
`Software`, `DateTime` e coordenadas de GPS, baixa a imagem publicada e verifica que `exif`, `icc`,
`iptc` e `xmp` estão ausentes — mais uma varredura textual atrás de `SparkPhone`, `GPS` e `Exif`
nos bytes armazenados.

### 14.4 Storage

| | |
| --- | --- |
| Fronteira | `SocialMediaStore` (interface) — o domínio conhece `storageKey`, e só |
| Implementação | `ObjectStorageSocialMediaStore` sobre a camada neutra `ObjectStorageClient` (T18.1) |
| Providers | `local` (disco sob `SOCIAL_MEDIA_ROOT`) e `gcs` (bucket privado do Google Cloud Storage, autenticado por ADC) — escolhidos em **um** lugar, `object-storage.factory.ts` |
| Chave (banco) | `checkins/<2 hex>/<2 hex>/<uuid v4>.webp`, gerada no servidor — inalterada desde a T17.9 |
| Objeto (bucket) | `social/checkins/<2 hex>/<2 hex>/<uuid v4>.webp`; no provider `local`, `SOCIAL_MEDIA_ROOT/checkins/…` (o layout de sempre) |
| Escrita | **create-only**: `ifGenerationMatch = 0` no GCS, `wx` no disco. Uma chave nunca é sobrescrita |
| Raiz local | `SOCIAL_MEDIA_ROOT` — obrigatória em produção **com o provider `local`**; não participa de nada com `gcs` |
| Volume (VPS, provider `local`) | `/opt/spark/media` → `/media` no container |
| Path traversal | impossível: allowlist de forma + confinamento na raiz, duas barreiras |
| Quota | 250 MB por conta (`PENDING` + `ATTACHED`), configurável |
| Bytes no banco | **nunca** — o banco guarda metadata |
| URL pública, URL assinada, ACL pública | **nunca** — há teste estrutural |

A chave nunca deriva de uid, `socialId`, `friendCode`, `displayName` ou nome de arquivo original, e
nunca vem do cliente. Em produção com o provider `local`, subir sem `SOCIAL_MEDIA_ROOT` é **falha
de startup** (`AppConfig.missingRequirements`): um deploy que montasse o banco sem montar a mídia
perderia todas as fotos na primeira recriação de container — em silêncio. Com `gcs`, é a ausência
de `GCS_BUCKET_NAME` que derruba o startup: nunca há bucket default no código, e nunca há fallback
silencioso para o disco.

O Android continua sem saber onde os bytes moram: ele nunca recebe credencial GCS e nunca fala com
o bucket. `GET /v1/social/media/{id}` continua sendo o único caminho — token, autorização em SQL,
e só então os bytes. Uma falha do bucket (timeout, permissão, quota) responde `503`, e não `404`:
"não encontrada" continua significando ausência, nunca um incidente de infraestrutura escondido.

### 14.5 Ciclo de vida da mídia

```text
upload aceito
     ▼
  PENDING ──── anexada ao check-in ────▶ ATTACHED
     │                                      │
     │ 1 hora sem anexo                     │ check-in excluído / conta excluída
     ▼                                      ▼
  expira ──────────────────────────────▶ DELETED
                    │
                    ▼
        SocialMediaCleaner (a cada 15 min, lote de 200)
                    ├── remove o arquivo
                    ├── remove a linha
                    └── varre órfãos (arquivo sem metadata)
```

A **visibilidade** cai no instante da exclusão; o arquivo sai depois. É isso que permite ao
`DELETE` responder sem esperar I/O de sistema de arquivos, sem que exista um instante em que o post
sumiu e a foto ainda responde.

### 14.6 Autorização dos bytes

`GET /v1/social/media/{mediaId}` — autenticado, sempre. Não existe URL pública, diretório servido
estaticamente, URL assinada ou CDN.

Para os bytes saírem:

```text
viewer autenticado
  ∧ mídia ATTACHED
  ∧ check-in PUBLISHED
  ∧ autor ∈ eligible_authors(viewer)
  ∧ perfil do autor ACTIVE
  ∧ perfil do viewer ACTIVE
```

Qualquer falha responde `404`, indistinguível de inexistente. **Conhecer o `mediaId` não concede
nada.** Cabeçalhos: `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`, e
nenhum `ETag` — uma revalidação condicional devolveria `304` sem a política correr.

### 14.7 Legenda e comentário: texto, e nada mais

| | Legenda | Comentário |
| --- | --- | --- |
| Tamanho | 0..280 code points | 1..300 code points |
| Vazio | vira `null` (ausência) | é erro |
| Normalização | NFC, `CRLF`→`LF`, TAB→espaço, trim | idem |
| Quebras de linha | até 4, sequências colapsadas | idem |
| Recusado | C0/C1, zero-width, override bidirecional | idem |
| HTML/Markdown/JS | **não interpretado e não escapado** | idem |
| URL, `@menção`, `#hashtag` | texto | texto |

Não há escaping porque não há injeção em HTML neste caminho: o Android desenha com `Text` de
Compose, que renderiza `String`. Escapar antes de armazenar produziria `&amp;` visível para quem
escreveu `&` — corromper o texto da pessoa para se defender de um risco que não existe aqui.

A legenda **nunca** é preenchida a partir de `WorkoutSession.notes`, `WorkoutTemplate.notes` ou do
nome do treino: ela nasce só quando o usuário digita algo para publicar.

### 14.8 Reações

Enum fechado — `FIRE`, `MUSCLE`, `CLAP` — no contrato **e** no `CHECK` do banco. O cliente envia o
nome, e a tela escolhe o emoji. Uma reação por pessoa por publicação, garantida pela chave primária
`(checkin_id, reactor_uid)`: trocar 🔥 por 💪 é um `UPDATE`, nunca uma segunda linha.

Reagir exige conseguir ver o post **agora**. "Já reagiu antes" não é permissão: depois de um
`unfriend` ou de um bloqueio, a próxima requisição é recusada.

Não geram XP, não geram push, não entram na Activity da T17.4.

### 14.9 As contagens são **do viewer**, e não do post

Este é o ponto mais fácil de errar da fase:

```text
A ── amiga de ── C ── amiga de ── B
A bloqueia B
A reage ao post de C
```

Quando B abre o Feed, o post de C aparece — B e C continuam amigos —, mas a participação de A não
pode transparecer **nem como número**. Uma contagem global vazaria exatamente a informação que o
bloqueio esconde: que aquelas duas pessoas estão no mesmo lugar.

Por isso `countReactionsForCheckIns` e `countCommentsForCheckIns` recebem o `viewerUid`, e o mesmo
predicado (`interactionVisibleSql`) filtra os dois. Um card dizendo "3 comentários" com uma lista de
2 seria o bloqueio anunciando a si mesmo.

O Android **não** recalcula contagem nenhuma: a atualização otimista de reação mexe só no que a
ação do usuário determina, e a resposta do servidor substitui tudo em seguida.

### 14.10 Comentários — visibilidade e moderação

Um comentário é visível para o viewer quando:

```text
autor do comentário tem perfil ACTIVE
  ∧ ( autor do comentário = autor do post  ∨  são amigos )
  ∧ autor do comentário ∉ viewer_blocked
```

- **unfriend** com o autor do post esconde o comentário, sem hard delete;
- **bloqueio** é aplicado **por viewer**, e nunca apaga globalmente: o comentário de B no post de C
  continua visível para C depois de A bloquear B.

Podem apagar: o **autor do comentário** e o **autor do check-in** (moderação do próprio post). Um
terceiro recebe `404` — a mesma resposta de "não existe". `canDelete` no DTO é decidido no
servidor; a tela usa o booleano para desenhar o menu, e o servidor recusa de qualquer forma.

Sem edição: para corrigir, apaga e cria outro.

### 14.11 O DTO do Feed — campo a campo

```json
{
  "type": "WORKOUT_CHECK_IN",
  "checkInId": "5f3c…",
  "author": { "socialId": "8b1e…", "displayName": "Igor" },
  "publishedAt": 1788912345678,
  "caption": "Hoje rendeu demais",
  "media": { "mediaId": "a7d0…", "width": 1080, "height": 1350 },
  "reactions": { "FIRE": 4, "MUSCLE": 3, "CLAP": 2 },
  "currentUserReaction": "FIRE",
  "commentCount": 5,
  "isCurrentUser": false
}
```

Não cruzam a fronteira: uid, e-mail, `friendCode`, `sessionSyncId`, `storageKey`, `contentHash`,
URL de qualquer tipo, base64, `startedAt`, `finishedAt`, `templateId`, nome do treino, exercício,
série, repetição, carga, duração, volume, PR, caloria, nota, medida e horário do treino. Há teste
que varre a resposta real atrás de todos.

### 14.12 Denúncia — a evolução da T17.6

```text
targetType ∈ { USER, CHECKIN, COMMENT }
targetId
reason      ← a mesma taxonomia da T17.6
```

**Não existe `MEDIA`**: a foto pertence ao check-in. O alvo é resolvido no servidor — o cliente diz
*o que*, e o banco responde *de quem*. `reportedUid` é recusado **por nome**; aceitá-lo deixaria
qualquer pessoa registrar denúncia contra a conta que quisesse, apontando para conteúdo que nem é
dela.

Denunciar exige conseguir ver o alvo, não permite denunciar o próprio conteúdo, não pune, não
oculta nada e não notifica ninguém.

A forma antiga (`{ reportedSocialId, reason }`) continua aceita: um APK já instalado não pode parar
de denunciar porque o servidor subiu.

### 14.13 Matriz de acesso

| Viewer | Post | Foto | Reagir | Comentar | Denunciar |
| --- | --- | --- | --- | --- | --- |
| autor | ✅ | ✅ | ✅ | ✅ | ❌ (próprio) |
| amigo ativo | ✅ | ✅ | ✅ | ✅ | ✅ |
| não-amigo | 404 | 404 | 404 | 404 | 404 |
| bloqueado (qualquer direção) | 404 | 404 | 404 | 404 | 404 |
| autor desativou o Social | 404 | 404 | 404 | 404 | 404 |
| viewer desativou o Social | 403 | 404 | 403 | 403 | — |
| check-in excluído | 404 | 404 | 404 | 404 | 404 |

### 14.14 Matriz de exclusão (atualiza a §13.9)

| Evento | Banco | Arquivo |
| --- | --- | --- |
| excluir check-in | soft delete; mídia → `DELETED` na mesma transação | removido pelo cleaner |
| excluir comentário | soft delete, idempotente | — |
| remover reação | linha removida | — |
| mídia `PENDING` expirada | linha removida | removido pelo cleaner |
| exclusão de conta | check-ins, legendas, mídia, comentários (inclusive em posts alheios) e reações | chaves lidas **antes** do purge, arquivos removidos depois do commit |
| restore antigo + reconciliação | repurga o banco da conta com tombstone | purga os arquivos ressuscitados |
| órfão (objeto sem metadata) | — | recolhido pela varredura, **só depois de 24 h de carência** (T18.1): um objeto recente pode ser um upload cuja linha ainda não commitou |

### 14.15 Backup e DR

O backup off-site leva **duas** coisas:

```text
PostgreSQL ──pg_dump --format=custom──▶ snapshot ──pg_restore --list──▶ manifesto ──┐
                                                                                     ├──▶ restic ──▶ off-site
/opt/spark/media  ────────────────────────────────────────────────────────────────┘
```

A mídia entra como segundo caminho do mesmo `restic backup`: sem cópia extra em disco, deduplicada
entre snapshots (as fotos são imutáveis depois de escritas) e criptografada antes de sair da VPS.

**Isso descreve o provider `local`.** Com `OBJECT_STORAGE_PROVIDER=gcs` (T18.1) as fotos vivem no
bucket privado, não em disco nenhum da VPS: `/opt/spark/media` fica vazio, o `restic` não as leva, e
a durabilidade delas é a do bucket. O `pg_dump` continua levando a metadata (`storage_key`,
`content_hash`), e a reconciliação de DR (`reconcile-account-deletions`) usa o **mesmo** provider
do runtime — com `gcs`, ela purga o bucket. Proteção do bucket contra exclusão acidental é T18.3.

Histórico: até a T18.0.2 o banco era um arquivo SQLite (`spark.db`), copiado por `VACUUM INTO` e
verificado por `integrity_check`. Desde a T18.0 o banco do servidor é PostgreSQL (`DATABASE_URL`);
não existe mais arquivo de banco na VPS. O Room do Android continua usando SQLite localmente — é
autoridade local do app, e não tem relação com este backup do servidor (§14.16).

`ops/restore.sh` restaura os dois e instala os dois (`--install`), preservando o diretório anterior
em `media.pre-restore-<timestamp>`. `ops/verify-backup.sh` sobe o backend real sobre a cópia e
**lê os bytes de dentro do container** — o modelo de grupo compartilhado precisa sobreviver à
restauração, e "o arquivo está lá" não prova que ele abre.

Depois de um restore antigo, a reconciliação de tombstones (T17.6) purga banco **e** arquivos da
conta excluída. Purgar só o banco deixaria as fotos ressuscitadas no disco, sem metadata que as
revogue.

### 14.16 Android

| Peça | Onde | Decisão |
| --- | --- | --- |
| Seleção de foto | `ActivityResultContracts.PickVisualMedia` | sem `READ_MEDIA_IMAGES`, sem `CAMERA` |
| Redução local | `SocialPhotoOptimizer` | 1920 px, JPEG; orientação aplicada aos pixels |
| Cache de exibição | `SocialMediaCache` | **memória**, account-scoped, um `Mutex` por `mediaId` |
| Compositor | `ShareCheckInSection` | preview dos bytes que serão enviados; remover é permitido |
| Feed | `SocialFeedScreen` | foto, legenda, barra de reações, contagem de comentários |
| Detalhe | `CheckInDetailScreen` | conversa; sem bottom navigation nova |

Não existe cache em disco de mídia social. A foto de um amigo é conteúdo autenticado de outra
pessoa, e um arquivo no disco sobrevive ao logout e à troca de conta. O custo — trocar de tela
rebaixa as imagens — é aceito.

**Reação é otimista; comentário não é.** A reação é reversível e barata: a tela responde na hora e
reconcilia, com rollback para o estado que veio do servidor. O comentário espera a resposta: ele
carrega texto que a pessoa escreveu, e um que aparece e some faz quem escreveu acreditar que a
outra pessoa leu. Quando o envio falha, o rascunho **permanece** no campo.

**A foto que falha não some em silêncio.** A publicação para, e a tela oferece "Tentar novamente" e
"Publicar sem foto". Nenhuma das duas acontece sozinha — publicar sem a foto que a pessoa escolheu
seria tomar por ela uma decisão que é dela.

### 14.17 O que a T17.9 deliberadamente não faz

Vídeo, GIF animado, múltiplas fotos, carrossel, Stories, DM, hashtag, menção, link clicável ou
preview de link, edição de publicação ou de comentário, feed público, seguidores, perfil público,
repost, compartilhamento externo, download da foto, avatar, push de reação ou de comentário, e
qualquer preferência de notificação nova.
