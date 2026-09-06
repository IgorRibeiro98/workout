# Matriz de dados do Spark — classificação para sincronização

- **Tarefa:** T16.0
- **Base:** código real em `app/src/main/java/com/example/data/` (Room `version = 30`) e
  `SettingsManager` (DataStore), lidos em 2026-09-06. Nenhum nome aqui foi inferido de documentação.
- **Status:** documento de arquitetura. **Nada nesta matriz está implementado.** Nenhum `syncId`
  foi adicionado a nenhuma entidade Room pela T16.0.

## Como ler

- **Autoridade atual** — quem decide o valor hoje.
- **Precisa de syncId** — se a entidade precisa de identidade global estável para convergir entre
  dispositivos (ver [`identity-contract.md`](./identity-contract.md)).
- **Estratégia** — `sync` | `derived` | `local` | `server-only`.
- **Fase** — em qual tarefa da T16 o item passa a existir remotamente.

---

## Grupo A — Dados pessoais canônicos sincronizáveis

Criados pelo usuário, insubstituíveis se perdidos, e com significado igual em qualquer dispositivo.

| Domínio | Tabela Room | Autoridade atual | Identificador atual | syncId | Estratégia | Conflito esperado | Delete | Fase |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Programa de treino | `workout_programs` | Room | `id` autoincrement (+ `externalId` para conteúdo importado) | sim | sync | última revisão vence, com `revision` | tombstone | T16.3 / T16.6 |
| Template de treino | `workout_templates` | Room | `id` autoincrement | sim | sync | última revisão vence, com `revision` | tombstone | T16.3 / T16.6 |
| Exercício do template | `workout_template_exercises` | Room | `id` autoincrement | sim | sync (filho do template) | resolvido junto com o template pai | tombstone | T16.3 / T16.6 |
| Sessão de treino | `workout_sessions` | Room | `id` autoincrement | sim | sync | **imutável quando `COMPLETED`** — divergência é conflito de integridade | tombstone | T16.3 / T16.6 |
| Exercício da sessão | `exercise_sessions` | Room | `id` autoincrement | sim | sync (filho da sessão) | herda a imutabilidade da sessão | tombstone | T16.3 / T16.6 |
| Série executada | `set_logs` | Room | `id` autoincrement | sim | sync (filho de `exercise_sessions`) | herda a imutabilidade da sessão | tombstone | T16.3 / T16.6 |
| Exercício criado pelo usuário | `exercises` com `isUserCreated = 1` | Room | `id` autoincrement | sim | sync | última revisão vence | tombstone | T16.3 / T16.6 |
| Customização de exercício | `exercise_user_overrides` | Room | `exerciseId` (PK = FK) | sim | sync | última revisão vence | tombstone | T16.3 / T16.6 |
| Medidas corporais | `body_measurements` | Room | `id` autoincrement | sim | sync | mesma data + conteúdo divergente = conflito | tombstone | T16.3 / T16.6 |
| Check-in na academia | `check_ins` | Room | `id` autoincrement | sim | sync | última revisão vence | tombstone | T16.3 / T16.6 |
| Alternativas de exercício definidas pelo usuário | `exercise_alternatives` | Room | `id` autoincrement | sim | sync | última revisão vence | tombstone | T16.6 |
| Meta semanal (histórico) | `weekly_goal_history` | Room | `effectiveFromWeekStartEpochDay` (PK natural, estável) | não — a PK já é global | sync | mesma semana + meta divergente = última revisão vence | tombstone | T16.3 |

