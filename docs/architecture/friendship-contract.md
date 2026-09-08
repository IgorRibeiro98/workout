# Grafo social do Spark — amizade bilateral, pedidos e descoberta por código

- **Tarefa:** T17.1 — amigos, convites por código e QR Code.
- **Status (verificado em 2026-09-07): implementado.** Migration `0008_friend_graph.sql`,
  `friendship.*` em `backend/src/modules/social/`, gateway e telas de Amigos/Solicitações no
  Android.
- **Base:** [`social-domain.md`](./social-domain.md) (T17.0 — identidade e privacidade).
- **Contrato de protocolo:** [`contracts/social/v1/README.md`](../../contracts/social/v1/README.md)

**Não existe** nesta fase: bloqueio, denúncia, perfil social rico (nível, XP, sequência,
frequência, conquistas), atividade, feed, ranking, desafio, notificação push (FCM), busca por
nome, busca por e-mail, sugestão de pessoas, avatar e exclusão de conta.

---

## 1. O grafo

```text
SocialProfile A                          SocialProfile B
      │                                        │
      │  friendCode de B ──▶ lookup exato ─────┤
      │                                        │
      └──────── FriendRequest (PENDING) ──────▶│
                      │                        │
        ┌─────────────┼─────────────┐          │
        │             │             │          │
   ACCEPTED      REJECTED      CANCELLED       │
        │         (só B)         (só A)        │
        ▼                                      │
   Friendship(A,B) ◀───────────────────────────┘
        │           uma linha, par canônico
        └── unfriend (qualquer um dos dois) ──▶ a linha deixa de existir
```

Três afirmações que o resto do documento sustenta:

1. **descoberta é só por código exato.** Não existe busca, listagem nem enumeração;
2. **amizade é um par, não duas relações.** `A-B == B-A`, e o banco não consegue representar o
   contrário;
3. **amizade não é acesso.** Ser amigo não concede treino, backup, sync, medida, histórico, e-mail
   nem Firebase UID.

## 2. Autoridade

```text
TREINO           ação → domínio → Room → Outbox → backend      local-first (T16.3–T16.7)
PERFIL SOCIAL    ação → backend → resultado → UI/cache         server-authoritative (T17.0)
GRAFO SOCIAL     ação → backend → resultado → UI/cache         server-authoritative (T17.1)
```

O grafo é server-authoritative pela razão que a T17.0 já estabeleceu para a identidade, elevada ao
quadrado: **uma amizade é um fato sobre duas contas**. Dois aparelhos offline não podem convergir
para "somos amigos" — convergir aqui significaria escolher qual dos dois consentimentos vale.

Consequências que valem como contrato:

- **sem Outbox social.** Uma ação feita offline **não acontece**: não fica pendente e não é
  reenviada. A tela diz isso, com a frase que impede o mal-entendido — *"nada foi enviado"*;
- **sem `sync_entities`, sem tombstone, sem `revision`, sem `clientMutationId`.** Amizade vive em
  tabelas próprias (`friend_requests`, `friendships`);
- **sem Room como autoridade.** Não existe `FriendshipEntity` nem `FriendRequestEntity`. O que o
  app guarda é a última leitura, em memória, invalidada ao trocar de conta;
- **fora do backup e do restore.** Restaurar um snapshot num celular novo não cria, apaga nem
  altera amizade nenhuma; entrar na conta e ler `GET /v1/social/friends` traz o que o servidor tem;
- **sem gamificação.** Adicionar amigo não dá XP, não desbloqueia conquista e não move missão.

## 3. Descoberta: `friendCode`, e nada além dele

```text
"spk 7k2p 9d8q"  ──normalizar (T17.0 §3)──▶  SPK-7K2P9D8Q  ──match exato──▶  preview mínimo
```

- **a normalização é a da T17.0**, e é uma só: `normalizeFriendCode` no servidor. O Android tem uma
  cópia do **formato** para dar resposta imediata na tela (ligar o botão, recusar um QR), e as duas
  são amarradas pela fixture compartilhada
  [`contracts/social/v1/friend-code-normalization.json`](../../contracts/social/v1/friend-code-normalization.json),
  lida pelos testes dos dois lados. Quem decide um lookup continua sendo o servidor;
