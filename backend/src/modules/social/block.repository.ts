import { Injectable } from '@nestjs/common';
import { PostgresService, type PoolClient } from '../../database/postgres.service';
import { lockRelationshipPair } from './friendship.repository';
import type { BlockedUserItemDto } from './block.contract';

@Injectable()
export class BlockRepository {
  constructor(private readonly db: PostgresService) {}

  /**
   * Bloqueia e limpa os relacionamentos compartilhados numa única transação, sob o lock do par
   * canônico (T18.0.3).
   *
   * Antes, `createBlock` e `cleanupSharedRelationsOnBlock` eram duas chamadas separadas, nenhuma
   * sob o protocolo de lock que `sendRequest`/`acceptRequest`/`resolveRequest` já usavam — uma
   * transação concorrente (enviar pedido, aceitar, desfazer amizade) podia correr **entre** as
   * duas, ou correr sem nunca enxergar o bloqueio recém-criado. Unificado aqui, block e limpeza
   * disputam o mesmo lock que toda mudança de relação do par usa, e o par nunca fica um instante
   * "bloqueado, mas ainda com amizade/pedido pendente" por acaso de interleaving.
   */
  async blockAndCleanup(
    id: string,
    blockerUid: string,
    blockedUid: string,
    now: number,
  ): Promise<void> {
    await this.db.transaction(async (client) => {
      await lockRelationshipPair(client, blockerUid, blockedUid);
      await client.query(
        `INSERT INTO social_blocks (id, blocker_uid, blocked_uid, created_at)
         VALUES ($1, $2, $3, $4)
         ON CONFLICT (blocker_uid, blocked_uid) DO NOTHING`,
        [id, blockerUid, blockedUid, now],
      );
      await this.cleanupSharedRelations(client, blockerUid, blockedUid, now);
    });
  }

  /**
   * Remove bloqueio (idempotente), sob o mesmo lock do par (T18.0.3): desbloquear também altera o
   * estado relacional do par, e precisa serializar com as demais mutações — mesmo que, por
   * decisão de produto, ele não restaure nada (§ "Não alterar" da T18.0.3).
   */
  async deleteBlock(blockerUid: string, blockedUid: string): Promise<boolean> {
    return await this.db.transaction(async (client) => {
      await lockRelationshipPair(client, blockerUid, blockedUid);
      const res = await client.query(
        `DELETE FROM social_blocks WHERE blocker_uid = $1 AND blocked_uid = $2`,
        [blockerUid, blockedUid],
      );
      return (res.rowCount ?? 0) > 0;
    });
  }

  /** Confere se existe bloqueio em qualquer uma das duas direções. */
  async isBlockedBidirectional(uidA: string, uidB: string): Promise<boolean> {
    const res = await this.db.query(
      `SELECT 1 FROM social_blocks
       WHERE (blocker_uid = $1 AND blocked_uid = $2)
          OR (blocker_uid = $3 AND blocked_uid = $4)
       LIMIT 1`,
      [uidA, uidB, uidB, uidA],
    );
    return res.rows.length > 0;
  }

  /** Retorna conjunto de todos os UIDs bloqueados pelo usuário ou que bloquearam o usuário. */
  async findBlockedUidsBidirectional(uid: string): Promise<Set<string>> {
    const res = await this.db.query<{ uid: string }>(
      `SELECT blocked_uid AS uid FROM social_blocks WHERE blocker_uid = $1
       UNION
       SELECT blocker_uid AS uid FROM social_blocks WHERE blocked_uid = $2`,
      [uid, uid],
    );
    return new Set(res.rows.map((r) => r.uid));
  }

