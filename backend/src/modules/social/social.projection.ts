/**
 * A fronteira entre o domínio privado e o domínio social (T17.0 §29–§31).
 *
 * ## O problema que ela existe para impedir
 *
 * O caminho mais curto para "mostrar que o Igor treinou hoje" é um endpoint social lendo
 * `backup_items`, o payload de um push de sync ou a tabela de sessões. Ele funciona no primeiro
 * dia e é irreversível no segundo: a partir daí, um campo novo no snapshot de treino vira campo
 * novo na superfície social sem que ninguém decida isso.
 *
 * ```text
 * PROIBIDO                                    OBRIGATÓRIO
 * endpoint social                             WorkoutSession (privado)
 *   → SELECT payload de sync/backup                 ↓
 *   → "treinou hoje"                          SocialProjection
 *                                                   ↓
 *                                             "treinou hoje"
 * ```
 *
 * A projeção é o lugar onde alguém **escolheu**, campo a campo, o que vira informação social. Tudo
 * o que não passar por ela não é social — é dado privado sendo exibido.
 *
 * ## O estado hoje
 *
 * A T17.0 criou a fronteira e a política, sem nenhuma projeção. A **T17.2** escreveu a primeira:
 * `SocialProgressProjector`, com `SocialProgressSource` como adapter e
 * `SocialProgressPrivacyFilter` como último passo. Ela projeta **perfil**, e nada mais — não há
 * feed, atividade recente, ranking nem "último treino" (T17.4), e a projeção de perfil
 * **não é autoridade de pontuação de desafio** (T17.3, ver
 * `docs/architecture/social-profile-contract.md`).
 *
 * As regras que ela obedece — e que qualquer projeção futura obedece — estão em
 * [SOCIAL_PROJECTION_RULES], com a nota sobre o que a T17.2 mudou e por quê.
 */

/**
 * As regras que **toda** projeção social obedece.
 *
 * Escritas como constante, e não só em prosa em um `.md`, porque elas precisam estar visíveis no
 * arquivo que alguém abre para escrever a próxima projeção.
 *
 * ## O que a T17.2 mudou aqui, e por quê
 *
 * A T17.0 declarou `NO_CROSS_DOMAIN_READ` — "a projeção não lê `sync_entities`" — quando **nenhuma**
 * projeção existia. Era a regra certa para aquele dia: sem ela, a primeira projeção nasceria como
 * um endpoint social fazendo `SELECT payload`.
 *
 * A T17.2 escreveu a primeira projeção e descobriu que a regra, na forma absoluta, proibia também
 * o desenho correto. O que precisa continuar proibido é **a superfície**: o domínio social lendo
 * payload de treino, devolvendo agregado bruto, ou virando um caminho alternativo para o dado
 * privado. O que precisa ser permitido é um **adapter estreito** que responde uma pergunta
 * agregada sobre estado canônico já sincronizado — `SocialProgressSource`, com um escalar por
 * método e `COUNT(*)` por baixo.
 *
 * A regra foi então dividida em duas mais precisas, e uma terceira foi acrescentada:
 *
 * ```text
 * T17.0                       T17.2
 * NO_CROSS_DOMAIN_READ   ──▶  NO_BACKUP_READ        (o absoluto que continua absoluto)
 *                             AGGREGATE_ONLY        (o que substitui o absoluto que caiu)
 *                             SINGLE_AUTHORITY      (a regra que a T17.2 precisou declarar)
 * ```
 *
 * Isso é um afrouxamento **declarado**, com o substituto no lugar. O que não pode acontecer é a
 * regra ser contornada em silêncio por um import — e os imports proibidos continuam testados.
 */
