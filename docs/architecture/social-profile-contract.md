# Perfil social do Spark — projeção de progresso, privacidade e freshness

- **Tarefa:** T17.2 — perfil social e compartilhamento controlado de progresso.
- **Status (verificado em 2026-09-08): implementado, com uma métrica das quatro.** Migration
  `0009_social_progress_profile.sql`, `social-profile.*` e `social-progress.*` em
  `backend/src/modules/social/`, gateway e telas de Perfil de amigo / Compartilhar progresso no
  Android.
- **Atualização (T19.2, 2026-09-16): as quatro métricas têm autoridade remota.** Nível, sequência
  semanal e conquistas deixaram de responder `UNSUPPORTED`: o servidor passou a **derivá-las** dos
  treinos sincronizados e de parâmetros de configuração declarados pelo dono (meta por semana e
  início do acompanhamento). O pipeline, a privacidade e a autorização desta página **não mudaram**;
  o que mudou está em [`social-progress-authority.md`](./social-progress-authority.md), e as
  seções §2 e §6 abaixo trazem a nota correspondente.
- **Base:** [`social-domain.md`](./social-domain.md) (T17.0 — identidade e privacidade) e
  [`friendship-contract.md`](./friendship-contract.md) (T17.1 — o grafo).
- **Contrato de protocolo:** [`contracts/social/v1/README.md`](../../contracts/social/v1/README.md)
- **Fixture compartilhada:**
  [`contracts/social/v1/weekly-window.json`](../../contracts/social/v1/weekly-window.json)

**Não existe** nesta fase: seleção de conquistas em destaque, atividade recente, feed, ranking,
desafio, presença ("online agora"), `lastSyncAt` para amigos, perfil em lote, notificação push,
avatar e exclusão de conta.

---

## 1. O pipeline, e por que ele tem quatro etapas

```text
PRIVATE DOMAIN
   Room do aparelho          ──sync (T16.6)──▶   sync_entities (servidor)
        │                                              │
        │  (nível, sequência e conquistas               │
        │   NÃO atravessam — ver §3)                    │
        ▼                                              ▼
autoridade canônica de progresso              SocialProgressSource      ← adapter estreito
        │                                              │
        └──────────────────────────────────────────────┤
                                                       ▼
                                          SocialProgressProjector       ← projeta, não calcula
                                                       │
                                                       ▼
                                       SocialProgressPrivacyFilter      ← o que o dono permitiu
                                                       │
                                                       ▼
                                            SocialFriendProfileDto      ← o que o amigo recebe
```

Cada etapa existe para impedir uma coisa concreta:

| Etapa | O que ela impede |
| --- | --- |
| `SocialProgressSource` | um serviço social fazendo `SELECT payload` e um campo novo do treino virando campo social sem ninguém decidir |
| `SocialProgressProjector` | o Social virar uma segunda autoridade de progresso |
| `SocialProgressPrivacyFilter` | privacidade aplicada na tela em vez de no servidor |
| DTO | identidade privada, dado bruto e vestígio de configuração atravessando |

E a **ordem** também é contrato (§116/§117 da tarefa): autenticar → resolver o visitante →
resolver o alvo → verificar a amizade **agora** → projetar → filtrar. Projetar antes de autorizar
seria o desenho que, no dia de um bug, responde o dado de alguém para quem não tem direito a ele.

## 2. As fontes canônicas, campo a campo

Esta é a tabela que a T17.2 tinha de produzir **antes** de qualquer código, e ela foi produzida
lendo o código — não a documentação.

| Campo social | Autoridade canônica real | Regra | Chega ao servidor? | Estratégia (T17.2) | Estratégia (T19.2) |
| --- | --- | --- | --- | --- | --- |
| `level` | `XpTransactionRepositoryImpl.calculateProgress` sobre `xp_transactions` (Room) | 500 XP no nível 1, `level × 500` depois | **não** | `UNSUPPORTED` | **derivado**: XP reconstruível (treinos, primeiro treino, meta semanal, 4 missões) → mesma curva; PR fica fora — nível verificado ≤ local |
| `consistencyStreak` | `ConsistencyCalculator.calculateProgress` sobre semanas, `weekly_goal_history` e `trackingStartedAt` | sequência **semanal** | sessões **sim**; meta e início **declarados** pelo dono via `PATCH` | `UNSUPPORTED` | **derivado**: `social-consistency.ts`, a mesma regra, presa por `consistency-streak.json` |
| `weeklyWorkoutCount` | contagem de sessões `COMPLETED` na semana canônica (`ConsistencyCalculator.weekStart`) | segunda a domingo, data local | **sim** — `sync_entities` / `WORKOUT_SESSION` | **projetado** | inalterado |
| `highlightedAchievementIds` | `AchievementEvaluator` + `achievement_unlocks` (Room) | catálogo `AchievementCatalog` + eventos, PRs, sequência e medições | treinos e medições **sim**; PRs **não** | `UNSUPPORTED` | **derivado** para `TRAINING`, `CONSISTENCY` e `BODY`; `PERFORMANCE` nunca publicada |