- **match exato**, sobre índice único. Não existe `LIKE`, prefixo, *contains*, Levenshtein nem
  sugestão: *fuzzy matching* aqui significa devolver o perfil de outra pessoa para quem digitou
  errado;
- **`POST`, não `GET`.** O código vai no corpo. Ele não é segredo, mas é um identificador
  compartilhável, e a URL é a parte da requisição que vaza mais fácil — log de proxy, log de acesso
  do Caddy, histórico. Um log de acesso com códigos em texto claro é uma lista de convites válidos;
- **teto próprio de requisições** (20 lookups/min por conta). O teto geral de 600/min existe para
  conter laço; 600 tentativas de código por minuto **é** uma varredura;
- **três situações, uma resposta.** Código malformado, código inexistente e código de perfil
  `DISABLED` respondem `NOT_FOUND`. Distinguir qualquer uma delas transformaria a rota num oráculo:
  bastaria comparar respostas para descobrir que um código existe mas está desligado;
- **o próprio código responde `SELF`**, sem preview. A tela diz "este é o seu próprio código", e
  não há o que enviar.

### O que o preview carrega

```json
{ "socialId": "...", "displayName": "Igor" }
```

E **nunca**: `ownerUid`, Firebase UID, e-mail, provedor de autenticação, `friendCode`,
`createdAt` interno, estado de backup, metadata de sync ou qualquer projeção de treino.

## 4. Ciclo de vida de um pedido

```text
              ┌─────────── aceitar (só o destinatário) ──▶ ACCEPTED + Friendship
PENDING ──────┼─────────── recusar (só o destinatário) ──▶ REJECTED
              └─────────── cancelar (só quem enviou)   ──▶ CANCELLED
```

- **os três estados finais são terminais.** Nada volta para `PENDING`. Pedir de novo depois de uma
  recusa é uma **operação nova**, com `requestId` novo — e é isso que permite ao destinatário ver
  que foi pedido duas vezes, em vez de ver uma linha ressuscitada;
- **a linha não é apagada** em transição. Apagar tornaria "aceitar duas vezes" indistinguível de
  "aceitar um pedido que nunca existiu", e é justamente a segunda tentativa — depois de uma
  resposta perdida — que precisa de uma resposta correta;
- **`requestId` é UUID v4**, opaco e não sequencial. Um `rowid` no lugar contaria de graça quantos
  pedidos existem no servidor;
- **só participante age, e só o participante certo.** Uma conta C recebe `FRIEND_REQUEST_NOT_FOUND`
  — a mesma resposta de um id inventado, porque saber que o pedido existe já é mais do que ela tem
  o direito de aprender. Um participante na função errada (quem enviou tentando aceitar) recebe
  `NOT_REQUEST_RECIPIENT`/`NOT_REQUEST_SENDER`;
- **idempotência é escrita condicional no banco**, não flag em memória:
  `UPDATE ... WHERE request_id = ? AND status = 'PENDING'`, e o número de linhas afetadas decide o
  desfecho. Uma verificação antes do `UPDATE` perderia a corrida.

### Enviar é idempotente

```text
A → B (1ª vez)   REQUEST_CREATED
A → B (2ª vez)   REQUEST_ALREADY_PENDING, com o MESMO requestId
```

`REQUEST_ALREADY_PENDING` é **sucesso**, não erro: é o reenvio depois de uma resposta perdida e o
toque duplo, e a situação lógica é a que o cliente queria. Tratá-lo como falha faria um retry
seguro parecer um defeito. O banco garante o mesmo por baixo, com um índice único **parcial** sobre
`(requester_uid, recipient_uid) WHERE status = 'PENDING'` — que também é o que deixa a vaga livre
para um pedido novo depois de um `REJECTED`.

## 5. Pedido cruzado: a política, e por quê

```text
A → B  PENDING
B → A  (envio)
       ↓
Friendship(A,B) criada + pedido de A marcado ACCEPTED — na MESMA transação
```

