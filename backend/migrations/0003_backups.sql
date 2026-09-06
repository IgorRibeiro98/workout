-- T16.4 — backup estruturado.
--
-- Estas são as **primeiras tabelas de dado pessoal** do Spark no servidor. Elas não são uma cópia
-- do Room: não há `workout_templates`, `set_logs` nem `exercises` aqui. O que existe é um snapshot
-- imutável, versionado, pertencente a um `owner_uid` que sai sempre do token verificado.
--
-- O que deliberadamente NÃO existe nesta migration:
--
--   * `revision`, `change_seq`, `cursor`, tombstone — sync incremental é T16.6/T16.7;
--   * tabela de entidade de domínio (um template não vira linha consultável) — o servidor guarda
--     o snapshot, não uma segunda autoridade operacional sobre o treino do usuário;
--   * qualquer coluna que aceite `ownerUid` vindo do corpo da requisição.

-- Um snapshot completo e autocontido, em um ponto do tempo, de uma conta.
CREATE TABLE backup_snapshots (
    -- Sequência do servidor. É ela — e não o relógio do aparelho — que ordena "qual é o mais
    -- recente" e a retenção. AUTOINCREMENT para que um id nunca seja reaproveitado depois de uma
    -- limpeza de retenção: o id de um backup apagado não pode voltar apontando para outro.
    id                    INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,

    -- Identidade opaca do backup para o cliente. Separada de `id` para que a numeração interna do
    -- servidor não vaze na API.
    backup_id             TEXT    NOT NULL,

    -- O dono, derivado de `AuthenticatedPrincipal.uid`. Toda leitura filtra por ele.
    owner_uid             TEXT    NOT NULL,

    -- A identidade da tentativa, gerada pelo Android. Com `owner_uid`, é a chave de idempotência.
    client_backup_id      TEXT    NOT NULL,

    -- Qual instalação produziu o snapshot (T16.3). Metadado — não autoriza nada.
    device_id             TEXT    NOT NULL,

    backup_schema_version INTEGER NOT NULL,

    -- SHA-256 da forma canônica do corpo recebido, calculado **pelo servidor**.
    payload_hash          TEXT    NOT NULL,

    item_count            INTEGER NOT NULL,
    size_bytes            INTEGER NOT NULL,

    -- Relógio do aparelho, informativo. Nunca é usado para ordenar nem para escolher o mais
    -- recente: relógio de dispositivo diverge, e um aparelho adiantado esconderia backups reais.
    captured_at           INTEGER,

    -- Relógio do servidor. Esta é a autoridade temporal.
    created_at            INTEGER NOT NULL
) STRICT;

-- Idempotência: a mesma tentativa lógica não vira dois backups. É constraint de banco e não
-- verificação em código porque duas requisições simultâneas passariam por qualquer verificação.
CREATE UNIQUE INDEX idx_backup_snapshots_owner_client ON backup_snapshots (owner_uid, client_backup_id);

CREATE UNIQUE INDEX idx_backup_snapshots_backup_id ON backup_snapshots (backup_id);

-- "O mais recente desta conta" e a varredura de retenção, sem varrer a tabela inteira.
CREATE INDEX idx_backup_snapshots_owner_id ON backup_snapshots (owner_uid, id DESC);

-- Os agregados de um snapshot. Um item por (tipo, identidade portátil).
CREATE TABLE backup_items (
    snapshot_id           INTEGER NOT NULL REFERENCES backup_snapshots (id) ON DELETE CASCADE,
    entity_type           TEXT    NOT NULL,

    -- Identidade **portátil**: `syncId` (UUID) para as raízes de agregado da T16.3, e a chave
    -- derivada documentada em `contracts/backup/v1/README.md` para as outras. Nunca um `localId`.
    entity_sync_id        TEXT    NOT NULL,

    entity_schema_version INTEGER NOT NULL,

    -- O agregado inteiro, na forma canônica do contrato. O servidor não desmonta o payload em
    -- colunas: ele não interpreta treino, e virar consulta de domínio seria a segunda autoridade
    -- operacional que o ADR-0001 proíbe.
    payload               TEXT    NOT NULL,

    -- SHA-256 do item. Permite diagnosticar divergência de um agregado específico sem ler o
    -- conteúdo dele.
    content_hash          TEXT    NOT NULL,

    -- Duplicidade dentro do mesmo snapshot é impossível por constraint, não só por validação.
    PRIMARY KEY (snapshot_id, entity_type, entity_sync_id)
) STRICT;
