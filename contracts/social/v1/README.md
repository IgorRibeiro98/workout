# Contrato do domínio social do Spark — v1

- **Tarefas:** T17.0 — identidade pública e privacidade; **T17.1** — grafo social (amizade
  bilateral, pedidos, descoberta por `friendCode` e QR Code); **T17.2** — perfil enriquecido
  (projeção de progresso e compartilhamento controlado); **T17.3** — desafios entre amigos;
  **T17.4** — atividade dos amigos + rankings contextuais. **Não** inclui bloqueio, denúncia,
  notificação push, busca pública por nome, busca por e-mail nem exclusão de conta.
- **Implementações:**
  - Android — `com.example.data.social.*` (`SocialContract`, `FriendshipContract`,
    `SocialProfileContract`, `SocialActivityDtos`, `SparkSocialActivityGateway`)
  - Backend — `backend/src/modules/social/` (`social.contract.ts`, `friendship.contract.ts`,
    `social-profile.contract.ts`, `canonical-training.source.ts`, `social-activity.service.ts`,
    `friend-ranking.service.ts`)
- **Documentos de decisão:**
  [`docs/architecture/social-domain.md`](../../../docs/architecture/social-domain.md) (T17.0),
  [`docs/architecture/friendship-contract.md`](../../../docs/architecture/friendship-contract.md)
  (T17.1),
  [`docs/architecture/social-profile-contract.md`](../../../docs/architecture/social-profile-contract.md)
  (T17.2),
  [`docs/architecture/challenge-domain.md`](../../../docs/architecture/challenge-domain.md) (T17.3) e
  [`docs/architecture/social-activity-ranking.md`](../../../docs/architecture/social-activity-ranking.md) (T17.4)
- **Fixtures compartilhadas:**
  - [`friend-code-normalization.json`](./friend-code-normalization.json) — os casos canônicos de
    normalização de `friendCode`;
  - [`weekly-window.json`](./weekly-window.json) — a semana canônica do Spark (segunda a domingo,
    data local) e a contagem de treinos concluídos dentro dela.

  As duas são lidas pelos testes **dos dois lados**. É o que impede a cópia do Android e a regra do
  servidor de divergirem em silêncio.

Este arquivo é a definição legível do que os dois lados precisam concordar. Ele existe pelo mesmo
motivo que [`contracts/backup/v1/`](../../backup/v1/README.md) e
[`contracts/sync/v1/`](../../sync/v1/README.md): um formato que muda de um lado só deve quebrar os
dois, e não produzir um bug silencioso no aparelho de alguém.

---

## 1. Social é server-authoritative. Treino continua local-first.

```text
TREINO   ação → domínio → Room → Outbox → Spark Backend      T16.3–T16.7
SOCIAL   ação → Spark Backend → resultado → UI/cache         T17
```

Não há protocolo de sync aqui: nem `revision`, nem `cursor`, nem `baseRevision`, nem
`clientMutationId`, nem tombstone. Uma ação social exige conta e servidor — e isso é intencional.
O que o aparelho não pode fazer offline é exatamente o que ele não pode decidir sozinho.

Consequências que valem como contrato:

- **Social não entra na Outbox.** Uma edição feita offline **não acontece**: ela não fica pendente
  e não é reenviada depois.
- **Social não usa `sync_entities` nem tombstones.** O perfil vive em tabelas próprias.
- **Social não aparece no backup nem no restore.** Restaurar um snapshot em um celular novo não
  cria nem altera perfil social; entrar na conta e ler `GET /v1/social/me` traz o perfil que já
  existe no servidor.
- **Room não é autoridade social.** Não existe `SocialProfileEntity`. O que o app guarda é a
  última leitura, em memória, invalidada ao trocar de conta.

## 2. Identidades

