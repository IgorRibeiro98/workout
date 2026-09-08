-- T17.3 — desafios privados entre amigos.
--
-- A T17.0 criou identidade (`social_profiles`), a T17.1 a relação (`friend_requests`,
-- `friendships`) e a T17.2 o consentimento de exibição (`social_progress_settings`). Esta
-- migration acrescenta a primeira coisa do domínio social que **compara** duas pessoas.
--
-- ```text
-- Friendship ──▶ ChallengeInvitation ──aceitar──▶ ChallengeParticipant
--                                                        │
--                                        (dados canônicos de treino, na leitura)
--                                                        ▼
--                                                 score · rank · goalReached
-- ```
--
-- ## Nenhuma coluna de pontuação mora aqui
--
-- Não existe `score`, `progress`, `current_count`, `rank` nem `winner` em lugar nenhum deste
-- schema, e isso é o contrato (§82/§134). A pontuação é **derivada na leitura** dos dados
-- canônicos de treino que já chegaram ao servidor por sync, e um contador aqui seria uma segunda
-- verdade sobre o progresso de alguém.
--
-- O motivo é concreto, e não estético. O Spark é local-first no treino: uma sessão feita durante o
-- desafio pode chegar ao servidor **depois** de o desafio acabar (§71). Um contador incremental
-- teria de ser corrigido retroativamente por um caminho que ninguém escreveu — e o modo de falhar
-- seria um placar que nunca converge, ou que conta duas vezes quando um push é reenviado. Contar
-- na leitura, sobre `sync_entities`, é sempre o número certo para o estado conhecido agora.
--
-- ## Por que tabelas próprias, e não `sync_entities` (§141)
--
-- Pela mesma razão da T17.0: um desafio é um fato sobre **várias** contas, e nenhum aparelho pode
-- decidi-lo sozinho. Guardá-lo no protocolo de sync daria a um dado server-authoritative
-- `baseRevision` vinda do cliente, tombstone, change log e resolução de conflito arbitrada pelo
-- usuário — e a primeira consequência prática seria um aparelho conseguindo enviar a própria
-- pontuação por push.
--
-- ## O que deliberadamente NÃO existe nesta migration
--
--   * **`challenge_progress(score)`** (§134). Ver acima. Se um dia a escala exigir cache, ele
--     nasce como tabela explicitamente derivada, com o cursor da fonte, e nunca como coluna aqui;
--   * **tipos além de `WORKOUTS_COMPLETED` e `ACTIVE_DAYS`**. `TOTAL_VOLUME`, `XP_GAINED`,
--     `PR_COUNT`, `CALORIES`, `TOTAL_SETS`, `TOTAL_REPS` e `TIME_TRAINED` não têm autoridade
--     remota clara hoje — XP e PR são DERIVED na matriz da T16, e os demais exigiriam abrir o
--     payload da sessão, que é exatamente o que `AGGREGATE_ONLY` proíbe. O `CHECK` abaixo os
--     torna irrepresentáveis em vez de deixá-los como string livre;
--   * **`status` com `UPCOMING`/`ACTIVE`/`ENDED`/`VOID`** (§27). Os quatro são **derivados** de
--     `starts_at`, `ends_at_exclusive`, do relógio do servidor e da contagem de participantes.
--     Persisti-los exigiria um cron para virá-los, e um cron que não roda é um desafio que nunca
--     começa. O que esta tabela guarda é a única coisa que alguém **escreve**: cancelou ou não;
--   * **`EXPIRED`/`CANCELLED` em `challenge_invitations`**. Também derivados: um convite pendente
--     depois do início está expirado, e um convite pendente de um desafio cancelado está
--     cancelado. Nenhum dos dois precisa de uma escrita para ser verdade;
--   * **rejoin.** Um participante que saiu fica `WITHDRAWN`, e isso é terminal nesta fase (§66);
--   * **hard delete** (§138/§139). Cancelar é o caminho; nenhuma rota apaga desafio.
--
-- Nenhuma tabela da T16 (`sync_*`, `backup_*`, `ai_usage_daily`, `server_metadata`) e nenhuma
-- tabela da T17.0/T17.1/T17.2 é lida, alterada ou apagada por esta migration. Ela só **cria**.

