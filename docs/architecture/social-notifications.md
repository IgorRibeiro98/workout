# T17.5 — Notificações Sociais com Firebase Cloud Messaging

- **Módulo:** Social Spark (`backend/src/modules/social/` e `app/src/main/java/com/example/`)
- **Base:** T17.0 (Identidade Social e Privacidade), T17.1 (Grafo de Amizades), T17.2 (Perfil Enriquecido), T17.3 (Desafios), T17.4 (Atividade e Ranking), T16.8 (Segurança e Privacidade)
- **Status:** Implementado e Verificado

---

## 1. Princípios e Limites Não Negociáveis

1. **Push é sinal best-effort, nunca fonte da verdade:**
   - Notificações push servem exclusivamente como convite para o usuário abrir o aplicativo.
   - O estado canônico é sempre consultado e sincronizado a partir dos endpoints do backend Spark via HTTPS quando o aplicativo é aberto.
   - Falhas na entrega de push, expiração ou bloqueio de notificações no sistema operacional nunca deixam o aplicativo inconsistente ou dessincronizado.

2. **Payload FCM data-only e estritamente minimalista:**
   - Proibidas mensagens FCM do tipo display (`notification` payload). Todas as notificações utilizam exclusivamente mensagens `data-only`.
   - Proibida inclusão de dados pessoais (UID do Firebase, e-mail, foto, nome completo) ou de treino (exercícios, séries, repetições, cargas, pontuações de desafios, XP ou histórico).
   - O payload carrega somente os campos necessários para a resolução local segura:
     - `v`: versão do protocolo push (sempre `"1"`).
     - `eventId`: identificador único do evento para deduplicação no cliente.
     - `type`: tipo do evento de notificação (`NotificationType`).
     - `recipientSocialId`: `socialId` público do destinatário para verificação de isolamento de conta.
     - `entityId`: identificador da entidade vinculada (`requestId`, `challengeId`, etc.).
   - Textos legíveis e localizados (como títulos e descrições amigáveis) são sintetizados pelo próprio aplicativo Android a partir de strings locais (`strings.xml`), sem depender de texto externo ou não seguro no payload.

3. **Isolamento estrito entre contas:**
   - Tokens FCM estão estritamente atrelados ao usuário autenticado (`owner_uid`) no backend.
   - Se um dispositivo troca de usuário logado, o token é transferido atomicamente para a nova conta e desvinculado da conta anterior.
   - No cliente Android, ao receber um push, o `SparkFirebaseMessagingService` verifica se o `recipientSocialId` da mensagem confere com a conta social atualmente logada e ativa. Se não conferir (ou se o usuário estiver deslogado), o push é silenciosamente descartado.

4. **Desacoplamento transacional:**
   - Falhas no envio de notificações para a infraestrutura do FCM ou limitações de quota nunca revertem, abortam ou bloqueiam transações de negócios (ex.: envio de pedido de amizade, aceite de amizade, criação de desafio ou encerramento).
   - O enfileiramento de eventos utiliza o padrão **Transactional Outbox**, garantindo atomicidade com a mutação no banco de dados do backend (PostgreSQL).

5. **Privacidade e Governança dos Tokens:**
   - Tokens FCM são credenciais de infraestrutura efêmeras; nunca são expostos em endpoints de leitura pública ou privada, e nunca são logados em texto claro.
   - Ao realizar logout ou desativar o módulo social, os dispositivos vinculados são desregistrados e os tokens são invalidados.

---

## 2. Eventos Suportados

| Tipo de Evento (`type`) | Categoria | Quando é Gerado | Ação ao Tocar na Notificação |
| --- | --- | --- | --- |
| `FRIEND_REQUEST_RECEIVED` | `friendRequestReceived` | Outro usuário envia um pedido de amizade | Abre tela de Pedidos de Amizade (`Screen.FriendRequests`) |
| `FRIEND_REQUEST_ACCEPTED` | `friendRequestAccepted` | O destinatário aceita um pedido de amizade enviado pelo remetente | Abre lista de Amigos (`Screen.Friends`) |
| `CHALLENGE_INVITATION_RECEIVED` | `challengeInvitationReceived` | Usuário é convidado como participante de um desafio | Abre tela de Desafios (`Screen.Challenges`) |
| `CHALLENGE_STARTING_SOON` | `challengeStartingSoon` | Falta menos de 24 horas para o início da janela de um desafio aceito | Abre tela de Detalhe do Desafio (`Screen.ChallengeDetail`) |
| `CHALLENGE_ENDED` | `challengeEnded` | O desafio atingiu o término da sua janela temporal | Abre tela de Detalhe do Desafio (`Screen.ChallengeDetail`) |

---

## 3. Arquitetura do Backend

### 3.1 Esquema do Banco de Dados (`0012_social_notifications.sql`)

1. **`social_notification_preferences`**:
   - `owner_uid` (PK, FK para `social_profiles`)
   - `push_enabled` (INTEGER 0/1, default 0 - opt-in master desligado por padrão)
   - Switches granulares por tipo: `friend_request_received`, `friend_request_accepted`, `challenge_invitation_received`, `challenge_starting_soon`, `challenge_ended` (todos default 1)
   - `updated_at` (INTEGER)

2. **`social_push_devices`**:
   - `id` (TEXT PK)
   - `owner_uid` (TEXT, FK para `social_profiles`)
   - `device_id` (TEXT NOT NULL)
   - `platform` (TEXT NOT NULL, `'ANDROID'`)
   - `fcm_token` (TEXT NOT NULL UNIQUE)
   - `enabled` (INTEGER 0/1, default 1)
   - `created_at`, `updated_at`, `last_registered_at` (INTEGER)
   - Índice único por `(owner_uid, device_id)`

