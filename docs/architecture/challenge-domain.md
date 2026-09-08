# Desafios entre amigos (T17.3)

> **Status (verificado em 2026-09-08): implementado.** Dois tipos (`WORKOUTS_COMPLETED`,
> `ACTIVE_DAYS`), migration `0010_social_challenges.sql`, oito rotas sob `/v1/social`, e três telas
> no Android (lista, criação, detalhe com placar).
>
> **Não existe:** desafio público, descoberta global, ranking permanente, competição por
> "primeiro a atingir a meta", vencedor por ordem de chegada, placar em tempo real (WebSocket, SSE
> ou FCM), comentários, reações, chat, prêmios, XP por vencer, conquistas de desafio, entrada
> depois do início, rejoin, convite depois da criação, edição de desafio e exclusão de desafio.

---

## 1. As autoridades

```text
TREINO — local-first                      DESAFIO — server-authoritative

Ação                                      Ação
 ↓                                         ↓
Domain                                    Spark Backend
 ↓                                         ↓
Room  ← autoridade operacional            resultado
 ↓                                         ↓
Outbox → Spark Backend                    UI (sem cache durável)
```

| Coisa | Autoridade |
| --- | --- |
| Regras do desafio (tipo, meta, período, fuso) | **Backend** — imutáveis depois da criação |
| Participação (quem entrou, quem saiu) | **Backend** |
| Ciclo de vida (`UPCOMING`/`ACTIVE`/`ENDED`/`CANCELLED`/`VOID`) | **Backend**, derivado do relógio dele |
| Pontuação, posição, `goalReached` | **Backend**, derivado na leitura |
| Histórico de treino | **Room do aparelho**, replicado por sync — inalterado pela T17.3 |

O desafio é server-authoritative pela mesma razão que a identidade social (T17.0) e a amizade
(T17.1): ele é um fato sobre **várias** contas, e nenhum aparelho pode decidi-lo sozinho. Dois
celulares offline não podem inventar dois placares e depois "convergir" — convergir aqui
significaria dizer a alguém que ele perdeu uma disputa que achava ter vencido.

O treino continua local-first porque **executar um treino não pode depender de rede**. É dessa
assimetria que sai a regra mais importante desta fase, a de sincronização tardia (§6).

---

## 2. O fluxo

```text
Amizade (T17.1)
   │
   ▼
Criar desafio  ──── tipo · meta · período · fuso · amigos convidados
   │
   ├──▶ ChallengeInvitation (PENDING)
   │            ├── aceitar ──▶ participação (JOINED)
   │            └── recusar ──▶ DECLINED
   ▼
Challenge
   │
   ▼
dados canônicos de treino  (sync_entities / WORKOUT_SESSION, status COMPLETED)
   │
   ▼
ChallengeProgressSource      ← um escalar por pergunta
   │
   ▼
ChallengeScoringService      ← ranking, empates, goalReached
   │
   ▼
placar
```

---

## 3. A pontuação: o que conta, e por quê

### 3.1 O princípio

**O celular nunca informa ao servidor "estou com 8 de 12".**

```text
PROIBIDO                          IMPLEMENTADO
{ "challengeId": "...",           WorkoutSession canônica
  "progress": 8 }                        ↓
                                  Spark Backend (sync)
                                         ↓
                                  ChallengeProgressSource
                                         ↓
                                  ChallengeScoringService
                                         ↓
                                  score = 8
```

`score`, `progress`, `rank`, `winner`, `goalReached`, `points`, `count`, `leaderboard` e
`participants` são recusados **por nome** no corpo de qualquer requisição de desafio
(`challenge.validator.ts`), invalidando a requisição inteira. Não é filtragem silenciosa: um corpo
que carrega `progress: 8` descreve um cliente que acredita ser autoridade de pontuação, e aceitar o
resto dele seria concordar em parte.

### 3.2 A matriz

| Tipo | Fonte canônica | Regra exata | Deduplicação | Fuso | Sync tardio |
| --- | --- | --- | --- | --- | --- |
| `WORKOUTS_COMPLETED` | `sync_entities` / `WORKOUT_SESSION`, `deleted = 0` | `COUNT(*)` de sessões `COMPLETED` cujo `startedAt` cai em `[startsAt, endsAtExclusive)` | `UNIQUE (owner_uid, entity_type, entity_sync_id)` — uma linha por `syncId` | Janela derivada do `timeZoneId` do desafio | Conta quando chegar; elegibilidade é `startedAt` |
| `ACTIVE_DAYS` | a mesma | dias de calendário do desafio que contêm ≥1 sessão `COMPLETED` | a mesma, mais `EXISTS` por dia (2 treinos no mesmo dia = 1) | Cada dia convertido com `localMidnightToInstant` (ciente de horário de verão) | a mesma |

