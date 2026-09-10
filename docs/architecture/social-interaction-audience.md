# Interações por audiência: reações e comentários com contexto (T17.12)

> **Status: implementado.** Backend, Android, migration `0020_social_interaction_audience.sql` e
> suíte de testes. Este documento descreve o modelo; o contrato executável mora em
> `backend/src/modules/social/workout-checkin.contract.ts` e
> `backend/src/modules/social/workout-checkin-context.resolver.ts`.

## 1. O problema que esta fase resolve

A T17.11 permitiu que um membro de Squad **visse** um check-in trazido para o grupo, mas manteve
reagir e comentar como privilégio de relação direta. A restrição não era desconfiança do grupo: era
que a mesma publicação pode estar em vários lugares ao mesmo tempo.

```text
WorkoutCheckIn A
├── Feed de amigos
├── Squad X
└── Squad Y
```

Com interações globais ao check-in, um comentário escrito dentro do Squad X apareceria para quem
abrisse o mesmo check-in no Squad Y ou no Feed de amigos — pessoas que podem não ter relação nenhuma
com quem escreveu. A T17.11 evitou o vazamento fechando a porta; a T17.12 resolve a causa.

**Princípio central: interação social pertence a uma audiência explícita.**

```text
                    WorkoutCheckIn
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
      Feed amigos      Squad X        Squad Y
          │              │              │
       FRIEND         GROUP(X)       GROUP(Y)
          │              │              │
     comentários/    comentários/   comentários/
      reações          reações        reações
```

O check-in continua sendo **um** objeto. Não existe post duplicado por Squad, não existe segundo
modelo de publicação e não existe segundo Feed.

## 2. As audiências

Duas, e só duas:

| Audiência | `group_id` | O que significa |
|---|---|---|
| `FRIEND` | sempre `NULL` | a conversa do Feed de amigos |
| `GROUP` | sempre presente | a conversa de **um** Squad |

`PUBLIC`, `FOLLOWERS`, `CUSTOM`, `MULTI_GROUP` e `DIRECT_MESSAGE` não existem. `SELF` também não é
uma audiência persistida: o autor enxerga as audiências em que a própria publicação existe, e criar
uma terceira para ele seria uma linha a mais que ninguém lê.

## 3. O contexto é proposta, nunca concessão

O cliente informa de onde veio:

```json
{ "type": "FIRE", "context": { "type": "GROUP", "groupId": "…" } }
```

`WorkoutCheckInContextResolver` é o **único** lugar que transforma isso em audiência autorizada, e
ele revalida contra as tabelas a cada requisição:

```text
FRIEND  →  viewer é o autor  ∨  amizade direta atual        (∧ perfis ACTIVE ∧ ¬bloqueio)
GROUP   →  Squad ACTIVE
           ∧ check-in explicitamente compartilhado ali
           ∧ requisitante é membro ativo
           ∧ autor ainda é membro ativo
           ∧ ¬bloqueio entre requisitante e autor
```

Duas consequências que valem por escrito:

- **um `groupId` não abre nada sozinho.** É a mesma regra que já vale para `checkInId` e `mediaId`
  desde a T17.9: conhecer um identificador nunca foi autorização;
- **fail-closed.** Contexto de grupo inválido responde `404`, e **nunca** cai para `FRIEND`. O
  caminho contrário publicaria no Feed de amigos algo que a pessoa escreveu achando que estava
  dentro de um Squad. Contexto malformado (`GROUP` sem `groupId`) responde `400`, porque o pedido
  está errado — dizer "não encontrado" mandaria o cliente procurar o defeito no lugar errado.

Ausência de contexto significa `FRIEND`, por compatibilidade: um APK anterior à T17.12 continua
reagindo e comentando no Feed de amigos exatamente como fazia.

## 4. O que a participação passa a autorizar — e o que ela continua não autorizando

Dentro do Squad, **participação ativa é a autorização**: um membro sem amizade nenhuma reage e
comenta ali.

O que isso **não** faz, e cada item é uma barreira testada: não cria amizade, não concede perfil de
amigo, não abre o Feed de amigos, não habilita compartilhamento de treino, não torna ninguém
elegível a desafio, e não autoriza interagir com a mesma publicação fora daquele Squad.

## 5. O modelo no banco

As duas tabelas da T17.9 ganharam a audiência; nenhuma tabela nova foi criada.

```text
social_checkin_reactions   checkin_id · reactor_uid · type · audience_type · group_id · …
social_checkin_comments    id · checkin_id · author_uid · body · audience_type · group_id · …
```

Um `CHECK` cruzado em cada tabela garante a forma: `FRIEND` exige `group_id IS NULL`, `GROUP` exige
`group_id IS NOT NULL`.

### A unicidade da reação, e o problema do `NULL`

A regra da T17.9 era "uma reação por pessoa por publicação". A regra agora é **uma reação por
pessoa, por publicação, por audiência** — a mesma pessoa pode ter 🔥 no Feed de amigos e 💪 no Squad
X sobre o mesmo check-in.

A forma óbvia não funciona:

```sql
-- ERRADO: em SQL cada NULL é distinto de qualquer outro numa UNIQUE, então duas reações
-- FRIEND da mesma pessoa no mesmo post (group_id IS NULL nas duas) conviveriam sem conflito.
UNIQUE (checkin_id, reactor_uid, group_id)
```

A solução são **dois índices únicos parciais**, um por partição de audiência:

```sql
CREATE UNIQUE INDEX idx_checkin_reactions_friend_unique
    ON social_checkin_reactions (checkin_id, reactor_uid)            WHERE audience_type = 'FRIEND';
CREATE UNIQUE INDEX idx_checkin_reactions_group_unique
    ON social_checkin_reactions (checkin_id, reactor_uid, group_id)  WHERE audience_type = 'GROUP';
```

