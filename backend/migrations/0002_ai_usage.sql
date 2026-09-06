-- T16.2 — uso do Coach IA, para proteção de custo.
--
-- Esta tabela é **metadata técnica do servidor**, não dado de domínio do Spark. Ela responde
-- "quantas chamadas ao provider esta conta fez hoje" e nada além disso.
--
-- O que deliberadamente NÃO existe aqui: prompt, contexto, histórico de treino, corpo da
-- resposta, texto do usuário. O contexto do Coach entra, é usado e não é persistido (§37) — e
-- nenhuma tabela de treino, sessão ou série é criada no servidor nesta fase (§70). Sync é T16.3+.
--
-- O `uid` é o Firebase UID que saiu do token verificado; ele nunca vem do corpo da requisição.

CREATE TABLE ai_usage_daily (
    uid           TEXT    NOT NULL,
    -- Data em UTC (YYYY-MM-DD): a janela de quota é a mesma para todo mundo, sem depender do
    -- fuso do aparelho, que é entrada não confiável.
    utc_date      TEXT    NOT NULL,
    request_type  TEXT    NOT NULL,
    -- Tentativas que chegaram ao provider — bem-sucedidas ou não. Uma chamada que falhou já
    -- pode ter custado, então ela conta (§35).
    request_count INTEGER NOT NULL DEFAULT 0,
    -- Tokens, quando o provider os informa. Zero significa "não informado", não "não usou".
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    output_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens  INTEGER NOT NULL DEFAULT 0,
    updated_at    INTEGER NOT NULL,
    PRIMARY KEY (uid, utc_date, request_type)
) STRICT;

-- O teto global do dia é uma soma por data; sem este índice ela varreria a tabela inteira.
CREATE INDEX idx_ai_usage_daily_date ON ai_usage_daily (utc_date);
