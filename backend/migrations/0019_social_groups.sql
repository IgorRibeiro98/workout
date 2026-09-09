-- T17.11 — Squads privados e feed de grupo (§117–§120)
--
-- Aditiva e não destrutiva (§117): quatro tabelas novas, uma coluna nova de preferência de
-- notificação, e **nenhuma** alteração no que já existe. Um banco da T17.10 sobe até aqui sem
-- perder linha nenhuma, e o Social continua funcionando inteiro para quem nunca criar um Squad.
--
-- ## O que um Squad é, no banco
--
-- ```text
-- social_groups                  ← identidade do grupo (UUID público, dono, nome, status)
--   ├── social_group_memberships ← quem está dentro, e com qual papel
--   ├── social_group_invitations ← quem foi chamado, e em que estado
--   └── social_group_checkin_shares ← quais check-ins **já publicados** foram trazidos para cá
-- ```
--
-- A quarta é a que define a fase: ela é uma **referência** a `social_workout_checkins`, e não uma
-- cópia. Não existe `social_group_posts`, não existe corpo de publicação aqui e não existe um
-- segundo Feed (§50/§170). O feed do Squad é a mesma publicação da T17.8/T17.9 lida por outra
-- audiência.
--
-- ## Nada de treino atravessa esta migration (§75)
--
-- Não há coluna de exercício, série, repetição, carga, duração, instante de treino nem
-- `session_sync_id` em nenhuma das quatro tabelas. O que um Squad alcança de um treino é
-- exatamente o que o Feed de amigos já alcançava — e por exatamente o mesmo caminho.

-- -------------------------------------------------------------------------------------------------
-- 1. O Squad (§6/§7/§8/§14)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_groups (
    -- §7 — UUID gerado pelo servidor, e ele **é** o identificador público. Não existe um `id`
    -- sequencial interno ao lado de um `public_id`: um inteiro que nunca sai do servidor seria uma
    -- segunda identidade para manter, e a primeira vez que alguém o expusesse por engano
    -- entregaria a ordem de criação e a contagem de Squads do produto inteiro. O resto do domínio
    -- social (`social_workout_checkins`, `workout_shares`, `challenges`) já é assim.
    id           TEXT    PRIMARY KEY,

    -- O dono **atual**. Ele muda na transferência de posse (§40), e é sempre alguém que também
    -- tem uma linha em `social_group_memberships` com `role = 'OWNER'` (§15) — a coluna aqui é a
    -- resposta rápida para "de quem é este Squad", e a membership é a autoridade de participação.
    owner_uid    TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §8 — 3..40 code points, texto puro. O `CHECK` conta unidades UTF-16 e por isso é apenas o
    -- piso: quem conta code points, normaliza NFC e recusa caractere de controle é o validador,
    -- porque o SQLite não sabe fazer nenhuma das três coisas. O `CHECK` existe para que uma
    -- escrita que escapasse do validador ainda encontrasse uma parede.
    name         TEXT    NOT NULL CHECK (length(name) >= 3 AND length(name) <= 80),

    -- §46/§48 — exclusão é soft: a linha continua e o Squad para de responder. É ela que faz o
    -- `(group_id, checkin_id)` continuar significando alguma coisa depois da exclusão, e é ela
    -- que permite a um `DELETE` repetido convergir em vez de virar `404`.
    status       TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),

    created_at   INTEGER NOT NULL,
    updated_at   INTEGER NOT NULL,
    deleted_at   INTEGER,

    -- §146 — idempotência da **intenção** do usuário. Sem ela, um toque duplo em "Criar Squad"
    -- produz dois Squads com o mesmo nome e o usuário fica com um fantasma que ele não pediu.
    -- Diferente do convite e do compartilhamento, aqui não existe chave natural: dois Squads
    -- chamados "Os Monstros" são legítimos.
    client_request_id TEXT
);

-- §18/§120 — "quantos Squads esta conta criou" e "os Squads desta conta" são a mesma varredura.
CREATE INDEX idx_social_groups_owner ON social_groups (owner_uid, status);

