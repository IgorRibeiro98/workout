import { Inject, Injectable } from '@nestjs/common';
import { createHash, randomUUID } from 'node:crypto';
import type { Readable } from 'node:stream';
import type { PoolClient } from 'pg';
import { hashAccountUid } from '../../common/account-uid-hash';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { AccountMutationFencedError } from '../../database/account-mutation-fence';
import { isPgConstraintError } from '../../database/database.errors';
import { accountDeletedException, uidPrefix } from '../auth/bearer-auth.guard';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { SocialContentRateLimiter } from './social-content.rate-limit';
import { MEDIA_PENDING_TTL_MS } from './social-media.limits';
import { ImageProcessingError, SocialMediaProcessor } from './social-media.processor';
import { SocialMediaRepository, type StoredCheckInMedia } from './social-media.repository';
import { ObjectStorageUnavailableError } from '../../object-storage/object-storage.client';
import { SOCIAL_MEDIA_STORE, contentHashOf, type SocialMediaStore } from './social-media.store';
import { SocialRepository } from './social.repository';
import {
  CHECKIN_CLOCK_SKEW_TOLERANCE_MS,
  CHECKIN_WINDOW_MS,
  type UploadedMediaDto,
} from './workout-checkin.contract';
import { WorkoutCheckInErrors } from './workout-checkin.errors';

/** Um upload, já validado. O dono **nunca** vem daqui: ele sai do token (§14 da T17.8). */
export interface UploadMediaCommand {
  readonly ownerUid: string;
  readonly requestId: string;
  readonly sessionSyncId: string;
  readonly clientUploadId: string;
  readonly bytes: Buffer;
}

/**
 * A mídia dos check-ins (T17.9, Etapa B).
 *
 * ```text
 * Photo Picker (Android)          ← sem permissão ampla de galeria (§44), sem CAMERA (§45)
 *        │
 *        ▼  redução opcional no aparelho, só para economizar banda (§46)
 * POST /v1/social/checkin-media   ← exige sessão candidata elegível (§32)
 *        │
 *        ▼  decode REAL — o Content-Type não participa (§14)
 *        ▼  valida formato, animação, pixels e arestas (§13/§20)
 *        ▼  rotate() aplica a orientação e descarta o EXIF inteiro (§16)
 *        ▼  resize 1600px + re-encode WebP (§15/§18)
 *        ▼  quota da conta (§29/§30)
 * SocialMediaStore.write          ← chave opaca gerada no servidor (§23/§24/§25); disco ou bucket (T18.1)
 *        │
 *        ▼  linha PENDING com prazo de 1h (§38)
 * mediaId
 *        │
 *        ▼  POST /v1/social/workout-checkins { sessionSyncId, clientRequestId, caption?, mediaId? }
 * ATTACHED
 * ```
 *
 * ## O upload não é armazenamento genérico (§32)
 *
 * Toda mídia nasce vinculada a uma **sessão candidata**: a mesma verificação de elegibilidade que
 * o check-in faz — dona da conta, `WORKOUT_SESSION`, sem tombstone, `COMPLETED`, dentro de 48h.
 * Sem isso, `POST /v1/social/checkin-media` seria um serviço de hospedagem de imagens autenticado
 * de graça, e a quota por conta seria a única coisa entre ele e um cliente com paciência.
 *
 * ## O original é descartado (§17)
 *
 * O `Buffer` recebido vive na memória da requisição e some com ela. Ele **nunca** é escrito em
 * disco — nem "por um instante, para processar". Um arquivo temporário com o original é um arquivo
 * com GPS dentro, e a diferença entre "descartado" e "descartado, exceto quando o processo morre
 * no meio" é a diferença entre a promessa e a verdade.
 */
