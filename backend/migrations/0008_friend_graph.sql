-- T17.1 — o primeiro grafo social do Spark: pedidos de amizade e amizade bilateral.
--
-- A T17.0 criou identidade pública (`social_profiles`) e privacidade
-- (`social_privacy_settings`). Esta migration acrescenta a **relação** entre duas identidades, e
-- ela é server-authoritative pelo mesmo motivo que a identidade é: uma amizade é um fato sobre
-- **duas** contas, e nenhum dos dois aparelhos pode decidi-lo sozinho.
--
-- ```text
-- SocialProfile A ──FriendRequest──▶ SocialProfile B
--                                          │ accept
--                                          ▼
--                                   Friendship(A,B)   ← um par, não duas relações
-- ```
--
-- ## O que deliberadamente NÃO existe aqui
--
--   * **`sync_entities`, tombstone, `revision`, `baseRevision`, `clientMutationId`** — amizade não
--     é dado local-first e não converge por sync. Guardá-la no protocolo do treino daria a um
--     fato bilateral um mecanismo de resolução de conflito que o usuário teria de arbitrar;
--   * **`blocks`** — bloqueio é feature distinta (T17.6). "Remover amigo" não é bloquear, e criar
--     a tabela agora convidaria o primeiro leitor a fundir as duas coisas;
--   * **`friend_activity`, feed, XP compartilhado, streak compartilhado** — T17.2/T17.4. Nenhuma
--     coluna aqui carrega dado de treino: amizade **não** é chave para o domínio privado;
--   * **contagem desnormalizada de amigos/pedidos** em `social_profiles`. Um contador é uma
--     segunda verdade que diverge no primeiro erro; a contagem sai de `COUNT(*)` sobre um índice.
--
-- Nenhuma tabela da T16 (`sync_*`, `backup_*`, `ai_usage_daily`, `server_metadata`) e nenhuma
-- tabela da T17.0 é lida, alterada ou apagada por esta migration. Ela só cria.

-- Um pedido de amizade, do requisitante para o destinatário.
--
-- A linha **não é apagada** em transição de estado (§18): ela vira `ACCEPTED`, `REJECTED` ou
-- `CANCELLED` e fica. Apagar tornaria "aceitar duas vezes" indistinguível de "aceitar um pedido
-- que nunca existiu" — e é justamente a segunda tentativa, depois de uma resposta perdida, que
-- precisa de uma resposta correta em vez de um 404.
CREATE TABLE friend_requests (
    -- Identificador **público** do pedido: UUID v4 de CSPRNG, o mesmo gerador do `social_id`.
    --
    -- É ele que o Android usa para aceitar/recusar/cancelar. Um `rowid` sequencial no lugar
    -- vazaria de graça quantos pedidos existem no servidor e permitiria adivinhar o vizinho —
    -- e adivinhar não daria acesso (a autorização é por participante), mas um identificador que
    -- convida a tentar é um identificador mal escolhido.
    request_id      TEXT    NOT NULL PRIMARY KEY,

    -- Quem pediu e quem recebeu, por Firebase UID.
    --
    -- UID **interno**, e nunca em DTO: o servidor resolve `socialId → owner_uid` na entrada e
    -- `owner_uid → socialId` na saída. Guardar o `social_id` aqui duplicaria a identidade em duas
    -- tabelas, e a `FK` abaixo é o que garante que os dois lados do pedido são perfis que existem.
    requester_uid   TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    recipient_uid   TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- `PENDING` | `ACCEPTED` | `REJECTED` | `CANCELLED`.
    --
    -- Os três últimos são **terminais**: nada volta para `PENDING` (§19). Querer ser amigo de
    -- novo depois de uma recusa é uma operação nova, com um `request_id` novo — e isso é o que
    -- permite ao destinatário ver que foi pedido duas vezes em vez de ver uma linha ressuscitada.
    status          TEXT    NOT NULL,

    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,

    -- Ninguém pede amizade a si mesmo, e a garantia é do banco — não da UI e não do serviço.
    CHECK (requester_uid <> recipient_uid),
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED'))
) STRICT;

-- **No máximo um pedido pendente de A para B**, garantido pelo banco (§86).
--
-- Índice único **parcial**: ele só vale para `PENDING`. É exatamente isso que permite as duas
-- coisas ao mesmo tempo — reenviar não duplica (§25), e A pode pedir de novo no futuro depois de
-- um `REJECTED`/`CANCELLED` (§46), porque as linhas terminais não ocupam a constraint.
--
-- Sem ele, duas requisições simultâneas do mesmo aparelho criariam dois pedidos: entre o `SELECT`
-- que não encontrou nada e o `INSERT` cabe outro `INSERT`.
CREATE UNIQUE INDEX idx_friend_requests_pending_pair
    ON friend_requests (requester_uid, recipient_uid)
    WHERE status = 'PENDING';

-- "Meus pedidos recebidos, mais recentes primeiro" — a consulta da tela de Solicitações.
CREATE INDEX idx_friend_requests_incoming
    ON friend_requests (recipient_uid, status, created_at DESC, request_id DESC);

-- "Meus pedidos enviados" — a tela que permite cancelar um pendente.
CREATE INDEX idx_friend_requests_outgoing
    ON friend_requests (requester_uid, status, created_at DESC, request_id DESC);

-- A amizade. **Uma linha por par**, não duas.
--
-- ```text
-- PROIBIDO                        CORRETO
-- (A segue B)                     (A ↔ B)   uma linha
-- (B segue A)                     min(uid) na coluna A, max(uid) na coluna B
-- ```
--
-- Duas linhas independentes permitiriam o estado que não existe no produto: "A é amigo de B, mas
-- B não é amigo de A". Amizade aqui é simétrica por construção, e não por disciplina de serviço.
CREATE TABLE friendships (
    -- O par **canônico**: `user_a_uid < user_b_uid`, sempre. A ordenação é lexicográfica sobre o
    -- Firebase UID e não significa nada além de "uma forma só de escrever o mesmo par".
    user_a_uid  TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    user_b_uid  TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    created_at  INTEGER NOT NULL,

    -- A chave primária é o par. Combinada com o `CHECK` abaixo, ela torna **irrepresentável**:
    --
    --   * amizade duplicada A-B (mesma linha, recusada pela PK);
    --   * amizade duplicada B-A (não existe outra forma de escrever o par — a canonicalização é
    --     obrigatória, e uma inserção invertida quebra o `CHECK` em vez de virar uma linha nova);
    --   * amizade consigo mesmo (`A < A` é falso).
    --
    -- Isso é o que sustenta a idempotência de "aceitar duas vezes" mesmo sob concorrência: o
    -- segundo `INSERT` não vira uma segunda amizade nem sob duas transações simultâneas.
    PRIMARY KEY (user_a_uid, user_b_uid),
    CHECK (user_a_uid < user_b_uid)
) STRICT;

-- A lista de amigos de uma conta chega por duas colunas: `WHERE user_a_uid = ?` já é servido pela
-- chave primária, e este índice serve o outro lado. Sem ele, "meus amigos" faria varredura da
-- tabela para metade dos pares.
CREATE INDEX idx_friendships_user_b ON friendships (user_b_uid);