-- §146 — a idempotência é por conta: o `clientRequestId` de A não colide com o de B. Índice
-- parcial porque as linhas anteriores a esta migration não têm um (não existem, mas o desenho
-- precisa continuar válido se um dia alguém inserir por outro caminho).
CREATE UNIQUE INDEX idx_social_groups_client_request
    ON social_groups (owner_uid, client_request_id)
    WHERE client_request_id IS NOT NULL;

-- -------------------------------------------------------------------------------------------------
-- 2. Membership (§12/§13/§14/§15/§37/§118)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_group_memberships (
    -- §37 — UUID próprio, e não o `member_uid`, porque a administração do Squad precisa de um
    -- identificador que possa circular **dentro do contexto autorizado do grupo** sem carregar
    -- identidade. É ele que permite ao dono remover um participante bloqueado (§36) sem que a
    -- tela jamais receba o `socialId` daquela pessoa.
    id          TEXT    PRIMARY KEY,

    group_id    TEXT    NOT NULL REFERENCES social_groups (id) ON DELETE CASCADE,
    member_uid  TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §13 — dois papéis, e só dois. `ADMIN`/`MODERATOR` ficam fora desta fase porque cada um deles
    -- exigiria decidir o que pode moderar, e moderação de conteúdo é justamente o que §129 mantém
    -- fora: quem tem problema com uma publicação usa Bloqueio e Denúncia, que já existem.
    role        TEXT    NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),

    joined_at   INTEGER NOT NULL,

    -- §118 — uma participação por pessoa por Squad. Sair e voltar reescrevem a linha; nunca
    -- existem duas. Sem isso, dois aceites simultâneos do mesmo convite produziriam duas linhas e
    -- a contagem de membros passaria a mentir.
    UNIQUE (group_id, member_uid)
);

-- §14/§118/§150 — **exatamente um** `OWNER` enquanto o Squad existe, e a garantia é do banco.
--
-- Um índice único parcial, e não uma verificação no serviço: a transferência de posse é duas
-- escritas (o antigo vira `MEMBER`, o alvo vira `OWNER`), e uma transação que falhasse no meio —
-- ou duas transferências simultâneas — poderia deixar zero ou dois donos. Nenhum dos dois estados
-- se corrige sozinho, e os dois são visíveis para outras pessoas. Aqui o segundo `OWNER` é
-- impossível, e a transação inteira falha em vez de gravar o estado inválido.
CREATE UNIQUE INDEX idx_social_group_single_owner
    ON social_group_memberships (group_id)
    WHERE role = 'OWNER';

-- §120 — "os Squads de que esta pessoa participa" é a consulta que a tela de lista faz, e o
-- caminho de exclusão de conta e de desativação do Social varre por esta mesma coluna.
CREATE INDEX idx_social_group_memberships_member ON social_group_memberships (member_uid);

-- -------------------------------------------------------------------------------------------------
-- 3. Convites (§19/§20/§21/§28/§118)
-- -------------------------------------------------------------------------------------------------
CREATE TABLE social_group_invitations (
    -- §20 — UUID do servidor. O cliente não propõe identidade em lugar nenhum deste domínio.
    id            TEXT    PRIMARY KEY,

    group_id      TEXT    NOT NULL REFERENCES social_groups (id) ON DELETE CASCADE,
    sender_uid    TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    recipient_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §21 — cinco estados. `EXPIRED` é **derivado** na leitura a partir de `expires_at`, como o
    -- convite de desafio da T17.3: gravar a expiração exigiria um processo passando por ali antes
    -- do toque, e o modo de falhar seria um convite aceito depois do prazo.
    status        TEXT    NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED')),

    created_at    INTEGER NOT NULL,
    expires_at    INTEGER NOT NULL,
    responded_at  INTEGER,

    -- §146 — idempotência do envio. O toque duplo em "Convidar" não gera dois convites.
    client_request_id TEXT,

    -- §26 — convidar a si mesmo é impossível, e a garantia é do banco além do serviço.
    CHECK (sender_uid <> recipient_uid)
);

-- §28/§118 — **um** convite pendente por (Squad, destinatário). Um convite recusado e um novo
-- convite depois são duas linhas legítimas; dois pendentes ao mesmo tempo, não — eles apareceriam
-- duas vezes na lista de quem recebeu, e aceitar um deixaria o outro pendente para sempre.
CREATE UNIQUE INDEX idx_social_group_invitations_pending
    ON social_group_invitations (group_id, recipient_uid)
    WHERE status = 'PENDING';