Os dois métodos devolvem **um número**. Não existe — e não pode existir — `getSessions()`,
`List<RawSyncEntity>` ou qualquer coisa que materialize uma linha de treino em JavaScript.
`json_extract` aparece só na cláusula `WHERE`: carga, exercício, nota e horário nunca saem do
SQLite. Há teste estrutural (`social-logging.spec.ts`) que lê o que as consultas selecionam.

### 3.3 A divergência resolvida: `startedAt`, e não `completedAt`

A especificação da tarefa fala em `completedAt`. **O Spark não tem esse campo.** O agregado
canônico é:

```text
WorkoutSessionSyncDto     status: String        (o servidor só aceita 'COMPLETED')
                          startedAt: Long       obrigatório
                          finishedAt: Long?     NULÁVEL
```

O instante canônico que atribui um treino a um dia é **`startedAt`**, e isso está estabelecido em
três lugares independentes do repositório:

1. `ConsistencyRepositoryImpl` alimenta o `ConsistencyCalculator` com `session.startedAt`;
2. a fixture `contracts/social/v1/weekly-window.json` declara
   `"counts": "somente WORKOUT_SESSION com status COMPLETED, pelo campo startedAt"`;
3. a T17.2 conta a semana social por ele.

Usar `finishedAt` teria dois defeitos, e os dois são bloqueantes da própria tarefa:

- seria uma **segunda regra** de atribuição de treino a dia. Um treino que começa 23h30 e termina
  00h15 cairia em dias diferentes no desafio e na tela de consistência da mesma pessoa — e ela
  veria dois números para o mesmo treino;
- `finishedAt` é **nulável**. Sessões concluídas com ele nulo deixariam de contar em silêncio. A
  regra "sessão sem instante válido não conta" é correta e vazia com `startedAt` (o campo é
  obrigatório); com `finishedAt` seria perda de treinos reais.

### 3.4 O que **não** conta

`PLANNED`, `IN_PROGRESS`, `PAUSED` e `CANCELLED` não valem nada — e nem chegam ao servidor, porque
o registry de sync só aceita `COMPLETED`. A cláusula está escrita na consulta mesmo assim: ela é a
declaração da regra no lugar onde a regra é aplicada, e é o que faz o teste falhar se o registry
algum dia passar a aceitar outro status.

Sessão com tombstone (`deleted = 1`) também não conta: apagar o próprio histórico é direito do
usuário (T16.7), e continuar pontuando por um treino removido faria o placar afirmar um fato que o
dono apagou.

---

## 4. Pontuação na leitura, e não um contador

Não existe `challenge_progress.current_score`, e não deve existir.

O motivo é o Spark ser local-first no treino: uma sessão feita durante o desafio pode chegar ao
servidor **depois** do fim. Um contador incremental teria de ser corrigido retroativamente por um
caminho que ninguém escreveu, e os modos de falhar são conhecidos — *drift*, incremento duplo num
reenvio, e o placar que nunca converge. Contar na leitura é sempre o número certo para o estado
conhecido agora.

Se a escala um dia exigir cache, ele nasce **derivado**, reconstruível e versionado pelo cursor da
fonte — nunca como autoridade. Há teste estrutural que prova que nenhuma tabela de desafio tem
coluna de pontuação.

Ler o placar é **read-only**: não escreve `WorkoutSession`, XP, conquista, missão, Outbox,
`sync_entities` nem `sync_changes`. Há teste que tira um retrato do banco, lê o placar dez vezes, e
compara.

---

## 5. Ciclo de vida derivado (sem cron)

O banco guarda **uma** coluna de estado: `lifecycle`, com `OPEN` ou `CANCELLED` — a única parte que
alguém escreve. Todo o resto é lido:

```text
lifecycle = CANCELLED                              ──▶ CANCELLED
now  <  startsAt                                   ──▶ UPCOMING
now >=  startsAt  e  participantes ativos < 2      ──▶ VOID
now  <  endsAtExclusive                            ──▶ ACTIVE
caso contrário                                     ──▶ ENDED
```

