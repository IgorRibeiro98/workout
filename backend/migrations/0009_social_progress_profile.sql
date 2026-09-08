-- T17.2 — perfil social enriquecido: o que cada pessoa escolhe compartilhar de progresso.
--
-- A T17.0 criou identidade (`social_profiles`) e privacidade de descoberta
-- (`social_privacy_settings`). A T17.1 criou a relação (`friend_requests`, `friendships`). Esta
-- migration acrescenta **uma coisa só**: o consentimento explícito, campo a campo, sobre o que um
-- amigo pode ver do progresso de alguém.
--
-- ```text
-- Friendship(A,B) ──▶ SocialAccessPolicy ──▶ SocialProgressProjector ──▶ PrivacyFilter ──▶ DTO
--                                                      ▲                       ▲
--                                        estado canônico sincronizado    esta tabela
-- ```
--
-- ## Nenhuma coluna de progresso mora aqui
--
-- Não existe `level`, `xp`, `streak`, `weekly_workouts` nem `achievement` **com valor** em lugar
-- nenhum deste schema, e isso é o contrato (§81): progresso é **derivado** das autoridades reais
-- do Spark, projetado na leitura. Uma coluna `level` aqui seria uma segunda verdade sobre o nível
-- de alguém, e a primeira divergência seria um perfil social afirmando um nível que o aparelho da
-- própria pessoa não reconhece.
--
-- O que esta tabela guarda é **preferência**: quatro booleanos e um fuso. Preferência é
-- server-authoritative por natureza — ela é a resposta a "o que os outros podem ver", e quem
-- responde isso é o servidor, nunca o aparelho.
--
-- ## O que deliberadamente NÃO existe nesta migration
--
--   * **`social_profile_achievement_highlights`** (§31). A seleção de conquistas em destaque exige
--     que o servidor consiga responder "esta conquista foi conquistada por este dono?" (§26), e
--     **ele não consegue**: `achievement_unlocks`, `xp_transactions` e `gamification_events` são
--     DERIVED na matriz da T16 — não entram no sync incremental nem no backup, e cada aparelho os
--     reconstrói do histórico. As duas saídas seriam aceitar o que o cliente declara (o servidor
--     passaria a confiar no cliente sobre progresso) ou portar `AchievementEvaluator` para
--     TypeScript (o Social viraria uma segunda autoridade de gamificação). As duas são proibidas,
--     então a tabela não nasce. O interruptor `share_highlighted_achievements` existe, nasce
--     `false`, e a disponibilidade do campo responde `UNSUPPORTED` até que exista uma autoridade
--     remota de conquistas;
--   * **cache materializado de projeção** (`social_progress_projection`, §82/§83). Na escala do
--     Spark a projeção sai de um `COUNT(*)` sobre índice; materializá-la agora criaria um valor
--     derivado que envelhece sem ninguém perceber. Se um dia for necessário, ele nasce como tabela
--     explicitamente derivada, com `source_cursor`, e nunca como coluna aqui;
--   * **qualquer alteração** em `social_profiles`, `social_privacy_settings`, `friend_requests`,
--     `friendships` ou em qualquer tabela da T16. Esta migration só **cria** uma tabela e a
--     preenche para quem já existe. Nenhum `DROP`, nenhum `ALTER`, nenhum `DELETE`, e nenhum
--     `UPDATE` sobre linha alheia: `social_id`, `friend_code` e as amizades saem daqui exatamente
--     como entraram.

-- O que o dono decidiu compartilhar. Uma linha por perfil social.
CREATE TABLE social_progress_settings (
    -- O mesmo dono do perfil. `ON DELETE CASCADE` pela mesma razão de `social_privacy_settings`:
    -- uma preferência sobre um perfil que não existe mais não é preferência de ninguém.
    owner_uid                      TEXT    NOT NULL PRIMARY KEY
        REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- Os quatro interruptores. **Todos nascem `0`** (§13/§14/§75): ativar o Social na T17.0 não
    -- pode publicar progresso no dia em que a T17.2 subir. Um default `1` transformaria o deploy
    -- em uma publicação que ninguém escolheu.
    --
    -- Eles são consultados no servidor, e não na UI (§3): esconder um campo no Compose não é
    -- controle de acesso — um cliente modificado pediria o mesmo JSON e o receberia inteiro.
    share_level                    INTEGER NOT NULL DEFAULT 0,
    share_consistency_streak       INTEGER NOT NULL DEFAULT 0,
    share_weekly_workout_count     INTEGER NOT NULL DEFAULT 0,
    share_highlighted_achievements INTEGER NOT NULL DEFAULT 0,

    -- O fuso horário IANA do dono (`America/Sao_Paulo`), ou `NULL` enquanto ele não for conhecido.
    --
    -- **Não é preferência de privacidade.** É o parâmetro que torna a semana canônica do Spark
    -- reproduzível no servidor: `ConsistencyCalculator.weekStart` é a segunda-feira da semana que
    -- contém a data **local** do treino, e o servidor guarda `startedAt` em epoch millis UTC. Sem
    -- o fuso, um treino de domingo 22h em São Paulo cairia na semana seguinte para o servidor e na
    -- semana corrente para o aparelho — e a contagem social discordaria da tela do próprio dono.
    --
    -- `NULL` significa "ainda não sei", e a resposta correta para isso é `UNAVAILABLE`, nunca uma
    -- suposição de UTC (§4): ausência de dado não vira zero, e não vira palpite.
    week_time_zone                 TEXT,

    -- Relógio do **servidor**, epoch millis UTC (§58). O relógio do cliente não decide ordem de
    -- preferência: dois aparelhos alterando ao mesmo tempo são resolvidos pela ordem de chegada
    -- no servidor, e não por quem tem o relógio adiantado (§93).
    updated_at                     INTEGER NOT NULL
) STRICT;

-- Quem já tem perfil social ganha a linha com **todos os interruptores desligados** (§14/§75).
--
-- A alternativa — criar a linha preguiçosamente na primeira leitura — foi recusada pela mesma
-- razão que a T17.0 recusou para a privacidade: um perfil sem preferência é um perfil cujo default
-- ninguém escolheu, e a primeira leitura teria de inventar um. Aqui o default é gravado, com a
-- data em que ele passou a existir.
--
-- É um `INSERT` sobre linhas novas de uma tabela nova. Nenhuma linha existente é lida para ser
-- alterada: `social_profiles` é apenas a origem dos `owner_uid`, e sai desta migration idêntica.
INSERT INTO social_progress_settings (
    owner_uid,
    share_level,
    share_consistency_streak,
    share_weekly_workout_count,
    share_highlighted_achievements,
    week_time_zone,
    updated_at
)
SELECT owner_uid, 0, 0, 0, 0, NULL, updated_at
FROM social_profiles;

-- Sem índice além da chave primária, deliberadamente. Toda consulta desta tabela chega por
-- `owner_uid` — o do dono autenticado, ou o do alvo já resolvido server-side a partir do
-- `social_id`. Não existe, e não pode existir, consulta que varra preferências: "quem compartilha
-- nível?" seria enumeração de usuários por uma porta nova.
