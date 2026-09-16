-- ============================================================================
-- Spark Backend — 0004_ai_capability_entitlements.sql (T19.0)
--
-- ACL/entitlements granular por conta para capabilities do Coach IA. Complementa
-- AI_ENABLED (kill switch global) e ai_usage_daily (quota): esta tabela decide
-- se ESTA conta pode usar ESTA capability, antes de qualquer quota ou provider.
--
-- Modelo: tabela de EXCEÇÕES, não de concessões. Ausência de linha para uma
-- capability conhecida = ALLOW (decisão de compatibilidade — toda conta com
-- Firebase Auth já usa o Coach livremente hoje; introduzir a ACL não pode
-- revogar acesso de ninguém por omissão). Uma linha só existe quando alguém
-- (ferramenta operacional) grava um estado explícito — normalmente REVOKED.
-- `state = 'GRANTED'` também é válido (idempotência de um grant que segue um
-- revoke), e tem o mesmo efeito de "linha ausente".
--
-- A validação de que `capability` é uma das quatro conhecidas mora aqui e no
-- código (AI_CAPABILITIES em ai-capability.ts) — os dois precisam concordar; um
-- valor fora da lista nunca deve ser inserido, e o CHECK é a última linha de
-- defesa contra isso.
-- ============================================================================

CREATE TABLE ai_capability_entitlements (
  uid         TEXT    NOT NULL,
  capability  TEXT    NOT NULL CHECK (capability IN (
                 'AI_ANALYZE_WORKOUT',
                 'AI_GENERATE_WORKOUT',
                 'AI_ADAPT_WORKOUT',
                 'AI_EXPLAIN'
              )),
  state       TEXT    NOT NULL CHECK (state IN ('GRANTED', 'REVOKED')),
  created_at  BIGINT  NOT NULL,
  updated_at  BIGINT  NOT NULL,
  PRIMARY KEY (uid, capability)
);