> **T19.2.** O texto que segue nesta seção descreve a decisão da T17.2 e o motivo dela. A saída 2
> ("portar para TypeScript") foi a escolhida na T19.2 — **com** a fixture compartilhada que a T17.2
> já usava para a semana, que é o que transforma "duas implementações que divergem em silêncio" em
> "duas implementações em que a divergência reprova um teste". A saída 1 continua proibida. Ver
> [`social-progress-authority.md`](./social-progress-authority.md).

A razão das três `UNSUPPORTED` é a mesma, e ela está registrada desde a T16 na
[matriz de dados](./data-classification-matrix.md): **gamificação é `DERIVED`**. `xp_transactions`,
`achievement_unlocks` e `gamification_events` não entram no sync incremental nem no backup — cada
aparelho os reconstrói do histórico. `weekly_goal_history` está no backup, mas **fora** do sync
incremental; `trackingStartedAt` vive no DataStore e nunca sai do aparelho.

As duas saídas para publicá-las seriam:

1. **aceitar o valor que o Android declara.** Proibido (§85–§87): o servidor passaria a confiar no
   cliente sobre progresso, e um APK modificado se declararia nível 99. "O perfil social do Igor
   diz nível 14" deixaria de significar alguma coisa;
2. **portar `XpCalculatorService`, `ConsistencyCalculator` e `AchievementEvaluator` para
   TypeScript.** Proibido (§3): duas implementações da mesma regra divergem no primeiro ajuste, e a
   divergência aparece como um perfil social afirmando um nível que o aparelho da própria pessoa
   não reconhece.

Então elas respondem `UNSUPPORTED`, o interruptor existe e pode ser ligado, e o campo continua
ausente. **Não fingir suporte** é a decisão; ela custa uma tela mais pobre nesta versão.

### O que desbloqueou as três (T19.2)

Uma autoridade **remota** para elas — não um envio do cliente. A T19.2 a construiu sem criar um
agregado de gamificação sincronizado: o servidor reconstrói consistência, XP verificável, nível e
conquistas a partir dos treinos e medições que **já** sincronizam, mais dois parâmetros de
configuração (meta por semana e início do acompanhamento) que o dono declara pela rota social —
exatamente como já declarava o fuso. A gamificação local continua `DERIVED`, continua a autoridade
operacional do aparelho, e continua sem sincronizar resultado nenhum.

## 3. `weeklyWorkoutCount`: uma derivação, e não uma regra nova

```text
Android (ConsistencyCalculator)                 Servidor (canonicalWeekWindow)
timestamps de sessões COMPLETED         sync_entities / WORKOUT_SESSION, deleted = 0
agrupados por weekStart = segunda-feira  COUNT(*) em [segunda 00:00, próxima segunda 00:00)
na data LOCAL do treino                  na data local do dono, pelo fuso declarado
```

O que torna isso uma derivação legítima, e não uma segunda autoridade:

- **a definição de semana é canônica.** `ConsistencyCalculator.weekStart` é segunda-feira, e o
  domínio a expõe explicitamente "para que outras camadas compartilhem esta regra em vez de
  recriarem um segundo conceito de início de semana";
- **a definição de "treino que conta" é do schema.** `workoutSessionSchema` aceita **apenas**
  `status: 'COMPLETED'`. `PLANNED`, `IN_PROGRESS`, `PAUSED` e `CANCELLED` sequer chegam ao servidor
  — e a cláusula está escrita na consulta mesmo assim, porque é lá que a regra é aplicada;
- **o que sobra é uma contagem.** Não há política, não há limiar, não há decisão.

### O fuso, e por que ele precisou existir

O servidor guarda `startedAt` em epoch millis UTC e não sabia em que fuso a pessoa treina. Sem
isso, um treino de domingo 22h em São Paulo cairia na semana seguinte para o servidor e na semana
corrente para o aparelho — e a contagem social discordaria da tela do próprio dono.

`social_progress_settings.week_time_zone` guarda o identificador IANA do aparelho, enviado junto
com a primeira alteração de compartilhamento. Ele **não é preferência de privacidade** e **não é
progresso**: é o parâmetro que torna a semana canônica reproduzível. Ausente, a contagem responde
`UNAVAILABLE` — nunca uma suposição de UTC, que produziria um número plausível e errado.

O risco aceito e documentado: um cliente modificado poderia declarar outro fuso e deslocar a janela
em algumas horas. O que ele obteria é a contagem de **sessões reais suas** numa janela vizinha —
não um número inventado, e não dado de mais ninguém.

### A fixture que amarra os dois lados

