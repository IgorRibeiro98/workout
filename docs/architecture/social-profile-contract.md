# Perfil social do Spark — projeção de progresso, privacidade e freshness

- **Tarefa:** T17.2 — perfil social e compartilhamento controlado de progresso.
- **Status (verificado em 2026-09-08): implementado, com uma métrica das quatro.** Migration
  `0009_social_progress_profile.sql`, `social-profile.*` e `social-progress.*` em
  `backend/src/modules/social/`, gateway e telas de Perfil de amigo / Compartilhar progresso no
  Android.
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

| Campo social | Autoridade canônica real | Regra | Chega ao servidor? | Estratégia |
| --- | --- | --- | --- | --- |
| `level` | `XpTransactionRepositoryImpl.calculateProgress` sobre `xp_transactions` (Room) | 500 XP no nível 1, `level × 500` depois | **não** | `UNSUPPORTED` |
| `consistencyStreak` | `ConsistencyCalculator.calculateProgress` sobre semanas, `weekly_goal_history` e `trackingStartedAt` | sequência **semanal** | **não** | `UNSUPPORTED` |
| `weeklyWorkoutCount` | contagem de sessões `COMPLETED` na semana canônica (`ConsistencyCalculator.weekStart`) | segunda a domingo, data local | **sim** — `sync_entities` / `WORKOUT_SESSION` | **projetado** |
| `highlightedAchievementIds` | `AchievementEvaluator` + `achievement_unlocks` (Room) | catálogo `AchievementCatalog` + eventos, PRs, sequência e medições | **não** | `UNSUPPORTED` |

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

### O que desbloquearia as três

Uma autoridade **remota** para elas — não um envio do cliente. O caminho natural é a gamificação
deixar de ser puramente derivada e passar a ter um agregado canônico sincronizado, com as mesmas
garantias do resto do T16 (identidade global, revision, imutabilidade onde couber). Isso é uma
decisão de arquitetura de gamificação, não de social, e continua registrada como pendência.

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
| `shareLevel` | `false` | nunca publica nesta versão (`UNSUPPORTED`) |
| `shareConsistencyStreak` | `false` | idem |
| `shareWeeklyWorkoutCount` | `false` | publica quando há dado e fuso |
| `shareHighlightedAchievements` | `false` | nunca publica nesta versão (`UNSUPPORTED`) |

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
AVAILABLE     "Disponível"                   nada a fazer
UNAVAILABLE   "Ainda não disponível"         sincronizar resolve
UNSUPPORTED   "Não disponível nesta versão"  sincronizar NÃO resolve
```

Colapsar os dois últimos faria a tela prometer que sincronizar publicaria o nível.

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
| `GET /v1/social/me/progress-sharing` | minhas preferências + a disponibilidade de cada campo |
| `PATCH /v1/social/me/progress-sharing` | altera preferências (**parcial**) e o fuso |

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
  "weekTimeZone": "America/Sao_Paulo"
}
```

Semântica de `PATCH`, e só ela: o que não veio no corpo **não muda**. `level`, `streak`,
`weeklyWorkoutCount`, `totalXp` e `earnedAchievementIds` são recusados **por nome**, com a
requisição inteira invalidada — ignorar em silêncio deixaria um cliente acreditando que enviar
progresso significa alguma coisa. `updatedAt` vem do relógio do servidor.

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
desligou tudo e para quem ligou sem ter dado.

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

## 8. O que nunca é social

Registrado explicitamente, porque a lista é o contrato:

```text
e-mail                    Firebase UID              friendCode (no perfil)
medidas corporais         peso                      percentual de gordura
cargas                    séries                    repetições, RPE, RIR
sessões individuais       horário de treino         duração de treino
nomes de treino           exercícios                notas
payload de sync           payload de backup         sync_entities como API
lastSyncAt                presença ("online agora")
```

Há teste que varre a resposta real do endpoint de amigo procurando cada um desses.

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
