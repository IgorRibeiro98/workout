# Squads privados e feed de grupo (T17.11)

> **Status: implementado.** Backend, Android, migration `0019_social_groups.sql` e suíte de testes.
> Este documento descreve o domínio; o contrato executável mora em
> `backend/src/modules/social/social-group.contract.ts` e
> `app/src/main/java/com/example/data/social/SocialGroupContract.kt`.

## 1. O que um Squad é

Um grupo **privado** de treino, formado por convite, com um feed onde os membros compartilham
explicitamente check-ins que **já** publicaram.

```text
WorkoutSession
      │ sync T16 (o único caminho de upload de sessão que existe)
      ▼
WorkoutCheckIn (T17.8/T17.9)          ← a publicação, que já existia
      │ ação explícita do autor
      ▼
GroupCheckInShare                     ← uma aresta, e não um post
      │
      ▼
Feed privado do Squad
```

A frase que resume a fase inteira: **a T17.11 não cria um segundo modelo de publicação.** O que
entra no feed de um Squad é o mesmo `WorkoutCheckIn` do Feed de amigos, lido por outra audiência.

## 2. O que ele deliberadamente não é

Sem chat, DM, mensagem de grupo, voz ou vídeo. Sem post de texto, post genérico ou enquete. Sem
desafio ou ranking de Squad. Sem template ou programa compartilhado para o grupo. Sem evento,
agenda ou presença online. Sem grupo público, descoberta, busca global, link de convite, QR ou
código de entrada. Sem denúncia de grupo — quem tem problema com uma publicação usa Bloqueio e
Denúncia, que já existem no domínio.

> **Mudou na T17.12.** Esta lista dizia também "sem comentário ou reação específicos de Squad", e
> era verdade nesta fase: interagir exigia relação direta, porque uma interação sem audiência
> vazaria entre os Squads em que o mesmo check-in estivesse. A T17.12 resolveu a causa — a
> interação passou a pertencer a uma audiência explícita — e hoje um membro reage e comenta dentro
> do Squad, **e só ali**. Ver
> [`social-interaction-audience.md`](social-interaction-audience.md).

## 3. Privacidade estrutural

Não existe `GET /groups/search` nem `GET /public/groups`, e a ausência é o contrato. Um usuário
conhece um Squad em exatamente duas situações: **é membro dele**, ou **recebeu um convite válido**.

Conhecer o `groupId` não concede nada. Toda superfície do grupo — detalhe, membros, feed,
compartilhamento, administração — começa por uma consulta de participação contra as tabelas, e quem
não é membro ativo recebe `GROUP_NOT_FOUND`: a mesma resposta de "não existe", pelo mesmo motivo que
o resto do domínio social dá uma resposta só para vários casos. Distinguir transformaria a rota num
oráculo de existência.

## 4. O modelo

```text
social_groups                       id (UUID público) · owner_uid · name · status · client_request_id
  ├── social_group_memberships      id (UUID) · group_id · member_uid · role · joined_at
  ├── social_group_invitations      id (UUID) · group_id · sender · recipient · status · expires_at
  └── social_group_checkin_shares   id · group_id · checkin_id · author_uid · created_at
```

Quatro invariantes vivem **no banco**, e não no serviço:

| Garantia | Como |
|---|---|
| uma participação por (Squad, pessoa) | `UNIQUE (group_id, member_uid)` |
| **exatamente um** `OWNER` por Squad | índice único parcial `WHERE role = 'OWNER'` |
| um convite pendente por (Squad, destinatário) | índice único parcial `WHERE status = 'PENDING'` |
| um compartilhamento por (Squad, check-in) | `UNIQUE (group_id, checkin_id)` |

A do dono é a que mais importa: a transferência de posse é duas escritas, e uma transação que
falhasse no meio deixaria zero ou dois donos — dois estados visíveis para outras pessoas que não se
corrigem sozinhos. Com o índice, o estado inválido não é improvável: é irrepresentável.

`social_group_checkin_shares.checkin_id` tem `ON DELETE CASCADE` **na direção certa**: apagar o
check-in apaga a aresta. O inverso não existe, e é o ponto da fase.

## 5. Ciclo de vida da participação

