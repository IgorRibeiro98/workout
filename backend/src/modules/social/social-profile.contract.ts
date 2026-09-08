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
 *   semana ainda não é conhecido). Sincronizar resolve;
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

/** A disponibilidade de cada campo do perfil, como o **dono** a vê. */
export interface SocialProgressAvailabilityDto {
  readonly level: SocialFieldAvailability;
  readonly consistencyStreak: SocialFieldAvailability;
  readonly weeklyWorkoutCount: SocialFieldAvailability;
  readonly highlightedAchievements: SocialFieldAvailability;
}

// --------------------------------------------------------------------------------- preferências

/**
 * O que o dono decidiu compartilhar.
 *
 * Os quatro nascem `false` (§13/§14). Ativar o Social não publica progresso, e subir esta versão
 * tampouco: um default `true` transformaria um deploy em uma publicação que ninguém escolheu.
 *
 * `weekTimeZone` não é privacidade — é o parâmetro que torna a semana canônica reproduzível no
 * servidor. Ele fica aqui, e não em `social_privacy_settings`, porque só a contagem semanal o usa
 * e porque ele não responde "o que os outros podem ver".
 */
export interface SocialProgressSettingsDto {
  readonly shareLevel: boolean;
  readonly shareConsistencyStreak: boolean;
  readonly shareWeeklyWorkoutCount: boolean;
  readonly shareHighlightedAchievements: boolean;
  /** Fuso IANA do dono, ou `null` enquanto ele não for conhecido. */
  readonly weekTimeZone: string | null;
  /** Relógio do servidor, epoch millis UTC (§58). */
  readonly updatedAt: number;
}

/**
 * `GET`/`PATCH /v1/social/me/progress-sharing`.
 *
 * Preferência **e** disponibilidade na mesma resposta, porque a tela precisa das duas juntas para
 * dizer a frase certa: "Nível — ligado, ainda não disponível" é um estado real, e nenhuma das
 * duas metades sozinha o descreve.
 */
export interface SocialProgressSharingResponse {
  readonly settings: SocialProgressSettingsDto;
  readonly availability: SocialProgressAvailabilityDto;
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
 * privacidade, `lastSyncAt`, presença, horário de treino, nome de treino, exercício, carga, nota,
 * medida corporal, PR, payload de sync e payload de backup. Há teste que varre a resposta real
 * procurando cada um deles.
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