-- §146 — o mesmo `clientRequestId` no mesmo Squad é a mesma intenção.
CREATE UNIQUE INDEX idx_social_group_invitations_client_request
    ON social_group_invitations (group_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

-- §120 — "meus convites de Squad" é a consulta da tela de convites, e ela filtra por estado.
CREATE INDEX idx_social_group_invitations_recipient
    ON social_group_invitations (recipient_uid, status, created_at);

-- O caminho do Bloqueio (§105) e o da administração do Squad varrem pelo remetente.
CREATE INDEX idx_social_group_invitations_sender
    ON social_group_invitations (sender_uid, status);

-- -------------------------------------------------------------------------------------------------
-- 4. Check-ins trazidos para o Squad (§53/§54/§55)
-- -------------------------------------------------------------------------------------------------
--
-- Esta tabela é uma **aresta**, e não um post. Ela liga um `social_workout_checkins` que já existe
-- a um Squad, e some sem tocar no check-in (§47/§49). É isso que faz "apagar o Squad" e "sair do
-- Squad" nunca alcançarem uma publicação de ninguém.
CREATE TABLE social_group_checkin_shares (
    id         TEXT    PRIMARY KEY,

    group_id   TEXT    NOT NULL REFERENCES social_groups (id) ON DELETE CASCADE,

    -- `ON DELETE CASCADE` na direção certa: apagar o **check-in** apaga a aresta. O inverso não
    -- existe, e é o ponto da fase inteira.
    checkin_id TEXT    NOT NULL REFERENCES social_workout_checkins (id) ON DELETE CASCADE,

    -- §56 — só o autor compartilha o próprio check-in. A coluna existe para que "os
    -- compartilhamentos de A neste Squad" — a varredura de `leave`/`remove` (§62/§63) — não
    -- precise passar por `social_workout_checkins` a cada vez.
    author_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- §84/§85 — a ordenação do feed do Squad é **esta** data, e não a da publicação original:
    -- compartilhar hoje um check-in de ontem precisa aparecer no topo, ou o ato de compartilhar
    -- não teria efeito visível para quem já rolou a lista.
    created_at INTEGER NOT NULL,

    -- §55/§118 — um check-in entra uma vez em cada Squad. É esta `UNIQUE` que torna o
    -- compartilhamento idempotente (§170) sem precisar de um `clientRequestId`: a chave natural é
    -- mais forte que um identificador de tentativa, porque ela vale também entre dispositivos.
    UNIQUE (group_id, checkin_id)
);

-- §84/§120 — a leitura do feed é "este Squad, do mais novo para o mais velho, com janela".
CREATE INDEX idx_social_group_shares_feed
    ON social_group_checkin_shares (group_id, created_at DESC);

-- §68/§120 — "em quantos Squads este check-in já está" (o teto de 5) e a limpeza por autor.
CREATE INDEX idx_social_group_shares_checkin ON social_group_checkin_shares (checkin_id);
CREATE INDEX idx_social_group_shares_author ON social_group_checkin_shares (author_uid, group_id);

-- -------------------------------------------------------------------------------------------------
-- 5. A única categoria de push desta fase (§90/§94/§95)
-- -------------------------------------------------------------------------------------------------
--
-- `DEFAULT 1` como todas as outras: a categoria nasce ligada **sob** o interruptor mestre
-- `push_enabled`, que continua nascendo desligado (T17.5). Quem nunca ligou push não passa a
-- receber nada por causa desta coluna.
--
-- Não existe categoria para "entrou", "saiu", "foi removido", "posse transferida", "check-in
-- compartilhado" nem "Squad excluído" (§95): nenhuma delas é um convite, e um Squad de 20 pessoas
-- que notificasse cada movimento viraria um chat com outro nome.
ALTER TABLE social_notification_preferences
    ADD COLUMN group_invitation_received INTEGER NOT NULL DEFAULT 1;

-- -------------------------------------------------------------------------------------------------
-- 6. O vocabulário de `social_notification_events.type` (§90)
-- -------------------------------------------------------------------------------------------------
--
-- O SQLite não sabe alterar um `CHECK`: acrescentar um valor exige recriar a tabela. É o mesmo
-- procedimento que a T17.7 usou para `WORKOUT_SHARE_RECEIVED`, e ele é **aditivo** — as linhas
-- existentes são copiadas uma a uma, e nenhum evento em voo é perdido.
--
-- ## Por que o `CHECK` continua existindo, em vez de virar texto livre
--
-- Porque ele é a segunda barreira sobre um vocabulário fechado. O `NotificationType` do TypeScript
-- é a primeira, e é a que o compilador cobre; o `CHECK` é a que pega o caminho que escapou do tipo
-- — um `type` montado por concatenação, uma migração de dados escrita à mão. Sem ele, um valor
-- desconhecido chegaria ao dispatcher e cairia no `default: UNKNOWN_TYPE`, que **suprime em
-- silêncio**: o push simplesmente não sairia, e ninguém saberia por quê.
--
-- A ordem importa: `social_notification_deliveries` referencia `social_notification_events`, então
-- as duas são recriadas juntas — recriar só a primeira deixaria a FK apontando para uma tabela que
-- deixou de existir.
--
-- O sufixo `_new` é o mesmo da 0014, e não é decoração: o teste estrutural da T17.10
-- (`social-migrations.spec.ts`) só aceita um `DROP TABLE` quando encontra o `INSERT INTO
-- <tabela>_new ... FROM <tabela>` antes dele e o `RENAME TO <tabela>` depois. É essa convenção que
-- distingue um rebuild de doze passos de uma migration destrutiva.

CREATE TABLE social_notification_events_new (
  id              TEXT PRIMARY KEY,
  recipient_uid   TEXT NOT NULL,
  type            TEXT NOT NULL CHECK (type IN (
                    'FRIEND_REQUEST_RECEIVED',
                    'FRIEND_REQUEST_ACCEPTED',
                    'CHALLENGE_INVITATION_RECEIVED',
                    'CHALLENGE_STARTING_SOON',
                    'CHALLENGE_ENDED',
                    'WORKOUT_SHARE_RECEIVED',
                    'GROUP_INVITATION_RECEIVED'
                  )),
  entity_id       TEXT NOT NULL,
  dedupe_key      TEXT NOT NULL UNIQUE,
  deliver_after   INTEGER NOT NULL,
  expires_at      INTEGER NOT NULL,
  status          TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                    'PENDING',
                    'COMPLETED',
                    'SUPPRESSED',
                    'EXPIRED',
                    'CANCELLED'
                  )),
  created_at      INTEGER NOT NULL,
  completed_at    INTEGER
);

