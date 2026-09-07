import type { SyncEntityType } from './sync.contract';

/**
 * A política de sincronização de cada agregado (T16.7).
 *
 * ## Por que um registry, e não `if (entityType === ...)`
 *
 * "Uma sessão concluída pode ser editada?" e "um check-in pode ser excluído remotamente?" são
 * perguntas de **domínio**, e elas aparecem em pelo menos quatro lugares do servidor: aplicar um
 * `UPSERT`, aplicar um `DELETE`, classificar um conflito e recusar uma operação que este servidor
 * não deve aceitar de cliente nenhum. Espalhadas como condicionais, elas divergem — e a quinta
 * cópia é a que trata histórico como documento editável.
 *
 * Aqui a resposta é uma tabela, e o espelho Kotlin é `com.example.data.sync.SyncEntityPolicies`.
 * Os dois lados precisam concordar: o servidor é quem **recusa**, o Android é quem **oferece a
 * escolha**, e uma divergência entre eles produziria um botão que sempre falha.
 *
 * ## Não existe política padrão
 *
 * `POLICIES` é um `Record` completo de [SyncEntityType]: acrescentar um agregado ao sync sem
 * declarar a política dele não compila. Um `default` aqui seria exatamente o "genérico" que a
 * T16.7 proíbe — e o genérico que dá menos trabalho é sempre *last write wins*.
 */

/** Como o conteúdo do agregado evolui. */
export type SyncMutabilityPolicy =
  /** O agregado é um plano/registro editável: `UPSERT` avança a `revision`. */
  | 'MUTABLE_SNAPSHOT'
  /** O agregado registra o que aconteceu. `revision = 1` e nunca mais. */
  | 'IMMUTABLE_HISTORY';

/**
 * O que fazer quando as duas cópias divergem.
 *
 * O servidor **nunca** executa nenhuma destas: ele detecta e recusa. Elas descrevem o que o
 * Android pode oferecer, e existem aqui para que os dois lados não tenham opiniões diferentes.
 */
export type SyncConflictStrategy =
  /** O usuário escolhe entre a versão local e a da nuvem. Nada é decidido sozinho. */
  | 'USER_CHOICE'
  /**
   * Divergência é defeito de integridade, não edição concorrente.
   *
   * Não se oferece "manter local"/"usar remoto" para histórico concluído: as duas versões
   * afirmam ter registrado o mesmo treino de formas diferentes, e sobrescrever uma delas
   * apagaria um fato.
   */
  | 'IMMUTABLE_CONFLICT'
  /**
   * O último a chegar vence, decidido pela **ordem do servidor** (`revision`), nunca por relógio.
   *
   * Existe como valor declarável e **nenhum agregado do Spark o usa hoje** — há teste sobre isso.
   * Ele só faria sentido para uma preferência escalar de aparelho, e preferências ainda não
   * participam do sync incremental. Ele nunca é padrão: quem quiser usá-lo precisa escrevê-lo
   * aqui, para um tipo específico, com o motivo de domínio junto.
   */
  | 'LAST_WRITE_WINS_ALLOWED';

export interface SyncEntityPolicy {
  readonly mutability: SyncMutabilityPolicy;
  /** `false` significa: este servidor não aceita `DELETE` deste tipo, de cliente nenhum. */
  readonly deleteAllowed: boolean;
  readonly conflictStrategy: SyncConflictStrategy;
}

const POLICIES: Record<SyncEntityType, SyncEntityPolicy> = {
  WORKOUT_PROGRAM: {
    mutability: 'MUTABLE_SNAPSHOT',
    deleteAllowed: true,
    conflictStrategy: 'USER_CHOICE',
  },
  WORKOUT_TEMPLATE: {
    mutability: 'MUTABLE_SNAPSHOT',
    deleteAllowed: true,
    conflictStrategy: 'USER_CHOICE',
  },
  CUSTOM_EXERCISE: {
    mutability: 'MUTABLE_SNAPSHOT',
    deleteAllowed: true,
    conflictStrategy: 'USER_CHOICE',
  },
  /**
   * Cada medida tem `syncId` próprio, então duas medidas criadas no mesmo dia em aparelhos
   * diferentes **coexistem** — não são conflito, e transformá-las em um seria inventar
   * divergência onde há dois fatos. O que é conflito é editar a **mesma** medida nos dois
   * aparelhos, e aí quem escolhe é o usuário.
   */
  BODY_MEASUREMENT: {
    mutability: 'MUTABLE_SNAPSHOT',
    deleteAllowed: true,
    conflictStrategy: 'USER_CHOICE',
  },
  /**
   * O Spark não tem caminho de exclusão de check-in: nenhuma tela, nenhum repositório e nenhuma
   * mutação de domínio o produz. Um `DELETE` deste tipo só poderia vir de um cliente defeituoso
   * ou hostil, e o servidor o recusa em vez de criar um tombstone para algo que o domínio não
   * sabe apagar.
   */
  CHECK_IN: {
    mutability: 'MUTABLE_SNAPSHOT',
    deleteAllowed: false,
    conflictStrategy: 'USER_CHOICE',
  },
  /**
   * Uma `WorkoutSession` só entra no sync quando `COMPLETED`. Depois disso ela é histórico:
   * conteúdo divergente é conflito de integridade, nunca `revision++`.
   *
   * Excluir, porém, **é** permitido — apagar o próprio histórico é direito do usuário, e apagar
   * não é reescrever. O tombstone preserva a diferença: a sessão não passa a contar outra
   * história, ela deixa de existir.
   */
  WORKOUT_SESSION: {
    mutability: 'IMMUTABLE_HISTORY',
    deleteAllowed: true,
    conflictStrategy: 'IMMUTABLE_CONFLICT',
  },
};

export const SyncEntityPolicyRegistry = {
  policyFor(entityType: SyncEntityType): SyncEntityPolicy {
    return POLICIES[entityType];
  },

  isImmutableHistory(entityType: SyncEntityType): boolean {
    return POLICIES[entityType].mutability === 'IMMUTABLE_HISTORY';
  },

  isDeleteAllowed(entityType: SyncEntityType): boolean {
    return POLICIES[entityType].deleteAllowed;
  },

  /** Todas as políticas, para diagnóstico e teste. Somente leitura. */
  all(): Readonly<Record<SyncEntityType, SyncEntityPolicy>> {
    return POLICIES;
  },
} as const;