`contracts/social/v1/weekly-window.json` carrega seis casos — semana comum, o treino de domingo à
noite que em UTC já é segunda, meia-noite exata, início e fim de horário de verão (semanas de 167 e
169 horas) e um fuso `UTC+14`. Ela é lida por `backend/test/social-progress.spec.ts` e por
`SocialWeekWindowContractTest` no Android. Uma mudança unilateral quebra o teste de quem mudou, em
vez de virar "o perfil do meu amigo mostra um número diferente do meu".

## 4. Privacidade

| Campo | Default | Efeito |
| --- | --- | --- |
| `shareLevel` | `false` | T17.2: nunca publicava (`UNSUPPORTED`). **T19.2:** publica o nível verificado quando há fuso, parâmetros de consistência e sessão sincronizada |
| `shareConsistencyStreak` | `false` | T17.2: idem. **T19.2A:** publica a sequência canônica quando há fuso, parâmetros e sessão |
| `shareWeeklyWorkoutCount` | `false` | publica quando há dado e fuso |
| `shareHighlightedAchievements` | `false` | T17.2: nunca publicava. **T19.2C:** publica as conquistas verificáveis (treino, consistência, corpo) quando há fuso e ao menos uma |

- **todos nascem desligados**, e a migration os grava assim para quem já tinha perfil social
  (T17.0/T17.1). Subir esta versão não publica nada de ninguém;
- **`displayName` não tem interruptor.** Sem nome social, uma amizade não teria representação útil
  na tela — e ele já é o que a lista de amigos da T17.1 mostra;
- **`friendCode` não entra no perfil.** Depois que o código cumpriu a função de descoberta, quem
  identifica é o `socialId`. Uma lista de amigos que carregasse o código de convite de todo mundo
  seria uma lista redistribuível que ninguém escolheu publicar;
- **XP exato não é compartilhável**, em nenhuma forma: não há `totalXp`, `xpTransactions` nem
  `xpHistory` no contrato. `level` é a representação social pretendida.

### Duas condições, e as duas precisam ser verdade

Um campo só aparece quando o dono **ligou** o interruptor **e** o servidor **tem** o valor. Ligar
sem dado não publica zero; ter dado sem ligar não publica nada.

### Escondido e indisponível são a mesma coisa — para o amigo

Os dois resultam em **campo ausente**. Não existe `level: null`, não existe `levelHidden: true` e
não existe `visibility` no DTO do amigo: qualquer um dos três contaria ao visitante o que o dono
escolheu, e essa é informação do dono.

O **próprio dono** distingue os três estados, e só ele:

```text
AVAILABLE     "Disponível"              nada a fazer
UNAVAILABLE   "Ainda não disponível"    sincronizar resolve
UNSUPPORTED   "Em breve"                sincronizar NÃO resolve
```

Colapsar os dois últimos faria a tela prometer que sincronizar publicaria o nível. Desde a T19.H0
a etiqueta e o interruptor de `UNSUPPORTED` também não podem sugerir "APK antigo" ou "atualização
pendente" (é limitação arquitetural, não versão desatualizada) — ver
[`SocialProfileMessages.kt`](../../app/src/main/java/com/example/presentation/friends/SocialProfileMessages.kt).

## 5. Autorização

```text
autenticar (Firebase ID Token)
   ↓
visitante tem perfil social?  não → SOCIAL_NOT_ENABLED (404)
visitante está ACTIVE?        não → SOCIAL_PROFILE_DISABLED (409)
   ↓
alvo existe? está ACTIVE? são amigos AGORA?
   ↓ qualquer não
FRIEND_PROFILE_NOT_FOUND (404)
```

Quatro situações respondem **a mesma coisa**, de propósito: `socialId` inexistente, alvo
desativado, "não somos amigos" e "existe apenas um pedido pendente". Distinguir qualquer uma delas
transformaria a rota num oráculo — uma conta C que conhecesse o `socialId` de B aprenderia, só
comparando respostas, que B existe e que B desativou o social.

Consequências que valem como contrato:

- **pedido `PENDING` não concede acesso.** Pedir para ver não é ser autorizado a ver;
- **`unfriend` revoga na requisição seguinte.** A amizade é verificada em cada leitura, contra a
  linha de `friendships` de agora — não há cache, token de acesso ou estado que o app apresente no
  lugar da verificação;
- **desativar o Social esconde dos dois lados.** O alvo desativado some; o visitante desativado
  para de consumir perfil social, inclusive o próprio preview. Reativar devolve tudo, porque a
  amizade continuava gravada (política da T17.1);
- **o próprio perfil sai por `/me/profile-preview`**, e não por uma amizade fingida consigo mesmo.

## 6. API

| Rota | O quê |
| --- | --- |
| `GET /v1/social/friends/{socialId}/profile` | o perfil enriquecido de um amigo |
| `GET /v1/social/me/profile-preview` | exatamente o que um amigo veria de mim agora |
| `GET /v1/social/me/progress-sharing` | minhas preferências + a disponibilidade de cada campo, o motivo de cada indisponível e a versão do contrato (T19.H5, §H5) |
| `PATCH /v1/social/me/progress-sharing` | altera preferências (**parcial** — os quinze interruptores desde a T19.H3), o fuso e, desde a T19.2A, os parâmetros de consistência |