```text
                    (só o OWNER convida, e só amigo direto ativo)
    OWNER ──── convite ────▶ PENDING ──── aceite do destinatário ────▶ MEMBER
                               │                                        │
                               ├── DECLINED (destinatário)              ├── LEAVE (o próprio)
                               ├── CANCELLED (remetente, ou bloqueio)   └── REMOVE (só o OWNER)
                               └── EXPIRED  (gravado quando expires_at vence)
```

`EXPIRED` é **gravado** desde a T17.13.1 (migration `0022`). A T17.11 o derivava na leitura, e a
intenção era boa — não depender de um processo ter passado por ali antes do toque —, mas a
derivação não alcança o banco:

```text
idx_social_group_invitations_pending  UNIQUE (group_id, recipient_uid) WHERE status = 'PENDING'
```

Um índice não consulta o relógio. O convite vencido continuava `PENDING` na coluna, ocupando a vaga
única daquele par (Squad, destinatário) e contando na quota de convites pendentes — um beco sem
saída em que ninguém consegue aceitar e ninguém consegue reconvidar.

`SocialGroupService.sweepExpiredInvitations()` grava a expiração antes de cada operação sensível a
`PENDING` — convidar, listar, aceitar, recusar e cancelar. Não há varredura de fundo: nenhuma
decisão depende de o convite ter sido marcado *antes* de alguém olhar, e no instante em que alguém
olha a marcação já aconteceu. A comparação `now >= expiresAt` continua no caminho de aceite como
rede de segurança, e nunca como a única defesa.

Expirar é silencioso: não gera push, e não existe tipo de notificação para isso — avisar sobre todo
convite ignorado seria ruído.

O aceite revalida **sete** coisas, porque sete podem ter mudado desde o envio: o convite é meu, está
pendente, não expirou, o Squad está ativo, meu perfil está ativo, o Squad tem vaga, e — a que merece
explicação — **a amizade com quem convidou ainda existe** e não há bloqueio.

## 6. Friendship ≠ Group Membership

São consentimentos diferentes, e a T17.11 mantém os dois separados nas duas direções.

```text
             CONVITE                      DEPOIS DO ACEITE
   amizade  ─── autoriza ───▶  entrar     participação ─── autoriza ───▶ ver o feed do grupo
                                          amizade      ─── autoriza ───▶ Feed de amigos, perfil
```

- **antes do aceite**, a amizade é a autorização, e por isso é revalidada no envio e no aceite;
- **depois do aceite**, a participação é um consentimento próprio. Desfazer a amizade revoga o que
  a amizade concedia — Feed de amigos, perfil — e **não** remove ninguém do Squad. Continua visível
  ali exatamente o que foi compartilhado naquele grupo;
- **estar no mesmo Squad não cria amizade**, não concede perfil de amigo e não torna ninguém
  elegível para um desafio.

É o mesmo princípio de consentimento específico que os desafios da T17.3 usam.

## 7. Bloqueio

Bloqueio é mais forte que participação na **visibilidade**, e não na composição do grupo.

| Efeito | Acontece? |
|---|---|
| conteúdo do par some, nos dois sentidos (feed, mídia, detalhe) | sim, na próxima leitura |
| identidade some da lista de membros, nos dois sentidos | sim — vira entrada opaca |
| convites pendentes entre o par são cancelados | sim |
| push do convite ainda na fila | suprimido |
| participações são destruídas | **não** |
| a contagem de membros muda | **não** |
| o Squad inteiro é vetado para os outros | **não** |

Destruir a participação contaria a todos os outros membros que houve um bloqueio entre duas pessoas,
e daria a qualquer um o poder de expulsar outro de um grupo que não é dele. Usar o bloqueio de um
como veto para os outros dezenove daria a uma pessoa o poder de silenciar o grupo.

A **entrada opaca** é o que resolve a tensão entre privacidade e administração: o par bloqueado
aparece na lista com `membershipId`, papel e "Participante indisponível", sem `socialId` e sem
`displayName`. O dono consegue remover quem o bloqueou sem que a tela jamais receba a identidade
daquela pessoa — e é por isso que toda operação administrativa é endereçada por `membershipId`, um
identificador opaco válido só dentro do grupo, e nunca por uid.

Desbloquear devolve o acesso ao conteúdo do Squad mesmo sem amizade: ali o acesso vem da
participação, e as duas continuam de pé.

## 8. Access policy

```text
canViewCheckIn = SELF ∨ FRIEND ∨ GROUP
                 sempre sob: perfil ACTIVE dos dois lados ∧ ¬bloqueio (em qualquer direção)

GROUP = existe Squad ACTIVE
        ∧ o check-in foi explicitamente compartilhado nele
        ∧ o viewer é membro ativo dele
        ∧ o autor ainda é membro ativo dele
```