export const SOCIAL_PROJECTION_RULES = [
  /**
   * **Ownership.** Uma projeção só pode usar dado que pertence ao **mesmo** `ownerUid` do
   * `SocialProfile` que ela descreve.
   *
   * O cenário que isso impede é real e já é possível hoje: o dataset local pode estar vinculado
   * à conta A (`cloud_data_binding`, T16.4) enquanto a sessão do Firebase é a conta B. Sem esta
   * regra, o progresso de A seria publicado como sendo de B. A T16.7.1 resolveu o mesmo problema
   * do lado da escrita revalidando a conta depois da resposta remota; aqui ele é resolvido na
   * origem — a projeção nunca lê dado de outro dono, e o `owner_uid` do alvo é resolvido
   * server-side, nunca recebido do cliente.
   */
  'OWNER_SCOPED',

  /**
   * **Consentimento.** Nenhum campo é publicado sem o dono ter ligado o interruptor
   * correspondente, e todos nascem desligados. Na T17.2 isso é `social_progress_settings`,
   * aplicado pelo `SocialProgressPrivacyFilter` **no servidor** — nunca no Compose.
   */
  'CONSENT_REQUIRED',

  /**
   * **Derivação explícita.** A projeção produz um fato pequeno e escolhido ("3 treinos esta
   * semana"), nunca o agregado bruto. Peso, medidas corporais, cargas, notas, nomes de treino,
   * horários e histórico não têm forma social — não porque estejam escondidos, mas porque nunca
   * são convertidos.
   */
  'DERIVED_NEVER_RAW',

  /**
   * **Backup nunca.** `backup_snapshots`, `backup_items` e `backup_payloads` não são fonte de
   * projeção social, em nenhuma hipótese.
   *
   * Um snapshot é a conta **inteira** em um documento: peso, medidas, notas, cargas, tudo. Abrir
   * essa caixa para responder "quantos treinos esta semana" seria dar ao domínio social acesso a
   * um agregado cujo conteúdo cresce por decisões que nada têm a ver com o social — no dia em que
   * o backup ganhasse um campo novo, ele estaria ao alcance de uma projeção sem que ninguém
   * decidisse isso.
   */
  'NO_BACKUP_READ',

  /**
   * **Só agregado sai.** O adapter devolve escalares — uma contagem, um nível, uma lista de
   * identificadores canônicos —, nunca payload, linha, sessão, série ou timestamp de treino.
   *
   * É esta regra que substitui a proibição absoluta de ler estado sincronizado. Ler
   * `SELECT COUNT(*) ... WHERE owner_uid = ?` é responder uma pergunta; `SELECT payload` seria
   * abrir uma porta. A diferença é verificável, e é testada.
   */
  'AGGREGATE_ONLY',

  /**
   * **Uma autoridade por métrica.** A projeção nunca recalcula uma regra de domínio que já existe
   * em outro lugar do Spark.
   *
   * Ou ela lê o estado canônico, ou faz a derivação que o **próprio** contrato canônico define
   * (contar sessões `COMPLETED` na semana canônica), ou responde que não sabe. O que ela não pode
   * fazer é reimplementar `XpCalculatorService`, `ConsistencyCalculator` ou `AchievementEvaluator`
   * "só para o Social funcionar": duas implementações da mesma regra divergem no primeiro ajuste,
   * e a divergência aparece como um perfil social afirmando um nível que o aparelho da própria
   * pessoa não reconhece.
   *
   * A consequência prática, na T17.2: nível, sequência e conquistas respondem `UNSUPPORTED`,
   * porque a autoridade delas é `DERIVED` e não sai do aparelho.
   */
  'SINGLE_AUTHORITY',
] as const;

export type SocialProjectionRule = (typeof SOCIAL_PROJECTION_RULES)[number];

/**
 * Um fato social derivado do domínio privado.
 *
 * A forma é deliberadamente pobre: um tipo, um instante e nada mais. Uma projeção que precisasse
 * de `payload: unknown` já seria a fuga que este arquivo existe para fechar.
 */
export interface SocialProjectedFact {
  readonly kind: string;
  /** Relógio do servidor, epoch millis UTC. */
  readonly at: number;
}

/**
 * A fronteira que qualquer projeção futura implementa.
 *
 * `ownerUid` é parâmetro **e** é a regra: a implementação usa esse uid — o do perfil social — para
 * ler dado, e não o dono de um dataset qualquer que esteja por perto.
 *
 * A T17.2 implementou a primeira projeção com uma forma própria e mais rica
 * ([SocialProgressProjection], em `social-progress.projector.ts`): um perfil não é uma lista de
 * fatos datados, e forçá-lo neste tipo teria produzido `kind: 'level'` com o valor perdido em
 * algum campo genérico. Este contrato continua descrevendo a forma **de atividade** — o feed da
 * T17.4 —, que é a que realmente é uma sequência de fatos com instante.
 */
export interface SocialProjection {
  /** Os fatos publicáveis desta conta, já filtrados por consentimento e ownership. */
  project(ownerUid: string): Promise<readonly SocialProjectedFact[]>;
}