| Peça | O que é | Quem gera | Pública? |
| --- | --- | --- | --- |
| Firebase UID | quem é o usuário (conta) | Firebase | **Não** — infraestrutura |
| `socialId` | identidade social estável | **servidor** (UUID v4) | Sim |
| `friendCode` | convite/descoberta controlada | **servidor** (CSPRNG) | Compartilhável |
| `displayName` | nome social exibido | usuário | Sim, e não é único |
| `deviceId` | qual instalação | dispositivo | Não — nunca social |
| `syncId` | qual entidade de treino | dispositivo | Não — nunca social |
| `clientMutationId` | qual mutação de sync | dispositivo | Não — nunca social |

**Nenhuma identidade é reutilizada para a função de outra.** Em particular: o Firebase UID nunca é
identificador social, e `email`, `localId`, `deviceId` e `syncId` **não** são aceitos como
identificador social em lugar nenhum.

`ownerUid` não existe em nenhum corpo de requisição nem em nenhuma resposta. Enviá-lo — ou enviar
`uid`, `socialId`, `friendCode`, `status`, `createdAt`, `updatedAt` ou `email` — recusa a
requisição inteira com `INVALID_SOCIAL_REQUEST`. Ignorar em silêncio seria pior: um cliente que
manda `ownerUid` acredita que ele significa alguma coisa.

**Conhecer um `socialId` ou um `friendCode` não concede permissão nenhuma.** Toda autorização
continua saindo do Firebase ID Token verificado.

## 3. `friendCode`

```text
SPK-7K2P9D8Q
└┬┘ └───┬──┘
 │      └── 8 símbolos de ABCDEFGHJKMNPQRSTUVWXYZ23456789   (31^8 ≈ 8,5 × 10^11)
 └───────── prefixo fixo, sem entropia
```

- **Gerado no servidor**, por CSPRNG (`crypto.randomInt`, com rejeição de amostra — `% 31`
  enviesaria os primeiros símbolos e reduziria a entropia real).
- **Alfabeto sem ambiguidade:** sem `0`, `O`, `1`, `I`, `L`. Um código é lido em voz alta,
  digitado de uma foto e copiado de um bilhete.
- **Único**, por `UNIQUE` no banco. Colisão faz o servidor sortear de novo, até 5 vezes; esgotar
  responde `503 SOCIAL_UNAVAILABLE` — nunca `500`, e nunca um perfil sem código.
- **Não é customizável pelo usuário.** Isso evita `admin`, `spark`, `suporte` e o resto do
  problema de impersonação/namesquatting.
- **Não é credencial.** Ele não autentica nada; a entropia existe para impedir colheita em massa
  de perfis, não para proteger uma sessão.

### Normalização — a mesma dos dois lados, e a que o lookup da T17.1 usa

```text
spk-7k2p9d8q  ┐
SPK7K2P9D8Q   ├──▶  SPK-7K2P9D8Q      (forma canônica; é ela que vai para o banco)
 SPK 7K2P9D8Q ┘
```

Regra: maiúsculas em `en-US` (fixo — em turco `i` vira `İ`), remove `-` e espaços, exige `SPK` +
8 símbolos do alfabeto. Qualquer outra coisa é recusada — inclusive os ambíguos, que **não** são
"corrigidos" para um vizinho.

A recusa é um valor (`null`), não uma exceção: no lookup da T17.1, um código malformado e um
código inexistente são **a mesma resposta**, senão a rota vira um validador de formato
gratuito para quem estiver tentando enumerar.

## 4. `displayName`

- 2 a 40 caracteres, contados em **code points** (um emoji é um caractere, não dois).
- `trim` antes de medir. Unicode permitido: acento, emoji, alfabeto não latino.
- Recusados: vazio após normalização, controle (`U+0000`–`U+001F`, `U+007F`–`U+009F`) e marcas de
  direção bidirecional (`U+202A`–`U+202E`, `U+2066`–`U+2069`, `U+200E`, `U+200F`) — os dois
  últimos grupos permitem desenhar um nome que finge ser outra coisa na tela.
- **Não é único.** "Igor", "João Neto" e "Jonathas" podem coexistir; a identidade única é o
  `socialId`.

O nome da conta Google entra como **sugestão inicial** no Android. Depois da ativação, o perfil
social é a autoridade do próprio nome: trocar o nome no Google não reescreve nada.

## 5. Privacidade