**A política escolhida é a auto-aceitação determinística.** A alternativa considerada era responder
"já existe um pedido para você, aceite-o", e ela foi recusada por uma razão simples: quando A pede B
**e** B pede A, o consentimento bilateral já foi expresso pelos dois, explicitamente. Exigir um
terceiro toque não protege ninguém — protege contra uma intenção que ambos acabaram de declarar — e
produz a tela mais confusa do fluxo ("você já pediu, agora aceite o pedido dele").

Ela nunca produz duas amizades nem dois pedidos: a resolução acontece dentro de uma transação, e a
chave primária do par canônico torna a duplicata irrepresentável. Há teste que envia nas duas
direções e verifica que sobra **uma** amizade e **zero** pendências.

## 6. Amizade: o par canônico

```text
friendships
├── user_a_uid   ─┐  PRIMARY KEY (user_a_uid, user_b_uid)
├── user_b_uid   ─┘  CHECK (user_a_uid < user_b_uid)
└── created_at
```

O par é sempre `min(uid), max(uid)`. Combinando a chave primária com o `CHECK`, três coisas se
tornam **irrepresentáveis** — não "verificadas pelo serviço":

| | Por quê |
| --- | --- |
| amizade duplicada A-B | a chave primária recusa a segunda linha |
| amizade duplicada B-A | não existe outra forma de escrever o par; a inversão quebra o `CHECK` |
| amizade consigo mesmo | `A < A` é falso |

É isso que sustenta a idempotência de "aceitar duas vezes" mesmo sob duas transações simultâneas —
e é por isso que a garantia não depende de nenhum caminho de código lembrar de verificar.

### Ordenação e paginação

Amigos vêm por `display_name`, `social_id` (alfabético, determinístico); pedidos vêm dos mais
recentes primeiro. As duas listas têm limite (padrão 50, teto 100) e **cursor opaco** — keyset, e
não `offset`, porque `offset` pula ou repete itens sempre que a lista muda entre duas páginas. O
`total` vem do servidor, por `COUNT(*)` sobre índice: é o que permite ao Perfil dizer "3 amigos · 1
solicitação pendente" sem abrir a lista, e um contador desnormalizado numa coluna seria uma segunda
verdade que diverge no primeiro erro.

## 7. Desfazer a amizade

Qualquer um dos dois pode. Não há hierarquia: quem foi convidado tem exatamente o mesmo poder de
sair de quem convidou.

O que acontece: **uma linha de `friendships` deixa de existir.**

O que **não** acontece:

- não apaga treino, histórico, sessão, medida, PR, backup, snapshot de sync nem gamificação — este
  módulo não alcança nenhum deles, e há teste sobre os imports;
- não altera o `SocialProfile` de ninguém;
- **não bloqueia.** Bloqueio é uma capacidade distinta, com consequências distintas (não aparecer
  em lookup, não poder pedir de novo, esconder atividade), e continua fora de escopo — registrado
  como pendência para a T17.6;
- não reescreve o pedido histórico que originou a amizade. Ele registra que um dia alguém pediu e
  alguém aceitou; apagá-lo seria apagar um fato para simplificar uma consulta.

Depois de desfazer, os dois podem se adicionar de novo — o índice parcial já liberou a vaga.

## 8. Social desativado: suspensão, não exclusão

A T17.0 decidiu que desativar preserva a identidade. A T17.1 estende a mesma decisão às relações:

```text
B desativa Social
   │
   ├── Friendship(A,B)          PERMANECE gravada
   ├── na lista de A            NÃO aparece  (nem como "usuário indisponível")
   ├── FriendRequest pendente   SUSPENSO — não listado, não acionável, NÃO cancelado
   ├── lookup do código de B    NOT_FOUND
   └── B chamando o grafo       409 SOCIAL_PROFILE_DISABLED, inclusive nas leituras
   │
B reativa
   └── tudo volta inteiro: amizades, pedidos pendentes e o mesmo friendCode
```

Duas escolhas merecem justificativa:

- **ocultar, em vez de mostrar "usuário indisponível"** (§49). A amizade continua gravada; o que
  não existe, enquanto isso, é perfil social para mostrar. Exibir um espaço vazio contaria a quem
  olha uma informação que é do outro — que ele desativou —, e é exatamente essa informação que
  desativar deveria esconder;
