-- T17.0 — fundação do domínio social: identidade pública e privacidade.
--
-- Estas são as primeiras tabelas do Spark cujo dado é **server-authoritative**. Todo o resto do
-- servidor guarda cópia de um estado cuja autoridade operacional é o Room do aparelho: backup é
-- snapshot, `sync_entities` é ordem e convergência de um dataset que o app executa offline. Aqui é
-- o contrário — o perfil social nasce no servidor, existe no servidor, e o aparelho só o lê.
--
-- ```text
-- TREINO   ação → domínio → Room → Outbox → backend        local-first
-- SOCIAL   ação → backend → resultado → UI/cache           server-authoritative
-- ```
--
-- ## Por que tabelas próprias, e não `sync_entities`
--
-- Guardar `SocialProfile` dentro de `sync_entities` seria dar a um dado server-authoritative o
-- protocolo de um dado local-first: `baseRevision` vinda do aparelho, tombstone, change log,
-- resolução de conflito pelo usuário. Nada disso faz sentido para uma identidade que o servidor
-- gera e que o cliente nunca propõe — e a primeira consequência prática seria um aparelho
-- conseguindo enviar um `friendCode` por push.
--
-- ## O que deliberadamente NÃO existe nesta migration
--
--   * `friendships`, `friend_requests`, `blocks` — T17.1;
--   * qualquer projeção de treino (XP, streak, contagem, último treino, peso, PR). O perfil social
--     **não** guarda dado de domínio privado para facilitar a UI: quando existir progresso social,
--     ele virá de uma projeção explícita (`SocialProjection`), não de uma coluna aqui;
--   * `avatar_url` / `photo_url` — T17.2 decide identidade visual, e a T17.0 não sobe mídia;
--   * `email`. Ele continua sendo informação da camada de Auth (Firebase), e copiá-lo para cá
--     "porque é prático" criaria um segundo lugar onde ele pode vazar;
--   * qualquer coluna que aceite `ownerUid`, `socialId`, `friendCode` ou `createdAt` vindos do
--     corpo da requisição. Os quatro são server-side.
--
-- Nenhuma tabela da T16 é lida, alterada ou referenciada por esta migration.

-- A identidade social de uma Conta Spark. Uma linha por conta, no máximo.
CREATE TABLE social_profiles (
    -- FK lógica da conta Firebase, derivada de `AuthenticatedPrincipal.uid` — nunca do corpo.
    --
    -- É a chave primária porque "uma conta tem no máximo um perfil social" é a regra, e a forma
    -- mais barata de garanti-la é o banco não conseguir representar o contrário. Ela **não**
    -- aparece em nenhum DTO: o Firebase UID é identidade privada de infraestrutura.
    owner_uid               TEXT    NOT NULL PRIMARY KEY,

    -- A identidade **pública** do domínio social. UUID v4 gerado pelo servidor, imutável.
    --
    -- Não deriva do uid, do e-mail nem do nome — nem por hash: um identificador derivado permite
    -- confirmar um palpite ("o uid X tem o socialId Y?"), e a derivação é exatamente a informação
    -- que este identificador existe para não carregar.
    social_id               TEXT    NOT NULL,

    -- O código humano de convite, na forma canônica `SPK-XXXXXXXX`.
    --
    -- Não é segredo de autenticação: conhecê-lo não concede permissão nenhuma (toda autorização
    -- continua saindo do Firebase ID Token). Também não é escolhido pelo usuário — o que evita
    -- `admin`, `spark`, `suporte` e o resto do problema de impersonação.
    friend_code             TEXT    NOT NULL,

    -- O nome social, independente do nome da conta Google. Sem unicidade global: dois "Igor"
    -- podem coexistir, porque a identidade única é `social_id` e não o nome exibido.
    display_name            TEXT    NOT NULL,

    -- `ACTIVE` | `DISABLED`. A ausência de linha é o terceiro estado (`NOT_ENABLED`), e ela é
    -- um estado de verdade: login não cria perfil social.
    --
    -- `DISABLED` é persistido, e não representado por remoção da linha, justamente para que
    -- reativar preserve `social_id` e `friend_code`. Gerar identidade nova a cada toque
    -- inviabilizaria reconstruir relações no futuro.
    status                  TEXT    NOT NULL,

    -- Relógio do **servidor**, em epoch millis UTC. O relógio do aparelho não entra aqui.
    created_at              INTEGER NOT NULL,
    updated_at              INTEGER NOT NULL
) STRICT;

-- Unicidade global da identidade social. Constraint de banco, e não verificação em código: duas
-- ativações simultâneas passariam por qualquer verificação feita antes do INSERT.
CREATE UNIQUE INDEX idx_social_profiles_social_id ON social_profiles (social_id);

-- Unicidade do código de convite. É sobre esta constraint que o retry de colisão da geração
-- acontece: o servidor tenta um código novo quando o banco recusa, um número limitado de vezes,
-- em vez de devolver 500 ou — pior — um perfil sem código.
--
-- O valor gravado é sempre a forma canônica (maiúsculas, com hífen), então a comparação
-- case-insensitive do futuro lookup (T17.1) é feita normalizando a entrada antes de consultar, e
-- não por `COLLATE NOCASE`: uma função de normalização única é testável e serve também para
-- rejeitar entrada fora do alfabeto, o que uma collation não faz.
CREATE UNIQUE INDEX idx_social_profiles_friend_code ON social_profiles (friend_code);

-- Sem índice em `status`, deliberadamente. Toda consulta desta fase chega por chave única
-- (`owner_uid`, e na T17.1 `friend_code`) e lê o status da linha encontrada; não existe — e não
-- pode existir — consulta que varra perfis por status, porque isso seria enumeração de usuários.

-- As configurações de privacidade, em tabela própria.
--
-- Separadas do perfil porque respondem a perguntas diferentes: `social_profiles` diz *quem é*,
-- `social_privacy_settings` diz *o que os outros podem*. Elas mudam por caminhos diferentes
-- (PATCH distintos), evoluem em ritmos diferentes, e a T17.1+ vai ler as duas em pontos
-- diferentes. Misturá-las faria cada opção nova de privacidade virar uma coluna no meio da
-- identidade.
CREATE TABLE social_privacy_settings (
    owner_uid               TEXT    NOT NULL PRIMARY KEY
        REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- `FRIEND_CODE_ONLY` é o único valor aceito na T17.0 — e é o default.
    --
    -- Não existe `PUBLIC_SEARCH` nem `GLOBAL_PROFILE`: não há busca pública por nome, por e-mail
    -- ou listagem global neste servidor, então um valor que prometesse isso descreveria um
    -- comportamento inexistente.
    discoverability         TEXT    NOT NULL,

    -- Pedidos de amizade só existem na T17.1, mas a preferência é declarada aqui para que a T17.1
    -- a **respeite** em vez de nascer sem ela.
    friend_requests_enabled INTEGER NOT NULL,

    -- Default `0`. Nenhum feed, ranking ou atividade existe na T17.0; quando existir (T17.4), ele
    -- lê esta coluna. Um default `1` publicaria, no dia em que a feature nascesse, a atividade de
    -- quem nunca escolheu publicar.
    activity_sharing_enabled INTEGER NOT NULL,

    updated_at              INTEGER NOT NULL
) STRICT;