| Campo | Default | Observação |
| --- | --- | --- |
| `discoverability` | `FRIEND_CODE_ONLY` | único valor aceito, e o único mecanismo de descoberta |
| `friendRequestsEnabled` | `true` | respeitado pela T17.1: `false` recusa novos pedidos |
| `activitySharingEnabled` | `false` | consumido pela T17.4 quando houver atividade |

Não existe `PUBLIC_SEARCH`, `GLOBAL_PROFILE` nem `ACTIVITY_PUBLIC` — nem como default, nem como
valor. Busca pública por nome, busca por e-mail e listagem global de usuários **não existem neste
servidor**, e declarar o valor descreveria um comportamento inexistente.

### Compartilhamento de progresso (T17.2)

Preferências separadas, em tabela própria (`social_progress_settings`), porque respondem a outra
pergunta: as de cima dizem *quem pode me alcançar*; estas dizem *o que aparece no meu perfil*.

| Campo | Default | Observação |
| --- | --- | --- |
| `shareLevel` | `false` | o campo nunca aparece nesta versão: sem autoridade remota (`UNSUPPORTED`) |
| `shareConsistencyStreak` | `false` | idem |
| `shareWeeklyWorkoutCount` | `false` | aparece quando há sessão sincronizada e fuso declarado |
| `shareHighlightedAchievements` | `false` | idem `shareLevel`; a seleção de destaques não existe |
| `weekTimeZone` | `null` | **não é privacidade**: é o fuso IANA que torna a semana canônica reproduzível no servidor |

O cliente **nunca** envia `level`, `streak`, `weeklyWorkoutCount`, `totalXp` nem lista de
conquistas: o validador recusa a requisição inteira, por nome de campo.

## 6. Endpoints

Todos sob `/v1/social`, todos exigem `Authorization: Bearer <Firebase ID Token>`, e o dono é
sempre o `uid` do token. **Não existe rota social pública.**

### `GET /v1/social/me`

```json
{ "enabled": false }
```

```json
{
  "enabled": true,
  "profile": {
    "socialId": "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d",
    "friendCode": "SPK-7K2P9D8Q",
    "displayName": "Igor",
    "status": "ACTIVE",
    "privacy": {
      "discoverability": "FRIEND_CODE_ONLY",
      "friendRequestsEnabled": true,
      "activitySharingEnabled": false,
      "updatedAt": 1788800000000
    },
    "createdAt": 1788800000000,
    "updatedAt": 1788800000000
  }
}
```

`{ "enabled": false }` e **não** `404`: "ainda não ativei" é um estado normal do produto, e o mais
comum de todos. Ler **não cria** perfil.

### `POST /v1/social/me/activate`

```json
{ "displayName": "Igor" }
```

Resposta `200` com `{ "profile": { ... } }`. O servidor gera `socialId`, `friendCode` e os
defaults de privacidade.

**Idempotente.** Uma conta que já tem perfil recebe o perfil que já tem — sem mutação, sem erro,
com o mesmo `socialId` e o mesmo `friendCode`. Isso cobre o reenvio depois de resposta perdida e
duas ativações simultâneas de aparelhos diferentes; as duas convergem para **um** perfil.

Consequência deliberada: o `displayName` de uma segunda ativação é ignorado. Ativar é "garanta que
eu tenho identidade social"; renomear é `PATCH`. Um perfil `DISABLED` também volta como está —
reativar é `POST /me/enable`, e fazer a ativação religar em silêncio esconderia do usuário que ele
tinha desativado.

`200`, e não `201`: alternar entre os dois faria um reenvio parecer diferente de uma criação.

### `PATCH /v1/social/me`

```json
{ "displayName": "Igor Ribeiro" }
```

Só o nome. `socialId`, `friendCode` e `ownerUid` **não** são alteráveis — uma identidade que muda
por requisição do cliente não é identidade. Rotação de `friendCode` está fora de escopo na T17.0.

### `PATCH /v1/social/me/privacy`

```json
{ "friendRequestsEnabled": false, "activitySharingEnabled": true }
```