@Injectable()
export class SocialMediaService {
  constructor(
    private readonly repository: SocialMediaRepository,
    private readonly socialRepository: SocialRepository,
    private readonly processor: SocialMediaProcessor,
    private readonly rateLimiter: SocialContentRateLimiter,
    @Inject(SOCIAL_MEDIA_STORE) private readonly store: SocialMediaStore,
    @Inject(CANONICAL_TRAINING_SOURCE)
    private readonly canonicalTraining: CanonicalTrainingSource,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  /**
   * `POST /v1/social/checkin-media`.
   *
   * A ordem é deliberada, e cada passo evita trabalho do seguinte: teto de requisições, perfil
   * ativo, idempotência, tamanho bruto, sessão elegível, e só então a decodificação — que é a
   * única operação cara aqui. Decodificar antes de saber se a conta pode publicar faria de uma
   * conta desativada um jeito barato de gastar CPU do servidor.
   */
  async upload(command: UploadMediaCommand): Promise<UploadedMediaDto> {
    const { ownerUid, requestId, sessionSyncId, clientUploadId, bytes } = command;

    await this.requireActiveProfile(ownerUid);

    const inputContentHash = createHash('sha256').update(bytes).digest('hex');

    const existing = await this.repository.findByOwnerAndUpload(ownerUid, clientUploadId);
    if (existing && existing.status !== 'DELETED') {
      if (existing.sourceSessionSyncId !== sessionSyncId) {
        throw WorkoutCheckInErrors.mediaNotFound();
      }

      if (existing.inputContentHash !== null && existing.inputContentHash !== inputContentHash) {
        throw WorkoutCheckInErrors.mediaUploadConflict();
      }

      return this.toDto(existing);
    }

    if (!this.rateLimiter.tryAcquireUpload(ownerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }

    if (bytes.length === 0) {
      throw WorkoutCheckInErrors.invalidImage('a imagem enviada está vazia');
    }
    if (bytes.length > this.config.socialMediaMaxUploadBytes) {
      throw WorkoutCheckInErrors.mediaTooLarge();
    }

    await this.assertSessionEligible(ownerUid, sessionSyncId);

    const processed = await this.processor.process(bytes).catch((error: unknown) => {
      if (error instanceof ImageProcessingError) {
        this.logger.info('social.media.rejected', {
          requestId,
          uidPrefix: uidPrefix(ownerUid),
          rejection: error.rejection,
          inputBytes: bytes.length,
        });
        throw WorkoutCheckInErrors.invalidImage(describe(error.rejection));
      }
      throw error;
    });

    const used = await this.repository.usedBytes(ownerUid);
    if (used + processed.bytes.length > this.config.socialMediaMaxUserBytes) {
      throw WorkoutCheckInErrors.mediaQuotaExceeded();
    }

    const now = this.clock.now();
    const contentHash = contentHashOf(processed.bytes);

    if (existing) {
      await this.repository.deleteRow(existing.id);
    }

    const storageKey = this.store.newStorageKey();
    try {
      await this.store.write(storageKey, processed.bytes);
    } catch (error) {
      // Armazenamento fora do ar é `503`, e o cliente tenta de novo com o mesmo `clientUploadId`
      // (§36). Nenhuma linha foi escrita: não há o que limpar.
      if (error instanceof ObjectStorageUnavailableError) {
        throw WorkoutCheckInErrors.unavailable('não foi possível guardar a imagem agora');
      }
      throw error;
    }

    const media: StoredCheckInMedia = {
      id: randomUUID(),
      ownerUid,
      sourceSessionSyncId: sessionSyncId,
      clientUploadId,
      storageKey,
      mimeType: processed.mimeType,
      byteSize: processed.bytes.length,
      width: processed.width,
      height: processed.height,
      contentHash,
      inputContentHash,
      status: 'PENDING',
      createdAt: now,
      expiresAt: now + MEDIA_PENDING_TTL_MS,
      attachedCheckInId: null,
      deletedAt: null,
    };

    try {
      await this.repository.create(media, hashAccountUid(this.config, ownerUid));
    } catch (error) {
      await this.store.remove(storageKey).catch(() => undefined);

      // A conta foi excluída durante o processamento da imagem, entre o `BearerAuthGuard` e este
      // `INSERT` (T18.1.1 §2): o Account Mutation Fence recusou a escrita, e o objeto que acabou de
      // subir já foi removido acima.
      if (error instanceof AccountMutationFencedError) {
        throw accountDeletedException();
      }

      if (!isPgConstraintError(error)) {
        throw error;
      }
      const winner = await this.repository.findByOwnerAndUpload(ownerUid, clientUploadId);
      if (winner && winner.status !== 'DELETED') {
        return this.toDto(winner);
      }
      throw WorkoutCheckInErrors.unavailable('não foi possível enviar a imagem agora');
    }

    this.logger.info('social.media.uploaded', {
      requestId,
      uidPrefix: uidPrefix(ownerUid),
      mediaIdPrefix: media.id.slice(0, 8),
      processedBytes: media.byteSize,
      width: media.width,
      height: media.height,
      sourceFormat: processed.sourceFormat,
      status: media.status,
    });

    return this.toDto(media);
  }

  /**
   * `GET /v1/social/media/{mediaId}` — os bytes, para quem pode vê-los (§49/§50).
   */
  async openViewable(
    viewerUid: string,
    mediaId: string,
  ): Promise<{ stream: Readable; mimeType: string; byteSize: number } | null> {
    const found = await this.repository.findViewableStorageKey(viewerUid, mediaId);
    if (!found) {
      return null;
    }

    // Só a **ausência** vira `null` (→ 404). Uma falha do armazenamento — bucket fora, timeout,
    // permissão — sobe como `503`: transformá-la em "não encontrada" esconderia um incidente de
    // infraestrutura atrás de um erro que o app trata como definitivo (T18.1 §40).
    let stream: Readable | null;
    try {
      stream = await this.store.openRead(found.storageKey);
    } catch (error) {
      if (error instanceof ObjectStorageUnavailableError) {
        throw WorkoutCheckInErrors.unavailable('a imagem não está disponível agora');
      }
      throw error;
    }
    if (!stream) {
      this.logger.warn('social.media.file_missing', {
        uidPrefix: uidPrefix(viewerUid),
        mediaIdPrefix: mediaId.slice(0, 8),
      });
      return null;
    }
    return { stream, mimeType: found.mimeType, byteSize: found.byteSize };
  }

  /**
   * Anexa a mídia a um check-in recém-criado (§34/§35/§148).
   */
  async attachToCheckIn(
    mediaId: string,
    ownerUid: string,
    sessionSyncId: string,
    checkInId: string,
    client?: PoolClient,
  ): Promise<boolean> {
    return await this.repository.attach(mediaId, ownerUid, sessionSyncId, checkInId, client);
  }

  /** A visibilidade da foto morre junto com a publicação (§99/§100). O arquivo sai no cleanup. */
  async revokeForCheckIn(checkInId: string, now: number, client?: PoolClient): Promise<void> {
    await this.repository.markDeletedByCheckIn(checkInId, now, client);
  }

  // ------------------------------------------------------------------ internas

  private async requireActiveProfile(ownerUid: string) {
    const account = await this.socialRepository.find(ownerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw WorkoutCheckInErrors.socialNotEnabled();
    }
    return account.profile;
  }

  /**
   * A mesma elegibilidade do check-in (§32), pela mesma fonte canônica.
   */
  private async assertSessionEligible(ownerUid: string, sessionSyncId: string): Promise<void> {
    const session = await this.canonicalTraining.findSessionForCheckIn(ownerUid, sessionSyncId);
    if (!session || session.deleted) {
      throw WorkoutCheckInErrors.sessionNotFound();
    }
    if (session.status !== 'COMPLETED') {
      throw WorkoutCheckInErrors.sessionNotCompleted();
    }
    const finishedAt = session.finishedAt;
    const now = this.clock.now();
    if (finishedAt === null) {
      throw WorkoutCheckInErrors.windowExpired();
    }
    if (finishedAt > now + CHECKIN_CLOCK_SKEW_TOLERANCE_MS) {
      throw WorkoutCheckInErrors.windowExpired();
    }
    if (now - finishedAt > CHECKIN_WINDOW_MS) {
      throw WorkoutCheckInErrors.windowExpired();
    }
  }

  private toDto(media: StoredCheckInMedia): UploadedMediaDto {
    return {
      mediaId: media.id,
      width: media.width,
      height: media.height,
      byteSize: media.byteSize,
      expiresAt: media.expiresAt ?? media.createdAt + MEDIA_PENDING_TTL_MS,
    };
  }
}

/** A classe da recusa, em texto para o usuário. Nunca a mensagem crua da biblioteca de imagem. */
function describe(rejection: ImageProcessingError['rejection']): string {
  switch (rejection) {
    case 'UNSUPPORTED_FORMAT':
      return 'formato de imagem não suportado: envie JPEG, PNG ou WebP';
    case 'ANIMATED_NOT_SUPPORTED':
      return 'imagens animadas não são aceitas';
    case 'DIMENSIONS_TOO_LARGE':
      return 'a imagem tem dimensões maiores que o permitido';
    case 'OUTPUT_TOO_LARGE':
      return 'não foi possível reduzir a imagem para o tamanho permitido';
    default:
      return 'não foi possível ler a imagem enviada';
  }
}