Todas exigem `Authorization: Bearer <Firebase ID Token>`. **Não existe rota pública**, não existe
busca por nome, por e-mail ou listagem global, e **não existe rota de perfil em lote** — um
endpoint de colheita é a diferença entre "meu amigo vê meu progresso" e "qualquer um baixa o
progresso de todo mundo".

### Corpo do `PATCH`

```json
{
  "shareLevel": true,
  "shareConsistencyStreak": false,
  "shareWeeklyWorkoutCount": true,
  "shareHighlightedAchievements": false,
  "weekTimeZone": "America/Sao_Paulo",
  "consistency": {
    "trackingStartedAtEpochDay": 20684,
    "weeklyGoals": [{ "weekStartEpochDay": 20682, "goal": 3 }]
  }
}
```

Semântica de `PATCH`, e só ela: o que não veio no corpo **não muda**. `level`, `streak`,
`weeklyWorkoutCount`, `totalXp` e `earnedAchievementIds` são recusados **por nome**, com a
requisição inteira invalidada — ignorar em silêncio deixaria um cliente acreditando que enviar
progresso significa alguma coisa. `updatedAt` vem do relógio do servidor.

`consistency` (T19.2A) é **configuração**, como o fuso: os dois insumos de `ConsistencyCalculator`
que não são sessão. Quando vem, substitui o conjunto inteiro. `weekStartEpochDay` precisa ser uma
segunda-feira, `goal` fica em 1..7, epoch days ficam entre 2020-01-01 e amanhã, e no máximo 520
snapshots. Campos de **resultado** aninhados aqui (`streak`, `longestStreak`, `completedWorkouts`,
`unlocked`...) são recusados por nome como no topo. A resposta devolve `settings.consistency` ao
dono (e só a ele) para que o app saiba quando reenviar.

### Resposta do amigo

```json
{
  "profile": {
    "socialId": "...",
    "displayName": "Igor",
    "sharedProgress": { "weeklyWorkoutCount": 3 }
  }
}
```

`sharedProgress` está sempre presente e pode estar **vazio** — e o vazio é o mesmo para quem
desligou tudo e para quem ligou sem ter dado. Desde a T19.2 ele pode carregar também `level`,
`consistencyStreak` e `highlightedAchievementIds` (ids canônicos do `AchievementCatalog`, na ordem
do catálogo); desde a T19.H3, `weeklyTrainingMinutes`, `weeklyCompletedSets`, `weeklyVolumeKg` e
`totalWorkouts` (§V3.1). Nunca parâmetros, fuso, XP ou contagens intermediárias.

### Erros

| `code` | HTTP | Quando |
| --- | --- | --- |
| `FRIEND_PROFILE_NOT_FOUND` | 404 | alvo inexistente, desativado, sem amizade, ou só com pedido pendente |
| `INVALID_PROGRESS_SETTINGS` | 400 | corpo fora do contrato, valor de progresso enviado, fuso inválido |
| `SOCIAL_NOT_ENABLED` | 404 | a conta do chamador não tem perfil social |
| `SOCIAL_PROFILE_DISABLED` | 409 | o **chamador** desativou os recursos sociais |
| `UNAUTHENTICATED` | 401 | sem token válido |

A T17.2 **não** criou limitador próprio: as rotas leem o próprio perfil e o perfil de **um** amigo
nomeado, sem enumeração possível — a resposta exige uma amizade que o outro lado aceitou. O teto
geral de 600/min do `BearerAuthGuard` basta.

## 7. Freshness — o que o perfil reflete

```text
usuário treina offline
        ↓
não sincronizou ainda
        ↓
o amigo continua vendo o estado anterior       ← e isso está CORRETO
```

O perfil social reflete **o que o servidor sabe**, nunca o que o aparelho da outra pessoa acabou de
registrar. Abrir um perfil **não** dispara push nem pull de ninguém (§68): o Social não sincroniza
o treino de terceiros, e não sincroniza o seu.

Na tela do dono, o texto discreto que evita o mal-entendido:
*"Seu progresso compartilhado é atualizado depois da sincronização."*

## 8. O que nunca é social — e o que passou a ser opt-in (T19.H3)

Registrado explicitamente, porque a lista é o contrato. **Nunca**, com ou sem escolha do dono:

```text
e-mail                    Firebase UID              friendCode (no perfil)
medidas corporais         peso corporal             percentual de gordura
RPE, RIR                  notas                     machineLabel, motivo de troca
PR                        localização, academia     presença ("online agora"), lastSyncAt
sessionSyncId             templateSyncId            syncId/localId de exercício
payload de sync           payload de backup         sync_entities como API
```