Persistir os cinco exigiria um processo periódico para virá-los, e um cron que não roda é um
desafio que nunca começa — silenciosamente. Derivar não tem esse modo de falhar.

`VOID` cobre as duas formas de "não há competição aqui": ninguém aceitou antes de começar, e todo
mundo saiu. Ele é **monotônico** — entrar exige aceitar, aceitar exige `now < startsAt`, e `VOID`
só é avaliado depois disso —, então o estado não pisca entre duas leituras.

O status do **convite** é derivado pela mesma lógica:

```text
gravado ACCEPTED / DECLINED         ──▶ ele mesmo (terminal)
gravado PENDING + desafio cancelado ──▶ CANCELLED
gravado PENDING + now >= startsAt   ──▶ EXPIRED
gravado PENDING                     ──▶ PENDING
```

Derivar `EXPIRED` é o que torna "aceitar depois do início" **impossível** em vez de "improvável":
não depende de um processo ter passado por ali antes do toque do usuário.

---

## 6. Sincronização tardia — a regra que protege quem treina offline

```text
B treina durante o desafio
   ↓
B fica offline por duas semanas
   ↓
o desafio termina        (status: ENDED)
   ↓
B sincroniza
   ↓
o placar converge — os treinos de B contam
```

Nenhuma consulta de pontuação olha `created_at`, `updated_at` ou `last_server_sequence` de
`sync_entities`. **A elegibilidade é o instante em que o treino aconteceu, e não o instante em que
ele chegou.**

É por isso que `ENDED` significa **"a janela de elegibilidade fechou"**, e não "o resultado é
final". O servidor não sabe — e não tem como saber — se todos os participantes já sincronizaram
tudo o que fizeram no período. Afirmar um resultado irrevogável seria afirmar o que ele não pode
verificar.

A resposta carrega `resultMayStillChange`, e a tela diz a verdade:

> O resultado pode atualizar se algum participante ainda estiver sincronizando treinos feitos no
> período.

Um resultado irrevogável exige uma política explícita de encerramento — quando parar de aceitar
dado tardio, e com que critério. Isso é uma tarefa própria (*settlement*), e não uma constante
arbitrária escondida aqui.

---

## 7. Consentimento — três autorizações distintas

```text
Amizade (T17.1)
   └──▶ permite CONVIDAR alguém para um desafio

Aceitar o convite (T17.3)
   └──▶ permite compartilhar, com os participantes DAQUELE desafio:
        displayName social · score daquele desafio · goalReached · posição

Interruptores de perfil (T17.2)
   └──▶ decidem o PERFIL — e nada mais
```

### 7.1 Privacidade de perfil ≠ autorização de desafio

Uma pessoa com `shareWeeklyWorkoutCount = false` — que é o **default** — continua aparecendo com
`7 / 12` no desafio que ela aceitou. As duas coisas são autorizações diferentes:

| | Perfil (T17.2) | Desafio (T17.3) |
| --- | --- | --- |
| O que autoriza | amizade + interruptor ligado | ter aceitado **aquele** convite |
| Quem vê | qualquer amigo ativo | só os participantes daquele desafio |
| O que vê | nível, sequência, treinos da semana | pontuação **daquele** desafio |
| Revogação | desligar o interruptor, ou desfazer a amizade | sair do desafio |

O caminho inverso também vale: **participar de um desafio não altera nenhum interruptor de
perfil.** Há teste dos dois lados.

### 7.2 O que participar **não** dá

Participar dá acesso ao **placar**, e não aos dados que o produziram. Nenhum DTO de desafio carrega
— e não pode passar a carregar:

```text
sessionId · syncId de sessão · exerciseId · nome de treino · horário de qualquer sessão
séries · cargas · repetições · notas
peso corporal · percentual de gordura · medidas · fotos
Firebase UID · e-mail · provider · deviceId · friendCode
```

Há teste que varre as respostas reais das quatro rotas de leitura atrás de cada um destes termos.

### 7.3 Desfazer amizade depois do aceite

```text
A e B amigos  →  B aceita o desafio  →  A e B desfazem a amizade
                                              ↓
                        o desafio CONTINUA, com os dois
                        o perfil social de B deixa de ser acessível a A
```

