/**
 * O inventário de colunas que nomeiam uma conta (T17.13.1 §22/§23/§24).
 *
 * ## Para que ele existe
 *
 * `AccountDeletionRepository.listAllOwnerUidsInDatabase()` promete encontrar **qualquer** rastro de
 * uma conta num banco restaurado. Essa promessa só vale se a lista de lugares onde procurar estiver
 * completa — e até a T17.13 ela tinha sete origens, de vinte e nove. Um restore parcial que
 * trouxesse de volta apenas o comentário, a reação, o convite de Squad, o compartilhamento ou o
 * dispositivo de push de uma conta excluída passaria pela reconciliação sem ser visto.
 *
 * ## Por que uma lista declarada, e não uma varredura do schema
 *
 * Porque nem toda coluna terminada em `_uid` é o rastro de uma conta, e nenhuma regra sintática
 * distingue os casos: `account_deletion_jobs.firebase_uid` **é** um Firebase UID e não pode entrar
 * na enumeração — ele é a máquina da exclusão, e enumerá-lo faria a reconciliação encontrar como
 * "conta a purgar" a própria conta que está sendo excluída, num laço que se realimenta.
 *
 * Uma lista escrita à mão é o que permite dizer isso. O que impede que ela apodreça é o teste de
 * §24 (`social-v2-audit.spec.ts`), que lê o schema real do banco e exige que **toda** coluna com
 * cara de uid esteja ou aqui ou em [NON_ACCOUNT_UID_COLUMNS], com justificativa. Uma tabela nova
 * com uma coluna nova não passa em silêncio: ela reprova o teste até alguém decidir a política.
 *
 * A geração do SQL a partir daqui é uma linha por entrada — §23 aceita um `UNION` completo para a
 * arquitetura atual e recusa metaprogramação. Isto é o primeiro, e não o segundo.
 */

/** Como a coluna nomeia a conta. */
export type AccountUidRole =
  /** A linha **pertence** à conta. Sai no purge por ser dela. */
  | 'OWNER'
  /** A linha pertence a outra pessoa e **cita** esta conta. Sai no purge por ser uma aresta que não pode sobreviver a uma das pontas. */
  | 'REFERENCE';

export interface AccountUidColumn {
  readonly table: string;
  readonly column: string;
  readonly role: AccountUidRole;
}

/**
 * Toda coluna que carrega um Firebase UID de conta de usuário.
 *
 * A ordem segue a de `purgeAccountData`, para que as duas políticas possam ser lidas lado a lado.
 */