  /** Lista usuários bloqueados pelo blockerUid. */
  async listBlocked(blockerUid: string): Promise<BlockedUserItemDto[]> {
    const res = await this.db.query<{
      socialId: string;
      displayName: string;
      blockedAt: string | number;
    }>(
      `SELECT p.social_id AS "socialId", p.display_name AS "displayName", b.created_at AS "blockedAt"
       FROM social_blocks b
       JOIN social_profiles p ON b.blocked_uid = p.owner_uid
       WHERE b.blocker_uid = $1
       ORDER BY b.created_at DESC`,
      [blockerUid],
    );
    return res.rows.map((r) => ({
      socialId: r.socialId,
      displayName: r.displayName,
      blockedAt: Number(r.blockedAt),
    }));
  }

  /**
   * A limpeza server-side de relacionamentos mútuos no momento do bloqueio, dentro da transação e
   * do lock de `blockAndCleanup` — nunca chamada fora dele (T18.0.3):
   * 1. Remove amizades;
   * 2. Cancela pedidos de amizade pendentes;
   * 3. Cancela convites de desafio pendentes entre o par;
   * 4. Trata desafios ativos/upcoming compartilhados:
   *    - Blocker creator: retira blocked member (WITHDRAWN);
   *    - Blocker member: retira blocker (WITHDRAWN);
   * 5. Cancela os eventos de notificação pendentes **do par** — e só os do par.
   */
  private async cleanupSharedRelations(
    client: PoolClient,
    blockerUid: string,
    blockedUid: string,
    now: number,
  ): Promise<void> {
    // 1. Remove amizades bilaterais
    await client.query(
      `DELETE FROM friendships
       WHERE (user_a_uid = $1 AND user_b_uid = $2)
          OR (user_a_uid = $3 AND user_b_uid = $4)`,
      [blockerUid, blockedUid, blockedUid, blockerUid],
    );

    // 2. Cancela pedidos de amizade pendentes entre o par
    await client.query(
      `UPDATE friend_requests
       SET status = 'CANCELLED', updated_at = $1
       WHERE status = 'PENDING'
         AND ((requester_uid = $2 AND recipient_uid = $3)
           OR (requester_uid = $4 AND recipient_uid = $5))`,
      [now, blockerUid, blockedUid, blockedUid, blockerUid],
    );

    // 3. Cancela convites de desafios pendentes entre o par
    await client.query(
      `UPDATE challenge_invitations
       SET status = 'DECLINED', updated_at = $1
       WHERE status = 'PENDING'
         AND ((inviter_uid = $2 AND recipient_uid = $3)
           OR (inviter_uid = $4 AND recipient_uid = $5))`,
      [now, blockerUid, blockedUid, blockedUid, blockerUid],
    );

    // 4. Desafios compartilhados:
    // Se blocker é creator e blocked é member em desafio não encerrado -> retira o blocked
    await client.query(
      `UPDATE challenge_participants
       SET status = 'WITHDRAWN', left_at = $1
       WHERE status = 'JOINED'
         AND participant_uid = $2
         AND challenge_id IN (
           SELECT challenge_id FROM challenges
           WHERE creator_uid = $3 AND lifecycle = 'OPEN'
         )`,
      [now, blockedUid, blockerUid],
    );

    // Se blocker é member em desafio não encerrado criado pelo blocked ou terceiro compartilhado -> retira o blocker
    await client.query(
      `UPDATE challenge_participants
       SET status = 'WITHDRAWN', left_at = $1
       WHERE status = 'JOINED'
         AND participant_uid = $2
         AND challenge_id IN (
           SELECT challenge_id FROM challenges
           WHERE creator_uid = $3 AND lifecycle = 'OPEN'
         )`,
      [now, blockerUid, blockedUid],
    );

    // Para desafios de terceiros onde ambos estão JOINED -> blocker é retirado
    await client.query(
      `UPDATE challenge_participants
       SET status = 'WITHDRAWN', left_at = $1
       WHERE status = 'JOINED'
         AND participant_uid = $2
         AND challenge_id IN (
           SELECT p1.challenge_id FROM challenge_participants p1
           JOIN challenge_participants p2 ON p1.challenge_id = p2.challenge_id
           JOIN challenges c ON p1.challenge_id = c.challenge_id
           WHERE p1.participant_uid = $3
             AND p2.participant_uid = $4
             AND p1.status = 'JOINED'
             AND p2.status = 'JOINED'
             AND c.lifecycle = 'OPEN'
         )`,
      [now, blockerUid, blockerUid, blockedUid],
    );

    // 5. Cancela workout shares PENDING ou ACCEPTED entre o par
    await client.query(
      `UPDATE workout_shares
       SET status = 'CANCELLED', cancelled_at = $1
       WHERE status IN ('PENDING', 'ACCEPTED')
         AND ((sender_uid = $2 AND recipient_uid = $3)
           OR (sender_uid = $4 AND recipient_uid = $5))`,
      [now, blockerUid, blockedUid, blockedUid, blockerUid],
    );

    // 6. Convites de Squad pendentes entre o par (T17.11 §105).
    await client.query(
      `UPDATE social_group_invitations
       SET status = 'CANCELLED', responded_at = $1
       WHERE status = 'PENDING'
         AND ((sender_uid = $2 AND recipient_uid = $3)
           OR (sender_uid = $4 AND recipient_uid = $5))`,
      [now, blockerUid, blockedUid, blockedUid, blockerUid],
    );

    // 7. Cancela os eventos de outbox pendentes **deste par**.
    //
    // Antes, o filtro era só `recipient_uid = blocker OR recipient_uid = blocked`: bloquear alguém
    // silenciava todo aviso pendente dos dois, inclusive o pedido de amizade de um terceiro que
    // não tem nada com este bloqueio. E o `UPDATE` das entregas varria **toda** linha `CANCELLED`
    // da tabela, de qualquer par, a cada bloqueio.
    //
    // O recorte é o `entity_id`: os eventos que este bloqueio torna irrelevantes são os que
    // nascem de uma relação entre as duas contas — pedido de amizade, convite de desafio, oferta
    // de treino e convite de Squad —, todos cancelados logo acima. Os eventos de desafio
    // (`CHALLENGE_STARTING_SOON`/`CHALLENGE_ENDED`) não entram: o `entity_id` deles é o desafio,
    // não o par, e a saída do participante (passo 4) já os faz cair no `checkRelevance` do
    // dispatcher, que suprime em vez de entregar.
    //
    // As entregas são restritas por `RETURNING`: exatamente os eventos que **esta** transação
    // cancelou, e nenhum outro.
    await client.query(
      `WITH pair_entities AS (
         SELECT request_id AS entity_id FROM friend_requests
          WHERE (requester_uid = $2 AND recipient_uid = $3)
             OR (requester_uid = $3 AND recipient_uid = $2)
         UNION ALL
         SELECT invitation_id FROM challenge_invitations
          WHERE (inviter_uid = $2 AND recipient_uid = $3)
             OR (inviter_uid = $3 AND recipient_uid = $2)
         UNION ALL
         SELECT id FROM workout_shares
          WHERE (sender_uid = $2 AND recipient_uid = $3)
             OR (sender_uid = $3 AND recipient_uid = $2)
         UNION ALL
         SELECT id FROM social_group_invitations
          WHERE (sender_uid = $2 AND recipient_uid = $3)
             OR (sender_uid = $3 AND recipient_uid = $2)
       ),
       cancelled AS (
         UPDATE social_notification_events
            SET status = 'CANCELLED', completed_at = $1
          WHERE status = 'PENDING'
            AND recipient_uid IN ($2, $3)
            AND entity_id IN (SELECT entity_id FROM pair_entities)
          RETURNING id
       )
       UPDATE social_notification_deliveries d
          SET status = 'FAILED_PERMANENT'
         FROM cancelled c
        WHERE d.event_id = c.id
          AND d.status = 'PENDING'`,
      [now, blockerUid, blockedUid],
    );
  }
}