CREATE TABLE social_notification_deliveries_new (
  event_id                TEXT NOT NULL REFERENCES social_notification_events_new(id) ON DELETE CASCADE,
  device_registration_id  TEXT NOT NULL REFERENCES social_push_devices(id) ON DELETE CASCADE,
  status                  TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN (
                            'PENDING',
                            'SENT',
                            'FAILED_PERMANENT',
                            'FAILED_TRANSIENT'
                          )),
  attempt_count           INTEGER NOT NULL DEFAULT 0,
  next_attempt_at         INTEGER,
  last_error_code         TEXT,
  sent_at                 INTEGER,
  PRIMARY KEY (event_id, device_registration_id)
);

INSERT INTO social_notification_events_new SELECT * FROM social_notification_events;
INSERT INTO social_notification_deliveries_new SELECT * FROM social_notification_deliveries;

DROP TABLE social_notification_deliveries;
DROP TABLE social_notification_events;

ALTER TABLE social_notification_events_new RENAME TO social_notification_events;
ALTER TABLE social_notification_deliveries_new RENAME TO social_notification_deliveries;

CREATE INDEX idx_social_notification_events_dispatch
  ON social_notification_events (status, deliver_after, expires_at);
CREATE INDEX idx_social_notification_events_recipient
  ON social_notification_events (recipient_uid, status);
CREATE INDEX idx_social_notification_deliveries_pending
  ON social_notification_deliveries (status, next_attempt_at);
