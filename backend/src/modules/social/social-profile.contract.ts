/**
 * O contrato do perfil social enriquecido (T17.2).
 *
 * O espelho Kotlin é `com.example.data.social.SocialProfileContract`. A descrição legível — fontes
 * canônicas, projeção, privacidade e freshness — vive em
 * `docs/architecture/social-profile-contract.md`.
 *
 * ```text
 * PRIVATE DOMAIN                     (Room do aparelho, sync_entities do servidor)
 *        ↓
 * autoridade canônica de progresso   (quem calcula nível, sequência, semana, conquistas)
 *        ↓
 * SocialProgressProjection           (o que o servidor consegue afirmar, com disponibilidade)
 *        ↓
 * SocialProgressPrivacyFilter        (o que o dono escolheu compartilhar)
 *        ↓
 * SocialFriendProfileDto             (o que um amigo recebe)
 * ```
 *
 * ## O Social projeta progresso; ele nunca vira autoridade de progresso
 *
 * Nenhum tipo aqui é escrito pelo cliente com um valor de progresso dentro. Não existe — e não
 * pode existir — `PATCH /social/me { level }`, `{ streak }` ou `{ weeklyWorkoutCount }`: o
 * servidor não confia no aparelho sobre progresso, porque um cliente modificado diria nível 99. O
 * que o dono envia é **preferência** (o que compartilhar) e o fuso da própria semana; o valor sai
 * sempre da autoridade canônica, pelo `SocialProgressProjector`.
 *
 * ## Ausência não é zero
 *
 * Um campo que o servidor não consegue afirmar **não aparece** no DTO do amigo. Ele não vira `0`,
 * não vira `null` e não vira "nível 1": um perfil que dissesse "0 treinos" para quem treinou seis
 * vezes offline estaria mentindo com a autoridade de um servidor.
 *
 * ## Escondido e indisponível são a mesma coisa para o amigo
 *
 * Para quem olha, os dois resultam em **campo ausente**. Distinguir contaria a terceiros a
 * configuração de privacidade de alguém — "este campo está escondido" é informação sobre a
 * escolha do dono, e ela não é do visitante. O **dono** distingue os dois, na própria tela de
 * configurações, onde a informação é dele (§38).
 */

// --------------------------------------------------------------------------------- disponibilidade

/**
 * O que o servidor consegue afirmar sobre um campo — a pergunta do **dono**, nunca do amigo.
 *
 * Três estados, e os três são necessários porque levam a três frases diferentes na tela de quem
 * configura:
 *
 * - `AVAILABLE` — "Disponível". Há dado canônico agora; ligar o interruptor publica um valor real;
 * - `UNAVAILABLE` — "Ainda não disponível". O campo **é** suportado por esta versão, e o servidor
 *   ainda não tem o que afirmar (a conta nunca sincronizou uma sessão concluída, ou o fuso da
 *   semana ainda não é conhecido). Desde a T19.H5 o **motivo** acompanha o estado
 *   ([SocialAvailabilityReason]) — nem todo `UNAVAILABLE` se resolve sincronizando;
 * - `UNSUPPORTED` — "Não disponível nesta versão". Não existe autoridade **remota** para a
 *   métrica: ela é calculada no aparelho, a partir de dado que nunca sai dele. Sincronizar não
 *   resolve; só uma decisão de arquitetura futura resolve.
 *
 * A diferença entre os dois últimos não é cosmética: `UNAVAILABLE` pede uma ação do usuário
 * ("sincronize"), e `UNSUPPORTED` pediria uma ação que não existe. Colapsá-los faria a tela
 * prometer que sincronizar publicaria o nível.
 */
export const SOCIAL_FIELD_AVAILABILITIES = ['AVAILABLE', 'UNAVAILABLE', 'UNSUPPORTED'] as const;
export type SocialFieldAvailability = (typeof SOCIAL_FIELD_AVAILABILITIES)[number];

