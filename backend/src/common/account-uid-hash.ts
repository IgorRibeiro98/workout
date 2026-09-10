import { createHmac } from 'node:crypto';
import type { AppConfig } from '../config/app-config';

/**
 * O HMAC-SHA256 irreversível de um Firebase UID (T17.13.1), a chave de
 * `account_deletion_tombstones.uid_hash` — e, desde a T18.1.1, a chave que o fence de mutação de
 * conta relê para decidir se uma escrita ainda pode acontecer.
 *
 * Função pura e neutra (sem DI, sem I/O) para que qualquer domínio que precise confirmar o mesmo
 * tombstone dentro da própria transação — backup, sync, mídia social — calcule exatamente o mesmo
 * hash que `AccountDeletionService` grava, sem importar o módulo de exclusão de conta.
 */
export function hashAccountUid(config: AppConfig, uid: string): string {
  return createHmac('sha256', config.accountDeletionHmacKey).update(uid).digest('hex');
}
