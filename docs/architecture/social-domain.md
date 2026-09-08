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
  Remove registros em todas as tabelas vinculadas ao `uid` do usuário: `social_profiles`, `social_privacy_settings`, `friendships`, `friend_requests`, `challenge_participants`, `challenges` (órfãos), `social_activity_events`, `social_blocks`, `social_reports`, `device_installations`, `notification_outbox`, `backup_snapshots`, `sync_changes`.
- **Tombstones Criptográficos (HMAC-SHA256)**:
  Gera um hash HMAC com salt e segredo do servidor (`account_deletion_tombstones`) para impedir ressurreição ou reutilização da identidade, interceptando qualquer chamada subsequente no `BearerAuthGuard` com HTTP 403 `ACCOUNT_DELETED`.
- **Fila Assíncrona de Exclusão no Firebase Auth (`account_deletion_jobs`)**:
  Se a chamada ao Firebase Admin Auth (`deleteUser(uid)`) falhar por indisponibilidade transitória do serviço, um job de retentativa com backoff exponencial garante a exclusão final no provider.
- **Disaster Recovery Append-Only Log (`deletion_tombstones.tsv`)**:
  Cada exclusão registra um evento no arquivo de tombstones para recuperação de desastres, auditável e com ferramenta de reconciliação de integridade (`reconcileTombstones`).

### 11.4 Preservação Local-First (Android)
- **Princípio Inviolável**: O banco de dados local do Room (sessões de treino, templates, histórico de exercícios, medidas corporais, recordes pessoais) **pertence ao dispositivo e nunca é apagado** durante a exclusão de conta na nuvem.
- **Desvinculação Local**: `cloudDataBindingDao.deleteBinding()` remove o vínculo do dataset local com a conta excluída, desregistra o token de push e efetua o logout no FirebaseAuth.
- **Confirmação Explícita de Alto Risco**: A UI exige confirmação em modal de dois passos com digitação explícita da palavra "EXCLUIR", deixando claro que os treinos no aparelho serão mantidos.