-- O desafio: as regras que os participantes aceitaram.
CREATE TABLE challenges (
    -- Identificador **público**: UUID v4 de CSPRNG, o mesmo gerador do `social_id` e do
    -- `request_id` da T17.1.
    --
    -- Nunca o `rowid` (§25): um id sequencial contaria de graça quantos desafios existem no
    -- servidor e convidaria a tentar o vizinho. Adivinhar não daria acesso — a autorização é por
    -- participação — mas um identificador que convida a tentar é um identificador mal escolhido.
    challenge_id      TEXT    NOT NULL PRIMARY KEY,

    -- Quem criou, por Firebase UID. Sai de `AuthenticatedPrincipal.uid`, **nunca** do corpo
    -- (§33/§34): o validador recusa a requisição inteira se `creatorUid` aparecer.
    --
    -- UID interno, e nunca em DTO. A fronteira onde ele para de existir é a projeção no serviço.
    creator_uid       TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- O nome que a pessoa deu. Sem unicidade: dois "12 treinos" podem coexistir, porque a
    -- identidade é `challenge_id` (§23).
    name              TEXT    NOT NULL,

    -- O tipo, e o `CHECK` é o que impede string arbitrária (§18). Acrescentar um tipo exige mudar
    -- esta linha **e** ter uma fonte canônica para ele — que é a ordem certa.
    type              TEXT    NOT NULL,

    -- A meta. Inteiro positivo (§19), com o teto por tipo aplicado no validador: `ACTIVE_DAYS`
    -- não pode pedir mais dias do que o desafio tem (§21), e essa regra depende das datas.
    target            INTEGER NOT NULL,

    -- A janela, como a pessoa a escolheu: **datas de calendário**, `YYYY-MM-DD`, inclusivas nas
    -- duas pontas (§12).
    --
    -- Guardadas como texto porque é o que elas são — uma data não é um instante. "10 de setembro"
    -- significa dias diferentes em fusos diferentes, e é o par (data, fuso) que produz o instante.
    start_date        TEXT    NOT NULL,
    end_date          TEXT    NOT NULL,

    -- O fuso IANA do **desafio** (§8/§9). Um só, para todos os participantes: com um fuso por
    -- pessoa, "dia 8" seria um dia diferente para cada um, e `ACTIVE_DAYS` compararia coisas
    -- distintas. Validado contra o runtime na criação (§10), nunca aceito como string livre.
    time_zone_id      TEXT    NOT NULL,

    -- A janela derivada **no servidor** (§13), em epoch millis UTC. `[starts_at, ends_at_exclusive)`.
    --
    -- Derivada e gravada, em vez de recalculada a cada leitura, por uma razão de correção e não de
    -- desempenho: o banco de fusos do ICU é atualizado com o runtime, e um país que mude a regra
    -- de horário de verão no meio de um desafio deslocaria a janela de um desafio **já aceito**.
    -- As regras que os participantes aceitaram não mudam depois (§47/§49) — e a janela é uma
    -- delas. As datas ficam guardadas ao lado para que a tela mostre o que a pessoa escolheu.
    starts_at         INTEGER NOT NULL,
    ends_at_exclusive INTEGER NOT NULL,

    -- `OPEN` | `CANCELLED`. **Não** é o status que a UI mostra.
    --
    -- É a única parte do ciclo de vida que alguém escreve: `UPCOMING`, `ACTIVE`, `ENDED` e `VOID`
    -- são calculados na leitura a partir do relógio e da contagem de participantes (§27). Um
    -- desafio não "vira" ativo — ele começa a ser ativo quando o instante chega, com ou sem
    -- alguém rodando um cron.
    lifecycle         TEXT    NOT NULL,

    -- Quando o criador cancelou. `NULL` enquanto `lifecycle = 'OPEN'`.
    cancelled_at      INTEGER,

    -- Relógio do **servidor**, epoch millis UTC (§201). O do aparelho não entra aqui (§14).
    created_at        INTEGER NOT NULL,
    updated_at        INTEGER NOT NULL,

    CHECK (type IN ('WORKOUTS_COMPLETED', 'ACTIVE_DAYS')),
    CHECK (target > 0),
    CHECK (lifecycle IN ('OPEN', 'CANCELLED')),
    -- A janela tem pelo menos um dia, e o fim vem depois do início. Garantia de banco: um desafio
    -- de duração negativa não é um estado que o serviço deva ter de lembrar de recusar.
    CHECK (ends_at_exclusive > starts_at),
    CHECK (end_date >= start_date),
    -- `cancelled_at` e `lifecycle` não podem discordar. Sem isto, um cancelamento pela metade
    -- produziria um desafio cancelado sem data, ou uma data de cancelamento em um desafio ativo.
    CHECK ((lifecycle = 'CANCELLED') = (cancelled_at IS NOT NULL))
) STRICT;

