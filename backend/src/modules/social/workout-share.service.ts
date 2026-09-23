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
import { isCanonicalWeekdayList } from '../../common/weekday';
import { BlockRepository } from './block.repository';
import { NotificationRepository } from './notification.repository';
import {
  CreateWorkoutShareRequest,
  SharedCustomExerciseV2,
  SharedExerciseV1,
  WorkoutProgramShareSnapshotV1,
  WorkoutShareDetailDto,
  WorkoutShareErrorCodes,
  WorkoutShareItemDto,
  WorkoutShareSnapshotV1,
  WorkoutTemplateShareSnapshotV1,
} from './workout-share.contract';
import { StoredWorkoutShare, WorkoutShareRepository } from './workout-share.repository';

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;
const MAX_SNAPSHOT_BYTES = 64 * 1024;
/**
 * O teto de um snapshot de **programa** (T19.3): vários treinos numa oferta só.
 *
 * 30 treinos × 30 exercícios × ~150 bytes por exercício não cabem nos 64 KiB do treino avulso; o
 * teto aqui cobre o programa máximo que a validação semântica aceita, com folga, e continua muito
 * abaixo do corpo global do processo (4 MiB, o do backup).
 */
const MAX_PROGRAM_SNAPSHOT_BYTES = 256 * 1024;
const MAX_PROGRAM_TEMPLATES = 30;
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

/**
 * A forma de um `customExerciseRef` (T19.H2 / V2).
 *
 * Ela é **escopada ao snapshot**, e o formato diz isso em voz alta: `custom-1`, `custom-2`. Não é
 * UUID, não é slug e não se parece com identidade global — justamente para que ninguém, de nenhum
 * lado, seja tentado a guardá-la como uma.
 */
const CUSTOM_EXERCISE_REF_PATTERN = /^custom-[0-9]{1,3}$/;

/** Quantos exercícios criados pelo usuário uma oferta pode carregar. */
const MAX_CUSTOM_EXERCISES = 30;

const MAX_CUSTOM_NAME_LENGTH = 100;
const MAX_CUSTOM_MUSCLE_LENGTH = 60;
const MAX_CUSTOM_EQUIPMENT_LENGTH = 60;
const MAX_CUSTOM_DESCRIPTION_LENGTH = 500;

/**
 * As versões de snapshot aceitas, e o que muda entre elas (T19.H2).
 *
 * ```text
 * V1   1..30 exercícios por treino, todos com canonicalExerciseId
 * V2   0..30 exercícios por treino  +  customExercises / customExerciseRef
 * ```
 *
 * A V1 continua valendo **sem mudança nenhuma**: uma oferta criada por um app anterior, ou pelo
 * app atual quando a V1 basta, é validada exatamente como sempre foi. Um `customExercises` ou um
 * `customExerciseRef` dentro de um snapshot `snapshotVersion: 1` recusa a oferta — a versão
 * descreve a forma, e uma forma que se contradiz é defeito, não flexibilidade.
 */
const SUPPORTED_SNAPSHOT_VERSIONS: readonly number[] = [1, 2];

