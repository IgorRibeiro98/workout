-- T17.13.1 §26/§27 — `EXPIRED` deixa de ser derivado e passa a ser um estado gravado
--
-- ## O defeito que esta migration corrige
--
-- A 0019 escolheu tratar a expiração como derivação na leitura, e o comentário dela explica a
-- intenção: "gravar a expiração exigiria um processo passando por ali antes do toque". A intenção
-- era razoável e a consequência não foi vista — porque a expiração derivada resolve a *exibição* e
-- não alcança nada que dependa do banco:
--
-- ```text
-- idx_social_group_invitations_pending  UNIQUE (group_id, recipient_uid) WHERE status = 'PENDING'
-- ```
--
-- Esse índice não sabe que horas são. Um convite vencido continua com `status = 'PENDING'`, e
-- portanto continua ocupando a vaga única daquele par (Squad, destinatário) — e continua contando
-- em `MAX_PENDING_INVITATIONS`. O resultado observado é um beco sem saída:
--
--   - quem recebeu **não pode aceitar**: a leitura recusa por vencimento;
--   - quem convidou **não pode reconvidar**: o serviço encontra a linha pendente e a devolve como
--     se fosse um convite bom.
--
-- A vaga daquele par fica perdida para sempre. É o blocker de §85 ("expired Squad invite bloquear
-- novo convite").
--
-- ## Por que um rebuild em 12 passos
--
-- O SQLite não altera uma `CHECK`. Acrescentar `EXPIRED` ao conjunto de estados exige `CREATE
-- TABLE` novo, cópia das linhas, `DROP` e `RENAME` — o mesmo procedimento que a 0014 usou para
-- acrescentar `WORKOUT_SHARE_RECEIVED` ao enum de notificações, que a 0019 usou para
-- `GROUP_INVITATION_RECEIVED` e que a 0020 usou nas interações por audiência. O teste estrutural
-- da T17.10 (`social-migrations.spec.ts`) só aceita um `DROP TABLE` nessa forma.
--
-- A 0019 **não** é editada (§26/§58): ela já rodou, o runner de migrations verifica checksum, e o
-- comentário dela descreve corretamente a intenção histórica daquela fase.
--
-- ## Nenhuma linha muda de estado aqui
--
-- A cópia preserva `status` como está. Nenhum convite existente é marcado `EXPIRED` por esta
-- migration, e isso é deliberado: quem decide que um convite venceu é o relógio do servidor, no
-- caminho de execução (`expirePendingInvitations`), e não uma migration que rodaria uma vez com o
-- relógio do momento do deploy. Um `UPDATE ... WHERE expires_at <= <agora do deploy>` aqui também
-- gravaria a decisão em cima de um instante arbitrário — o do deploy, e não o da leitura.
--
-- Na primeira operação sensível a `PENDING` depois do deploy, a varredura marca o que já venceu.

-- Sem `PRAGMA foreign_keys = OFF`, como nas 0014/0019/0020: o runner aplica cada migration dentro
-- de uma transação, e o SQLite **ignora** essa diretiva dentro de uma transação — escrevê-la aqui
-- daria uma falsa sensação de proteção. Ela também não é necessária: `social_group_invitations` é
-- uma tabela filha, e nenhuma outra a referencia.

-- 1. tabela nova, idêntica à 0019 exceto pelo `CHECK` de `status`.
CREATE TABLE social_group_invitations_new (
    id            TEXT    PRIMARY KEY,

    group_id      TEXT    NOT NULL REFERENCES social_groups (id) ON DELETE CASCADE,
    sender_uid    TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    recipient_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- Cinco estados, agora todos gravados. `EXPIRED` é terminal, como `DECLINED` e `CANCELLED`:
    -- um convite vencido não volta a ser pendente, e reconvidar cria uma linha nova.
    status        TEXT    NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),

    created_at    INTEGER NOT NULL,
    expires_at    INTEGER NOT NULL,

    -- Continua sendo "quando a pessoa respondeu", e a expiração **não** é uma resposta: a
    -- varredura deixa esta coluna como está. Ela é o que distingue "recusou" de "deixou vencer".
    responded_at  INTEGER,

    client_request_id TEXT,

    CHECK (sender_uid <> recipient_uid)
);

-- 2. as linhas, sem transformação.
INSERT INTO social_group_invitations_new
    (id, group_id, sender_uid, recipient_uid, status, created_at, expires_at, responded_at,
     client_request_id)
SELECT
    id, group_id, sender_uid, recipient_uid, status, created_at, expires_at, responded_at,
    client_request_id
FROM social_group_invitations;

-- 3. troca.
DROP TABLE social_group_invitations;
ALTER TABLE social_group_invitations_new RENAME TO social_group_invitations;

-- 4. os índices da 0019, recriados sem alteração de semântica.
--
-- O índice de pendentes é o que passa a funcionar de verdade: com `EXPIRED` gravado, a linha
-- vencida sai da partição `WHERE status = 'PENDING'` e libera a vaga do par.
CREATE UNIQUE INDEX idx_social_group_invitations_pending
    ON social_group_invitations (group_id, recipient_uid)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX idx_social_group_invitations_client_request
    ON social_group_invitations (group_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX idx_social_group_invitations_recipient
    ON social_group_invitations (recipient_uid, status, created_at);

CREATE INDEX idx_social_group_invitations_sender
    ON social_group_invitations (sender_uid, status);

-- 5. o índice que a varredura usa: "quais pendentes já venceram?".
--
-- Sem ele, `expirePendingInvitations` faria uma varredura completa da tabela em toda operação
-- sensível a `PENDING` — e ela roda no caminho de requisições comuns (listar, convidar, aceitar).
CREATE INDEX idx_social_group_invitations_expiry
    ON social_group_invitations (status, expires_at);