- **suspender o pedido, em vez de cancelá-lo** (§50). Cancelar decidiria, no lugar das duas
  pessoas, que aquele convite não vale mais — e reativar não teria como desfazer isso.

`friendRequestsEnabled = false` é mais simples e igualmente explícito: **novos** pedidos são
recusados (`FRIEND_REQUESTS_DISABLED`), e os pendentes continuam podendo ser aceitos ou recusados.
Desligar a campainha não devolve as cartas que já chegaram.

## 9. QR Code

```text
spark://friend/v1/SPK-7K2P9D8Q
└─┬─┘   └──┬──┘ └┬┘ └────┬────┘
  │        │     │       └── o friendCode canônico — a ÚNICA informação do payload
  │        │     └────────── versão do formato
  │        └──────────────── o que este QR é
  └───────────────────────── esquema do Spark
```

- **gerado no aparelho**, a partir do código que o perfil já trouxe (`zxing:core`, Java puro).
  Nenhuma requisição, nenhuma imagem vinda do servidor, e funciona sem internet;
- **proibido no payload:** Firebase UID, e-mail, token, `socialId`, `deviceId`, `syncId`, endereço
  de servidor e qualquer coisa de treino. O QR circula por foto, papel e grupo de mensagem: tudo o
  que ele carrega é público **por construção**;
- **versionado** (`v1`), para que um formato futuro conviva com os QRs já impressos;
- **`spark://` e não `https://`**, porque não existe domínio servindo deep link. Um
  `https://spark.exemplo/f/CODE` que ninguém serve levaria a pessoa a um erro de navegador em vez
  de ao app. Quando houver domínio e App Links verificados, o parser ganha o segundo formato sem
  quebrar os QRs deste;
- **o scanner nunca navega.** O parser devolve um código ou uma recusa: não abre `Intent`, não
  carrega URL, não toca `WebView`, nem para um `spark://` de outro tipo. Há teste estrutural sobre
  isso. Uma URL qualquer, um QR de PIX e um `spark://workout/...` são todos `QR_INVALID`, sem
  tentativa de adivinhação, e payload acima do teto é recusado antes de qualquer processamento;
- **leitura pelo Google Code Scanner**, que **não exige permissão de câmera**: a câmera é aberta
  pela UI do Play Services e o app recebe só o texto. Onde ele não existe, a tela oferece digitar o
  código — ler QR nunca é o único caminho.

## 10. API

Todas sob `/v1/social`, todas exigem `Authorization: Bearer <Firebase ID Token>`, e o dono é sempre
o `uid` do token. **Não existe rota pública.**

| Rota | O quê |
| --- | --- |
| `POST /v1/social/friends/lookup` | `{ friendCode }` → `FOUND` + preview / `SELF` / `NOT_FOUND` |
| `POST /v1/social/friend-requests` | `{ socialId }` → `REQUEST_CREATED` / `REQUEST_ALREADY_PENDING` / `FRIENDSHIP_CREATED` |
| `GET /v1/social/friend-requests/incoming` | pedidos recebidos, pendentes, mais recentes primeiro |
| `GET /v1/social/friend-requests/outgoing` | pedidos enviados, pendentes |
| `POST /v1/social/friend-requests/:id/accept` | só o destinatário → `ACCEPTED` / `ALREADY_FRIENDS` |
| `POST /v1/social/friend-requests/:id/reject` | só o destinatário → `REJECTED` / `ALREADY_REJECTED` |
| `POST /v1/social/friend-requests/:id/cancel` | só quem enviou → `CANCELLED` / `ALREADY_CANCELLED` |
| `GET /v1/social/friends` | meus amigos (`socialId`, `displayName`, `friendsSince`) |
| `POST /v1/social/friends/remove` | `{ socialId }` → `REMOVED` |

### Erros

