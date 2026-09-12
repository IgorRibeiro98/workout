import { randomUUID } from 'node:crypto';
import { BadRequestException, Inject, Injectable, NotFoundException } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { CLOCK, type Clock } from '../../common/clock';
import { BlockRepository } from './block.repository';
import { FriendshipRepository } from './friendship.repository';
import { SocialErrors } from './social.errors';
import { SocialRepository } from './social.repository';
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
    private readonly socialRepo: SocialRepository,
    private readonly logger: SparkLogger,
    @Inject(CLOCK) private readonly clock: Clock,
  ) {}

  /**
   * Exige perfil social ativo de quem bloqueia.
   *
   * `social_blocks.blocker_uid` referencia `social_profiles(owner_uid)`: sem perfil, o `INSERT`
   * morria numa violação de chave estrangeira — `500` para uma requisição que o servidor sabia
   * recusar. A resposta certa é a mesma que toda rota social dá a uma conta sem perfil
   * (`SOCIAL_NOT_ENABLED`), e ela precisa vir **antes** de qualquer escrita.
   */
  private async requireActiveProfile(callerUid: string): Promise<void> {
    const account = await this.socialRepo.find(callerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw SocialErrors.notEnabled();
    }
  }

  /** Bloqueia um usuário pelo seu socialId público. Operação idempotente. */
  async blockUser(blockerUid: string, blockedSocialId: string): Promise<BlockUserResponseDto> {
    await this.requireActiveProfile(blockerUid);

    const target = await this.friendshipRepo.findProfileBySocialId(blockedSocialId);
    if (!target) {
      throw new NotFoundException('Perfil social não encontrado.');
    }

    if (target.ownerUid === blockerUid) {
      throw new BadRequestException('Não é possível bloquear a si mesmo.');
    }

    const now = this.clock.now();
    const blockId = randomUUID();

    await this.blockRepo.blockAndCleanup(blockId, blockerUid, target.ownerUid, now);

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
  async unblockUser(blockerUid: string, blockedSocialId: string): Promise<UnblockUserResponseDto> {
    const target = await this.friendshipRepo.findProfileBySocialId(blockedSocialId);
    if (target) {
      await this.blockRepo.deleteBlock(blockerUid, target.ownerUid);
    }

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
  async listBlocked(blockerUid: string): Promise<ListBlockedUsersResponseDto> {
    const blockedUsers = await this.blockRepo.listBlocked(blockerUid);
    return { blockedUsers };
  }

  /** Consulta rápida se existe bloqueio entre dois UIDs em qualquer direção. */
  async isBlocked(uidA: string, uidB: string): Promise<boolean> {
    return await this.blockRepo.isBlockedBidirectional(uidA, uidB);
  }

  /** Retorna conjunto de UIDs com restrição de bloqueio para o usuário. */
  async getBlockedUids(uid: string): Promise<Set<string>> {
    return await this.blockRepo.findBlockedUidsBidirectional(uid);
  }
}