O consentimento do desafio já foi dado, e ele é sobre aquela disputa — não sobre a amizade. Remover
B automaticamente encerraria, por decisão do sistema, algo que as duas pessoas combinaram.

O que o `unfriend` **impede** é um convite novo: a amizade é revalidada na criação e no aceite.

---

## 8. Desativar o Social

Desativar retira o consentimento de compartilhar pontuação. Em **uma transação** com a mudança de
status do perfil:

```text
convites pendentes recebidos          ──▶ DECLINED
participações como MEMBER, em aberto  ──▶ WITHDRAWN
desafios abertos que a conta criou    ──▶ CANCELLED
desafios já encerrados                ──▶ intocados
amizades                              ──▶ intocadas (T17.1: desativar suspende, não desfaz)
```

O criador **cancela** em vez de apenas sair porque um desafio sem criador ativo é um desafio que
ninguém mais pode encerrar. Cancelar é honesto com os outros participantes: acaba para todos, sem
resultado.

Desafios encerrados não são tocados: o resultado deles é histórico do qual **outras** pessoas
participaram, e reescrevê-lo apagaria um fato delas para atender à decisão de uma.

A atomicidade não é zelo. Fora de uma transação, uma falha entre as duas escritas deixaria perfil
desativado com a pontuação da pessoa ainda atualizando no placar dos amigos — ou um desafio
cancelado para todos com o criador ainda ativo. As duas são visíveis para outras pessoas, e nenhuma
se corrige sozinha.

---

## 9. Datas, fuso e horário de verão

```text
startDate  2026-09-10  ─┐
endDate    2026-10-09  ─┼─▶ [meia-noite local de 10/09, meia-noite local de 10/10)
timeZoneId America/...  ─┘        derivado no SERVIDOR
```

- **O fuso é propriedade do desafio**, não do participante. Com um fuso por pessoa, "dia 8" seria
  um dia diferente para cada um, e `ACTIVE_DAYS` compararia coisas distintas.
- **O Android sugere; o servidor valida.** `ZoneId.systemDefault().id` vai no corpo, e o servidor o
  verifica contra o próprio runtime (ICU) — nunca contra uma lista mantida à mão.
- **Nenhum fuso é hardcoded.** `America/Sao_Paulo` aparece só em teste e em exemplo.
- **A janela é gravada em instantes na criação.** Não é recalculada a cada leitura: o banco de
  fusos do ICU é atualizado com o runtime, e um país que mude a regra de horário de verão no meio
  de um desafio deslocaria a janela de um desafio **já aceito**. As regras aceitas não mudam.
- **Um dia não é 24 horas.** Cada limite de dia é `localMidnightToInstant` da data local. Na virada
  do horário de verão a faixa tem 23 ou 25 horas — e é isso que a torna correta. Somar `+24h` faria
  um treino da noite cair no dia seguinte, e um dia ativo virar dois (ou sumir).

A conversão vive em `social-time.ts`, **compartilhada** com a semana canônica da T17.2. Duas
implementações de "meia-noite local" divergiriam, e a divergência apareceria como o perfil e o
desafio discordando sobre o dia de um treino.

**O desafio começa no dia seguinte, no mínimo.** Isso garante a ordem
`criação → convites → aceites → janela`, sem ninguém entrando horas depois e sem ter de decidir se
um treino anterior conta.

---

## 10. Placar, empates e vencedor

- Ordenação: `score` decrescente.
- Posição: *competition ranking* — `1, 1, 3`.
- **Empate permanece empate.** Não há desempate por ordem de chegada ao servidor (puniria quem
  sincronizou depois) nem por `createdAt` da sessão (inventaria um critério que ninguém combinou).
- O desempate **de exibição** entre duas linhas de mesma posição é `displayName` e depois
  `socialId`: estável e sem significado competitivo. Sem ele, duas linhas empatadas trocariam de
  lugar entre dois *refreshes*.
- `score` **pode ultrapassar** o `target`: 15 de 12 é `score = 15`. A barra de progresso é que se
  limita a 100%; truncar o número apagaria um fato para caber num desenho.
- `goalReached` é **separado** de liderar. Várias pessoas podem bater a meta; quem lidera é quem
  tem o maior `score`.
- Cancelado e `VOID` **não têm vencedor**, e o servidor nem calcula o placar deles.
- Quem saiu (`WITHDRAWN`) não aparece no placar competitivo. A linha permanece no banco — sair não
  apaga o fato de ter participado —, e a contagem de quem saiu aparece como metadado, sem nomes.