**Nota sobre `workout_sessions`:** este é o grupo mais sensível da matriz. Uma sessão `COMPLETED`
registra o que de fato aconteceu. Sincronização não pode reescrevê-la — ver
[`sync-protocol.md`](./sync-protocol.md#histórico-concluído).

---

## Grupo B — Dados derivados / recalculáveis

Reconstruíveis a partir do Grupo A pelas regras determinísticas que já existem no app
(`XpReconciler`, `AchievementReconciler`, `ConsistencyMilestoneEvaluator`, `XpCalculatorService`).

Sincronizar o **resultado** destes dados criaria uma segunda fonte de verdade: o servidor passaria
a ter uma opinião sobre XP que poderia divergir da regra local. A decisão é sincronizar o **fato**
(Grupo A) e deixar cada dispositivo recalcular.

| Domínio | Tabela Room | Autoridade atual | Derivado de | syncId | Estratégia | Fase |
| --- | --- | --- | --- | --- | --- | --- |
| Evento de gamificação | `gamification_events` | Room (`dedupeKey` único) | sessões concluídas, PRs, consistência | não | derived — recalculável; `dedupeKey` já garante idempotência local | — |
| Transação de XP | `xp_transactions` | Room (`eventId` único) | `gamification_events` + `XpRewardPolicy` | não | derived | — |
| Conquista desbloqueada | `achievement_unlocks` | Room | eventos + definições versionadas | não | derived | — |
| Recorde pessoal (PR) | `personal_records` | Room | `set_logs` | não | derived | — |
| Nível / XP total / streak | não persistido (calculado em `UserProgress`) | Domínio | `xp_transactions` | não | derived | — |

**Consequência aceita:** ao restaurar em um aparelho novo (T16.5), gamificação é **recalculada** a
partir do histórico restaurado, não copiada. É mais lento e é o comportamento correto: o XP passa a
ser sempre consistente com a política vigente, e não um número herdado que ninguém consegue auditar.

**Revisão prevista para a T16.4:** se a recomputação em massa se mostrar cara no restore, a saída é
um *snapshot* de conveniência explicitamente marcado como cache — nunca promover estes dados a
autoridade remota.

---

## Grupo C — Dados locais / específicos do dispositivo

Não sincronizam. Alguns porque não têm significado fora do aparelho, outros porque sincronizá-los
seria ativamente ruim (o timer de descanso de um aparelho não deve tocar em outro).

| Domínio | Armazenamento | Autoridade | Por que não sincroniza |
| --- | --- | --- | --- |
| Catálogo canônico de exercícios | `exercises` com `canonicalId` | Manifesto versionado em `app/src/main/assets/catalog` | Conteúdo do app, igual em todo aparelho. Distribuído pelo APK/manifesto, não pelo sync. Ver [`identity-contract.md`](./identity-contract.md#exercícios-canônicos). |
| Enriquecimento premium do exercício | `exercise_education`, `exercise_media`, `exercise_progression`, `exercise_safety`, `exercise_substitutions`, `exercise_ai_context`, `exercise_biomechanics`, `exercise_execution` | Manifesto premium versionado | Idem — conteúdo, não dado do usuário. |
| Estado do timer de descanso | DataStore (`REST_TIMER_DEADLINE`, `REST_TIMER_WORKOUT_SESSION_ID`, `REST_TIMER_EXERCISE_SESSION_ID`, `REST_TIMER_TYPE`) | `SettingsManager` | Estado transitório de execução **neste** aparelho. Sincronizar dispararia um timer em outro dispositivo. |
| Preferências de aparelho | DataStore (`DARK_THEME`, `KEEP_SCREEN_ON`, `HAPTIC_ENABLED`, `SOUND_ENABLED`, `PRE_ALERT_ENABLED`, `SHOW_GIFS`, `TIMER_NOTIFICATION_ENABLED`) | `SettingsManager` | Dependem da tela, do hardware e do contexto de uso. |
| Cache e estado de importação | DataStore (`INSTALLED_CATALOG_CONTENT_VERSION`, `INSTALLED_PREMIUM_CONTENT_VERSION`, `LAST_MEDIA_SYNC_AT`, `MEDIA_SYNC_CONTENT_VERSION`, `LAST_SYNC_STATUS`) | `SettingsManager` | Descreve o estado local de instalação de conteúdo. |
| Chave de API do ExerciseDB | DataStore (`EXERCISE_DB_V2_API_KEY`) | `SettingsManager` | **Credencial.** Não sai do aparelho, não vai para backup, não vai para o servidor. |
| Token de depuração do App Check | não persistido — provedor escolhido por variante em `AiCoachAppCheck` (`src/debug`) | build variant | Nunca sai de debug, nunca entra em release, nunca é versionado. |
| Preferências de treino candidatas a sync futuro | DataStore (`WEEKLY_GOAL`, `USE_KG`, `DEFAULT_REST_SECONDS`, `DEFAULT_EXERCISE_REST_SECONDS`, `RIR_RPE_ENABLED`, `AUTO_REST_TIMER_ON_SET`) | `SettingsManager` | Hoje local. **Candidatas** a sync em T16.4 (backup), porque descrevem a preferência do atleta e não do aparelho. Decisão adiada de propósito. |

---

## Grupo D — Dados futuros exclusivos do servidor

Não existem no Android e não devem existir. São estado que só faz sentido com identidade
autenticada e visão entre usuários.

| Domínio | Estratégia | Fase |
| --- | --- | --- |
| Conta / vínculo `uid` ↔ dados | server-only | T16.1 |
| Registro de dispositivos (`deviceId`) | server-only | T16.3 |
| Sequência de mudanças e cursor de sync | server-only | T16.6 |
| Registro de `clientMutationId` para idempotência | server-only | T16.3 |
| Tombstones e retenção | server-only | T16.7 |
| Rate limit e controle de uso da IA | server-only | T16.2 |
| Amizades, convites, desafios | server-only | T17 |

Nenhum destes está implementado. O schema atual do backend tem exatamente uma tabela
(`server_metadata`, estado técnico do servidor) e nenhuma tabela de domínio do Spark.

---

## Regra derivada desta matriz

O schema remoto **não é** um espelho do Room:

- o Grupo B não vira tabela remota;
- o Grupo C não sai do aparelho;
- o Grupo A vira schema remoto **conforme cada fase precisar**, com forma própria (ownership,
  `syncId`, `revision`, tombstone), e não com as colunas que o Room usa para renderizar tela.