| `code` | HTTP | Quando |
| --- | --- | --- |
| `INVALID_FRIEND_REQUEST` | 400 | corpo/parâmetro fora do contrato, cursor ou `limit` inválido |
| `SELF_FRIEND_REQUEST` | 400 | pedido para si mesmo |
| `FRIEND_REQUESTS_DISABLED` | 403 | o destinatário desligou os pedidos |
| `NOT_REQUEST_RECIPIENT` | 403 | quem enviou tentando aceitar/recusar |
| `NOT_REQUEST_SENDER` | 403 | quem recebeu tentando cancelar |
| `SOCIAL_NOT_ENABLED` | 404 | a conta não tem perfil social |
| `SOCIAL_PROFILE_NOT_FOUND` | 404 | `socialId` inexistente **ou** desativado |
| `FRIEND_REQUEST_NOT_FOUND` | 404 | pedido inexistente **ou** de terceiros |
| `FRIENDSHIP_NOT_FOUND` | 404 | não há amizade para desfazer |
| `SOCIAL_PROFILE_DISABLED` | 409 | o **chamador** desativou os recursos sociais |
| `ALREADY_FRIENDS` | 409 | já são amigos |
| `FRIEND_REQUEST_NOT_PENDING` | 409 | o pedido já é terminal (a corrida aceitar × cancelar) |
| `SOCIAL_RATE_LIMITED` | 429 | teto próprio do lookup ou do envio |

Nenhuma mensagem repete conteúdo do usuário: elas descrevem a **forma** do defeito, nunca o valor.

## 11. Android

```text
Perfil ──▶ [Amigos] [Solicitações] [Meu código]
              │           │              └── folha: código + QR + copiar + compartilhar
              │           └── recebidas (aceitar/recusar) · enviadas (cancelar)
              └── lista · [+ Adicionar amigo] ──▶ digitar código | ler QR
                                                        ↓
                                                  preview mínimo
                                                        ↓
                                                [ Enviar solicitação ]
```

- **um `FriendsViewModel` para as três telas.** Um por rota faria a lista ser lida três vezes e o
  contador do Perfil ficar velho logo depois de aceitar um pedido na tela de Solicitações;
- **nada acontece sozinho.** Criar o ViewModel não faz requisição: o `init` só observa a sessão
  para **invalidar** o estado na troca de conta. A leitura sai de `open()`, quando o usuário chega
  na tela — ou quando o Perfil abre com o perfil social ativo, para o resumo;
- **procurar nunca envia.** O envio exige um segundo toque, sobre um nome visível. Sem isso, um
  caractere errado viraria um convite para um desconhecido;
- **estados por alvo, não um `isLoading`.** Aceitar o pedido do Igor não bloqueia a resposta ao do
  João: a ocupação é por `requestId`/`socialId`, e só a tela inteira usa `FriendsAction`;
- **troca de conta invalida antes de qualquer requisição sair**, e a resposta de uma requisição
  iniciada pela conta anterior é descartada se a conta mudou no voo (a lição da T16.7.1: o `uid`
  capturado antes da chamada não vale depois dela);
- **sem bottom navigation nova.** Amigos e Solicitações são telas alcançadas do Perfil; "Meu
  código" é uma folha sobre ele. O `friendCode` **não** entra em rota nenhuma;
- **a área de transferência é só de escrita.** O Spark nunca lê o clipboard — nem para "detectar um
  código copiado", que seria o caminho educado para ler tudo o que a pessoa copiou;
- **nenhum log.** O pacote social não registra nada, e a ausência é testada.

## 12. Logs do servidor

Permitido: `requestId`, `uidPrefix` (6 caracteres), evento, desfecho, contagens e o `requestId` do
pedido de amizade. Proibido: `friendCode`, `socialId`, `displayName`, e-mail, Firebase UID
completo, `Authorization` e corpo da requisição.

Um log que carregasse `friendCode` transformaria qualquer cópia de log numa lista de convites
válidos — e agora que o lookup existe, ela seria **utilizável**.

## 13. O que a T17.2 herda pronto

```text
Friendship(A,B) ──▶ SocialAccessPolicy.canViewProfile ──▶ SocialProjection ──▶ perfil social rico
```

A relação existe, é bilateral, é autorizada por participante e tem uma política central
(`FriendshipAccessPolicy` para participação, `SocialAccessPolicy` para visibilidade). A T17.2
acrescenta **o que** um amigo vê — nível, sequência, frequência, conquistas selecionadas — passando
por `SocialProjection` e pelas configurações de privacidade, **sem redesenhar a amizade**.