/**
 * **Por que** um campo está `UNAVAILABLE` — a pergunta do dono que a T19.H5 passou a responder.
 *
 * Até a T19.H3 a tela dizia "Ainda não disponível" para tudo, e "sincronize" não resolvia metade
 * dos casos. Cada motivo aqui corresponde a uma condição **real** da projeção, com um produtor em
 * `social-progress.source.ts` e um teste que o alcança. Na ordem de precedência — quando mais de
 * um vale para o mesmo campo, o primeiro da lista é o que o dono lê:
 *
 * - `WEEK_TIME_ZONE_MISSING` — o servidor não conhece o fuso da semana do dono. É o primeiro elo do
 *   caminho (sem ele não há semana), e o app o declara sozinho ao abrir "Compartilhar progresso"
 *   com conexão. Treinos totais não dependem dele;
 * - `NO_SYNCED_WORKOUTS` — nenhuma `WORKOUT_SESSION` `COMPLETED` desta conta chegou ao servidor.
 *   Sincronizar resolve quando há treino no aparelho; o app oferece "Sincronizar dados";
 * - `CONSISTENCY_PARAMETERS_MISSING` — nível e sequência precisam da meta semanal e do início do
 *   acompanhamento (T19.2A). O app os declara sozinho, também ao abrir a tela;
 * - `SOURCE_LIMIT_REACHED` — a semana tem mais sessões do que a leitura bounded das estatísticas
 *   aceita (`MAX_WEEKLY_SESSIONS_FOR_TRAINING_STATS`). Publicar uma soma truncada seria pior.
 *
 * `UNSUPPORTED` não tem motivo: ele já é o motivo ("esta versão não sabe calcular").
 *
 * **Só o dono** recebe motivo. O amigo recebe campo presente ou ausente, e nada que diga por quê.
 */
export const SOCIAL_AVAILABILITY_REASONS = [
  'WEEK_TIME_ZONE_MISSING',
  'NO_SYNCED_WORKOUTS',
  'CONSISTENCY_PARAMETERS_MISSING',
  'SOURCE_LIMIT_REACHED',
] as const;
export type SocialAvailabilityReason = (typeof SOCIAL_AVAILABILITY_REASONS)[number];

/**
 * A versão do contrato de `GET`/`PATCH /v1/social/me/progress-sharing` (T19.H5).
 *
 * ```text
 * (ausente)  v1  T17.2 + T19.2 — os quatro interruptores de progresso geral
 *            v2  T19.H3 — estatísticas de treino e detalhes dos check-ins (onze interruptores);
 *                desde a T19.H5, também `availabilityReasons`
 * ```
 *
 * Existe porque um campo ausente na resposta não diz **por que** está ausente: um app novo contra
 * um servidor anterior à T19.H3 lia a falta de `totalWorkouts` como "Ainda não disponível", e o
 * `PATCH` de um interruptor que aquele servidor não conhecia voltava `400`. Com a versão
 * declarada, o app distingue "o servidor não tem o dado" de "o servidor não conhece o recurso".
 *
 * Sobe **somente** quando o contrato ganha recurso que um app precisa saber se existe antes de
 * oferecê-lo. Campo novo e opcional numa resposta não sobe a versão: todo APK publicado lê com
 * `ignoreUnknownKeys`.
 */
export const PROGRESS_SHARING_CONTRACT_VERSION = 2;

/** A disponibilidade de cada campo do perfil, como o **dono** a vê. */
export interface SocialProgressAvailabilityDto {
  readonly level: SocialFieldAvailability;
  readonly consistencyStreak: SocialFieldAvailability;
  readonly weeklyWorkoutCount: SocialFieldAvailability;
  readonly highlightedAchievements: SocialFieldAvailability;
  // ---- T19.H3 — estatísticas de treino. Os detalhes de check-in não têm disponibilidade aqui:
  // eles dependem de cada publicação, e não do perfil.
  readonly weeklyTrainingMinutes: SocialFieldAvailability;
  readonly weeklyCompletedSets: SocialFieldAvailability;
  readonly weeklyVolume: SocialFieldAvailability;
  readonly totalWorkouts: SocialFieldAvailability;
}

/**
 * O motivo de cada campo que **não** está `AVAILABLE` nem `UNSUPPORTED` (T19.H5).
 *
 * Um mapa ao lado de [SocialProgressAvailabilityDto], e não um objeto `{ status, reason }` dentro
 * dele: `availability.level` continua sendo a string que todo APK publicado lê — trocar a forma
 * quebraria a leitura inteira da tela nos aparelhos antigos. O estado mora num lugar só; o motivo,
 * em outro, e só existe para quem não está disponível.
 */
export type SocialProgressAvailabilityReasonsDto = {
  readonly [K in keyof SocialProgressAvailabilityDto]?: SocialAvailabilityReason;
};

// --------------------------------------------------------------------------------- preferências

/**
 * O que o dono decidiu compartilhar.
 *
 * Todos nascem `false` (§13/§14; T19.H3 §24). Ativar o Social não publica progresso, e subir uma
 * versão tampouco: um default `true` transformaria um deploy em uma publicação que ninguém
 * escolheu — a migration 0008 acrescentou onze interruptores, e cada linha antiga os recebeu
 * desligados.
 *
 * `weekTimeZone` não é privacidade — é o parâmetro que torna a semana canônica reproduzível no
 * servidor. Ele fica aqui, e não em `social_privacy_settings`, porque só a contagem semanal o usa
 * e porque ele não responde "o que os outros podem ver".
 */
