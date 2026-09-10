import type { PoolClient } from 'pg';

/**
 * A mutação foi bloqueada porque a conta já tem tombstone (T18.1.1 §2).
 *
 * Distinta de qualquer erro de validação: a requisição estava, por hipótese, correta — só chegou
 * tarde demais. Quem chama traduz isto para a mesma resposta que o `BearerAuthGuard` já dá a uma
 * requisição nova contra uma conta excluída (`403 ACCOUNT_DELETED`).
 */
export class AccountMutationFencedError extends Error {
  constructor() {
    super('a conta foi excluída; a mutação foi recusada pelo fence de conta');
    this.name = 'AccountMutationFencedError';
  }
}

/**
 * O **Account Mutation Fence** (T18.1.1 §2) — a barreira que impede uma escrita account-scoped já
 * em voo de persistir depois que a exclusão da mesma conta commitou.
 *
 * ## O problema que ele resolve
 *
 * `BearerAuthGuard` recusa uma requisição **nova** contra uma conta com tombstone. Ele não pode
 * fazer nada por uma requisição que **já passou** pelo guard e está no meio de um caminho de
 * escrita — upload no Object Storage, validação, o que for — quando `DELETE /v1/account` grava o
 * tombstone e purga o PostgreSQL. Sem uma segunda barreira **dentro da transação de escrita**, essa
 * requisição atrasada pode confirmar depois do purge, e a conta "excluída" volta a ter uma linha.
 *
 * ## O mecanismo: o mesmo já usado no projeto, generalizado por conta
 *
 * `pg_advisory_xact_lock(hashtext(a), hashtext(b))` — a mesma convenção de
 * `lockRelationshipPair` (par social, T18.0.1) e do lock de `ai_usage_daily` (T16.2): dois
 * argumentos hasheados, adquirido **dentro** da transação, liberado sozinho no COMMIT/ROLLBACK.
 * Aqui a chave é `(ACCOUNT_MUTATION_FENCE_NAMESPACE, ownerUid)` — um namespace fixo mais a conta —,
 * para nunca colidir com os outros dois usos do mesmo mecanismo.
 *
 * `AccountDeletionRepository.beginAccountDeletion` adquire o **mesmo** lock antes de gravar o
 * tombstone. As duas pontas disputam o mesmo lock:
 *
 * ```text
 * escrita account-scoped                    exclusão de conta
 * ─────────────────────                     ──────────────────
 * BEGIN
 * lockAccountMutationFence(uid)  ◀──────────▶  lockAccountMutationFence(uid)
 * assertAccountMutable(uid)                    INSERT tombstone
 * INSERT/UPDATE ...                            DELETE ... (purge)
 * COMMIT ou lança                              COMMIT
 * ```
 *
 * Qualquer ordem de chegada serializa no lock; quem entra depois **relê o tombstone dentro da
 * transação** antes de escrever — nunca decide sobre uma leitura anterior ao lock. É o mesmo
 * princípio que `lockRelationshipPair` já documenta: "uma decisão tomada sobre uma leitura anterior
 * ao lock é uma decisão sobre um estado que outra transação pode já ter mudado".
 *
 * ## Onde ele é obrigatório
 *
 * Todo caminho de escrita **novo** account-scoped cuja persistência sobrevivendo à exclusão seria
 * visível ou reativável: criação de backup (`BackupRepository.insert`), push de sync
 * (`SyncRepository.executeAtomicMutation`), upload de mídia social
 * (`SocialMediaRepository.create`) e criação de check-in (`WorkoutCheckInRepository.create`, via
 * `WorkoutCheckInService.insertOrResolveRace`). Não é necessário em toda mutação social (reação,
 * comentário, amizade) nem em `ai_usage_daily`: nenhuma delas resiste à exclusão de um jeito que
 * importe — o purge as alcança de qualquer forma, e elas não guardam conteúdo capaz de "ressuscitar"
 * a conta aos olhos de outra pessoa. Ver a auditoria da T18.1.1 no relatório final.
 */
const ACCOUNT_MUTATION_FENCE_NAMESPACE = 'account_mutation_fence';

/**
 * Adquire o lock transacional da conta. Deve ser a **primeira** instrução dentro da transação da
 * escrita — antes de qualquer lock mais específico (o de backup, o de mutação de sync) — para que
 * a ordem de aquisição nunca dependa de qual caminho chegou primeiro.
 */
export async function lockAccountMutationFence(
  client: PoolClient,
  ownerUid: string,
): Promise<void> {
  await client.query('SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))', [
    ACCOUNT_MUTATION_FENCE_NAMESPACE,
    ownerUid,
  ]);
}

/**
 * Relê o tombstone **dentro** da transação, sob o lock. Lança [AccountMutationFencedError] quando
 * a conta já foi excluída — a única resposta correta é abortar a escrita, nunca prosseguir.
 */
export async function assertAccountMutable(client: PoolClient, uidHash: string): Promise<void> {
  const res = await client.query(
    'SELECT 1 FROM account_deletion_tombstones WHERE uid_hash = $1 LIMIT 1',
    [uidHash],
  );
  if ((res.rowCount ?? 0) > 0) {
    throw new AccountMutationFencedError();
  }
}

/**
 * O fence completo: lock, depois releitura do tombstone. Chame isto como a primeira ação dentro de
 * toda transação de escrita account-scoped nova.
 */
export async function fenceAccountMutation(
  client: PoolClient,
  ownerUid: string,
  uidHash: string,
): Promise<void> {
  await lockAccountMutationFence(client, ownerUid);
  await assertAccountMutable(client, uidHash);
}
