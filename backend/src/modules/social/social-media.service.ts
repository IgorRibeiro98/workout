import { Inject, Injectable } from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import type { Readable } from 'node:stream';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { uidPrefix } from '../auth/bearer-auth.guard';
import {
  CANONICAL_TRAINING_SOURCE,
  type CanonicalTrainingSource,
} from './canonical-training.source';
import { SocialContentRateLimiter } from './social-content.rate-limit';
import { MEDIA_PENDING_TTL_MS } from './social-media.limits';
import { ImageProcessingError, SocialMediaProcessor } from './social-media.processor';
import { SocialMediaRepository, type StoredCheckInMedia } from './social-media.repository';
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
 * SocialMediaStore.write          ← chave opaca gerada no servidor (§23/§24/§25)
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

    if (!this.rateLimiter.tryAcquireUpload(ownerUid)) {
      throw WorkoutCheckInErrors.rateLimited();
    }
    this.requireActiveProfile(ownerUid);

    // §36 — retry do mesmo upload converge no **mesmo** `mediaId`, sem duplicar arquivo.
    //
    // A conferência do conteúdo é por hash da representação sanitizada: reusar o
    // `clientUploadId` para uma imagem diferente descreve um cliente confuso, e atender seria
    // concordar com a confusão. Mas o hash só existe depois de processar — então a igualdade de
    // sessão é conferida aqui, e a de conteúdo logo abaixo.
    const existing = this.repository.findByOwnerAndUpload(ownerUid, clientUploadId);
    if (existing && existing.status !== 'DELETED') {
      if (existing.sourceSessionSyncId !== sessionSyncId) {
        throw WorkoutCheckInErrors.mediaNotFound();
      }
      return this.toDto(existing);
    }

    if (bytes.length === 0) {
      throw WorkoutCheckInErrors.invalidImage('a imagem enviada está vazia');
    }
    // Relido aqui além do parser: um `Content-Length` mentiroso passa pelo parser e não passa por
    // este `if`, porque este mede os bytes que realmente chegaram.
    if (bytes.length > this.config.socialMediaMaxUploadBytes) {
      throw WorkoutCheckInErrors.mediaTooLarge();
    }

    this.assertSessionEligible(ownerUid, sessionSyncId);

    const processed = await this.processor.process(bytes).catch((error: unknown) => {
      if (error instanceof ImageProcessingError) {
        this.logger.info('social.media.rejected', {
          requestId,
          uidPrefix: uidPrefix(ownerUid),
          rejection: error.rejection,
          // O tamanho bruto é metadata técnica; os bytes, o nome do arquivo e o caminho não são
          // registrados em lugar nenhum (§161/§162).
          inputBytes: bytes.length,
        });
        throw WorkoutCheckInErrors.invalidImage(describe(error.rejection));
      }
      throw error;
    });

    // §29/§30 — a quota conta `PENDING` junto com `ATTACHED`, porque as duas ocupam disco.
    const used = this.repository.usedBytes(ownerUid);
    if (used + processed.bytes.length > this.config.socialMediaMaxUserBytes) {
      throw WorkoutCheckInErrors.mediaQuotaExceeded();
    }

    const now = this.clock.now();
    const contentHash = contentHashOf(processed.bytes);

    // Uma linha `DELETED` com o mesmo `clientUploadId` bloquearia a `UNIQUE`. Ela é removida junto
    // com o arquivo pelo cleaner; até lá, tentar de novo com o mesmo identificador precisa
    // funcionar — o usuário não tem como saber que uma limpeza está pendente.
    if (existing) {
      this.repository.deleteRow(existing.id);
    }

    const storageKey = this.store.newStorageKey();
    // O arquivo primeiro, a linha depois. Se a escrita falhar, não existe metadata apontando para
    // nada; se a linha falhar, sobra um arquivo sem metadata — que é exatamente o que a varredura
    // de órfãos (§140) existe para recolher. A ordem inversa produziria metadata apontando para um
    // arquivo que nunca existiu, e §141 teria de tratar isso no caminho de leitura de todo mundo.
    await this.store.write(storageKey, processed.bytes);

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
      status: 'PENDING',
      createdAt: now,
      expiresAt: now + MEDIA_PENDING_TTL_MS,
      attachedCheckInId: null,
      deletedAt: null,
    };

    try {
      this.repository.create(media);
    } catch (error) {
      // A corrida de dois uploads simultâneos com o mesmo `clientUploadId`: a `UNIQUE` recusa o
      // segundo. Devolvemos o vencedor e apagamos o arquivo que não vai ser usado — deixá-lo seria
      // criar um órfão de propósito. A decisão lê a **propriedade** `code`, e não `instanceof`:
      // sob Jest um erro nativo do `better-sqlite3` pode atravessar realms.
      await this.store.remove(storageKey).catch(() => undefined);
      const code = (error as { code?: unknown }).code;
      if (typeof code !== 'string' || !code.startsWith('SQLITE_CONSTRAINT')) {
        throw error;
      }
      const winner = this.repository.findByOwnerAndUpload(ownerUid, clientUploadId);
      if (winner && winner.status !== 'DELETED') {
        return this.toDto(winner);
      }
      throw WorkoutCheckInErrors.unavailable('não foi possível enviar a imagem agora');
    }

    // §160/§162 — evento, prefixo de uid, tamanho processado, dimensões e status. Nunca o caminho
    // do arquivo, o nome original, os bytes, a chave de armazenamento inteira ou o uid completo.
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
   *
   * `null` cobre inexistente, de não-amigo, em bloqueio (nas duas direções), de autor com Social
   * desativado, de check-in excluído e ainda não anexada. O controller devolve `404` para todos —
   * conhecer o `mediaId` não é autorização (§51), e distinguir os casos diria a quem perguntou o
   * que existe na conta dos outros.
   *
   * O arquivo ausente (§141) também vira `null`: um restore em que o banco veio e a mídia não pode
   * deixar o Feed sem imagem, mas **não** pode derrubar o backend nem expor o caminho no erro.
   */
  async openViewable(
    viewerUid: string,
    mediaId: string,
  ): Promise<{ stream: Readable; mimeType: string; byteSize: number } | null> {
    const found = this.repository.findViewableStorageKey(viewerUid, mediaId);
    if (!found) {
      return null;
    }

    const stream = await this.store.openRead(found.storageKey).catch(() => null);
    if (!stream) {
      // Falha operacional registrada sem expor caminho nem nome de arquivo (§141/§161).
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
   *
   * Chamado **dentro** da transação de criação do check-in, e devolve `false` quando a mídia não é
   * desta conta, não é desta sessão ou já foi anexada. O `UPDATE` condicional carrega as três
   * regras; o serviço de check-in transforma o `false` em `MEDIA_NOT_FOUND` e desfaz a publicação.
   *
   * §148/§149 — o servidor revalida o dono **mesmo que o Android erre**. É isto que impede o
   * cenário de troca de conta durante o upload: a mídia da conta A não anexa a um check-in da
   * conta B, porque `owner_uid` está na cláusula `WHERE`.
   */
  attachToCheckIn(
    mediaId: string,
    ownerUid: string,
    sessionSyncId: string,
    checkInId: string,
  ): boolean {
    return this.repository.attach(mediaId, ownerUid, sessionSyncId, checkInId);
  }

  /** A visibilidade da foto morre junto com a publicação (§99/§100). O arquivo sai no cleanup. */
  revokeForCheckIn(checkInId: string, now: number): void {
    this.repository.markDeletedByCheckIn(checkInId, now);
  }

  // ------------------------------------------------------------------ internas

  private requireActiveProfile(ownerUid: string) {
    const account = this.socialRepository.find(ownerUid);
    if (!account || account.profile.status !== 'ACTIVE') {
      throw WorkoutCheckInErrors.socialNotEnabled();
    }
    return account.profile;
  }

  /**
   * A mesma elegibilidade do check-in (§32), pela mesma fonte canônica.
   *
   * Não é uma cópia da regra: é a mesma operação (`findSessionForCheckIn`) do mesmo adapter que
   * responde perfil, desafio, atividade e check-in. Uma segunda definição de "sessão publicável"
   * apareceria como um upload aceito para um treino que o check-in depois recusa — e o usuário
   * teria gastado banda por nada.
   */
  private assertSessionEligible(ownerUid: string, sessionSyncId: string): void {
    const session = this.canonicalTraining.findSessionForCheckIn(ownerUid, sessionSyncId);
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