A definição mora **uma vez**, em `workout-checkin.access-policy.ts`, e é exportada como texto SQL —
o mesmo desenho de `VIEWER_SCOPE_CTE` na T17.9. Três superfícies a usam literalmente: o feed do
grupo, o detalhe do check-in e os bytes da foto. Três cópias seria o desenho em que, no dia de um
ajuste, duas mudam e a terceira continua respondendo o dado de quem não devia.

`findVisibleCheckIn` (SELF ∪ FRIEND) responde relação direta; `findAccessibleCheckIn`
(SELF ∪ FRIEND ∪ GROUP) responde leitura. A T17.12 acrescentou a terceira pergunta —
`findGroupAccessibleCheckIn`, "este viewer alcança este check-in por **este** Squad" —, que é a que
autoriza interação com contexto.

## 9. Por que interação nasceu read-only aqui — e o que a T17.12 fez com isso

Um mesmo check-in pode estar no Feed de amigos e em dois Squads — são audiências distintas sobre o
mesmo objeto. Se o acesso via Squad autorizasse comentar **com o modelo da T17.9**, aquela
publicação passaria a ter uma conversa com três audiências sobrepostas: quem comentou pelo Squad A
apareceria para o Squad B e para os amigos do autor, que não fazem parte daquele contexto.

Resolver isso corretamente exige comentários e reações **cientes de audiência**, e a T17.11 não
introduziu essa complexidade em silêncio: fechou a porta e deixou o problema nomeado. O contrato
carrega `canInteract`, decidido no servidor.

A T17.12 fez exatamente o que estava nomeado aqui: a interação passou a pertencer a uma audiência
explícita (`FRIEND` ou `GROUP(groupId)`), e com isso um membro sem amizade nenhuma reage e comenta
dentro do Squad — e **só** dentro dele. `canInteract` continua no contrato e continua decidido no
servidor; o que mudou é que, no feed de um Squad, ele é `true` para todo membro ativo. Ver
[`social-interaction-audience.md`](social-interaction-audience.md).

## 10. Ordenação e recorte

O feed é ordenado por `share.createdAt DESC`, e não pela data de publicação. Compartilhar hoje um
check-in de ontem precisa aparecer no topo, ou o ato de compartilhar não teria efeito visível para
quem já rolou a lista. O DTO expõe `sharedToGroupAt`, que representa a **ação social de
compartilhar** — nunca o horário do treino, que continua sem circular.

Bounded por construção: 30 dias sobre a data do compartilhamento, 20 itens por padrão, teto de 50.
Sem cursor histórico, sem infinite feed, sem polling, sem WebSocket.

## 11. Ciclo de vida da conta

```text
Social Disable   ──▶ pode recusar       ──▶ a pessoa transfere ou exclui, e escolhe quem fica
Account Deletion ──▶ nunca é bloqueada  ──▶ o Squad do dono vai junto, sem substituto silencioso
```

A assimetria é deliberada. A desativação é reversível e comporta uma pergunta ao usuário: ser dono
de um Squad com outras pessoas devolve `GROUP_OWNERSHIP_REQUIRES_ACTION` com uma **contagem**, e
nunca dados de membro. Escolher um novo dono automaticamente exigiria uma ordenação, e qualquer
ordenação entrega um grupo de pessoas reais a alguém que não pediu por ele.

A exclusão de conta não pode depender da escolha de um terceiro, e por isso resolve sozinha: o Squad
do dono excluído desaparece inteiro. O que ela **não** toca é qualquer coisa dos outros membros —
check-ins, sessões, templates e fotos deles continuam exatamente como estavam. Um membro excluído
perde a participação, e o Squad sobrevive.

Em ambos os casos, sair de um Squad remove também os **compartilhamentos** daquela pessoa naquele
grupo. É isso que garante que um `rejoin` futuro não ressuscite conteúdo antigo: não há o que
ressuscitar.

## 12. Notificação

Uma categoria, e uma só: `GROUP_INVITATION_RECEIVED`, data-only, com `entityId = invitationId`.
Nunca o nome do Squad, o de quem convidou ou o dos membros — o texto do aviso é local e genérico,
porque nada disso viaja no payload.