Parcial: o que não vier no corpo continua como está. Corpo vazio é recusado — ele quase sempre
significa que o cliente montou a requisição errado.

### `POST /v1/social/me/disable` · `POST /v1/social/me/enable`

Sem corpo. Resposta `200` com o perfil.

- `disable` sobre `ACTIVE` → `DISABLED`; sobre `DISABLED` → `409 SOCIAL_ALREADY_DISABLED`.
- `enable` sobre `DISABLED` → `ACTIVE`; sobre `ACTIVE` → `409 SOCIAL_ALREADY_ENABLED`.
- Qualquer um sem perfil → `404 SOCIAL_NOT_ENABLED`.

Os dois `409` não são falhas de tela no Android: eles significam "outro aparelho já fez isso", e o
app **recarrega** o perfil em vez de acusar erro.

**Desativar não apaga nada.** Nem Conta Spark, nem conta Firebase, nem backups, nem sync, nem
treinos, nem histórico, nem medidas, nem gamificação. A linha permanece com `status = DISABLED`, e
é ela que preserva `socialId` e `friendCode` para uma reativação futura.

## 7. Erros

Envelope da T16.0: `{ "error": { "code", "message", "requestId" } }`.

| `code` | HTTP | Quando |
| --- | --- | --- |
| `UNAUTHENTICATED` | 401 | sem token, token inválido |
| `AUTH_UNAVAILABLE` | 503 | o servidor não conseguiu verificar o token |
| `API_RATE_LIMITED` | 429 | teto geral por conta (600/min) |
| `INVALID_SOCIAL_REQUEST` | 400 | corpo fora do contrato, campo server-side, campo desconhecido |
| `INVALID_DISPLAY_NAME` | 400 | nome fora das regras de forma |
| `SOCIAL_NOT_ENABLED` | 404 | alterar/desativar/reativar sem perfil |
| `SOCIAL_ALREADY_ENABLED` | 409 | `enable` sobre perfil ativo |
| `SOCIAL_ALREADY_DISABLED` | 409 | `disable` sobre perfil desativado |
| `SOCIAL_UNAVAILABLE` | 503 | geração de `friendCode` esgotou as tentativas |

Nenhuma mensagem repete conteúdo do usuário: elas descrevem a **forma** do defeito, nunca o valor.

## 8. Rate limit

Só o teto geral por conta do `BearerAuthGuard` (600 requisições/minuto). As rotas sociais escrevem
um nome e três booleanos — não há custo assimétrico como o do backup ou o do Coach que justifique
política nova.

O lookup por `friendCode` da T17.1 **tem** teto próprio (20/min por conta), e o envio de pedidos
também (15/min): o lookup é a rota que alguém tentaria varrer, e um bug em laço no envio não pode
virar centenas de convites.

## 9. Logs

Permitido: `requestId`, `uidPrefix` (6 caracteres), evento, status, duração, quantas tentativas de
geração de código foram necessárias, e **quais chaves** de privacidade mudaram.

Proibido: `Authorization`, Firebase UID completo, e-mail, `displayName`, `friendCode`, `socialId`,
corpo da requisição.

## 10. O grafo social (T17.1)

Construído sobre a identidade acima, sem redesenhá-la:

```text
friendCode → normalização (§3) → match exato → SocialProfilePreview → FriendRequest → Friendship
```

### Rotas

| Rota | O quê |
| --- | --- |
| `POST /v1/social/friends/lookup` | `{ friendCode }` → `FOUND` + preview / `SELF` / `NOT_FOUND` |
| `POST /v1/social/friend-requests` | `{ socialId }` → `REQUEST_CREATED` / `REQUEST_ALREADY_PENDING` / `FRIENDSHIP_CREATED` |
| `GET /v1/social/friend-requests/incoming` | recebidos, pendentes, mais recentes primeiro |
| `GET /v1/social/friend-requests/outgoing` | enviados, pendentes |
| `POST /v1/social/friend-requests/:id/accept` | só o destinatário |
| `POST /v1/social/friend-requests/:id/reject` | só o destinatário |
| `POST /v1/social/friend-requests/:id/cancel` | só quem enviou |
| `GET /v1/social/friends` | `socialId`, `displayName`, `friendsSince` |
| `POST /v1/social/friends/remove` | `{ socialId }` → `REMOVED` |