Até a T19.H2 esta lista incluía também cargas, séries, repetições, horário, duração, nomes de
treino e exercícios. A T19.H3 substituiu esse "nunca" por **"somente quando explicitamente
autorizado + derivado do servidor"** — e com um lugar certo para cada coisa: agregado no perfil
(§V3.1), detalhe de uma sessão no check-in (§V3.2). Cada um é um interruptor próprio, nascido
desligado.

Há teste que varre a resposta real do endpoint de amigo, do Feed e do detalhe procurando cada item
da primeira lista, com os interruptores desligados **e** ligados.

## V3. Compartilhar Progresso V3 (T19.H3)

### V3.0 A regra central

> O usuário escolhe o que compartilhar; o servidor decide os valores a partir dos fatos canônicos
> sincronizados.

```text
WORKOUT_SESSION sincronizada ──▶ SocialWorkoutFactsSource (whitelist no SQL)
                                        │
                     ┌──────────────────┴──────────────────┐
                     ▼                                     ▼
           social-training-metrics.ts             social-training-metrics.ts
           (soma da semana canônica)              (uma sessão)
                     │                                     │
         SocialProgressPrivacyFilter            projectWorkoutSummary (inclusão)
                     │                                     │
          sharedProgress (perfil)              workoutSummary (check-in)
```

O cliente nunca envia valor: `weeklyVolumeKg`, `totalWorkouts`, `workoutSummary`, `exercises`,
`sets`, `reps`, `weightKg` e afins são recusados **por nome** no `PATCH`, como `level` e `streak`.

### V3.1 Estatísticas de treino (perfil)

| Interruptor | Campo em `sharedProgress` | Definição | Disponível quando |
| --- | --- | --- | --- |
| `shareWeeklyTrainingMinutes` | `weeklyTrainingMinutes` | `floor(Σ max(fim − início, 0) / 60)` das sessões da semana canônica | fuso conhecido e ≥ 1 sessão |
| `shareWeeklyCompletedSets` | `weeklyCompletedSets` | séries de trabalho concluídas na semana canônica | idem |
| `shareWeeklyVolume` | `weeklyVolumeKg` | `Σ peso × reps` das séries de trabalho concluídas, 1 casa | idem |
| `shareTotalWorkouts` | `totalWorkouts` | `COUNT` de `WORKOUT_SESSION` `COMPLETED`, sem janela | ≥ 1 sessão (não depende de fuso) |

A semana é a **mesma** de "Treinos da semana": segunda a domingo no fuso do dono, pelo `startedAt`.
A leitura é bounded (100 sessões na semana); acima disso a métrica responde `UNAVAILABLE` com
motivo `SOURCE_LIMIT_REACHED` (T19.H5), nunca uma soma truncada. Zero é valor: com uma sessão
sincronizada e fuso conhecido, uma semana sem treino publica `0` minutos, `0` séries e `0` kg, e
uma semana só de peso corporal publica volume `0` (§H5.4).

### V3.2 Detalhes dos check-ins (`workoutSummary`)

| Interruptor | Campo | Observação |
| --- | --- | --- |
| `shareWorkoutName` | `name` | `templateNameSnapshot` (treino livre não tem) |
| `shareWorkoutTime` | `startedAt` | epoch millis; o app formata no fuso de quem lê |
| `shareWorkoutDuration` | `durationSeconds` | `fim − início`; sem fim, ausente |
| `shareWorkoutExercises` | `exerciseCount`, `exercises[].name`, `exercises[].primaryMuscle` | só exercícios com ≥ 1 série de trabalho; nome do **snapshot** (CUSTOM incluído) |
| `shareWorkoutSets` | `completedSetCount`; com Exercícios, `exercises[].sets[].reps`/`durationSeconds` | séries de trabalho concluídas, em ordem |
| `shareWorkoutWeights` | `exercises[].sets[].weightKg` | **exige** Exercícios **e** Séries; peso 0 não vira "0 kg"; 2 casas |
| `shareWorkoutVolume` | `totalVolumeKg` | a mesma conta do agregado |

Sem nenhum interruptor ligado — ou sem sessão canônica (apagada depois do check-in) — o check-in
sai **sem** `workoutSummary`, exatamente como um check-in da T17.8.

### V3.3 As definições, uma vez

- **Série de trabalho concluída:** `completed = true` e tipo ≠ `WARMUP` — a regra de
  `VolumeCalculator.countEffectiveSets` do app.
- **Volume:** `Σ peso × reps` dessas séries, excluindo séries por tempo (`durationSeconds > 0`).
  Série com **peso 0** (peso corporal, elástico) **não adiciona carga**: o servidor não conhece o
  peso de ninguém e não estima peso corporal.
- **Duração:** `finishedAt − startedAt`, derivada no servidor; nunca um valor enviado à parte.

`weeklyVolumeKg` e `totalVolumeKg` saem do mesmo helper (`social-training-metrics.ts`); há teste
que prova que a soma da semana é a soma dos check-ins da semana.

### V3.4 Privacidade