export interface SocialProgressSettingsDto {
  // ---- Progresso geral (T17.2/T19.2)
  readonly shareLevel: boolean;
  readonly shareConsistencyStreak: boolean;
  readonly shareWeeklyWorkoutCount: boolean;
  readonly shareHighlightedAchievements: boolean;
  // ---- Estatísticas de treino (T19.H3) — no perfil, agregadas
  readonly shareWeeklyTrainingMinutes: boolean;
  readonly shareWeeklyCompletedSets: boolean;
  readonly shareWeeklyVolume: boolean;
  readonly shareTotalWorkouts: boolean;
  // ---- Detalhes dos check-ins (T19.H3) — em cada publicação, lidos da sessão de origem
  readonly shareWorkoutName: boolean;
  readonly shareWorkoutTime: boolean;
  readonly shareWorkoutDuration: boolean;
  readonly shareWorkoutExercises: boolean;
  readonly shareWorkoutSets: boolean;
  /** Só tem efeito com `shareWorkoutSets` **e** `shareWorkoutExercises` — a carga mora na série. */
  readonly shareWorkoutWeights: boolean;
  readonly shareWorkoutVolume: boolean;
  /** Fuso IANA do dono, ou `null` enquanto ele não for conhecido. */
  readonly weekTimeZone: string | null;
  /**
   * Os parâmetros de consistência que o dono declarou (T19.2A), ou `null` enquanto o app não os
   * enviar. São **configuração** — a mesma que o aparelho lê para calcular a própria sequência —,
   * e voltam para o dono para que o app saiba quando reenviá-los. Nunca chegam a um amigo.
   */
  readonly consistency: SocialConsistencyParametersDto | null;
  /** Relógio do servidor, epoch millis UTC (§58). */
  readonly updatedAt: number;
}

/**
 * Meta semanal por semana e início do acompanhamento — os dois insumos de
 * `ConsistencyCalculator` que não são sessão (T19.2A §6.1).
 *
 * `weekTimeZone` tornou a **semana** reproduzível no servidor; isto torna a **sequência**
 * reproduzível. Como o fuso, não é progresso: um cliente que declare meta 1 desde 2020 continua
 * com sequência zero enquanto não sincronizar treino nenhum. O que é recusado por nome é o
 * resultado (`streak`, `level`, `xp`...), não o parâmetro.
 */
export interface SocialConsistencyParametersDto {
  /** Epoch day do calendário local em que o acompanhamento começou. */
  readonly trackingStartedAtEpochDay: number;
  /** A meta vigente a partir de cada segunda-feira (`weekly_goal_history`). */
  readonly weeklyGoals: readonly SocialWeeklyGoalDto[];
}

export interface SocialWeeklyGoalDto {
  /** Epoch day de uma segunda-feira. */
  readonly weekStartEpochDay: number;
  /** Treinos por semana, 1..7 — o intervalo da tela de meta do app. */
  readonly goal: number;
}

/**
 * `GET`/`PATCH /v1/social/me/progress-sharing`.
 *
 * Preferência **e** disponibilidade na mesma resposta, porque a tela precisa das duas juntas para
 * dizer a frase certa: "Nível — ligado, ainda não disponível" é um estado real, e nenhuma das
 * duas metades sozinha o descreve.
 *
 * `contractVersion` e `availabilityReasons` (T19.H5) são do **dono**, como o resto desta resposta:
 * nenhum dos dois aparece no perfil que um amigo recebe.
 */
export interface SocialProgressSharingResponse {
  /** [PROGRESS_SHARING_CONTRACT_VERSION]. Ausente = servidor anterior à T19.H5 (tratado como v1). */
  readonly contractVersion: number;
  readonly settings: SocialProgressSettingsDto;
  readonly availability: SocialProgressAvailabilityDto;
  readonly availabilityReasons: SocialProgressAvailabilityReasonsDto;
}

// --------------------------------------------------------------------------------- perfil

/**
 * O progresso **já filtrado** que um amigo recebe.
 *
 * Todo campo é opcional, e a ausência é a única forma de "não". Não existe `level: null`, não
 * existe `levelHidden: true` e não existe `visibility` aqui: qualquer um dos três contaria ao
 * visitante o que o dono escolheu, que é informação do dono (§36/§37).
 */
export interface SocialSharedProgressDto {
  readonly level?: number;
  /** Sequência **semanal** de consistência — a semântica canônica do Spark (§20/§21). */
  readonly consistencyStreak?: number;
  readonly weeklyWorkoutCount?: number;
  readonly highlightedAchievementIds?: readonly string[];
  // ---- T19.H3 — estatísticas de treino, derivadas da `WORKOUT_SESSION` canônica.
  /** Minutos de treino da semana canônica (`Σ fim − início`). */
  readonly weeklyTrainingMinutes?: number;
  /** Séries de trabalho concluídas na semana canônica (aquecimento não conta). */
  readonly weeklyCompletedSets?: number;
  /** `Σ peso × reps` da semana canônica, uma casa decimal — mesma conta do check-in. */
  readonly weeklyVolumeKg?: number;
  /** Treinos concluídos desde sempre. */
  readonly totalWorkouts?: number;
}

