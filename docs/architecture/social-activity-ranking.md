# T17.4 — Atividade dos Amigos e Rankings Contextuais

- **Módulo:** Social Spark (`backend/src/modules/social/` e `app/src/main/java/com/example/presentation/friends/`)
- **Base:** T17.0 (Identidade e Privacidade), T17.1 (Grafo de Amizades), T17.2 (Perfil Enriquecido e Projeção Canônica), T17.3 (Desafios Entre Amigos), T16.8 (Privacidade e Segurança)
- **Status:** Implementado e Verificado

---

## 1. Princípios e Limites Não Negociáveis

1. **Spark NÃO é uma rede social generalista:**
   - Proibido qualquer mecanismo de curtidas, reações, comentários, aplausos ou emojis sociais.
   - Proibido feed de timeline pública, busca aberta ou perfis indexáveis.
   - Proibido ranking global, ranking de volume total, ranking de XP ou leaderboards perpétuos ("todos os tempos").
   - Proibida exposição de histórico detalhado, exercícios específicos, séries, cargas, repetições, notas ou timestamps exatos de início e término dos treinos de amigos.

2. **Projeções de Leitura Estritamente Server-Authoritative:**
   - As atividades e rankings são computados em tempo de leitura a partir de fontes canônicas de treino concluído (`WorkoutSessionEntity` sincronizada com status `COMPLETED`).
   - Nenhuma tabela secundária de placar, snapshot persistido ou feed precalculado foi introduzida no banco relacional.

3. **Zero Vazamento no Cliente (Android):**
   - O feed e o ranking são mantidos exclusivamente em memória (`SocialActivityUiState` nos ViewModels).
   - Nenhuma linha é gravada no Room, cache persistido ou na Outbox de sincronização.
   - Descarte imediato e sanitização de dados no chaveamento ou logout de contas.

4. **Consentimento Ortogonal e Reciprocidade Estrita:**
   - Os interruptores de privacidade são completamente independentes:
     - `friendRequestsEnabled`: aceitar convites de amizade.
     - `activitySharingEnabled`: permitir que amigos diretos vejam os dias em que treinou nos últimos 14 dias.
     - `friendRankingParticipationEnabled`: participar do ranking semanal entre amigos.
   - **Reciprocidade obrigatória para o ranking:** Um usuário só pode visualizar o ranking semanal se tiver ativado expressamente sua própria participação (`friendRankingParticipationEnabled = true`). Caso tente consultar o ranking estando desativado, o servidor rejeita com HTTP 403 `RANKING_NOT_ENABLED`.

---

## 2. Contratos e Endpoints

### 2.1 Feed de Atividade dos Amigos (`GET /v1/social/activity`)

- **Autenticação:** Obrigatória via Firebase ID Token (`BearerAuthGuard`).
- **Escopo:** Retorna dias de treino dos amigos mútuos ativos nos últimos 14 dias civis (`daysAgo`: 0..13).
- **Requisitos de Privacidade:**
  - O amigo precisa ter `activitySharingEnabled = 1` e fuso horário IANA válido configurado (`activityTimeZoneId`).
- **Deduplicação e Ordenação:**
  - Máximo de 1 item por amigo por dia civil (`TRAINING_DAY`).
  - Ordenação estável: `daysAgo ASC`, desempate por `displayName ASC`, e por fim `socialId ASC`.
  - Limite rígido de 30 itens na resposta.

```json
{
  "items": [
    {
      "socialId": "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d",
      "displayName": "Carlos",
      "daysAgo": 0
    }
  ]
}
```

### 2.2 Ranking Contextual Semanal (`GET /v1/social/rankings/last-7-days`)

- **Autenticação:** Obrigatória via Firebase ID Token (`BearerAuthGuard`).
- **Janela:** Exatamente os últimos 7 dias móveis baseados no `Clock` do servidor (`[now - 7 * DAY_MS, now]`).
- **Métrica:** Quantidade de sessões de treino concluídas (`WORKOUTS_COMPLETED_LAST_7_DAYS`).
- **Participantes:**
  - O visualizador (viewer) se ativo e com participação habilitada.
  - Amigos mútuos ativos que também habilitaram `friendRankingParticipationEnabled = 1`.
- **Regras de Posição (Competition Ranking):**
  - Empates recebem a mesma posição ordinal, com salto para o competidor seguinte (ex: 1º, 1º, 3º).
  - Critério determinístico de ordenação: `score DESC`, seguido por `displayName ASC`, e `socialId ASC`.
  - Limite rígido de 50 participantes.

```json
{
  "metric": "WORKOUTS_COMPLETED_LAST_7_DAYS",
  "participantCount": 2,
  "entries": [
    {
      "socialId": "8f14e45f-ceea-467a-a1c2-0f0e0a0b0c0d",
      "displayName": "Carlos",
      "score": 4,
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

---

## 3. Segurança e Performance

- **Prevenção de N+1:** Implementado `SyncedCanonicalTrainingSource` com consultas batch parametrizadas (`IN (?, ?, ...)`).
- **Sem mutações cegas:** Leitura direta de `sync_entities` filtrada por `entity_type = 'workout_session'` e `deleted_at IS NULL`.
- **Fuso Horário Canônico:** A conversão de timestamps de treino para dias civis utiliza exclusivamente o fuso do atleta dono do treino (`Intl.DateTimeFormat` com IANA timezone).