@Injectable()
export class WorkoutShareService {
  constructor(
    private readonly repository: WorkoutShareRepository,
    private readonly blockRepository: BlockRepository,
    private readonly notificationRepository: NotificationRepository,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  async createShare(
    senderUid: string,
    request: CreateWorkoutShareRequest,
  ): Promise<WorkoutShareDetailDto> {
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

    // Destinatário inexistente responde **igual** a destinatário que não é amigo: compartilhar
    // exige amizade, e distinguir os dois faria desta rota um oráculo de existência sobre
    // `socialId` — a mesma regra que a T16.7.1 aplica ao sync ("identidade de outra conta é 404,
    // indistinguível de inexistente") e que a denúncia de usuário passou a seguir.
    const recipientProfile = await this.repository.findProfileBySocialId(request.recipientSocialId);
    if (!recipientProfile) {
      throw friendshipRequired();
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
      throw friendshipRequired();
    }

    // 7. Validação estrita do Snapshot (V1), pelo tipo (T19.3)
    const content = request.content;
    if (content.shareType === 'WORKOUT_PROGRAM') {
      this.validateProgramSnapshot(content.snapshot);
    } else {
      this.validateSnapshot(content.snapshot);
    }

    const snapshotJson = JSON.stringify(content.snapshot);
    const maxBytes =
      content.shareType === 'WORKOUT_PROGRAM' ? MAX_PROGRAM_SNAPSHOT_BYTES : MAX_SNAPSHOT_BYTES;
    if (Buffer.byteLength(snapshotJson, 'utf8') > maxBytes) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `Snapshot excede o limite máximo permitido de ${maxBytes / 1024}KB.`,
      });
    }

    const snapshotHash = createHash('sha256').update(snapshotJson).digest('hex');

    // 8. Idempotência por clientRequestId: mesma chave + mesmo destinatário + mesmo tipo + mesmo
    // conteúdo é replay; qualquer divergência é conflito, nunca o resultado antigo.
    const existing = await this.repository.findBySenderAndClientRequestId(
      senderUid,
      request.clientRequestId,
    );
    if (existing) {
      if (
        existing.recipient_uid === recipientUid &&
        existing.share_type === content.shareType &&
        existing.snapshot_hash === snapshotHash
      ) {
        return this.toDetailDto(existing, senderProfile, recipientProfile);
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

    const dailyCount = await this.repository.countCreatedToday(
      senderUid,
      now - 24 * 60 * 60 * 1000,
    );
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
      share_type: content.shareType,
      snapshot_version: content.snapshot.snapshotVersion,
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

    // Só contagens e tipo: nome do programa, nome do treino e exercícios não vão para log.
    this.logger.info('social.workout_share.created', {
      shareId,
      shareType: content.shareType,
      snapshotVersion: content.snapshot.snapshotVersion,
      templateCount:
        content.shareType === 'WORKOUT_PROGRAM' ? content.snapshot.templates.length : 1,
      exerciseCount:
        content.shareType === 'WORKOUT_PROGRAM'
          ? content.snapshot.templates.reduce((sum, t) => sum + t.exercises.length, 0)
          : content.snapshot.exercises.length,
    });

    return this.toDetailDto(stored, senderProfile, recipientProfile);
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
      if (
        await this.repository.transitionStatus(shareId, 'PENDING', 'EXPIRED', 'cancelled_at', now)
      ) {
        currentStatus = 'EXPIRED';
      } else {
        currentStatus = (await this.repository.findById(shareId))?.status ?? currentStatus;
      }
    }

    return this.toDetailDto({ ...share, status: currentStatus }, senderProfile, recipientProfile);
  }

  /**
   * Aceitar: a revalidação **no servidor** de tudo o que pode ter mudado desde que o destinatário
   * leu a oferta — bloqueio, cancelamento, expiração, amizade — e a transição `PENDING → ACCEPTED`.
   *
   * Devolve a oferta inteira, com o snapshot no campo do seu tipo (T19.3): é sobre este conteúdo,
   * e não sobre o que a tela tinha em memória, que o aparelho constrói a cópia.
   *
   * Idempotente em `ACCEPTED` **e** em `IMPORTED`: um aceite repetido — toque duplo, retry depois
   * de resposta perdida, reabrir uma oferta cuja importação local falhou — devolve o mesmo
   * conteúdo, e é o recibo local do aparelho que impede uma segunda cópia. Recusar o replay em
   * `IMPORTED` deixaria sem saída quem importou, concluiu, e cujo recibo se perdeu.
   */
  async acceptShare(recipientUid: string, shareId: string): Promise<WorkoutShareDetailDto> {
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

    if (share.status === 'ACCEPTED' || share.status === 'IMPORTED') {
      return this.acceptedDetail(share);
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

    if (
      !(await this.repository.transitionStatus(shareId, 'PENDING', 'ACCEPTED', 'accepted_at', now))
    ) {
      const current = await this.repository.findById(shareId);
      if (current && (current.status === 'ACCEPTED' || current.status === 'IMPORTED')) {
        return this.acceptedDetail(current);
      }
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.SHARE_NOT_AVAILABLE,
        message: 'Oferta de treino não está mais disponível.',
      });
    }
    this.logger.info('social.workout_share.accepted', { shareId, shareType: share.share_type });

    return this.acceptedDetail({ ...share, status: 'ACCEPTED', accepted_at: now });
  }

  /** O detalhe devolvido por [acceptShare], com os perfis lidos como em [getShareDetail]. */
  private async acceptedDetail(share: StoredWorkoutShare): Promise<WorkoutShareDetailDto> {
    const senderProfile = (await this.repository.findProfileByUid(share.sender_uid)) ?? {
      socialId: 'indisponivel',
      displayName: 'Participante indisponível',
    };
    const recipientProfile = (await this.repository.findProfileByUid(share.recipient_uid)) ?? {
      socialId: 'indisponivel',
      displayName: 'Participante indisponível',
    };
    return this.toDetailDto(share, senderProfile, recipientProfile);
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

    if (
      !(await this.repository.transitionStatus(shareId, 'ACCEPTED', 'IMPORTED', 'imported_at', now))
    ) {
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

    if (
      !(await this.repository.transitionStatus(shareId, 'PENDING', 'DECLINED', 'declined_at', now))
    ) {
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

    if (
      !(await this.repository.transitionStatus(
        shareId,
        'PENDING',
        'CANCELLED',
        'cancelled_at',
        now,
      ))
    ) {
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

    const version = requireSupportedVersion(
      (snapshot as { snapshotVersion?: unknown }).snapshotVersion,
    );

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

    // V2 aceita treino vazio; V1 não muda (T19.H2). Um treino sem exercícios é um estado
    // legítimo do Workout local — o esqueleto que a pessoa ainda vai preencher — e recusá-lo era
    // o Social decidindo o que é um treino válido, que não é assunto dele.
    const minExercises = version >= 2 ? 0 : 1;
    if (
      !Array.isArray(snapshot.exercises) ||
      snapshot.exercises.length < minExercises ||
      snapshot.exercises.length > 30
    ) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `Treino deve conter entre ${minExercises} e 30 exercícios.`,
      });
    }

    rejectForbiddenKeys(snapshot, 'no snapshot');
    const customRefs = this.validateCustomExercises(snapshot.customExercises, version);
    this.validateExercises(snapshot.exercises, '', version, customRefs);
    requireEveryCustomUsed(customRefs, snapshot.exercises);
  }

  /**
   * O snapshot de programa (T19.3): o programa, seus treinos em ordem, e em cada treino a mesma
   * validação do treino avulso — a regra de exercício é **uma**, e mora em [validateExercises].
   */
  private validateProgramSnapshot(snapshot: WorkoutProgramShareSnapshotV1): void {
    if (!snapshot || typeof snapshot !== 'object') {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Snapshot inválido.',
      });
    }

    const version = requireSupportedVersion(
      (snapshot as { snapshotVersion?: unknown }).snapshotVersion,
    );

    const name = typeof snapshot.name === 'string' ? snapshot.name.trim() : '';
    if (!name || name.length > 100) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Nome do programa deve ter entre 1 e 100 caracteres.',
      });
    }

    if (
      snapshot.description != null &&
      (typeof snapshot.description !== 'string' || snapshot.description.length > 500)
    ) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Descrição do programa deve ter no máximo 500 caracteres.',
      });
    }

    rejectForbiddenKeys(snapshot, 'no snapshot');

    // Os CUSTOM do **programa inteiro**, e não de um treino: é assim que o mesmo exercício usado
    // em três treinos chega ao destinatário como uma cópia só (T19.H2 §15).
    const customRefs = this.validateCustomExercises(snapshot.customExercises, version);

    if (
      !Array.isArray(snapshot.templates) ||
      snapshot.templates.length === 0 ||
      snapshot.templates.length > MAX_PROGRAM_TEMPLATES
    ) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `Programa deve conter entre 1 e ${MAX_PROGRAM_TEMPLATES} treinos.`,
      });
    }

    snapshot.templates.forEach((template, tIdx) => {
      const where = `no treino [${tIdx}]`;
      const templateName = typeof template.name === 'string' ? template.name.trim() : '';
      if (!templateName || templateName.length > 100) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Nome do treino [${tIdx}] deve ter entre 1 e 100 caracteres.`,
        });
      }
      if (
        template.shortIdentifier != null &&
        (typeof template.shortIdentifier !== 'string' || template.shortIdentifier.length > 10)
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Identificador curto do treino [${tIdx}] deve ter no máximo 10 caracteres.`,
        });
      }
      if (
        typeof template.orderInProgram !== 'number' ||
        !Number.isInteger(template.orderInProgram) ||
        template.orderInProgram < 0 ||
        template.orderInProgram > MAX_PROGRAM_TEMPLATES
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `orderInProgram inválido no treino [${tIdx}].`,
        });
      }
      if (
        template.dayOfWeek != null &&
        (typeof template.dayOfWeek !== 'string' ||
          template.dayOfWeek.trim().length === 0 ||
          template.dayOfWeek.length > 32)
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `dayOfWeek inválido no treino [${tIdx}].`,
        });
      }
      // T19.8: os dias da semana, na forma canônica. `dayOfWeek` (um dia, rótulo) é a forma de um
      // app anterior; as duas juntas não descrevem nenhuma versão do Spark e são recusadas.
      if (template.scheduledDays !== undefined) {
        if (template.dayOfWeek != null) {
          throw new BadRequestException({
            code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
            message: `Treino [${tIdx}] traz dayOfWeek e scheduledDays ao mesmo tempo.`,
          });
        }
        if (!isCanonicalWeekdayList(template.scheduledDays)) {
          throw new BadRequestException({
            code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
            message: `scheduledDays inválido no treino [${tIdx}].`,
          });
        }
      }
      rejectForbiddenKeys(template, where);
      // Um treino vazio **dentro** de um programa também é válido em V2: bloquear o programa
      // inteiro por causa de um treino que ainda não tem exercício é recusar o programa da pessoa
      // por um estado que o app dela permite.
      const minExercises = version >= 2 ? 0 : 1;
      if (
        !Array.isArray(template.exercises) ||
        template.exercises.length < minExercises ||
        template.exercises.length > 30
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Treino [${tIdx}] deve conter entre ${minExercises} e 30 exercícios.`,
        });
      }
      this.validateExercises(template.exercises, `do treino [${tIdx}] `, version, customRefs);
    });

    requireEveryCustomUsed(
      customRefs,
      snapshot.templates.flatMap((template) =>
        Array.isArray(template.exercises) ? template.exercises : [],
      ),
    );
  }

  /**
   * Os exercícios CUSTOM da oferta (T19.H2 / V2). Devolve o conjunto de `ref` declaradas.
   *
   * O que é recusado aqui, e por quê:
   *
   * ```text
   * customExercises em V1        a V1 não tem essa forma, e aceitar seria inventar uma V1.5
   * ref fora de custom-N         a chave é do snapshot; qualquer outra forma sugere identidade global
   * ref repetida                 duas entradas com a mesma chave: o destinatário não saberia qual criar
   * nome vazio                   o único campo obrigatório de um exercício criado pelo usuário
   * ```
   */
  private validateCustomExercises(
    customExercises: readonly SharedCustomExerciseV2[] | undefined,
    version: number,
  ): Set<string> {
    if (customExercises === undefined) return new Set();

    if (version < 2) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: 'Exercícios personalizados exigem snapshotVersion 2.',
      });
    }

    if (!Array.isArray(customExercises) || customExercises.length > MAX_CUSTOM_EXERCISES) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `customExercises deve conter no máximo ${MAX_CUSTOM_EXERCISES} exercícios.`,
      });
    }

    const refs = new Set<string>();
    customExercises.forEach((custom, index) => {
      const label = `exercício personalizado [${index}]`;
      if (typeof custom?.ref !== 'string' || !CUSTOM_EXERCISE_REF_PATTERN.test(custom.ref)) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `ref inválida no ${label}.`,
        });
      }
      if (refs.has(custom.ref)) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `ref repetida no ${label}: ${custom.ref}.`,
        });
      }
      refs.add(custom.ref);

      rejectForbiddenKeys(custom, `no ${label}`);

      const name = typeof custom.name === 'string' ? custom.name.trim() : '';
      if (!name || name.length > MAX_CUSTOM_NAME_LENGTH) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Nome do ${label} deve ter entre 1 e ${MAX_CUSTOM_NAME_LENGTH} caracteres.`,
        });
      }
      requireOptionalText(
        custom.primaryMuscle,
        MAX_CUSTOM_MUSCLE_LENGTH,
        `primaryMuscle do ${label}`,
      );
      requireOptionalText(custom.equipment, MAX_CUSTOM_EQUIPMENT_LENGTH, `equipment do ${label}`);
      requireOptionalText(
        custom.description,
        MAX_CUSTOM_DESCRIPTION_LENGTH,
        `description do ${label}`,
      );
    });
    return refs;
  }

  /** As regras de um exercício compartilhado — as mesmas para treino avulso e para programa. */
  private validateExercises(
    exercises: SharedExerciseV1[],
    owner: string,
    version: number,
    customRefs: ReadonlySet<string>,
  ): void {
    exercises.forEach((ex, idx) => {
      const label = `exercício ${owner}[${idx}]`;
      const hasCanonical = ex.canonicalExerciseId !== undefined;
      const hasCustomRef = ex.customExerciseRef !== undefined;

      if (hasCustomRef && version < 2) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Exercício ${owner}[${idx}] usa customExerciseRef, que exige snapshotVersion 2.`,
        });
      }

      // Exatamente uma identidade. As duas juntas seriam duas afirmações sobre o mesmo
      // exercício; nenhuma deixaria o destinatário sem saber o que criar.
      if (hasCanonical === hasCustomRef) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message:
            `Exercício ${owner}[${idx}] precisa trazer exatamente um de canonicalExerciseId ` +
            `ou customExerciseRef.`,
        });
      }

      if (hasCanonical) {
        if (
          typeof ex.canonicalExerciseId !== 'string' ||
          !CANONICAL_EXERCISE_ID_PATTERN.test(ex.canonicalExerciseId)
        ) {
          throw new BadRequestException({
            code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
            message: `Exercício ${owner}[${idx}] sem canonicalExerciseId válido.`,
          });
        }
      } else if (
        typeof ex.customExerciseRef !== 'string' ||
        !customRefs.has(ex.customExerciseRef)
      ) {
        // Uma referência que não resolve chegaria ao destinatário como um exercício sem conteúdo.
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `Exercício ${owner}[${idx}] referencia um customExerciseRef inexistente.`,
        });
      }

      rejectForbiddenKeys(ex, `no ${label}`);

      if (typeof ex.sortOrder !== 'number' || ex.sortOrder < 0 || ex.sortOrder > 30) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `sortOrder inválido no ${label}.`,
        });
      }

      if (typeof ex.targetSets !== 'number' || ex.targetSets < 1 || ex.targetSets > 20) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `targetSets inválido no ${label} (1..20).`,
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
          message: `Faixa de repetições inválida no ${label} (minReps <= maxReps).`,
        });
      }

      if (
        typeof ex.restDurationSeconds !== 'number' ||
        ex.restDurationSeconds < 0 ||
        ex.restDurationSeconds > 600
      ) {
        throw new BadRequestException({
          code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
          message: `restDurationSeconds inválido no ${label} (0..600).`,
        });
      }
    });
  }

  /**
   * O detalhe de uma oferta, com o snapshot **no campo do seu tipo** (T19.3).
   *
   * O JSON gravado é interpretado pelo `share_type` da linha — nunca pela forma do conteúdo. Um
   * cliente anterior à T19.3 lê `snapshot` ausente numa oferta de programa e não tem o que
   * importar, que é o comportamento certo para quem não conhece o tipo.
   */
  private toDetailDto(
    stored: StoredWorkoutShare,
    sender: { socialId: string; displayName: string },
    recipient: { socialId: string; displayName: string },
  ): WorkoutShareDetailDto {
    const base = {
      shareId: stored.id,
      shareType: stored.share_type,
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
    };
    const parsed = JSON.parse(stored.snapshot_json) as WorkoutShareSnapshotV1['snapshot'];
    return stored.share_type === 'WORKOUT_PROGRAM'
      ? { ...base, programSnapshot: parsed as WorkoutProgramShareSnapshotV1 }
      : { ...base, snapshot: parsed as WorkoutTemplateShareSnapshotV1 };
  }
}

/**
 * O que **nunca** viaja num snapshot, em nenhum nível — carga, nota, máquina, identidade local ou
 * de sync, dono. A allowlist do validador estrutural já recusa qualquer chave desconhecida; esta
 * lista é a segunda linha, com nome, para que o motivo apareça na resposta.
 */
const FORBIDDEN_SNAPSHOT_KEYS = [
  'plannedWeight',
  'notes',
  'machineLabel',
  'localId',
  'syncId',
  'programId',
  'templateId',
  'ownerUid',
  'userId',
  'isCurrent',
  'externalId',
  // T19.H2: o que um exercício CUSTOM tem no aparelho de quem compartilha e que não significa
  // nada no de quem recebe — a foto é um arquivo local, `canonicalId`/`slug` são do catálogo, e
  // `isUserCreated`/`origin` são estado do dono.
  'canonicalId',
  'customPhotoUri',
  'mediaUrl',
  'gifUrl',
  'externalExerciseId',
  'contentVersion',
  'isUserCreated',
  'origin',
  'slug',
] as const;

function rejectForbiddenKeys(object: unknown, where: string): void {
  for (const key of FORBIDDEN_SNAPSHOT_KEYS) {
    if (key in (object as Record<string, unknown>)) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `Campo proibido ${where}: ${key}.`,
      });
    }
  }
}

/** A versão do snapshot, recusada por nome quando o servidor não a conhece. */
function requireSupportedVersion(value: unknown): number {
  if (typeof value !== 'number' || !SUPPORTED_SNAPSHOT_VERSIONS.includes(value)) {
    throw new BadRequestException({
      code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
      message: `Versão do snapshot não suportada: ${String(value)}.`,
    });
  }
  return value;
}

function requireOptionalText(value: unknown, maxLength: number, label: string): void {
  if (value === undefined || value === null) return;
  if (typeof value !== 'string' || value.length > maxLength) {
    throw new BadRequestException({
      code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
      message: `${label} deve ser texto de no máximo ${maxLength} caracteres.`,
    });
  }
}

/**
 * Todo CUSTOM declarado precisa ser usado por algum exercício da oferta.
 *
 * Conteúdo que ninguém referencia é peso escolhido pelo remetente dentro da folga do teto, e
 * atravessa o servidor até a tela de outra pessoa sem nunca aparecer como exercício. A oferta
 * descreve o que ela usa.
 */
function requireEveryCustomUsed(
  refs: ReadonlySet<string>,
  exercises: readonly SharedExerciseV1[],
): void {
  if (refs.size === 0) return;
  const used = new Set(
    exercises
      .map((exercise) => exercise?.customExerciseRef)
      .filter((ref): ref is string => typeof ref === 'string'),
  );
  for (const ref of refs) {
    if (!used.has(ref)) {
      throw new BadRequestException({
        code: WorkoutShareErrorCodes.INVALID_SNAPSHOT,
        message: `customExercises traz ${ref}, que nenhum exercício da oferta usa.`,
      });
    }
  }
}

/**
 * A recusa de um compartilhamento sem amizade — e também a de um `socialId` que não existe. Uma
 * função só porque as duas precisam ser **a mesma** resposta, palavra por palavra.
 */
function friendshipRequired(): ForbiddenException {
  return new ForbiddenException({
    code: WorkoutShareErrorCodes.FRIENDSHIP_REQUIRED,
    message: 'Compartilhamento permitido apenas entre amigos.',
  });
}