---

## 11. Fronteiras de acesso

| Quem | O que vê |
| --- | --- |
| Participante (`JOINED` ou `WITHDRAWN`) | regras + placar + o que pode fazer |
| Criador | o mesmo, mais **quantos** convites estão pendentes (nunca os nomes) |
| Convidado que ainda não respondeu | **preview**: regras, quem convidou, quantos aceitaram. **Sem placar** |
| Qualquer outra conta | `404 CHALLENGE_NOT_FOUND` |

Ver o progresso dos outros antes de consentir em mostrar o próprio é a assimetria que o
consentimento existe para impedir — por isso o convite não traz placar.

"Não existe", "é de outra pessoa" e "só fui convidado" respondem **a mesma coisa**. Conhecer um
`challengeId` não concede nada, e distinguir os casos transformaria a rota num oráculo sobre
desafios alheios.

---

## 12. O que o desafio não toca

- **Não entra no Outbox da T16.** Offline, criar/aceitar/recusar/sair/cancelar **não acontecem** —
  não ficam pendentes e não são reenviados. A tela diz que nada foi enviado.
- **Não é `sync_entities`.** Não existe `entityType` `CHALLENGE`; um push que tente declarar um
  responde `UNSUPPORTED`. Há teste.
- **Não entra no backup do Android nem no restore.** Restaurar um snapshot não altera desafio
  nenhum.
- **Não existe `ChallengeEntity` no Room.** Uma cópia local seria uma segunda verdade sobre um fato
  de várias pessoas, e continuaria mostrando um desafio que o criador cancelou.
- **Não gera gamificação.** Criar, participar, pontuar, vencer e ler o placar não dão XP, não
  desbloqueiam conquista e não movem missão.
- **Não força sincronização.** Abrir um desafio não pede push nem pull de ninguém.
- **Não tem tempo real.** Sem WebSocket, sem SSE, sem FCM, sem polling.

O backup do servidor (T16.8) protege as tabelas de desafio naturalmente, porque elas estão no mesmo
SQLite da VPS. Não há um segundo mecanismo.

---

## 13. A fronteira anti-fraude — o que esta fase garante, e o que não

**Esta fase não torna o desafio à prova de fraude, e dizer o contrário seria falso.**

O que ela garante:

1. o cliente **não envia** pontuação, posição nem vencedor — os campos são recusados por nome;
2. a pontuação deriva do domínio canônico que o servidor já conhece, pelas mesmas regras que a
   própria pessoa vê na tela de consistência dela;
3. o cliente não escolhe o dono da contagem: o `ownerUid` sai de `challenge_participants`,
   resolvido no servidor, e nunca de uma query string, corpo ou cabeçalho.

O que ela **não** garante: um cliente comprometido que consiga fabricar `WorkoutSession`
**canônicas** — sessões que passam pela validação do sync — produziria pontuação correspondente.
Esse é um problema diferente, de integridade do dado de treino em si, e ele existiria igual sem
desafio nenhum.

---

## 14. Superfície

### Rotas (todas exigem Firebase Auth e perfil social `ACTIVE`)

| Rota | Quem | O que faz |
| --- | --- | --- |
| `POST /v1/social/challenges` | criador | cria + convida, em uma transação; idempotente por `clientRequestId` |
| `GET /v1/social/challenges` | participante | meus desafios, ordenados por estado |
| `GET /v1/social/challenges/:id` | participante | regras + placar |
| `POST /v1/social/challenges/:id/cancel` | só o criador | encerra para todos, sem resultado |
| `POST /v1/social/challenges/:id/leave` | só membro | sai; o criador cancela |
| `GET /v1/social/challenge-invitations` | destinatário | convites pendentes (preview, sem placar) |
| `POST /v1/social/challenge-invitations/:id/accept` | só o destinatário | entra, antes do início |
| `POST /v1/social/challenge-invitations/:id/decline` | só o destinatário | recusa |

Criar, aceitar, recusar, sair e cancelar são **idempotentes**: o toque duplo e o reenvio depois de
uma resposta perdida devolvem o estado que o cliente queria, e não um erro.

### Tabelas (`0010_social_challenges.sql`)

