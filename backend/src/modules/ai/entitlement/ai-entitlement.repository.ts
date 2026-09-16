import { Injectable } from '@nestjs/common';
import { PostgresService } from '../../../database/postgres.service';
import { AI_CAPABILITIES, type AiCapability } from './ai-capability';

export type AiEntitlementState = 'GRANTED' | 'REVOKED';

/**
 * A persistência dos entitlements — só isso.
 *
 * Nenhuma decisão de autorização mora aqui: "linha ausente" é devolvida como `null`, e é o
 * `AiEntitlementResolver` (não este repositório) que decide o que `null` significa. Um repositório
 * que decidisse sozinho tenderia, com o tempo, a duplicar a decisão em outro lugar que também lê a
 * tabela diretamente — exatamente o que o resolver central existe para evitar (§6 da T19.0).
 */
@Injectable()
export class AiEntitlementRepository {
  constructor(private readonly db: PostgresService) {}

  /** O estado gravado para `uid` + `capability`, ou `null` quando não há linha. */
  async stateFor(uid: string, capability: AiCapability): Promise<AiEntitlementState | null> {
    const res = await this.db.query<{ state: AiEntitlementState }>(
      'SELECT state FROM ai_capability_entitlements WHERE uid = $1 AND capability = $2',
      [uid, capability],
    );
    return res.rows[0]?.state ?? null;
  }

  /**
   * O estado de **todas** as capabilities conhecidas para `uid`, em uma consulta.
   *
   * Devolve um mapa só com as capabilities que têm linha; quem chama decide o default para as
   * ausentes — mesma regra de `stateFor`, sem repeti-la aqui.
   */
  async statesFor(uid: string): Promise<ReadonlyMap<AiCapability, AiEntitlementState>> {
    const res = await this.db.query<{ capability: AiCapability; state: AiEntitlementState }>(
      'SELECT capability, state FROM ai_capability_entitlements WHERE uid = $1',
      [uid],
    );
    const byCapability = new Map<AiCapability, AiEntitlementState>();
    for (const row of res.rows) {
      byCapability.set(row.capability, row.state);
    }
    return byCapability;
  }

  /**
   * Grava um estado explícito — idempotente por definição de `PRIMARY KEY (uid, capability)`.
   *
   * `grant` de uma capability já concedida, ou `revoke` de uma já revogada, executa o mesmo
   * `UPDATE` e não duplica linha (§9). `created_at` só é gravado na primeira vez.
   */
  async setState(uid: string, capability: AiCapability, state: AiEntitlementState): Promise<void> {
    const now = Date.now();
    await this.db.query(
      `INSERT INTO ai_capability_entitlements (uid, capability, state, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $4)
       ON CONFLICT (uid, capability) DO UPDATE SET
         state = EXCLUDED.state,
         updated_at = EXCLUDED.updated_at`,
      [uid, capability, state, now],
    );
  }

  /** Todas as linhas gravadas para `uid`, para listagem operacional (CLI). */
  async listFor(
    uid: string,
  ): Promise<
    ReadonlyArray<{ capability: AiCapability; state: AiEntitlementState; updatedAt: number }>
  > {
    const res = await this.db.query<{
      capability: AiCapability;
      state: AiEntitlementState;
      updated_at: string | number;
    }>(
      'SELECT capability, state, updated_at FROM ai_capability_entitlements WHERE uid = $1 ORDER BY capability',
      [uid],
    );
    return res.rows.map((row) => ({
      capability: row.capability,
      state: row.state,
      updatedAt: Number(row.updated_at),
    }));
  }
}

/** As quatro capabilities, para quem precisa iterar todas (resolver, endpoint, CLI). */
export function allCapabilities(): readonly AiCapability[] {
  return AI_CAPABILITIES;
}