`friendCode` e `socialId` viajam no **corpo**, nunca na URL: a URL é a parte que vaza mais fácil
(log de proxy, log de acesso, histórico), e um log de acesso com códigos em texto claro é uma lista
de convites válidos. As listas têm `limit` (padrão 50, teto 100), `cursor` opaco e `total`.

### Erros da T17.1

| `code` | HTTP | Quando |
| --- | --- | --- |
| `INVALID_FRIEND_REQUEST` | 400 | corpo/parâmetro fora do contrato |
| `SELF_FRIEND_REQUEST` | 400 | pedido para si mesmo |
| `FRIEND_REQUESTS_DISABLED` | 403 | o destinatário desligou os pedidos |
| `NOT_REQUEST_RECIPIENT` · `NOT_REQUEST_SENDER` | 403 | participante na função errada |
| `SOCIAL_PROFILE_NOT_FOUND` | 404 | `socialId` inexistente **ou** desativado |
| `FRIEND_REQUEST_NOT_FOUND` | 404 | pedido inexistente **ou** de terceiros |
| `FRIENDSHIP_NOT_FOUND` | 404 | não há amizade para desfazer |
| `SOCIAL_PROFILE_DISABLED` | 409 | o **chamador** desativou os recursos sociais |
| `ALREADY_FRIENDS` | 409 | já são amigos |
| `FRIEND_REQUEST_NOT_PENDING` | 409 | o pedido já é terminal |
| `SOCIAL_RATE_LIMITED` | 429 | teto próprio do lookup (20/min) ou do envio (15/min) |

### QR Code

```text
spark://friend/v1/SPK-7K2P9D8Q
```

Só o código, com esquema e versão. **Proibido:** Firebase UID, e-mail, token, `socialId`,
`deviceId`, `syncId` e endereço de servidor. Gerado no aparelho; o parser devolve um código ou uma
recusa, e **nunca** navega, abre `Intent` ou carrega URL.

### Regras da T17.0 que a T17.1 respeita

- **`status = DISABLED` não é descobrível** — a resposta é a mesma de um código inexistente;
- **`friendRequestsEnabled = false` recusa o pedido**;
- **match exato sobre a forma normalizada**, sem *fuzzy matching*, sem prefixo, sem sugestão;
- **rate limit próprio** na rota de lookup;
- **o preview não carrega `ownerUid`, `email`, `friendCode`, provedor de autenticação nem
  metadata de backup** — só `socialId` e `displayName`;
- **nenhuma listagem global**, nenhuma busca por nome, nenhuma busca por e-mail;
- **`SocialAccessPolicy`** (`backend/src/modules/social/social.access-policy.ts`) é onde as
  respostas de visibilidade moram — e não um `if (privacy...)` em cada controller;
- **`SocialProjection`** (`social.projection.ts`) é o único caminho para transformar dado privado
  em informação social — e a T17.2 escreveu a primeira projeção passando por ele.

E as que a T17.1 acrescentou:

- **amizade é um par**, canonicalizado como `min(uid), max(uid)`, com chave primária e
  `CHECK (user_a_uid < user_b_uid)`: duplicata A-B, duplicata B-A e amizade consigo mesmo são
  irrepresentáveis no banco — não "verificadas pelo serviço";
- **pedido cruzado auto-aceita**, deterministicamente e em uma transação: quando os dois pedem, o
  consentimento bilateral já existe;
- **estado terminal não volta para `PENDING`**; pedir de novo é uma operação nova, com id novo;
- **enviar, aceitar, recusar e cancelar são idempotentes** — repetir uma operação bem-sucedida é
  sucesso, e a garantia é escrita condicional no banco, não flag em memória;
- **perfil desativado suspende as relações, e não as apaga**: elas ficam invisíveis e inacionáveis
  dos dois lados, e voltam inteiras ao reativar;
