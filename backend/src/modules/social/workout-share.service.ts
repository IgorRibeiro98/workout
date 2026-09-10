import {
  BadRequestException,
  ConflictException,
  ForbiddenException,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  NotFoundException,
} from '@nestjs/common';
import { createHash, randomUUID } from 'node:crypto';
import { SparkLogger } from '../../common/logger';
import { CLOCK, Clock } from '../../common/clock';
import { BlockRepository } from './block.repository';
import { NotificationRepository } from './notification.repository';
import {
  CreateWorkoutShareRequest,
  WorkoutShareDetailDto,
  WorkoutShareErrorCodes,
  WorkoutShareItemDto,
  WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';
import { StoredWorkoutShare, WorkoutShareRepository } from './workout-share.repository';

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_SNAPSHOT_BYTES = 64 * 1024;
const MAX_PENDING_SHARES = 10;
const MAX_DAILY_SHARES = 20;

/**
 * A forma de um `canonicalExerciseId` (T17.10 §99).
 *
 * Uma allowlist, e não uma blocklist: o catálogo produz slugs (`supino-reto-barra`), e 128
 * caracteres cobrem com folga o maior deles (47 hoje). Qualquer outra coisa é recusada antes de
 * virar linha no banco.
 */
const CANONICAL_EXERCISE_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;

@Injectable()
export class WorkoutShareService {
  constructor(
    private readonly repository: WorkoutShareRepository,
    private readonly blockRepository: BlockRepository,
    private readonly notificationRepository: NotificationRepository,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  async createShare(senderUid: string, request: CreateWorkoutShareRequest): Promise<WorkoutShareDetailDto> {
    const now = this.clock.now();

    // 1. Validação de clientRequestId
    if (!request.clientRequestId || typeof request.clientRequestId !== 'string') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'clientRequestId obrigatório.',
      });
    }

    // 2. Validação de perfil remetente
    const senderProfile = await this.repository.findProfileByUid(senderUid);
    if (!senderProfile) {
      throw new ForbiddenException({
        code: WorkoutShareErrorCodes.SOCIAL_NOT_ENABLED,
        message: 'Perfil social do remetente não está ativo.',
      });
    }

    // 3. Validação de destinatário
    if (!request.recipientSocialId || typeof request.recipientSocialId !== 'string') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.RECIPIENT_NOT_FOUND,
        message: 'recipientSocialId obrigatório.',
      });
    }

    const recipientProfile = await this.repository.findProfileBySocialId(request.recipientSocialId);
    if (!recipientProfile) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.RECIPIENT_NOT_FOUND,
        message: 'Destinatário não encontrado ou com perfil inativo.',
      });
    }

    const recipientUid = recipientProfile.ownerUid;

    // 4. Bloqueio de auto-compartilhamento
    if (senderUid === recipientUid) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.CANNOT_SHARE_WITH_SELF,
        message: 'Não é possível compartilhar um treino consigo mesmo.',
      });
    }

    // 5. Bloqueio mútuo (T17.6)
    if (await this.blockRepository.isBlockedBidirectional(senderUid, recipientUid)) {
      throw new ForbiddenException({
        code: WorkoutShareErrorCodes.BLOCKED_USER,
        message: 'Não é possível interagir com este usuário.',
      });
    }

    // 6. Amizade ativa obrigatória (T17.1)
    if (!(await this.repository.isFriendshipActive(senderUid, recipientUid))) {
      throw new ForbiddenException({
        code: WorkoutShareErrorCodes.FRIENDSHIP_REQUIRED,
        message: 'Compartilhamento permitido apenas entre amigos.',
      });
    }

    // 7. Validação estrita do Snapshot (V1)
    this.validateSnapshot(request.snapshot);

    const snapshotJson = JSON.stringify(request.snapshot);
    if (Buffer.byteLength(snapshotJson, 'utf8') > MAX_SNAPSHOT_BYTES) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Snapshot excede o limite máximo permitido de 64KB.',
      });
    }

    const snapshotHash = createHash('sha256').update(snapshotJson).digest('hex');

    // 8. Idempotência por clientRequestId
    const existing = await this.repository.findBySenderAndClientRequestId(
      senderUid,
      request.clientRequestId,
    );
    if (existing) {
      if (existing.recipient_uid === recipientUid && existing.snapshot_hash === snapshotHash) {
        return this.toDetailDto(existing, senderProfile, recipientProfile, request.snapshot);
      }
      throw new ConflictException({
        code: WorkoutShareErrorCodes.CONFLICT,
        message: 'clientRequestId já utilizado com parâmetros divergentes.',
      });
    }

    // 9. Rate limit de criação
    const pendingCount = await this.repository.countPendingBySender(senderUid, now);
    if (pendingCount >= MAX_PENDING_SHARES) {
      throw new HttpException(
        {
          code: WorkoutShareErrorCodes.RATE_LIMITED,
          message: 'Limite de ofertas de treino pendentes atingido.',
        },
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    const dailyCount = await this.repository.countCreatedToday(senderUid, now - 24 * 60 * 60 * 1000);
    if (dailyCount >= MAX_DAILY_SHARES) {
      throw new HttpException(
        {
          code: WorkoutShareErrorCodes.RATE_LIMITED,
          message: 'Limite diário de compartilhamento de treinos atingido.',
        },
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    // 10. Persistência
    const shareId = randomUUID();
    const expiresAt = now + THIRTY_DAYS_MS;

    const stored: StoredWorkoutShare = {
      id: shareId,
      sender_uid: senderUid,
      recipient_uid: recipientUid,
      snapshot_version: 1,
      snapshot_json: snapshotJson,
      snapshot_hash: snapshotHash,
      status: 'PENDING',
      client_request_id: request.clientRequestId,
      created_at: now,
      accepted_at: null,
      imported_at: null,
      declined_at: null,
      cancelled_at: null,
      expires_at: expiresAt,
    };

    // 11. O share e o evento de notificação, numa transação só (T17.13.1 §45–§47).
    await this.repository.insertShareWithNotification(stored, async (client) => {
      await this.notificationRepository.createEvent(
        {
          id: randomUUID(),
          recipientUid,
          type: 'WORKOUT_SHARE_RECEIVED',
          entityId: shareId,
          dedupeKey: `workout_share:${shareId}`,
          deliverAfter: now,
          expiresAt,
        },
        now,
        client,
      );
    });

    this.logger.info('social.workout_share.created', {
      shareId,
      snapshotVersion: 1,
      exerciseCount: request.snapshot.exercises.length,
    });

    return this.toDetailDto(stored, senderProfile, recipientProfile, request.snapshot);
  }

  async listReceived(recipientUid: string): Promise<WorkoutShareItemDto[]> {
    const now = this.clock.now();
    return this.repository.listReceived(recipientUid, now);
  }

  async listSent(senderUid: string): Promise<WorkoutShareItemDto[]> {
    const now = this.clock.now();
    return this.repository.listSent(senderUid, now);
  }

  async getShareDetail(callerUid: string, shareId: string): Promise<WorkoutShareDetailDto> {
    const now = this.clock.now();
    const share = await this.repository.findById(shareId);

    if (!share) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    // Anti-enumeração: terceiro recebe 404
    if (share.sender_uid !== callerUid && share.recipient_uid !== callerUid) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    // Se houver bloqueio bilateral, responde 404
    if (await this.blockRepository.isBlockedBidirectional(share.sender_uid, share.recipient_uid)) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    const senderProfile = (await this.repository.findProfileByUid(share.sender_uid)) ?? {
      socialId: 'indisponivel',
      displayName: 'Participante indisponível',
    };
    const recipientProfile = (await this.repository.findProfileByUid(share.recipient_uid)) ?? {
      socialId: 'indisponivel',
      displayName: 'Participante indisponível',
    };

    // Auto-expira se necessário
    let currentStatus = share.status;
    if (currentStatus === 'PENDING' && now >= share.expires_at) {
      if (await this.repository.transitionStatus(shareId, 'PENDING', 'EXPIRED', 'cancelled_at', now)) {
        currentStatus = 'EXPIRED';
      } else {
        currentStatus = (await this.repository.findById(shareId))?.status ?? currentStatus;
      }
    }

    const snapshot = JSON.parse(share.snapshot_json) as WorkoutTemplateShareSnapshotV1;
    return this.toDetailDto(
      { ...share, status: currentStatus },
      senderProfile,
      recipientProfile,
      snapshot,
    );
  }

  async acceptShare(recipientUid: string, shareId: string): Promise<WorkoutTemplateShareSnapshotV1> {
    const now = this.clock.now();
    const share = await this.repository.findById(shareId);

    if (!share || share.recipient_uid !== recipientUid) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    if (await this.blockRepository.isBlockedBidirectional(share.sender_uid, recipientUid)) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    // Aceite idempotente: se já estiver ACCEPTED pelo mesmo recipient, devolve o snapshot
    if (share.status === 'ACCEPTED') {
      return JSON.parse(share.snapshot_json) as WorkoutTemplateShareSnapshotV1;
    }

    if (share.status !== 'PENDING') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino não está mais disponível.',
      });
    }

    if (now >= share.expires_at) {
      await this.repository.transitionStatus(shareId, 'PENDING', 'EXPIRED', 'cancelled_at', now);
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino expirada.',
      });
    }

    // Amizade ainda ativa?
    if (!(await this.repository.isFriendshipActive(share.sender_uid, recipientUid))) {
      await this.repository.transitionStatus(shareId, 'PENDING', 'CANCELLED', 'cancelled_at', now);
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'A amizade não está mais ativa.',
      });
    }

    if (!(await this.repository.transitionStatus(shareId, 'PENDING', 'ACCEPTED', 'accepted_at', now))) {
      const current = await this.repository.findById(shareId);
      if (current && (current.status === 'ACCEPTED' || current.status === 'IMPORTED')) {
        return JSON.parse(current.snapshot_json) as WorkoutTemplateShareSnapshotV1;
      }
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino não está mais disponível.',
      });
    }
    this.logger.info('social.workout_share.accepted', { shareId });

    return JSON.parse(share.snapshot_json) as WorkoutTemplateShareSnapshotV1;
  }

  async completeImport(recipientUid: string, shareId: string): Promise<{ success: boolean }> {
    const now = this.clock.now();
    const share = await this.repository.findById(shareId);

    if (!share || share.recipient_uid !== recipientUid) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    if (share.status === 'IMPORTED') {
      return { success: true };
    }

    if (share.status !== 'ACCEPTED') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino precisa ser aceita antes de concluir importação.',
      });
    }

    if (!(await this.repository.transitionStatus(shareId, 'ACCEPTED', 'IMPORTED', 'imported_at', now))) {
      if ((await this.repository.findById(shareId))?.status === 'IMPORTED') {
        return { success: true };
      }
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino precisa ser aceita antes de concluir importação.',
      });
    }
    this.logger.info('social.workout_share.imported', { shareId });

    return { success: true };
  }

  async declineShare(recipientUid: string, shareId: string): Promise<{ success: boolean }> {
    const now = this.clock.now();
    const share = await this.repository.findById(shareId);

    if (!share || share.recipient_uid !== recipientUid) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    if (share.status === 'DECLINED') {
      return { success: true };
    }

    if (share.status !== 'PENDING') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino não está mais pendente.',
      });
    }

    if (!(await this.repository.transitionStatus(shareId, 'PENDING', 'DECLINED', 'declined_at', now))) {
      if ((await this.repository.findById(shareId))?.status === 'DECLINED') {
        return { success: true };
      }
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino não está mais pendente.',
      });
    }
    this.logger.info('social.workout_share.declined', { shareId });

    return { success: true };
  }

  async cancelShare(senderUid: string, shareId: string): Promise<{ success: boolean }> {
    const now = this.clock.now();
    const share = await this.repository.findById(shareId);

    if (!share || share.sender_uid !== senderUid) {
      throw new NotFoundException({
        code: WorkoutShareErrorCodes.SHARE_NOT_FOUND,
        message: 'Oferta de treino não encontrada.',
      });
    }

    if (share.status === 'CANCELLED') {
      return { success: true };
    }

    if (share.status !== 'PENDING') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Não é possível cancelar uma oferta que não esteja pendente.',
      });
    }

    if (!(await this.repository.transitionStatus(shareId, 'PENDING', 'CANCELLED', 'cancelled_at', now))) {
      if ((await this.repository.findById(shareId))?.status === 'CANCELLED') {
        return { success: true };
      }
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Não é possível cancelar uma oferta que não esteja pendente.',
      });
    }
    this.logger.info('social.workout_share.cancelled', { shareId });

    return { success: true };
  }

  private validateSnapshot(snapshot: WorkoutTemplateShareSnapshotV1): void {
    if (!snapshot || typeof snapshot !== 'object') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Snapshot inválido.',
      });
    }

    const version = (snapshot as { snapshotVersion?: unknown }).snapshotVersion;
    if (version !== 1) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `Versão do snapshot não suportada: ${String(version)}.`,
      });
    }

    const name = snapshot.name?.trim();
    if (!name || name.length > 100) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Nome do treino deve ter entre 1 e 100 caracteres.',
      });
    }

    if (snapshot.shortIdentifier && snapshot.shortIdentifier.length > 10) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Identificador curto deve ter no máximo 10 caracteres.',
      });
    }

    if (
      !Array.isArray(snapshot.exercises) ||
      snapshot.exercises.length === 0 ||
      snapshot.exercises.length > 30
    ) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Treino deve conter entre 1 e 30 exercícios.',
      });
    }

    // Validação de proibições e limites por exercício
    const forbiddenKeys = [
      'plannedWeight',
      'notes',
      'machineLabel',
      'localId',
      'syncId',
      'programId',
      'ownerUid',
      'userId',
    ];
    for (const key of forbiddenKeys) {
      if (key in (snapshot as unknown as Record<string, unknown>)) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Campo proibido no snapshot: ${key}.`,
        });
      }
    }

    snapshot.exercises.forEach((ex, idx) => {
      if (
        typeof ex.canonicalExerciseId !== 'string' ||
        !CANONICAL_EXERCISE_ID_PATTERN.test(ex.canonicalExerciseId)
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Exercício [${idx}] sem canonicalExerciseId válido.`,
        });
      }

      for (const key of forbiddenKeys) {
        if (key in (ex as unknown as Record<string, unknown>)) {
          throw new BadRequestException({
            code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
            message: `Campo proibido no exercício [${idx}]: ${key}.`,
          });
        }
      }

      if (typeof ex.sortOrder !== 'number' || ex.sortOrder < 0 || ex.sortOrder > 30) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `sortOrder inválido no exercício [${idx}].`,
        });
      }

      if (typeof ex.targetSets !== 'number' || ex.targetSets < 1 || ex.targetSets > 20) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `targetSets inválido no exercício [${idx}] (1..20).`,
        });
      }

      if (
        typeof ex.minReps !== 'number' ||
        typeof ex.maxReps !== 'number' ||
        ex.minReps < 1 ||
        ex.maxReps > 100 ||
        ex.minReps > ex.maxReps
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Faixa de repetições inválida no exercício [${idx}] (minReps <= maxReps).`,
        });
      }

      if (
        typeof ex.restDurationSeconds !== 'number' ||
        ex.restDurationSeconds < 0 ||
        ex.restDurationSeconds > 600
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `restDurationSeconds inválido no exercício [${idx}] (0..600).`,
        });
      }
    });
  }

  private toDetailDto(
    stored: StoredWorkoutShare,
    sender: { socialId: string; displayName: string },
    recipient: { socialId: string; displayName: string },
    snapshot?: WorkoutTemplateShareSnapshotV1,
  ): WorkoutShareDetailDto {
    return {
      shareId: stored.id,
      status: stored.status,
      createdAt: stored.created_at,
      expiresAt: stored.expires_at,
      sender: {
        socialId: sender.socialId,
        displayName: sender.displayName,
      },
      recipient: {
        socialId: recipient.socialId,
        displayName: recipient.displayName,
      },
      snapshot,
    };
  }
}