```text
challenges                     regras + lifecycle (OPEN/CANCELLED) + janela derivada
challenge_invitations          PENDING/ACCEPTED/DECLINED, UNIQUE (challenge_id, recipient_uid)
challenge_participants         CREATOR/MEMBER × JOINED/WITHDRAWN, PK (challenge_id, uid)
challenge_creation_requests    ledger de idempotência, PK (owner_uid, client_request_id)
```

Nenhuma tem coluna de pontuação.

### Telas (Android)

```text
Perfil → Social → Desafios
                    ├── lista (ativos, próximos, encerrados) + convites
                    ├── Criar desafio  (tipo · nome · meta · período · amigos · revisão)
                    └── Detalhe        (regras + placar + cancelar/sair)
```

Sem item novo de *bottom navigation*: a barra inferior é do núcleo do produto, e o social continua
sendo uma área dentro do Perfil.

### Limites (todos aplicados no servidor)

| | Valor |
| --- | --- |
| Nome | 3–60 code points, Unicode, sem caracteres de controle |
| Duração | 1–90 dias de calendário, inclusivos |
| Meta `WORKOUTS_COMPLETED` | 1–200 |
| Meta `ACTIVE_DAYS` | 1 até a duração do desafio |
| Participantes | 10, incluindo o criador |
| Mínimo para competir | 2 (abaixo disso: `VOID`) |
| Desafios abertos por criador | 20 |
| Criações por minuto, por conta | 5 |
| Respostas por minuto, por conta | 30 |

---

## 15. Logs

**Servidor:** `requestId`, prefixo de uid (6 caracteres), evento, desfecho, **tipo** de desafio,
status e contagens.

**Nunca:** nome do desafio, `displayName`, `socialId`, `friendCode`, e-mail, uid completo, corpo da
requisição — e **pontuação individual**. Um placar em log seria o progresso de treino de alguém em
texto claro numa cópia de arquivo, e ele não é necessário para operar nada.

**Android:** nada. O pacote social não registra log, e a ausência é testada.

---

## 16. Testes

**Backend** (`npm test` em `backend/`):

- `challenge.spec.ts` — criação, convites, participação, ciclo de vida, autorização A/B/C,
  privacidade do DTO, listagem, desativar o Social;
- `challenge-scoring.spec.ts` — os dois tipos sobre o **sync real**, janela, deduplicação por
  `syncId`, sync tardio, empates, `goalReached`, fronteiras de horário de verão, e a fonte
  canônica direta;
- `challenge-persistence.spec.ts` — migration aditiva, constraints, restart, e a prova de que
  desafio não entra em `sync_entities` nem no backup;
- `social-logging.spec.ts` — as fronteiras estruturais, estendidas para a fonte da T17.3.

**Android** (`./gradlew :app:testDebugUnitTest`):

- `ChallengeViewModelTest` — o que o app envia (campo a campo), toque duplo, retry idempotente,
  offline, troca de conta, e a prova de que o Room não muda;
- `ChallengeScreensTest` — placar, empates, pontuação acima da meta, aviso de convergência,
  cancelado/`VOID` sem placar, e a varredura de privacidade;
- `SocialBoundaryInspectionTest` — as fronteiras estruturais do pacote, estendidas para a T17.3.

Os dois lados são **offline**: sem Firebase real, sem VPS, sem rede. O ciclo de vida usa um relógio
injetado (`Clock` no servidor, `today`/`deviceTimeZoneId` no app) — nenhum teste dorme.

---

## 17. Pendências registradas

- **Resultado irrevogável** (*settlement*): exige uma política explícita sobre até quando aceitar
  dado tardio. Fora de escopo.
- **Tipos novos** (`TOTAL_VOLUME`, `XP_GAINED`, `PR_COUNT`, `CALORIES`, `TOTAL_SETS`,
  `TOTAL_REPS`, `TIME_TRAINED`): cada um exige resolver a fonte primeiro. Volume, séries,
  repetições e tempo exigiriam abrir o conteúdo da sessão, o que `AGGREGATE_ONLY` proíbe; XP e PR
  são DERIVED na matriz da T16 e não saem do aparelho.
- **Convite depois da criação**, **rejoin** e **edição de desafio**: fora de escopo por decisão,
  não por esquecimento — os três criam corridas e regras que esta fase não precisa.
- **Notificação (FCM)**: quem abre a tela vê; quem não abre não é interrompido.