- **nada disso entra na Outbox, no `sync_entities`, no backup ou no restore.**

## 11. O perfil enriquecido (T17.2)

```text
GET   /v1/social/friends/{socialId}/profile   perfil de um amigo (exige amizade ativa)
GET   /v1/social/me/profile-preview           o que um amigo veria de mim agora
GET   /v1/social/me/progress-sharing          minhas preferências + disponibilidade por campo
PATCH /v1/social/me/progress-sharing          altera preferências (parcial) e o fuso
```

### O que um amigo recebe

```json
{
  "profile": {
    "socialId": "...",
    "displayName": "Igor",
    "sharedProgress": { "weeklyWorkoutCount": 3 }
  }
}
```

`sharedProgress` está sempre presente e pode estar vazio. **Campo não compartilhado não existe no
JSON** — não há `null`, não há flag e não há marcador. Escondido e indisponível são a mesma
ausência para quem olha; só o dono distingue os dois, em `progress-sharing`.

E **nunca**: `ownerUid`, Firebase UID, e-mail, `friendCode`, flags de privacidade, `lastSyncAt`,
presença, sessão de treino, série, carga, exercício, nota, horário, medida corporal, PR, payload de
sync ou de backup.

### Disponibilidade — só na resposta do dono

| Valor | Significa | Ação |
| --- | --- | --- |
| `AVAILABLE` | há dado canônico agora | — |
| `UNAVAILABLE` | suportado, e o servidor ainda não sabe | sincronizar resolve |
| `UNSUPPORTED` | não há autoridade remota nesta versão | sincronizar **não** resolve |

### Erros da T17.2

| `code` | HTTP | Quando |
| --- | --- | --- |
| `FRIEND_PROFILE_NOT_FOUND` | 404 | alvo inexistente, desativado, sem amizade, ou só com pedido pendente — **a mesma resposta para os quatro** |
| `INVALID_PROGRESS_SETTINGS` | 400 | corpo fora do contrato, valor de progresso enviado, fuso inválido |

### Regras que a T17.2 acrescentou

- **amizade ativa é a única porta.** Pedido pendente não concede acesso, e `unfriend` revoga na
  requisição seguinte — a amizade é verificada em cada leitura;
- **o visitante também precisa estar `ACTIVE`.** Quem desativou o Social não consome perfil social;
- **o servidor nunca aceita progresso do cliente**, e a recusa é da requisição inteira;
- **ausência de dado não vira zero.** Sem sessão sincronizada, a contagem semanal é omitida — nunca
  publicada como `0`;
- **a semana é a canônica do Spark** (`ConsistencyCalculator.weekStart`, segunda a domingo, data
  local), e a fixture [`weekly-window.json`](./weekly-window.json) amarra os dois lados;
- **só sessões `COMPLETED` contam**;
- **não há cache de perfil**, nem no servidor nem no aparelho — é isso que faz revogar funcionar;
- **nada disso entra na Outbox, no `sync_entities`, no backup ou no restore**, e nada dá XP,
  conquista ou missão.

---

## T17.3 — desafios entre amigos

Espelhos: `backend/src/modules/social/challenge.contract.ts` e
`com.example.data.social.ChallengeContract`. Descrição legível em
[`docs/architecture/challenge-domain.md`](../../../docs/architecture/challenge-domain.md).

### Rotas

| Método | Caminho | Quem |
| --- | --- | --- |
| `POST` | `/v1/social/challenges` | criador — cria e convida, em uma transação |
| `GET` | `/v1/social/challenges` | participante — os meus |
| `GET` | `/v1/social/challenges/{challengeId}` | participante — regras + placar |
| `POST` | `/v1/social/challenges/{challengeId}/cancel` | só o criador |
| `POST` | `/v1/social/challenges/{challengeId}/leave` | só membro |
| `GET` | `/v1/social/challenge-invitations` | destinatário — preview, **sem placar** |
| `POST` | `/v1/social/challenge-invitations/{invitationId}/accept` | só o destinatário |
| `POST` | `/v1/social/challenge-invitations/{invitationId}/decline` | só o destinatário |