export const ACCOUNT_UID_COLUMNS: readonly AccountUidColumn[] = [
  // 1. Sync (T16.3–T16.7). O núcleo local-first: é o maior volume de dado pessoal do servidor.
  { table: 'sync_entities', column: 'owner_uid', role: 'OWNER' },
  { table: 'sync_changes', column: 'owner_uid', role: 'OWNER' },
  { table: 'sync_mutations', column: 'owner_uid', role: 'OWNER' },

  // 2. Backup estruturado (T16.4/T16.5). `backup_items` não aparece: ela referencia o snapshot,
  //    e não a conta — sai por `snapshot_id`, no purge e no cascade.
  { table: 'backup_snapshots', column: 'owner_uid', role: 'OWNER' },

  // 3. Coach IA (T16.2). Contagem de uso por conta.
  { table: 'ai_usage_daily', column: 'uid', role: 'OWNER' },

  // 4. Notificações (T17.5). `social_notification_deliveries` não aparece pelo mesmo motivo de
  //    `backup_items`: ela referencia o evento e o dispositivo, nunca a conta diretamente.
  { table: 'social_notification_events', column: 'recipient_uid', role: 'OWNER' },
  { table: 'social_push_devices', column: 'owner_uid', role: 'OWNER' },
  { table: 'social_notification_preferences', column: 'owner_uid', role: 'OWNER' },

  // 5. Configurações sociais (T17.0/T17.2).
  { table: 'social_progress_settings', column: 'owner_uid', role: 'OWNER' },
  { table: 'social_privacy_settings', column: 'owner_uid', role: 'OWNER' },

  // 6. Grafo de amizade (T17.1). As duas pontas: uma amizade é uma aresta, e encontrá-la pelo
  //    outro lado é o que permite reconciliar uma conta cujo perfil não sobreviveu ao restore.
  { table: 'friend_requests', column: 'requester_uid', role: 'OWNER' },
  { table: 'friend_requests', column: 'recipient_uid', role: 'REFERENCE' },
  { table: 'friendships', column: 'user_a_uid', role: 'OWNER' },
  { table: 'friendships', column: 'user_b_uid', role: 'REFERENCE' },

  // 7. Bloqueio e denúncia (T17.6).
  { table: 'social_blocks', column: 'blocker_uid', role: 'OWNER' },
  { table: 'social_blocks', column: 'blocked_uid', role: 'REFERENCE' },
  { table: 'social_reports', column: 'reporter_uid', role: 'OWNER' },
  { table: 'social_reports', column: 'reported_uid', role: 'REFERENCE' },

  // 8. Desafios (T17.3). `challenges.creator_uid` faltava na enumeração antiga: um desafio que
  //    sobrevivesse a um restore parcial sem o perfil do criador não seria encontrado, e ficaria
  //    de pé — com participantes reais — sob um criador que já não existe.
  { table: 'challenges', column: 'creator_uid', role: 'OWNER' },
  { table: 'challenge_participants', column: 'participant_uid', role: 'OWNER' },
  { table: 'challenge_invitations', column: 'inviter_uid', role: 'OWNER' },
  { table: 'challenge_invitations', column: 'recipient_uid', role: 'REFERENCE' },
  { table: 'challenge_creation_requests', column: 'owner_uid', role: 'OWNER' },

  // 9. Compartilhamento de treino (T17.7).
  { table: 'workout_shares', column: 'sender_uid', role: 'OWNER' },
  { table: 'workout_shares', column: 'recipient_uid', role: 'REFERENCE' },

  // 10. Check-ins e conteúdo (T17.8/T17.9). Comentário e reação entram pelo autor: eles podem
  //     estar na publicação de **outra** pessoa, e é justamente esse o rastro que sobrevive quando
  //     o resto da conta não sobrevive.
  { table: 'social_workout_checkins', column: 'author_uid', role: 'OWNER' },
  { table: 'social_checkin_media', column: 'owner_uid', role: 'OWNER' },
  { table: 'social_checkin_comments', column: 'author_uid', role: 'OWNER' },
  { table: 'social_checkin_reactions', column: 'reactor_uid', role: 'OWNER' },

  // 11. Squads (T17.11/T17.12).
  { table: 'social_groups', column: 'owner_uid', role: 'OWNER' },
  { table: 'social_group_memberships', column: 'member_uid', role: 'OWNER' },
  { table: 'social_group_invitations', column: 'sender_uid', role: 'OWNER' },
  { table: 'social_group_invitations', column: 'recipient_uid', role: 'REFERENCE' },
  { table: 'social_group_checkin_shares', column: 'author_uid', role: 'OWNER' },

  // 12. Perfil social raiz (T17.0). É a origem do `ON DELETE CASCADE` de tudo acima que é social.
  { table: 'social_profiles', column: 'owner_uid', role: 'OWNER' },
];

/**
 * Colunas com cara de uid que **não** são rastro de conta a reconciliar.
 *
 * Existir aqui é uma decisão registrada, e não um esquecimento: o teste de §24 aceita uma coluna
 * nova apenas se ela estiver nesta lista ou em [ACCOUNT_UID_COLUMNS].
 */
export const NON_ACCOUNT_UID_COLUMNS: readonly {
  readonly table: string;
  readonly column: string;
  readonly why: string;
}[] = [
  {
    table: 'account_deletion_jobs',
    column: 'firebase_uid',
    why:
      'É a máquina da exclusão, e não um rastro da conta. Enumerá-lo faria a reconciliação de DR ' +
      'encontrar como "conta a purgar" exatamente a conta cuja exclusão ainda está em andamento — ' +
      'e o purge apagaria o job que representa o trabalho que falta terminar.',
  },
];

/**
 * O `SELECT ... UNION` que enumera todo uid presente no banco.
 *
 * `UNION` (e não `UNION ALL`) porque o resultado é um conjunto de contas, e a mesma conta aparece
 * em dezenas destas colunas. A ordenação não importa: quem consome itera para comparar hashes.
 */
export function allAccountUidsQuery(): string {
  return ACCOUNT_UID_COLUMNS.map(
    ({ table, column }) => `SELECT DISTINCT ${column} AS owner_uid FROM ${table}`,
  ).join('\n UNION\n');
}