- **Por inclusão, no servidor.** Campo desligado não existe no JSON — nunca `null`, nunca
  escondido no Compose. Um cliente modificado recebe exatamente o que a tela mostra.
- **Retroativo.** O check-in guarda só `source_session_sync_id`; o resumo é refeito a cada
  leitura com as escolhas **atuais**. Desligar "Cargas" tira as cargas dos check-ins antigos na
  próxima leitura de qualquer amigo.
- **Audiência.** O resumo aparece onde o check-in já aparece — Feed de amigos, Squads onde foi
  compartilhado, detalhe e o próprio autor (que vê o mesmo que os outros). Quem vê continua
  decidido pela política de acesso do check-in, e **bloqueio continua superior**.
- **Defaults.** A migration `0008` criou os onze interruptores com `NOT NULL DEFAULT FALSE`;
  nenhuma conta existente passou a publicar nada.

### V3.5 Custo

O Feed faz três consultas por página para o resumo (escolhas dos autores; sessão de origem das
publicações cujos autores ligaram algum detalhe; fatos dessas sessões), independentemente do
número de itens — há teste que compara 2 e 8 publicações. Um Feed de autores que não compartilham
nada custa uma consulta a mais.

## H5. Disponibilidade real, sync assistido e contrato versionado (T19.H5)

### H5.0 O que aconteceu, e o que a T19.H5 corrigiu

O deploy da T19.H3 (`02c1289`) falhou em 2026-09-24 no portão de procedência, e a produção ficou
num backend anterior a ela até 2026-09-25 (`7a748db`, revision `spark-backend-00024-zan`, migration
`0008` aplicada). O APK da T19.H3 falou com esse backend antigo e:

- leu a falta de `weeklyTrainingMinutes`/`totalWorkouts`/... como `UNAVAILABLE` — "Ainda não
  disponível" — para um recurso que o servidor **não conhecia**;
- mandou `PATCH { shareWorkoutName: true }` e tomou `400 INVALID_PROGRESS_SETTINGS` (cinco nos
  request logs de 2026-09-24T21:00Z, todos em `spark-backend-00022-jom`).

O cálculo da T19.H3 estava certo: com o backend dela, `level AVAILABLE` implica sessão sincronizada,
que implica `totalWorkouts AVAILABLE`. O defeito era o **contrato** não conseguir dizer "não conheço
este recurso", e a tela não conseguir dizer **por que** um campo faltava.

### H5.1 Versão explícita

`GET`/`PATCH /v1/social/me/progress-sharing` respondem `contractVersion` (`2` hoje;
`PROGRESS_SHARING_CONTRACT_VERSION` nos dois lados).

| Versão | O que o servidor conhece |
| --- | --- |
| ausente → **v1** | T17.2 + T19.2: os quatro interruptores de progresso geral |
| **2** | T19.H3: estatísticas de treino e detalhes dos check-ins; desde a T19.H5, `availabilityReasons` |

O app lê **ausência como v1**, mesmo que o servidor por acaso conheça os campos da T19.H3 — supor
que conhece foi o defeito. Com um servidor abaixo da versão de um grupo
(`ProgressSharingGroup.sinceContractVersion`):

- os interruptores do grupo **não aparecem**, e a ViewModel também não os envia;
- a tela mostra **um** aviso para os grupos que faltam: "Este recurso ainda não está disponível no
  servidor atual" — nunca "Ainda não disponível" campo a campo;
- no domínio, esses campos são `UNSUPPORTED` com motivo `LEGACY_BACKEND` (derivado pelo app; um
  motivo `LEGACY_BACKEND` vindo do servidor é lido como `UNKNOWN`).

A versão sobe **só** quando o contrato ganha recurso que o app precisa saber se existe antes de
oferecer. Campo opcional novo numa resposta não sobe: todo APK publicado lê com
`ignoreUnknownKeys` (verificado de `6d29dab` a `abbfa28`).

### H5.2 Motivo de cada indisponibilidade

`availabilityReasons` é um mapa **ao lado** de `availability`, com as mesmas chaves, só para campos
`UNAVAILABLE`. `availability` continua um mapa de strings — trocar para `{ status, reason }`
quebraria a leitura inteira nos aparelhos antigos. No app, estado e motivo se juntam em
`SocialFieldAvailabilityDetail`.

| Motivo | Produtor (`social-progress.source.ts`) | Campos | O que resolve |
| --- | --- | --- | --- |
| `NO_SYNCED_WORKOUTS` | nenhuma `WORKOUT_SESSION` `COMPLETED` sem tombstone | todos os oito; conquistas só quando a lista sai vazia | "Sincronizar dados" |
| `WEEK_TIME_ZONE_MISSING` | `weekTimeZone` ausente ou inválido | todos, exceto treinos totais | o app declara ao abrir a tela |
| `CONSISTENCY_PARAMETERS_MISSING` | sem meta semanal/início do acompanhamento | nível, sequência | o app declara ao abrir a tela |
| `SOURCE_LIMIT_REACHED` | mais de `MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS` sessões na semana | minutos, séries, volume | a próxima semana |

