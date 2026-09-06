-- T16.0 — schema inicial do Spark Backend.
--
-- Deliberadamente mínimo. Nenhuma tabela do Room foi copiada para cá: o schema remoto nasce
-- conforme cada fase da T16 precisar dele (identidade em T16.3, sync em T16.6), e não como espelho
-- de um schema que existe para a execução local do aplicativo.
--
-- `server_metadata` é estado técnico do próprio servidor — não é dado de domínio do Spark e não é
-- uma segunda fonte de verdade de nada que o Android já possui.

CREATE TABLE server_metadata (
    key        TEXT    NOT NULL PRIMARY KEY,
    value      TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
) STRICT;