### O corpo da criação

```json
{
  "clientRequestId": "b6f1...",
  "name": "12 treinos",
  "type": "WORKOUTS_COMPLETED",
  "target": 12,
  "startDate": "2026-09-10",
  "endDate": "2026-10-09",
  "timeZoneId": "America/Sao_Paulo",
  "invitedSocialIds": ["...", "..."]
}
```

### Campos recusados **por nome** (invalidam a requisição inteira)

```text
pontuação    score · progress · currentScore · points · count · rank · ranking
             winner · goalReached · leaderboard · participants
identidade   ownerUid · uid · creatorUid · firebaseUid · email · participantUids
derivados    status · lifecycle · startsAt · endsAtExclusive · cancelledAt
             createdAt · updatedAt
```

### Tipos, estados e limites

| | Valores |
| --- | --- |
| `type` | `WORKOUTS_COMPLETED`, `ACTIVE_DAYS` |
| `status` (derivado) | `UPCOMING`, `ACTIVE`, `ENDED`, `CANCELLED`, `VOID` |
| status de convite (derivado) | `PENDING`, `ACCEPTED`, `DECLINED`, `EXPIRED`, `CANCELLED` |
| papel · participação | `CREATOR`/`MEMBER` · `JOINED`/`WITHDRAWN` |
| nome | 3–60 code points, Unicode, sem controle |
| duração | 1–90 dias inclusivos, começando no dia seguinte |
| meta | 1–200 (`WORKOUTS_COMPLETED`) · 1..duração (`ACTIVE_DAYS`) |
| participantes | 10, incluindo o criador; mínimo 2 para competir |

### Erros da T17.3

| `code` | HTTP | Quando |
| --- | --- | --- |
| `INVALID_CHALLENGE_REQUEST` | 400 | corpo fora do contrato — inclusive campo de pontuação |
| `INVALID_CHALLENGE_TYPE` | 400 | tipo sem fonte canônica neste servidor |
| `INVALID_CHALLENGE_TARGET` | 400 | meta fora de faixa, ou impossível para a duração |
| `INVALID_CHALLENGE_PERIOD` | 400 | datas fora de forma, invertidas, longas demais, ou não começando amanhã |
| `INVALID_CHALLENGE_TIMEZONE` | 400 | fuso que o runtime não conhece |
| `TOO_MANY_PARTICIPANTS` | 400/409 | acima do teto, ou desafio cheio no aceite |
| `CHALLENGE_PARTICIPANT_NOT_AVAILABLE` | 409 | convidado inexistente, desativado ou não amigo — **a mesma resposta para os três** |
| `TOO_MANY_OPEN_CHALLENGES` | 409 | teto de desafios abertos por criador |
| `CHALLENGE_NOT_FOUND` | 404 | inexistente, de terceiros, ou só convidado — **a mesma resposta** |
| `CHALLENGE_INVITATION_NOT_FOUND` | 404 | convite inexistente, de outra conta, ou amizade desfeita antes do aceite |
| `CHALLENGE_INVITATION_NOT_PENDING` | 409 | convite já respondido |
| `CHALLENGE_ALREADY_STARTED` | 409 | aceite depois do início |
| `CHALLENGE_CANCELLED` | 409 | o criador cancelou |
| `NOT_CHALLENGE_CREATOR` | 403 | só o criador cancela |
| `CANNOT_LEAVE_AS_CREATOR` | 409 | o criador cancela em vez de sair |
| `CHALLENGE_IDEMPOTENCY_CONFLICT` | 409 | mesmo `clientRequestId`, conteúdo diferente |
| `CHALLENGE_RATE_LIMITED` | 429 | teto de criação (5/min) ou de respostas (30/min) |

### Regras que a T17.3 acrescentou

- **o cliente nunca envia pontuação**, e a recusa é da requisição inteira;
- **a pontuação é derivada na leitura** dos dados canônicos — não há coluna de placar no schema;
- **`startedAt` é o instante canônico** (o Spark não tem `completedAt`, e `finishedAt` é nulável);
- **elegibilidade é o instante do treino, nunca o da chegada**: `ENDED` fecha a janela, e o
  resultado ainda pode convergir — `resultMayStillChange` diz isso;