-- "Os desafios que eu criei", e o teto de desafios abertos por criador (§111).
CREATE INDEX idx_challenges_creator ON challenges (creator_uid, lifecycle, starts_at DESC);

-- A ordenação das listas: por janela, dentro do que já foi filtrado por participação.
CREATE INDEX idx_challenges_window ON challenges (starts_at, ends_at_exclusive);

-- O convite. Uma linha por (desafio, convidado).
--
-- A linha **não é apagada** em transição (a mesma decisão da T17.1): recusar duas vezes precisa ser
-- distinguível de recusar um convite que nunca existiu, e é justamente a segunda tentativa —
-- depois de uma resposta perdida — que precisa de uma resposta correta em vez de um 404.
CREATE TABLE challenge_invitations (
    invitation_id TEXT    NOT NULL PRIMARY KEY,

    challenge_id  TEXT    NOT NULL REFERENCES challenges (challenge_id) ON DELETE CASCADE,

    -- Quem convidou. É sempre o criador nesta fase (§46: a seleção é completa na criação), e a
    -- coluna existe para que "quem me chamou" continue respondível se um dia isso deixar de valer.
    inviter_uid   TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,
    recipient_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- `PENDING` | `ACCEPTED` | `DECLINED`. Os dois últimos são terminais.
    --
    -- `EXPIRED` e `CANCELLED` **não** são gravados: eles são derivados na leitura de `PENDING` +
    -- (o desafio já começou) e `PENDING` + (o desafio foi cancelado). Gravá-los exigiria um cron,
    -- e um convite cujo estado depende de um processo que pode não ter rodado é um convite que
    -- pode ser aceito tarde demais.
    status        TEXT    NOT NULL,

    created_at    INTEGER NOT NULL,
    updated_at    INTEGER NOT NULL,

    CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED')),
    -- Ninguém convida a si mesmo. O criador já entra como participante (§31/§42), e um convite
    -- para ele seria um convite para aceitar o que já está aceito.
    CHECK (inviter_uid <> recipient_uid),

    -- **Um convite por pessoa, por desafio** (§43). É esta constraint que torna
    -- `invitedSocialIds: [B, B]` incapaz de produzir dois convites, mesmo que a normalização em
    -- código falhasse — e ela vale sob concorrência, onde uma verificação prévia não valeria.
    UNIQUE (challenge_id, recipient_uid)
) STRICT;

-- "Meus convites de desafio pendentes", mais recentes primeiro — a consulta da tela.
CREATE INDEX idx_challenge_invitations_recipient
    ON challenge_invitations (recipient_uid, status, created_at DESC, invitation_id DESC);

-- "Quantos convites deste desafio ainda estão pendentes" — o contador que o criador vê (§172).
CREATE INDEX idx_challenge_invitations_challenge
    ON challenge_invitations (challenge_id, status);

