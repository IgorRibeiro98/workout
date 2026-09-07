-- T16.6 — sincronização incremental multi-device.
--
-- Estas são as primeiras tabelas de **estado sincronizado** do Spark no servidor. Elas não são um
-- espelho do Room: não existe `workout_template_exercises`, `set_logs` nem `exercises` aqui. A
-- unidade continua sendo o **agregado** da T16.3 — a raiz com identidade global, serializada
-- inteira, opaca para o servidor.
--
-- ```text
-- sync_entities   estado atual de cada agregado da conta        (uma linha por agregado)
-- sync_changes    log append-only do que mudou, em sequência    (uma linha por mudança aceita)
-- sync_mutations  ledger de idempotência por clientMutationId   (uma linha por tentativa aceita)
-- ```
--
-- O que deliberadamente NÃO existe nesta migration:
--
--   * tombstone, `deleted_at` e política de exclusão — T16.7 (o push de `DELETE` é recusado com
--     `SYNC_DELETE_NOT_SUPPORTED` e a intenção continua pendente no aparelho);
--   * resolução de conflito. O servidor **detecta** stale write e divergência de histórico; ele
--     não escolhe vencedor, não faz merge e não aplica last-write-wins;
--   * qualquer coluna que aceite `ownerUid` vindo do corpo da requisição. O dono sai sempre de
--     `AuthenticatedPrincipal.uid`;
--   * relação com `backup_snapshots`. Backup e sync são mecanismos diferentes sobre o mesmo dado:
--     um guarda snapshots imutáveis inteiros, o outro converge mudanças. Um não vira o outro.

-- O estado atual de um agregado da conta.
CREATE TABLE sync_entities (
    id                    INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,

    -- O dono, derivado do token verificado. Toda leitura e toda escrita filtram por ele.
    owner_uid             TEXT    NOT NULL,

    entity_type           TEXT    NOT NULL,

    -- Identidade **portátil** do agregado (`syncId` da T16.3). Nunca um `localId`.
    entity_sync_id        TEXT    NOT NULL,

    entity_schema_version INTEGER NOT NULL,

    -- A versão da entidade **no servidor**, monotonicamente crescente para aquela entidade.
    -- É ela — e não `updated_at`, e não o relógio do aparelho — que detecta escrita stale.
    server_revision       INTEGER NOT NULL,

    -- A posição no change log da mudança que produziu `server_revision`.
    -- Guardada aqui para que reconhecer um reenvio convergente não precise varrer o log.
    last_server_sequence  INTEGER NOT NULL,

    -- O agregado inteiro, na forma canônica do contrato. O servidor guarda e devolve; ele não
    -- desmonta o payload em colunas e não interpreta treino (ADR-0001).
    payload               TEXT    NOT NULL,

    -- SHA-256 da forma canônica do payload. Resolve "é o mesmo conteúdo?" — que é uma pergunta
    -- diferente de "é a mesma versão?", respondida por `server_revision`.
    payload_hash          TEXT    NOT NULL,

    -- Qual instalação originou a última mudança. Metadado de diagnóstico: `deviceId` não
    -- autoriza nada e não participa de ownership.
    origin_device_id      TEXT    NOT NULL,

    created_at            INTEGER NOT NULL,
    updated_at            INTEGER NOT NULL
) STRICT;

-- Um agregado por conta, por tipo, por identidade. É constraint de banco e não verificação em
-- código porque duas requisições simultâneas passariam por qualquer verificação.
CREATE UNIQUE INDEX idx_sync_entities_identity
    ON sync_entities (owner_uid, entity_type, entity_sync_id);

-- O log append-only de mudanças aceitas. É ele que o pull incremental percorre.
--
-- Uma linha aqui **nunca** é editada depois de criada. O `payload` é o snapshot do agregado no
-- momento daquela mudança, e não uma referência ao estado atual: assim a sequência X representa
-- deterministicamente o estado associado à mudança X, mesmo que a entidade mude dez vezes depois.
-- O custo é duplicar o conteúdo; a alternativa custaria corretude.
CREATE TABLE sync_changes (
    -- A sequência **global** do servidor. AUTOINCREMENT para que um número nunca seja
    -- reaproveitado, e para que a ordem seja a de aceitação — nunca a de um relógio de aparelho.
    -- O cursor do cliente é uma posição nesta coluna.
    server_sequence       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,

    owner_uid             TEXT    NOT NULL,
    entity_type           TEXT    NOT NULL,
    entity_sync_id        TEXT    NOT NULL,
    entity_schema_version INTEGER NOT NULL,

    -- A revision que esta mudança produziu.
    server_revision       INTEGER NOT NULL,

    operation             TEXT    NOT NULL,
    payload               TEXT    NOT NULL,
    payload_hash          TEXT    NOT NULL,
    origin_device_id      TEXT    NOT NULL,
    created_at            INTEGER NOT NULL
) STRICT;

-- O pull é sempre "as mudanças desta conta depois deste cursor, em ordem".
CREATE INDEX idx_sync_changes_owner_sequence ON sync_changes (owner_uid, server_sequence);

-- O ledger de idempotência: qual tentativa de mutação já foi aplicada, e com que resultado.
--
-- Sem ele, uma resposta perdida viraria uma segunda aplicação — duas sessões de treino, duas
-- medidas, duas revisions. Com ele, o reenvio devolve o resultado original.
CREATE TABLE sync_mutations (
    id                  INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    owner_uid           TEXT    NOT NULL,

    -- A identidade da **tentativa**, gerada pelo Android na T16.3.
    client_mutation_id  TEXT    NOT NULL,

    device_id           TEXT    NOT NULL,
    entity_type         TEXT    NOT NULL,
    entity_sync_id      TEXT    NOT NULL,
    operation           TEXT    NOT NULL,

    -- A revision sobre a qual o cliente construiu a mudança. `NULL` = criação.
    base_revision       INTEGER,

    -- O que a aplicação produziu. Um reenvio devolve exatamente estes dois valores.
    result_revision     INTEGER NOT NULL,
    result_sequence     INTEGER NOT NULL,

    -- Para distinguir "reenvio da mesma mutação" de "mesmo id, conteúdo outro" — que é conflito
    -- de idempotência, e nunca uma segunda aplicação silenciosa.
    payload_hash        TEXT    NOT NULL,

    applied_at          INTEGER NOT NULL
) STRICT;

-- A chave de idempotência. Duas linhas com o mesmo par seriam duas mutações se passando por uma.
CREATE UNIQUE INDEX idx_sync_mutations_owner_client
    ON sync_mutations (owner_uid, client_mutation_id);
