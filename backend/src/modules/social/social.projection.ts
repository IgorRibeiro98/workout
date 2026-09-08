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
 * ## O que a T17.0 implementa, e o que não
 *
 * Só a fronteira e a política. **Não existe** projeção de treino nesta fase: nem feed, nem XP
 * público, nem sequência pública, nem contagem de treinos, nem "último treino". Implementar uma
 * agora seria antecipar a T17.4 sem os controles que ela vai exigir.
 *
 * O que já é obrigatório e vale desde já está em [SOCIAL_PROJECTION_RULES] — em especial a regra
 * de ownership, que precisa existir **antes** da primeira projeção, não junto com ela.
 */

/**
 * As regras que qualquer projeção social futura terá de obedecer.
 *
 * Escritas como constante, e não só em prosa em um `.md`, porque elas precisam estar visíveis no
 * arquivo que alguém abre para escrever a primeira projeção.
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
   * origem — a projeção nunca lê dado de outro dono.
   */
  'OWNER_SCOPED',

  /**
   * **Consentimento.** `activitySharingEnabled` é consultado antes, via `SocialAccessPolicy`, e
   * o padrão é `false`. Nenhuma projeção publica por padrão.
   */
  'CONSENT_REQUIRED',

  /**
   * **Derivação explícita.** A projeção produz um fato pequeno e escolhido ("treinou hoje"),
   * nunca o agregado bruto. Peso, medidas corporais, cargas, notas, nomes de treino e histórico
   * não têm forma social — não porque estejam escondidos, mas porque nunca são convertidos.
   */
  'DERIVED_NEVER_RAW',

  /**
   * **Sem acesso cruzado.** A projeção não lê `sync_entities`, `backup_items`, `backup_payloads`
   * nem qualquer payload de sync/backup. Se o dado que ela precisa só existe lá, o caminho é uma
   * origem própria — e não abrir a caixa do outro domínio.
   */
  'NO_CROSS_DOMAIN_READ',
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
 * Nenhuma implementação existe na T17.0, e isso é intencional. A interface não é uma promessa de
 * que o feed está quase pronto; é o contrato que ele terá de respeitar quando for decidido.
 */
export interface SocialProjection {
  /** Os fatos publicáveis desta conta, já filtrados por consentimento e ownership. */
  project(ownerUid: string): Promise<readonly SocialProjectedFact[]>;
}