/**
 * O perfil social de outra pessoa, como um amigo o vê.
 *
 * `socialId` e `displayName` continuam sendo a identidade pública da T17.0/T17.1 — e `friendCode`
 * **não** está aqui (§16): depois que o código cumpriu a função de descoberta, quem identifica é o
 * `socialId`. Uma lista de amigos que carregasse o código de convite de todo mundo seria uma lista
 * redistribuível que ninguém escolheu publicar.
 *
 * O que ele nunca pode ganhar: `ownerUid`, Firebase UID, e-mail, `friendCode`, flags de
 * privacidade, `lastSyncAt`, presença, nota, medida corporal, PR, RPE, RIR, payload de sync e
 * payload de backup. Há teste que varre a resposta real procurando cada um deles.
 *
 * Detalhe de **uma** sessão — nome do treino, horário, exercício, carga — também não mora aqui: ele
 * pertence ao check-in (`WorkoutCheckInDto.workoutSummary`, T19.H3 §22), e o perfil só publica
 * agregados. Misturar os dois faria o perfil virar "o último treino de alguém" sem que ninguém
 * tivesse publicado aquele treino.
 */
export interface SocialFriendProfileDto {
  readonly socialId: string;
  readonly displayName: string;
  /**
   * Sempre presente, possivelmente **vazio**.
   *
   * Vazio significa "esta pessoa não compartilha nada agora" — e é o mesmo objeto vazio para quem
   * desligou tudo e para quem ligou tudo sem ter dado sincronizado. A tela diz "ainda não
   * compartilha informações de progresso", nunca "não treina" (§95).
   */
  readonly sharedProgress: SocialSharedProgressDto;
}

/** `GET /v1/social/friends/:socialId/profile` e `GET /v1/social/me/profile-preview`. */
export interface SocialFriendProfileResponse {
  readonly profile: SocialFriendProfileDto;
}

// --------------------------------------------------------------------------------- erros

export const SOCIAL_PROFILE_ERROR_CODES = {
  /**
   * O perfil enriquecido não é alcançável.
   *
   * **A mesma** resposta para quatro situações (§120): `socialId` inexistente, perfil do alvo
   * desativado, vocês não são amigos e existe apenas um pedido pendente. Distinguir qualquer uma
   * delas transformaria a rota num oráculo — uma conta C que conhecesse o `socialId` de B
   * aprenderia, comparando respostas, que B existe, que B desativou o social ou que B tem um
   * pedido pendente dela. Nenhuma das três é informação de C.
   */
  FRIEND_PROFILE_NOT_FOUND: 'FRIEND_PROFILE_NOT_FOUND',

  /** Corpo, campo ou valor fora do contrato em `PATCH /v1/social/me/progress-sharing`. */
  INVALID_PROGRESS_SETTINGS: 'INVALID_PROGRESS_SETTINGS',
} as const;

export type SocialProfileErrorCode =
  (typeof SOCIAL_PROFILE_ERROR_CODES)[keyof typeof SOCIAL_PROFILE_ERROR_CODES];

// --------------------------------------------------------------------------------- rotas

/** `GET` — o que **eu** compartilho e o que está disponível. */
export const PROGRESS_SHARING_ROUTE = 'social/me/progress-sharing';

/**
 * `GET` — exatamente o que um amigo veria de mim agora (§40).
 *
 * Ela existe para não haver duas lógicas de perfil. O preview e o perfil do amigo passam pelo
 * **mesmo** projetor e pelo **mesmo** filtro de privacidade; a única diferença é quem é o alvo, e
 * ela é resolvida antes do pipeline. Uma "lógica de preview" separada divergiria da real no
 * primeiro campo novo, e a divergência apareceria como "a prévia mostra o que o meu amigo não vê".
 */
export const PROFILE_PREVIEW_ROUTE = 'social/me/profile-preview';

/**
 * `GET /v1/social/friends/:socialId/profile`.
 *
 * O `socialId` vai no caminho, e não no corpo como o `friendCode` da T17.1. A razão da T17.1 era
 * específica do código de convite: um log de acesso com `friendCode` em texto claro é uma lista de
 * convites válidos. `socialId` é um UUID opaco que não convida a nada — e, além disso, o log de
 * acesso deste servidor registra o **padrão** da rota (`/friends/:socialId/profile`), nunca o
 * valor. É a mesma escolha já feita para `requestId` na T17.1.
 */
export const FRIEND_PROFILE_ROUTE_SUFFIX = 'profile';