Precedência, quando mais de um vale: **fuso → sessão → parâmetros → teto**. O fuso vem primeiro
porque é o primeiro elo do caminho e o app o corrige sozinho.

Investigados e **não** criados, por não corresponderem a estado real: `NO_RECONSTRUCTABLE_DATA` (com
uma sessão, `first_workout` sempre é obtida; com uma medição, `first_measurement` — lista vazia só
existe sem sync, e um teste prende essa premissa ao catálogo) e `TEMPORARILY_UNAVAILABLE` (nenhuma
falha parcial da projeção é transformada em disponibilidade; uma falha de banco é `5xx` da leitura
inteira). `NEEDS_SYNC` é a **ação** que o app oferece para `NO_SYNCED_WORKOUTS`, não um motivo do
servidor: só o aparelho sabe se tem treino a enviar — e, depois de um "Sincronizar dados" bem-
sucedido, a frase muda para "Nenhum treino concluído chegou ao servidor ainda".

A montagem sem fonte de fatos (dublê de agregados) responde as estatísticas da semana como
`UNSUPPORTED`: aquela instância não tem de onde ler séries. Produção sempre injeta a fonte.

### H5.3 Sincronizar dados ≠ ↻

| Ação | O que faz | O que nunca faz |
| --- | --- | --- |
| **↻** (`refreshProgressSharing`) | relê o servidor; declara fuso/parâmetros que ele não conhece | enviar treino, rodar ciclo de sync |
| **Sincronizar dados** (`syncData`) | roda o ciclo da T16 (`SyncCoordinator.runOnce`) e, se ele rodou, relê | rodar sozinho, rodar ao abrir a tela |

- **Não existe sync social.** A ViewModel recebe `suspend () -> SocialSyncResult`, montada em
  `MainViewModelFactory` por `runSocialAssistedSync` (`presentation/SocialAssistedSync.kt`) — o
  pacote social continua sem conhecer Outbox, cursor ou desfecho da T16
  (`SocialBoundaryInspectionTest`). Dois toques produzem um ciclo; um ciclo que já estava rodando
  (app voltando ao primeiro plano) é **esperado** por até 60 s, não duplicado.
- **Cada desfecho tem frase:** sincronizou e o dado apareceu; sincronizou e o servidor continua sem
  treino; itens que precisam de atenção (conflito, cursor expirado, mudança que o app não lê); este
  aparelho não sincroniza; outra conta; offline; falha; já em andamento. Nunca um spinner que volta
  ao mesmo estado sem explicação.
- **Durante o ciclo os interruptores esperam**, e cada leitura/escrita da tela pega uma geração: a
  resposta mais velha — um "↻" que saiu antes de um `PATCH` ou do sync — não escreve.
- **Offline**, o sync não relê, não mexe em preferência e a Outbox fica intacta; o interruptor
  continua não fingindo salvar (§10).

### H5.4 As regras de domínio, reafirmadas com teste

- **Treinos totais** ficam `AVAILABLE` com uma sessão sincronizada, com ou sem fuso e parâmetros.
- **Minutos/séries/volume** ficam `AVAILABLE` com sessão (em qualquer semana) e fuso — inclusive
  `AVAILABLE(0)`.
- **Tempo treinado** é `floor(Σ max(fim − início, 0) / 60)`: sessão sem fim ou com fim antes do
  início soma zero, nunca negativo.
- **Séries** não contam aquecimento nem série não concluída; `targetSets` do template não entra.
- **Volume** é `Σ peso × reps`; peso `0` não é erro nem peso corporal estimado.
- **O teto de 100 sessões por semana fica**, justificado: a soma lê séries e cargas de cada sessão
  pela definição única em `social-training-metrics.ts` (compartilhada com o resumo do check-in).
  Agregar em SQL sem teto exigiria uma segunda definição de série e volume. 100 é mais de catorze
  treinos por dia; acima disso, o motivo é explícito.

### H5.5 Detalhes do check-in e "Cargas"

Os sete interruptores de detalhe são **preferência de privacidade**, sem disponibilidade global:
num servidor v2 eles são configuráveis mesmo sem check-in publicado, e cada `PATCH` isolado responde
`200` (idempotente — `true` três vezes, uma linha). "Cargas utilizadas" ligada sem Exercícios **e**
Séries e repetições não publica nada (a carga mora dentro da série); a tela diz "Sem efeito agora"
nesse estado. O check-in só é publicado com a sessão já sincronizada (T17.8, `SESSION_NOT_FOUND`),
então "check-in sem sessão no servidor" só existe quando a sessão foi apagada depois — e aí o
resumo some, sem nada fabricado.

### H5.6 Rollout contract-first

```text
backend (contrato novo) → migration → smoke (com conta de teste: contrato vN) → Android
```

