import { Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { sha256Hex } from '../backup/canonical-json';
import {
  policyOf,
  SYNC_MUTATION_REASONS,
  type SyncMutationResult,
  type SyncMutationStatus,
  type SyncPullResponse,
  type SyncPushResponse,
} from './sync.contract';
import { SyncErrors } from './sync.errors';
import { SyncRateLimiter } from './sync.rate-limit';
import { SyncRepository } from './sync.repository';
import {
  parseCursor,
  parseLimit,
  parsePushRequest,
  validateMutation,
  type AcceptedMutation,
  type ParsedMutation,
} from './sync.validator';

/**
 * O caso de uso do sync incremental (T16.6).
 *
 * ## O que este serviço decide — e o que ele deliberadamente não decide
 *
 * Ele **detecta**: reenvio, conteúdo convergente, escrita stale, divergência de histórico
 * imutável, operação não suportada e payload fora do contrato.
 *
 * Ele **não resolve** conflito. Não existe aqui *last write wins*, "manda de novo com a revision
 * atual", desempate por `updatedAt` nem merge por campo. Uma mutação stale é recusada com a
 * revision atual, e a decisão volta para o aparelho — onde ela é preservada até a T16.7. Resolver
 * automaticamente seria apagar em silêncio a alteração de outro dispositivo, que é exatamente o
 * defeito que este protocolo existe para impedir.
 *
 * ## Ownership
 *
 * O dono é sempre `principal.uid`, que saiu de um Firebase ID Token verificado. O corpo tem
 * `deviceId` — metadado de diagnóstico — e **não** tem campo de dono. Conhecer o `deviceId` de
 * outro aparelho não dá acesso a nada.
 *
 * ## Resultado por item, transação por item
 *
 * Cada mutação é julgada e aplicada sozinha, na própria transação. Um lote não é atômico de
 * propósito: as mutações da Outbox são independentes entre si (agregados diferentes), e recusar
 * as quatro válidas porque a quinta ficou stale faria o aparelho reenviar tudo para sempre. O que
 * **é** atômico é cada aplicação — entidade, mudança e ledger, ou nada.
 */
@Injectable()
export class SyncService {
  constructor(
    private readonly repository: SyncRepository,
    private readonly rateLimiter: SyncRateLimiter,
    private readonly logger: SparkLogger,
  ) {}

  push(principal: AuthenticatedPrincipal, requestId: string, rawBody: string): SyncPushResponse {
    this.assertWithinRateLimit(principal.uid);

    const startedAt = Date.now();
    const request = parsePushRequest(rawBody);
    const results: SyncMutationResult[] = [];

    // Em ordem: a Outbox despacha por `id` crescente, e duas mutações do mesmo agregado precisam
    // chegar na ordem em que a intenção nasceu. Aplicar a segunda antes da primeira produziria
    // uma revision que descreve um estado intermediário abandonado.
    for (const mutation of request.mutations) {
      results.push(this.applyOne(principal.uid, request.deviceId, mutation));
    }

    this.logger.info('sync.push', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      devicePrefix: devicePrefix(request.deviceId),
      mutationCount: request.mutations.length,
      // Contagem por desfecho. Nem identidade de entidade, nem payload, nem nome de treino.
      outcomes: countBy(results),
      durationMs: Date.now() - startedAt,
    });

    return { results };
  }

  /**
   * A página de mudanças da conta autenticada depois do cursor.
   *
   * O cursor é posição no change log do **servidor**. Ele nunca é um timestamp, nunca vem do
   * relógio do aparelho, e um valor impossível é recusado em vez de virar zero em silêncio.
   */
  pull(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawCursor: unknown,
    rawLimit: unknown,
  ): SyncPullResponse {
    this.assertWithinRateLimit(principal.uid);

    const startedAt = Date.now();
    const cursor = parseCursor(rawCursor, this.repository.maxSequence());
    const limit = parseLimit(rawLimit);

    const { changes, hasMore } = this.repository.changesAfter(principal.uid, cursor, limit);
    const nextCursor = changes.length > 0 ? changes[changes.length - 1].serverSequence : cursor;

    this.logger.info('sync.pull', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      cursor,
      limit,
      changeCount: changes.length,
      nextCursor,
      hasMore,
      durationMs: Date.now() - startedAt,
    });

    return { changes, nextCursor, hasMore };
  }

  private assertWithinRateLimit(uid: string): void {
    if (!this.rateLimiter.tryAcquire(uid)) {
      throw SyncErrors.rateLimited();
    }
  }

  private applyOne(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
  ): SyncMutationResult {
    // 1. Idempotência primeiro, antes de qualquer validação de conteúdo.
    //
    // Um reenvio precisa devolver o resultado original mesmo que o servidor tenha ficado mais
    // exigente entre as duas tentativas: a mutação já foi aplicada, e "revalidar e recusar agora"
    // faria o aparelho reenviar para sempre algo que o servidor já tem.
    const ledger = this.repository.findMutation(ownerUid, mutation.clientMutationId);
    if (ledger) {
      const hash = mutation.canonicalPayload ? sha256Hex(mutation.canonicalPayload) : '';
      const sameIntent =
        ledger.entityType === mutation.entityType &&
        ledger.entitySyncId === mutation.entitySyncId &&
        ledger.operation === mutation.operation &&
        ledger.payloadHash === hash;

      if (!sameIntent) {
        // Mesma tentativa, conteúdo (ou alvo) outro. Aceitar apagaria em silêncio o que a
        // primeira significava; recusar deixa o cliente criar uma mutação nova, que é o correto.
        return {
          clientMutationId: mutation.clientMutationId,
          status: 'IDEMPOTENCY_CONFLICT',
        };
      }
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'ALREADY_APPLIED',
        serverRevision: ledger.resultRevision,
        serverSequence: ledger.resultSequence,
      };
    }

    // 2. Contrato: tipo, versão, payload, identidade, operação.
    const verdict = validateMutation(mutation);
    if (!verdict.ok) {
      return {
        clientMutationId: mutation.clientMutationId,
        status: verdict.rejected.unsupported ? 'UNSUPPORTED' : 'INVALID',
        reason: verdict.rejected.reason,
      };
    }
    const accepted = verdict.accepted;

    const entity = this.repository.findEntity(ownerUid, accepted.entityType, mutation.entitySyncId);
    const now = Date.now();

    // 3. Política do agregado. Histórico concluído e plano mutável não têm a mesma semântica.
    if (policyOf(accepted.entityType) === 'IMMUTABLE_HISTORY') {
      if (!entity) {
        return this.apply(ownerUid, deviceId, mutation, accepted, 1, now, 'APPLIED');
      }
      if (entity.payloadHash === accepted.payloadHash) {
        // A mesma sessão, byte a byte. Idempotente — e nunca `revision++`, que trataria histórico
        // como documento editável.
        return this.converged(ownerUid, deviceId, mutation, accepted, entity, now);
      }
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'IMMUTABLE_HISTORY_CONFLICT',
        currentRevision: entity.serverRevision,
        reason: SYNC_MUTATION_REASONS.IMMUTABLE_HISTORY,
      };
    }

    const base = mutation.baseRevision ?? 0;

    if (!entity) {
      if (base === 0) {
        return this.apply(ownerUid, deviceId, mutation, accepted, 1, now, 'APPLIED');
      }
      // O cliente diz conhecer uma revision que este servidor nunca emitiu para esta entidade.
      // Não é criação e não é atualização: é estado divergente, e quem decide é a T16.7.
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'STALE',
        currentRevision: 0,
      };
    }

    if (base > entity.serverRevision) {
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'INVALID',
        currentRevision: entity.serverRevision,
        reason: SYNC_MUTATION_REASONS.BASE_REVISION_AHEAD,
      };
    }

    if (accepted.payloadHash === entity.payloadHash) {
      // Conteúdo idêntico ao que já está gravado. Criar uma revision nova aqui seria inventar uma
      // mudança que não houve — e faria os outros aparelhos baixarem o que já têm.
      return this.converged(ownerUid, deviceId, mutation, accepted, entity, now);
    }

    if (base === entity.serverRevision && base > 0) {
      return this.apply(
        ownerUid,
        deviceId,
        mutation,
        accepted,
        entity.serverRevision + 1,
        now,
        'APPLIED',
      );
    }

    // `base < current` (outro aparelho já escreveu por cima), ou `base === 0` numa entidade que
    // existe (o cliente acha que está criando). Os dois são escrita stale: o servidor devolve a
    // revision atual e **não** aplica nada.
    return {
      clientMutationId: mutation.clientMutationId,
      status: 'STALE',
      currentRevision: entity.serverRevision,
    };
  }

  private apply(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    accepted: AcceptedMutation,
    nextRevision: number,
    now: number,
    status: SyncMutationStatus,
  ): SyncMutationResult {
    const applied = this.repository.applyMutation({
      ownerUid,
      deviceId,
      clientMutationId: mutation.clientMutationId,
      entityType: accepted.entityType,
      entitySyncId: mutation.entitySyncId,
      entitySchemaVersion: mutation.entitySchemaVersion,
      operation: 'UPSERT',
      baseRevision: mutation.baseRevision,
      canonicalPayload: accepted.canonicalPayload,
      payloadHash: accepted.payloadHash,
      nextRevision,
      now,
    });
    return {
      clientMutationId: mutation.clientMutationId,
      status,
      serverRevision: applied.serverRevision,
      serverSequence: applied.serverSequence,
    };
  }

  /**
   * O conteúdo já estava lá: nada é aplicado, e a tentativa passa a ter resposta guardada.
   *
   * Não é `APPLIED` — nenhuma revision foi gasta e nenhuma mudança foi anexada ao log. Para o
   * aparelho o efeito é o mesmo: ele confirma a Outbox e passa a conhecer a revision remota.
   */
  private converged(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    accepted: AcceptedMutation,
    entity: { serverRevision: number; lastServerSequence: number },
    now: number,
  ): SyncMutationResult {
    this.repository.recordConverged({
      ownerUid,
      deviceId,
      clientMutationId: mutation.clientMutationId,
      entityType: mutation.entityType,
      entitySyncId: mutation.entitySyncId,
      operation: mutation.operation,
      baseRevision: mutation.baseRevision,
      payloadHash: accepted.payloadHash,
      resultRevision: entity.serverRevision,
      resultSequence: entity.lastServerSequence,
      now,
    });
    return {
      clientMutationId: mutation.clientMutationId,
      status: 'ALREADY_APPLIED',
      serverRevision: entity.serverRevision,
      serverSequence: entity.lastServerSequence,
    };
  }
}

/** Quantos de cada desfecho. Só contagem — nenhuma identidade de entidade vai para log. */
function countBy(results: readonly SyncMutationResult[]): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const result of results) {
    counts[result.status] = (counts[result.status] ?? 0) + 1;
  }
  return counts;
}

/** Prefixo do `deviceId` para correlacionar log sem registrar a identidade inteira. */
function devicePrefix(deviceId: string): string {
  return deviceId.slice(0, 8);
}