- **o ciclo de vida é derivado do relógio do servidor**, sem cron;
- **o fuso é do desafio**, um só para todos, com a janela gravada na criação;
- **as regras são imutáveis depois da criação** — não há rota de edição;
- **amizade permite convidar; aceitar permite compartilhar a pontuação daquele desafio**. Os
  interruptores da T17.2 não decidem nada aqui, e participar não altera nenhum deles;
- **empate permanece empate** (`1, 1, 3`), sem desempate por ordem de chegada;
- **nada entra na Outbox, no `sync_entities`, no backup ou no restore**, e ler o placar não gera
  XP, conquista, missão nem escrita nenhuma.

## 12. Atividade dos amigos e rankings contextuais (T17.4)

### 12.1 Rotas

| Rota | Método | O quê |
| --- | --- | --- |
| `/v1/social/activity` | GET | Projeção dos dias de treino (`TRAINING_DAY`) dos amigos nos últimos 14 dias civis (0..13) |
| `/v1/social/rankings/last-7-days` | GET | Ranking contextual de sessões concluídas nos últimos 7 dias móveis entre amigos com opt-in |

### 12.2 Modelos e Schemas

#### Resposta de Atividade (`GET /v1/social/activity`):
```json
{
  "items": [
    {
      "type": "TRAINING_DAY",
      "actor": {
        "socialId": "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d",
        "displayName": "Carlos"
      },
      "daysAgo": 0
    }
  ]
}
```
- Deduplicado: no máximo 1 item por amigo por dia civil.
- Ordenado por `daysAgo ASC`, desempate por `displayName ASC`, depois `socialId ASC`.
- Teto: 30 itens.
- Requisitos: amigos mútuos ativos, `activitySharingEnabled = 1`, `activityTimeZoneId` válido.
- Fonte canônica: `sync_entities` com `entity_type = 'WORKOUT_SESSION'`, `deleted = 0`, `status = 'COMPLETED'`, ancorado em `startedAt`.

#### Resposta de Ranking (`GET /v1/social/rankings/last-7-days`):
```json
{
  "type": "WORKOUTS_COMPLETED_LAST_7_DAYS",
  "participantCount": 2,
  "entries": [
    {
      "socialId": "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d",
      "displayName": "Carlos",
      "score": 5,
      "rank": 1,
      "isCurrentUser": false
    },
    {
      "socialId": "c4b7890a-1234-4567-89ab-cdef01234567",
      "displayName": "Você",
      "score": 3,
      "rank": 2,
      "isCurrentUser": true
    }
  ]
}
```
- Reciprocidade estrita: visualizador precisa ter `friendRankingParticipationEnabled = 1`.
- Participantes: visualizador e amigos mútuos ativos com opt-in habilitado.
- Posições ordinais: competition ranking (`1, 1, 3`), desempate estável por `displayName ASC`, `socialId ASC`.
- Teto: top 50 entradas na classificação geral.
- Visibilidade garantida além do top 50: se o usuário autenticado estiver além da 50ª posição, sua linha é incluída ao final de `entries` (com `isCurrentUser: true` e seu `rank` real), permitindo que visualize sua colocação sem romper o contrato de lista.
- Fonte canônica: `sync_entities` com `entity_type = 'WORKOUT_SESSION'`, `deleted = 0`, `status = 'COMPLETED'`, ancorado em `startedAt`, centralizado via `CanonicalTrainingSource`.

### 12.3 Erros da T17.4

| `code` | HTTP | Quando |
| --- | --- | --- |
| `RANKING_NOT_ENABLED` | 403 | Consulta ao ranking sem ter habilitado `friendRankingParticipationEnabled` |
| `INVALID_ACTIVITY_TIMEZONE` | 400 | Tentativa de atualizar privacidade com fuso IANA desconhecido ou inválido |
| `ACTIVITY_NOT_AVAILABLE` | 503 | Fonte canônica de treino temporariamente indisponível |