-- Quem está de fato participando.
--
-- Tabela separada dos convites, e não um `status = 'ACCEPTED'` reaproveitado, porque as duas
-- respondem perguntas diferentes: o convite registra **o que foi oferecido e respondido**, a
-- participação registra **quem conta agora**. Fundi-las faria "sair do desafio" ter de reescrever
-- a resposta que a pessoa deu um dia — apagando um fato para simplificar uma consulta.
CREATE TABLE challenge_participants (
    challenge_id    TEXT    NOT NULL REFERENCES challenges (challenge_id) ON DELETE CASCADE,
    participant_uid TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- `CREATOR` | `MEMBER` (§38). O papel decide o que a pessoa pode: o criador cancela e não sai
    -- (§62/§67), o membro sai e não cancela.
    role            TEXT    NOT NULL,

    -- `JOINED` | `WITHDRAWN` (§39). Terminal nos dois sentidos que importam: não há rejoin nesta
    -- fase (§66), e `WITHDRAWN` sai do placar competitivo (§64/§96) sem que a linha seja apagada
    -- (§65) — a integridade histórica do desafio não depende de ninguém ter ficado até o fim.
    status          TEXT    NOT NULL,

    joined_at       INTEGER NOT NULL,
    left_at         INTEGER,

    -- **Uma participação por pessoa, por desafio** (§40). Aceitar duas vezes não cria dois
    -- participantes, nem sob duas requisições simultâneas.
    PRIMARY KEY (challenge_id, participant_uid),

    CHECK (role IN ('CREATOR', 'MEMBER')),
    CHECK (status IN ('JOINED', 'WITHDRAWN')),
    CHECK ((status = 'WITHDRAWN') = (left_at IS NOT NULL))
) STRICT;

-- **Exatamente um criador por desafio** (§41), garantido pelo banco.
--
-- Índice único parcial sobre `challenge_id` onde `role = 'CREATOR'`: um segundo criador é
-- irrepresentável. Sem ele, a regra viveria só na disciplina do serviço — e "quem pode cancelar"
-- passaria a depender dessa disciplina.
CREATE UNIQUE INDEX idx_challenge_participants_creator
    ON challenge_participants (challenge_id)
    WHERE role = 'CREATOR';

-- "Os desafios de que eu participo" — a consulta da lista, e a base de toda autorização de
-- leitura: quem não tem linha aqui não vê o desafio (§97/§100).
CREATE INDEX idx_challenge_participants_participant
    ON challenge_participants (participant_uid, status);

-- O ledger de idempotência da criação (§188–§190/§196).
--
-- Focado no desafio, e **não** uma reutilização de `sync_mutations` (§196): aquele ledger pertence
-- ao protocolo local-first do treino, com `baseRevision`, `deviceId` e `entitySyncId`. Emprestá-lo
-- ao social daria ao social um vocabulário que ele não tem — e faria uma limpeza futura do sync
-- alcançar a criação de desafios.
--
-- Também **não** é um framework genérico (§197): uma tabela, uma chave, uma pergunta.
CREATE TABLE challenge_creation_requests (
    -- A tentativa pertence a quem a fez. `owner_uid` na chave impede que o `clientRequestId` de
    -- uma conta colida com o de outra — dois aparelhos podem gerar o mesmo UUID por defeito, e
    -- nesse caso o segundo receberia o desafio do primeiro.
    owner_uid         TEXT    NOT NULL REFERENCES social_profiles (owner_uid) ON DELETE CASCADE,

    -- O identificador da **tentativa**, gerado pelo Android (§188).
    client_request_id TEXT    NOT NULL,

    -- SHA-256 da forma canônica do pedido. É ele que distingue "reenvio do mesmo pedido" de
    -- "mesmo id, conteúdo outro" — que é conflito (§190), e nunca uma segunda criação silenciosa.
    request_hash      TEXT    NOT NULL,

    -- O desafio que aquela tentativa produziu. Um reenvio devolve exatamente este.
    challenge_id      TEXT    NOT NULL REFERENCES challenges (challenge_id) ON DELETE CASCADE,

    created_at        INTEGER NOT NULL,

    PRIMARY KEY (owner_uid, client_request_id)
) STRICT;