Um APK que depende de uma versão de contrato só vai para o Play depois que a produção a declara —
`ops/gcp/smoke-cloud-run.sh` com `SPARK_SMOKE_FIREBASE_ID_TOKEN` confere `contractVersion ≥ 2`, os
quinze interruptores, as oito disponibilidades e `availabilityReasons`, sem imprimir o corpo. Se o
app chegar antes, ele degrada: mostra o aviso de servidor legado em vez de interruptores recusados.

## 9. Cache — não existe, e é por isso que revogar funciona

Não há cache de perfil no servidor e não há cache persistente no aparelho. Não existe
`FriendProgressEntity` no Room, e o progresso de terceiros nunca é gravado.

Isso não é economia de trabalho: é o que faz "desliguei o compartilhamento" ter efeito na próxima
leitura de qualquer amigo, sem invalidação a coordenar. Um cache persistente continuaria mostrando
o que a outra pessoa desligou, e a T17.2 preferiu não ter o problema a resolvê-lo.

No aparelho, o perfil lido vive em memória, dentro do ViewModel, e é descartado na troca de conta —
**antes** de qualquer requisição da conta nova sair. A resposta de uma requisição iniciada pela
conta anterior é descartada se a conta mudou no voo (a lição da T16.7.1).

## 10. Offline

Sem internet, alterar o compartilhamento **não acontece**: não vai para a Outbox, não fica pendente
e não é reenviado. O interruptor não se move e a tela diz que nada foi alterado.

Não há atualização otimista: o interruptor só muda depois que o servidor confirmou. Numa tela de
privacidade, um "salvo" que não salvou é o pior desfecho possível.

## 11. Perfil ≠ pontuação de desafio

> **`SocialProgressProjection` é para exibição de perfil, e não será autoridade de pontuação de
> Challenge.**

A T17.3 precisará de dados canônicos próprios, com garantias que um perfil não precisa ter:

| | Perfil (T17.2) | Desafio (T17.3) |
| --- | --- | --- |
| Autorização | amizade + preferência do dono | participação explícita no desafio |
| Consentimento | quatro interruptores de perfil | autorização própria, por desafio |
| Frescor | o que o servidor sabe agora | precisa de janela e corte definidos |
| Ausência | campo some | precisa de política explícita para o placar |
| Manipulação | nenhuma vantagem em mentir | há vantagem: o placar premia |

Os quatro interruptores desta tarefa (`shareLevel`, `shareConsistencyStreak`,
`shareWeeklyWorkoutCount`, `shareHighlightedAchievements`) **não** devem ser reutilizados para
decidir progresso de desafio. Participar de um desafio é uma autorização diferente, dada em outro
momento, para outra finalidade.

## 12. O que a T17.2 deliberadamente não fez

- **não implementou a seleção de conquistas em destaque.** Ela exige que o servidor valide "esta
  conquista foi conquistada" (§26), e ele não consegue — as duas saídas seriam confiar no cliente
  ou recriar o avaliador. A tabela `social_profile_achievement_highlights` **não** foi criada;
- **não materializou cache de projeção** (`social_progress_projection`). Na escala do Spark a
  projeção sai de um `COUNT(*)` sobre índice; materializar agora criaria um valor derivado que
  envelhece sem ninguém perceber;
- **não criou atividade recente, feed, ranking, desafio, presença ou perfil em lote**;
- **não tocou** em `socialId`, `friendCode`, amizades, pedidos ou em qualquer tabela da T16;
- **não deu XP, conquista, missão ou evento de gamificação** por ver perfil, alterar privacidade ou
  qualquer outra ação desta tarefa.

## 13. A regra da T17.0 que a T17.2 mudou, e por quê

A T17.0 declarou `NO_CROSS_DOMAIN_READ` — "a projeção não lê `sync_entities`" — quando **nenhuma**
projeção existia. Era a regra certa naquele dia: sem ela, a primeira projeção nasceria como um
endpoint social fazendo `SELECT payload`.

A T17.2 escreveu a primeira projeção e descobriu que a regra, na forma absoluta, proibia também o
desenho correto. Ela foi então dividida em duas mais precisas, com uma terceira acrescentada:

```text
T17.0                       T17.2
NO_CROSS_DOMAIN_READ   ──▶  NO_BACKUP_READ     backup nunca é fonte de projeção
                            AGGREGATE_ONLY     só escalar sai do adapter; nunca payload
                            SINGLE_AUTHORITY   a projeção não recalcula regra de domínio
```

O conjunto vivo está em `social.projection.ts` (`SOCIAL_PROJECTION_RULES`), com teste que fixa a
lista. O que continua valendo por construção: o `SocialModule` **não importa** `SyncModule`,
`BackupModule` nem `AiModule`, e há teste sobre os imports; `social-progress.source.ts` é o
**único** arquivo do módulo que menciona `sync_entities`, e há teste que verifica que ele só
seleciona `COUNT(*)` e `1` — nenhum `SELECT payload`, nenhum `JSON.parse`, nenhuma linha de treino
materializada.