3. **`social_notification_events`** (Transactional Outbox):
   - `id` (TEXT PK)
   - `recipient_uid` (TEXT NOT NULL)
   - `type` (TEXT NOT NULL)
   - `entity_id` (TEXT NOT NULL)
   - `dedupe_key` (TEXT NOT NULL UNIQUE)
   - `deliver_after`, `expires_at`, `status`, `created_at`, `completed_at`

4. **`social_notification_deliveries`**:
   - `id` (TEXT PK)
   - `event_id` (TEXT NOT NULL, FK)
   - `device_registration_id` (TEXT NOT NULL, FK)
   - `status` (`'PENDING'`, `'SENT'`, `'FAILED_PERMANENT'`, `'FAILED_TRANSIENT'`)
   - `attempt_count`, `next_attempt_at`, `last_error_code`, `sent_at`

### 3.2 Despacho Transacional e Concorrência

- **Transactional Outbox**: Na mesma transação que cria a solicitação de amizade (`friendship_requests`) ou convite de desafio (`challenge_participants`), é inserido o registro em `social_notification_events`.
- **Varredura de Eventos Expirados (`markExpiredEvents`)**: No início de cada ciclo de despacho (`dispatchPendingNotifications`), eventos com `expires_at <= now` e status `PENDING` são marcados atomicamente como `EXPIRED`, evitando envio de notificações obsoletas.
- **Dispatcher com Mutex**: O `NotificationDispatcher` executa em lote (`PUSH_BATCH_SIZE`, padrão 50) e protege ciclos concorrentes com flag mutex em memória (`isProcessing`).
- **Verificação Canônica Prévia**: Antes de disparar mensagens para o gateway FCM, o despachante verifica:
  1. Preferências ativas do destinatário (`push_enabled` e switch específico da categoria).
  2. Aparelhos ativos associados à conta.
  3. Relevância canônica da entidade (por exemplo: se o pedido de amizade ainda está pendente, se o desafio não foi cancelado). Se a entidade não é mais relevante, o evento é marcado como `CANCELLED` ou `SUPPRESSED` sem envio.
- **Tratamento de Tokens Inválidos**: Erros permanentes (`messaging/registration-token-not-registered`, `messaging/invalid-registration-token`, `messaging/invalid-argument`) resultam na desativação ou remoção imediata do dispositivo em `social_push_devices`.

---

## 4. Arquitetura do Cliente Android

### 4.1 Ciclo de Vida do Token e Dispositivo

- **`PushAccountScope`**: Armazenamento seguro via Jetpack DataStore para rastrear:
  - Token FCM atual;
  - `socialId` associado ao registro ativo;
  - Carimbo de data/hora da última sincronização com o servidor.
- **`PushTokenRegistrationWorker`**: Tarefa em segundo plano com `WorkManager` para garantir envio idempotente do token ao backend com retry exponencial em caso de instabilidade de rede. Executa apenas se houver token válido, backend configurado, usuário autenticado e perfil social ativo.
- **`onNewToken`**: Capturado em `SparkFirebaseMessagingService`, disparando o agendamento no `PushRegistrationCoordinator`.
- **Opt-in Contextual de Notificações (Android 13+)**: A permissão de runtime `POST_NOTIFICATIONS` NUNCA é solicitada na abertura do app (`MainActivity.onCreate()`). Ela é requisitada sob demanda exclusivamente quando o usuário ativa explicitamente o switch principal de notificações na tela de Preferências de Notificação (`NotificationPreferencesScreen`). Se negada, o switch permanece desligado e uma mensagem de feedback amigável é exibida.
- **Sign Out / Troca de Conta / Desativação Social**: O `FirebaseAuthGateway` e `SocialViewModel` invocam o `PushRegistrationCoordinator` para desregistrar o dispositivo via `DELETE /v1/social/notifications/devices/:deviceId` e limpar o escopo local.

### 4.2 Recepção, Deduplicação e Exibição de Notificações

- **`SparkFirebaseMessagingService`**:
  - Recebe exclusivamente mensagens `RemoteMessage` com `data`.
  - Deserializa via `PushDataPayload.fromMap(remoteMessage.data)` com validação estrita (fail-closed: descarta se faltar `eventId`, versão != "1", tipo desconhecido ou campos em branco).
  - **Account Isolation**: Compara `payload.recipientSocialId` com o perfil social ativo em memória/DataStore. Se divergente, rejeita e descarta silenciosamente.
  - **Deduplicação LRU**: Mantém em memória um cache LRU (100 entradas) por `eventId`. Eventos repetidos em curto espaço de tempo são descartados.
  - **Canais de Notificação**:
    - `social_requests`: Importância Padrão (`IMPORTANCE_DEFAULT`) para pedidos e aceites de amizade.
    - `social_challenges`: Importância Padrão (`IMPORTANCE_DEFAULT`) para convites, avisos e término de desafios.
  - **Unicidade de PendingIntent**: O PendingIntent de cada notificação é gerado com `data = Uri.parse("spark://notification/$eventId")` e `requestCode = eventId.hashCode()`, garantindo que múltiplas notificações simultâneas mantenham seus respectivos extras sem sobreposição acidental pelo sistema operacional.
  - **`SocialNotificationNavigationResolver`**: Componente puro responsável por resolver a navegação de deep link a partir do intent (`EXTRA_DESTINATION` e `EXTRA_ENTITY_ID`), navegando para `Screen.FriendRequests`, `Screen.Friends`, `Screen.Challenges` ou `Screen.ChallengeDetail(challengeId)` e limpando os extras para evitar repetição da navegação em recomposições.

