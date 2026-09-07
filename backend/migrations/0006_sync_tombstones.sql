-- T16.7 — tombstones, propagação de exclusão e prevenção de ressurreição.
--
-- A T16.6 recusava `DELETE` explicitamente (`DELETE_NOT_SUPPORTED`) porque apagar sem tombstone é
-- pior do que não apagar:
--
-- ```text
-- A deleta X  →  servidor remove fisicamente  →  B (offline, com X) volta  →  B envia X
--                                                                          →  X ressuscita
-- ```
--
-- Esta migration dá ao servidor a memória que falta. A escolha de desenho é **uma autoridade só**:
-- a exclusão vive em `sync_entities`, como estado da própria entidade, e não em uma tabela
-- `sync_tombstones` paralela.
--
-- Por quê: a identidade `(owner_uid, entity_type, entity_sync_id)` já é única aqui, e é sobre ela
-- que todo push decide. Com duas tabelas, "esta entidade existe?" teria duas respostas possíveis —
-- e o dia em que elas discordassem seria o dia em que um dado ressuscitaria. Ownership, revision
-- monotônica e a constraint de identidade continuam valendo para o tombstone exatamente como
-- valiam para a entidade viva, sem nenhuma regra nova.
--
-- ```text
-- Template X   revision 5   deleted = 0
--      ↓ DELETE baseRevision = 5
-- Template X   revision 6   deleted = 1     ← a mesma linha, uma revision adiante
--      +
-- sync_changes  operation = DELETE, server_revision = 6   ← é isso que os outros aparelhos leem
-- ```
--
-- O que deliberadamente NÃO existe nesta migration:
--
--   * limpeza de tombstone. `SYNC_TOMBSTONE_RETENTION_DAYS` documenta a retenção pretendida, e
--     **nada** apaga tombstone hoje: um device que ficou meses offline precisa receber o delete
--     quando voltar, e apagar a evidência cedo demais o faria ressuscitar a entidade. Na escala do
--     Spark (um grupo pequeno), guardar é barato e correto;
--   * compactação do change log. O cursor de um aparelho antigo continua válido para sempre; se
--     algum dia houver compactação, o servidor recusa com `CURSOR_EXPIRED` em vez de recomeçar do
--     zero em silêncio — o caminho já existe em `sync.validator.ts`;
--   * qualquer forma de `force`/`overwrite`. Um `UPSERT` contra tombstone é conflito, nunca
--     recriação: recriar é decisão do usuário, e ela nasce com `syncId` novo.

-- O estado de exclusão da entidade. `0` = viva, `1` = tombstone.
ALTER TABLE sync_entities ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0;

-- Quando a exclusão foi aceita, em epoch millis do servidor. Metadado de diagnóstico e base da
-- retenção — nunca árbitro de conflito, que continua sendo `server_revision`.
ALTER TABLE sync_entities ADD COLUMN deleted_at INTEGER;

-- Qual instalação originou a exclusão. Diagnóstico: `device_id` não autoriza nada e não participa
-- de ownership, que continua saindo do token verificado.
ALTER TABLE sync_entities ADD COLUMN deleted_by_device_id TEXT;
