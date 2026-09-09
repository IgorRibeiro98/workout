import { randomUUID } from 'node:crypto';
import { BadRequestException, Inject, Injectable, NotFoundException } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { BlockRepository } from './block.repository';
import { FriendshipRepository } from './friendship.repository';
import type {
  BlockUserResponseDto,
  ListBlockedUsersResponseDto,
  UnblockUserResponseDto,
} from './block.contract';

@Injectable()
export class BlockService {
  constructor(
    private readonly blockRepo: BlockRepository,
    private readonly friendshipRepo: FriendshipRepository,
    private readonly logger: SparkLogger,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /** Bloqueia um usuário pelo seu socialId público. Operação idempotente. */
  blockUser(blockerUid: string, blockedSocialId: string): BlockUserResponseDto {
    const target = this.friendshipRepo.findProfileBySocialId(blockedSocialId);
    if (!target) {
      throw new NotFoundException('Perfil social não encontrado.');
    }

    if (target.ownerUid === blockerUid) {
      throw new BadRequestException('Não é possível bloquear a si mesmo.');
    }

    const now = this.clock.now();
    const blockId = randomUUID();

    this.blockRepo.createBlock(blockId, blockerUid, target.ownerUid, now);
    this.blockRepo.cleanupSharedRelationsOnBlock(blockerUid, target.ownerUid, now);

    this.logger.info('social.block.created', {
      blockerUidPrefix: blockerUid.slice(0, 6),
      blockedUidPrefix: target.ownerUid.slice(0, 6),
    });

    return {
      result: 'BLOCKED',
      blockedSocialId,
    };
  }

  /** Desbloqueia um usuário. Operação idempotente. Não restaura amizades ou desafios. */
  unblockUser(blockerUid: string, blockedSocialId: string): UnblockUserResponseDto {
    const target = this.friendshipRepo.findProfileBySocialId(blockedSocialId);
    if (target) {
      this.blockRepo.deleteBlock(blockerUid, target.ownerUid);
    }

    // T17.10 §120 — `socialId` é identidade pública de outra pessoa, e a regra de log do social
    // (§13.8/§13.9) o proíbe junto com uid completo, e-mail, `displayName` e `friendCode`. Aqui
    // ele estava saindo inteiro. O prefixo de uid do alvo correlaciona o mesmo evento no suporte
    // sem registrar o identificador com que essa pessoa é encontrável.
    this.logger.info('social.block.removed', {
      blockerUidPrefix: blockerUid.slice(0, 6),
      blockedUidPrefix: target ? target.ownerUid.slice(0, 6) : null,
      targetResolved: target !== null,
    });

    return {
      result: 'UNBLOCKED',
      unblockedSocialId: blockedSocialId,
    };
  }

  /** Lista usuários bloqueados pelo chamador. */
  listBlocked(blockerUid: string): ListBlockedUsersResponseDto {
    const blockedUsers = this.blockRepo.listBlocked(blockerUid);
    return { blockedUsers };
  }

  /** Consulta rápida se existe bloqueio entre dois UIDs em qualquer direção. */
  isBlocked(uidA: string, uidB: string): boolean {
    return this.blockRepo.isBlockedBidirectional(uidA, uidB);
  }

  /** Retorna conjunto de UIDs com restrição de bloqueio para o usuário. */
  getBlockedUids(uid: string): Set<string> {
    return this.blockRepo.findBlockedUidsBidirectional(uid);
  }
}