Não existe push para "entrou", "saiu", "foi removido", "posse transferida", "check-in compartilhado"
nem "Squad excluído": nenhum deles convida a decidir nada, e um grupo de 20 pessoas que notificasse
cada movimento seria um chat com outro nome.

O deep link abre a **lista de convites**, e nunca o detalhe do grupo: quem ainda não aceitou não é
membro, e o detalhe responderia `404`.

## 13. Autoridade e persistência

| Entidade | Autoridade | Persistência no Android |
|---|---|---|
| `SocialGroup` | servidor | nenhuma — memória, escopo de conta |
| `Membership` | servidor | nenhuma |
| `Invitation` | servidor | nenhuma |
| `GroupCheckInShare` | servidor | nenhuma |
| `WorkoutCheckIn` | servidor (T17.8) | nenhuma |
| `WorkoutSession` | **Room, local-first** | Room + sync T16 |

Nenhuma tabela de Squad é `sync_entity`, não existe Outbox social e não existe DAO de grupo. Sem
backend configurado, a área de Squads simplesmente não aparece — e treinar, consultar histórico e
ver evolução continuam funcionando offline, como sempre.

## 14. Rotas

```text
POST   /v1/social/groups                                  criar (idempotente por clientRequestId)
GET    /v1/social/groups                                  os squads deste usuário
GET    /v1/social/groups/invitations                      convites recebidos
GET    /v1/social/groups/{groupId}                        cabeçalho do detalhe
GET    /v1/social/groups/{groupId}/members                participantes (bloqueio já projetado)
POST   /v1/social/groups/{groupId}/invitations            convidar (só o dono)
POST   /v1/social/group-invitations/{id}/accept           aceitar (só o destinatário)
POST   /v1/social/group-invitations/{id}/decline          recusar
POST   /v1/social/group-invitations/{id}/cancel           cancelar (só quem enviou)
POST   /v1/social/groups/{groupId}/leave                  sair (o dono não)
DELETE /v1/social/groups/{groupId}/members/{membershipId} remover (só o dono)
POST   /v1/social/groups/{groupId}/transfer-ownership     transferir a posse
DELETE /v1/social/groups/{groupId}                        excluir (só o dono)
POST   /v1/social/groups/{groupId}/checkins/{checkInId}   compartilhar o próprio check-in
DELETE /v1/social/groups/{groupId}/checkins/{checkInId}   desfazer (só o autor)
GET    /v1/social/groups/{groupId}/feed                   o feed privado
GET    /v1/social/workout-checkins/{id}/groups            em quais squads ele já está
```

## 15. Limites

| O quê | Valor | Onde |
|---|---|---|
| nome do Squad | 3..40 code points | `SOCIAL_GROUP_NAME` |
| membros por Squad | 20 (inclui o dono) | `SOCIAL_GROUP_MAX_MEMBERS` |
| Squads criados por conta | 5 ativos | `SOCIAL_GROUP_MAX_OWNED` |
| participações por conta | 20 ativas | `SOCIAL_GROUP_MAX_MEMBERSHIPS` |
| Squads por check-in | 5 | `SOCIAL_GROUP_MAX_SHARES_PER_CHECKIN` |
| convites pendentes por Squad | 20 | `SOCIAL_GROUP_MAX_PENDING_INVITATIONS` |
| validade do convite | 14 dias | `SOCIAL_GROUP_INVITATION_TTL_MS` |
| rate limit (por conta/min) | criar 10 · convidar 30 · composição 30 · compartilhar 20 | `SOCIAL_GROUP_RATE_LIMIT` |

O teto de requisição de criação (10/min) fica **acima** do teto de domínio (5 Squads) de propósito:
iguais, o sexto pedido devolveria `429` em vez de `GROUP_OWNED_LIMIT_REACHED` — uma mensagem que não
explica nada e que some sozinha depois de um minuto.

## 16. O que atravessa a fronteira, e o que nunca atravessa

Um card do feed de Squad carrega: identidade **pública** do autor (`socialId`, `displayName`),
quando a publicação foi feita, quando ela entrou neste Squad, a legenda e a foto.

Nunca atravessa: exercício, série, repetição, carga, duração, volume, recorde, caloria, horário do
treino, nota privada, `WorkoutSession`, `WorkoutTemplate`, `sessionSyncId`, uid, e-mail ou
`friendCode`. Nenhuma das quatro tabelas desta fase tem coluna para nada disso.