Dentro de `FRIEND`, `group_id` não entra na comparação. Dentro de `GROUP`, ele nunca é `NULL` — o
`CHECK` garante — e a igualdade funciona normalmente. A escrita mira o índice certo com
`ON CONFLICT (…) WHERE audience_type = '…'`.

### Migration

`0020_social_interaction_audience.sql`, aditiva e não destrutiva. As duas tabelas são reconstruídas
no rebuild de 12 passos que o SQLite da época exigia (a de reações precisava perder a `PRIMARY KEY` antiga, e
a de comentários precisava de um `CHECK` que compara duas colunas — nenhum dos dois é possível com
`ALTER TABLE`). Todo o histórico é copiado com `audience_type = 'FRIEND'` e `group_id = NULL`: antes
desta fase, toda interação nascia de relação direta, que é exatamente o que `FRIEND` significa
agora. Os `id` dos comentários são preservados — eles já circulam como alvo de denúncia.

## 6. Leitura: duas filtragens independentes

Toda consulta de interação filtra por **audiência** e por **viewer**, e as duas existem por motivos
diferentes:

```text
por audiência  →  a conversa do Squad X não aparece em Y nem no Feed de amigos
por viewer     →  quem está em bloqueio não transparece nem como número (T17.9, intacto)
```

A visibilidade de quem interagiu muda com a audiência:

```text
FRIEND  →  interactionVisibleSql       — é o autor do post, ou amigo dele
GROUP   →  groupInteractionVisibleSql  — ainda é membro ativo daquele Squad
```

O caso de terceiro continua valendo dentro do Squad: com A, B e C no mesmo grupo e A bloqueando B,
uma publicação de C mostra as duas interações para C, esconde a de B para A e a de A para B — sem
apagar nada para ninguém.

## 7. Ciclo de vida

Participação é o consentimento que sustenta a audiência. Quando ele termina, a participação social
daquela pessoa naquele Squad termina junto:

| Evento | Efeito | O que **não** é tocado |
|---|---|---|
| sair / ser removido / desativar o Social | as interações daquela pessoa naquele Squad **e** as que os outros deixaram nas publicações dela ali | Feed de amigos, outros Squads, publicações |
| voltar ao Squad | nada ressuscita | — |
| desfazer o compartilhamento | audiência daquele Squad para aquele check-in | Feed de amigos, outros Squads, o check-in |
| excluir o Squad | audiência daquele Squad | Feed de amigos, outros Squads, o check-in |
| excluir o check-in | todas as audiências | — |
| excluir a conta | as interações da conta em todas as audiências | interações das outras contas |
| desfazer a amizade | visibilidade das interações `FRIEND` | audiência de Squad nenhuma |

Excluir um Squad exige limpeza **explícita**: a exclusão é soft (`status = 'DELETED'`), a linha de
`social_groups` continua existindo e nenhum `ON DELETE CASCADE` dispara.

A saída tem **dois** lados, e esquecer o segundo foi um defeito real pego em revisão. Sair leva
junto os compartilhamentos de quem sai (T17.11 §62), e com eles some o *objeto* daquela conversa —
então as reações e comentários que **os outros** deixaram nas publicações dela ali precisam sair
também. Sem isso ficariam vivos e inalcançáveis, e voltariam à tona no dia em que a pessoa
reentrasse e compartilhasse o mesmo check-in. "O compartilhamento acabou" precisa ter um efeito só,
e não dois conforme o caminho que o desfez.

## 8. Moderação

Podem apagar um comentário, nesta ordem de autoridade:

```text
autor do comentário  ∨  autor do check-in  ∨  dono do Squad — se a audiência for GROUP dele
```

O privilégio do dono não atravessa para o Feed de amigos (nem quando dono e autor são amigos), não
alcança outro Squad, não altera o check-in e não vira exceção de privacidade: um comentário que o
bloqueio já esconde do dono não se torna visível para ser moderado.

A rota de exclusão **não** recebe contexto: a audiência é uma propriedade do comentário, e é o
servidor que a lê. Aceitar um contexto ali deixaria a tela declarar em que audiência ela acha que
está — que é exatamente o que não pode decidir moderação.

Denunciar um comentário continua exigindo enxergá-lo naquela audiência (§59): o contexto da denúncia
é derivado do próprio comentário, e o denunciante precisa alcançá-lo por ali.

## 9. Android

O contexto pertence à **tela**, nunca a um estado global, e viaja pela navegação local
(`checkin/{id}?context=group&groupId=X`) — o backend revalida tudo de qualquer forma.

A regra que mais importa: **toda chave de cache inclui a audiência**. Uma chave por `checkInId`
sozinha mostraria os comentários do Squad X dentro do Squad Y só por cache, sem que o servidor
tivesse errado nada. Troca de conta limpa tudo, e resposta em voo cujo alvo mudou é descartada.

## 10. O que continua fora

Push de reação ou comentário, XP, missão, conquista, ranking, desafio, evento de Activity, mudança
de ordenação de feed, contador de não lidos, badge, realtime (WebSocket/SSE/polling), thread,
resposta a comentário, menção e hashtag. Nada de interação mora no Room ou no Outbox, e sem backend
a mutação falha claramente em vez de fingir sucesso.

O teto de comentários por publicação atravessa as audiências de propósito: ele contém enxurrada na
publicação de uma pessoa, e quem a recebe é o autor, que enxerga todas as audiências em que o
próprio post está. Contá-lo por audiência daria um multiplicador pelo número de Squads.
