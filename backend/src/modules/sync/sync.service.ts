import { Inject, Injectable } from '@nestjs/common';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { uidPrefix } from '../auth/bearer-auth.guard';
import { sha256Hex } from '../backup/canonical-json';
import { SyncEntityPolicyRegistry } from './sync.policy';
import {
  SYNC_MUTATION_REASONS,
  type SyncEntityStateResponse,
  type SyncMutationResult,
  type SyncMutationStatus,
  type SyncPullResponse,
  type SyncPushResponse,
} from './sync.contract';
import { SyncErrors } from './sync.errors';
import { SyncRateLimiter } from './sync.rate-limit';
import { SyncRepository, type StoredSyncEntity } from './sync.repository';
import {
  parseCursor,
  parseEntityLookup,
  parseLimit,
  parsePushRequest,
  validateMutation,
  type AcceptedMutation,
  type ParsedMutation,
} from './sync.validator';

/**
 * O caso de uso do sync incremental (T16.6) e das exclusões versionadas (T16.7).
 *
 * ## O que este serviço decide — e o que ele deliberadamente não decide
 *
 * Ele **detecta**: reenvio, conteúdo convergente, escrita stale, divergência de histórico
 * imutável, exclusão stale, `UPSERT` contra tombstone, operação não permitida pela política do
 * agregado e payload fora do contrato.
 *
 * Ele **não resolve** conflito. Não existe aqui *last write wins*, "manda de novo com a revision
 * atual", desempate por `updatedAt`, merge por campo nem `force`/`overwrite`. Uma mutação stale é
 * recusada com a revision atual, e a decisão volta para o aparelho — onde ela é preservada e onde
 * o **usuário** escolhe (T16.7). Resolver automaticamente seria apagar em silêncio a alteração de
 * outro dispositivo, que é exatamente o defeito que este protocolo existe para impedir.
 *
 * ## Exclusão é uma mudança, não a ausência de uma
 *
 * ```text
 * DELETE baseRevision = 5  →  tombstone revision 6  →  sync_change operation = DELETE
 * ```
 *
 * O tombstone é o que faz um aparelho que ficou meses offline **aprender** que a entidade morreu,
 * em vez de reenviá-la e ressuscitá-la. E `UPSERT` contra tombstone é sempre conflito: recriar o
 * que outro aparelho apagou é decisão do usuário, e ela nasce com `syncId` novo.
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
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  async push(principal: AuthenticatedPrincipal, requestId: string, rawBody: string): Promise<SyncPushResponse> {
    // Interruptor de escrita (T16.8 §121), antes de tudo: diante de um defeito grave no sync, o
    // que se quer é parar de gravar — não parar de responder. O pull continua, porque leitura não
    // corrompe nada, e a Outbox do aparelho permanece pendente porque 5xx nunca confirma.
    if (!this.config.syncWriteEnabled) {
      this.logger.warn('sync.write.disabled', { requestId, uidPrefix: uidPrefix(principal.uid) });
      throw SyncErrors.writeDisabled();
    }

    this.assertWithinRateLimit(principal.uid);

    const startedAt = Date.now();
    const request = parsePushRequest(rawBody);
    const results: SyncMutationResult[] = [];

    // Em ordem: a Outbox despacha por `id` crescente, e duas mutações do mesmo agregado precisam
    // chegar na ordem em que a intenção nasceu. Aplicar a segunda antes da primeira produziria
    // uma revision que descreve um estado intermediário abandonado.
    for (const mutation of request.mutations) {
      results.push(await this.applyOne(principal.uid, request.deviceId, mutation));
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
  async pull(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawCursor: unknown,
    rawLimit: unknown,
  ): Promise<SyncPullResponse> {
    this.assertWithinRateLimit(principal.uid);

    const startedAt = Date.now();
    const maxSeq = await this.repository.maxSequence();
    const oldestSeq = await this.repository.oldestSequence(principal.uid);
    const cursor = parseCursor(
      rawCursor,
      maxSeq,
      oldestSeq,
    );
    const limit = parseLimit(rawLimit);

    const { changes, hasMore } = await this.repository.changesAfter(principal.uid, cursor, limit);
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

  /**
   * O estado **atual** de um agregado da conta autenticada (T16.7.1).
   *
   * ## Por que esta leitura existe
   *
   * Um conflito guarda a cópia remota que o pull trouxe — validada quando chegou, e potencialmente
   * velha quando o usuário decide. Sem uma forma de perguntar "isso ainda é o que você tem?", o
   * aparelho aplicaria localmente uma versão que o servidor **já sabe** estar superada, e só
   * descobriria no ciclo seguinte. O pull não responde a essa pergunta: ele entrega mudanças em
   * sequência, e depois que o cursor passa de uma sequência aquela versão não é mais pedível.
   *
   * ## Estritamente somente leitura
   *
   * Um `SELECT`, e mais nada. Nenhuma `revision` é gasta, nenhuma linha entra em `sync_changes`,
   * nenhuma entrada nasce em `sync_mutations` e nenhum tombstone muda. Consultar o estado não é
   * um evento na vida da entidade, e transformá-lo em um faria os outros aparelhos baixarem uma
   * "mudança" que ninguém fez.
   *
   * ## Ownership
   *
   * O dono é `principal.uid`, do token verificado. Não existe `?ownerUid=`, e a consulta filtra
   * por conta no `WHERE`. Uma identidade que existe para **outra** conta é `404` — a mesma
   * resposta de uma que nunca existiu.
   */
  async entityState(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawEntityType: unknown,
    rawEntitySyncId: unknown,
  ): Promise<SyncEntityStateResponse> {
    this.assertWithinRateLimit(principal.uid);

    const startedAt = Date.now();
    const lookup = parseEntityLookup(rawEntityType, rawEntitySyncId);
    const entity = await this.repository.findEntitySnapshot(
      principal.uid,
      lookup.entityType,
      lookup.entitySyncId,
    );
    if (!entity) {
      throw SyncErrors.entityNotFound();
    }

    this.logger.info('sync.entity', {
      requestId,
      uidPrefix: uidPrefix(principal.uid),
      // Tipo e desfecho, nunca a identidade do agregado nem o conteúdo.
      entityType: lookup.entityType,
      serverRevision: entity.serverRevision,
      deleted: entity.deleted,
      durationMs: Date.now() - startedAt,
    });

    return {
      // Derivado do token verificado. É o que permite ao aparelho provar, **depois** da resposta,
      // que ela foi autenticada pela mesma conta dona do dataset local — uma troca de conta no
      // meio do voo não pode terminar em escrita cruzada.
      ownerUid: principal.uid,
      entityType: lookup.entityType,
      entitySyncId: lookup.entitySyncId,
      entitySchemaVersion: entity.entitySchemaVersion,
      serverRevision: entity.serverRevision,
      deleted: entity.deleted,
      payloadHash: entity.deleted ? null : entity.payloadHash,
      // O texto canônico guardado volta como valor JSON, igual ao pull. O que o cliente confere é
      // o `payloadHash`, calculado sobre a forma canônica.
      payload: entity.payload === null ? null : (JSON.parse(entity.payload) as unknown),
    };
  }

  private assertWithinRateLimit(uid: string): void {
    if (!this.rateLimiter.tryAcquire(uid)) {
      throw SyncErrors.rateLimited();
    }
  }

  private async applyOne(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
  ): Promise<SyncMutationResult> {
    // 1. Idempotência primeiro, antes de qualquer validação de conteúdo.
    //
    // Um reenvio precisa devolver o resultado original mesmo que o servidor tenha ficado mais
    // exigente entre as duas tentativas: a mutação já foi aplicada, e "revalidar e recusar agora"
    // faria o aparelho reenviar para sempre algo que o servidor já tem.
    const ledger = await this.repository.findMutation(ownerUid, mutation.clientMutationId);
    if (ledger) {
      // Uma exclusão não tem conteúdo, então o hash dela é vazio — e é a **intenção** (tipo,
      // identidade, operação) que o ledger compara para distinguir reenvio de mutação nova.
      const hash =
        mutation.operation === 'DELETE'
          ? ''
          : mutation.canonicalPayload
            ? sha256Hex(mutation.canonicalPayload)
            : '';
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

    const entity = await this.repository.findEntity(ownerUid, accepted.entityType, mutation.entitySyncId);
    const now = Date.now();

    if (accepted.operation === 'DELETE') {
      return this.applyDelete(ownerUid, deviceId, mutation, accepted, entity, now);
    }

    // 3. Tombstone antes de tudo: a entidade foi excluída, e este `UPSERT` a recriaria.
    //
    // É o caso do aparelho que ficou offline com a cópia antiga. Aceitar aqui — mesmo com
    // `baseRevision` "correta" — desfaria em silêncio uma exclusão que o usuário fez em outro
    // aparelho. Recriar é decisão dele, e ela nasce com `syncId` novo.
    if (entity?.deleted) {
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'REMOTE_DELETED',
        currentRevision: entity.serverRevision,
        reason: SYNC_MUTATION_REASONS.ENTITY_DELETED,
      };
    }

    // 4. Política do agregado. Histórico concluído e plano mutável não têm a mesma semântica.
    if (SyncEntityPolicyRegistry.isImmutableHistory(accepted.entityType)) {
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
      // Não é criação e não é atualização: é estado divergente, e quem decide é o usuário.
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

  /**
   * Uma exclusão (T16.7).
   */
  private async applyDelete(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    accepted: AcceptedMutation,
    entity: StoredSyncEntity | null,
    now: number,
  ): Promise<SyncMutationResult> {
    if (entity?.deleted) {
      // Já é tombstone. Idempotente: nenhuma revision nova, nenhuma mudança nova no log — e a
      // tentativa passa a ter resposta guardada, para que um reenvio não recomece o raciocínio.
      return this.converged(ownerUid, deviceId, mutation, accepted, entity, now);
    }

    const base = mutation.baseRevision ?? 0;

    if (!entity) {
      return this.deleteEntity(ownerUid, deviceId, mutation, accepted, 1, now);
    }

    if (base > entity.serverRevision) {
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'INVALID',
        currentRevision: entity.serverRevision,
        reason: SYNC_MUTATION_REASONS.BASE_REVISION_AHEAD,
      };
    }

    if (base !== entity.serverRevision) {
      // O aparelho quis apagar uma versão que já não é a atual. Quem chegou primeiro definiu a
      // revision seguinte, e o segundo recebe conflito — nunca uma exclusão silenciosa por cima
      // de uma alteração mais nova.
      return {
        clientMutationId: mutation.clientMutationId,
        status: 'STALE',
        currentRevision: entity.serverRevision,
      };
    }

    return this.deleteEntity(
      ownerUid,
      deviceId,
      mutation,
      accepted,
      entity.serverRevision + 1,
      now,
    );
  }

  private async deleteEntity(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    accepted: AcceptedMutation,
    nextRevision: number,
    now: number,
  ): Promise<SyncMutationResult> {
    const applied = await this.repository.applyDelete({
      ownerUid,
      deviceId,
      clientMutationId: mutation.clientMutationId,
      entityType: accepted.entityType,
      entitySyncId: mutation.entitySyncId,
      entitySchemaVersion: mutation.entitySchemaVersion,
      baseRevision: mutation.baseRevision,
      nextRevision,
      now,
    });
    return {
      clientMutationId: mutation.clientMutationId,
      status: 'APPLIED',
      serverRevision: applied.serverRevision,
      serverSequence: applied.serverSequence,
    };
  }

  private async apply(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    accepted: AcceptedMutation,
    nextRevision: number,
    now: number,
    status: SyncMutationStatus,
  ): Promise<SyncMutationResult> {
    const applied = await this.repository.applyMutation({
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
  private async converged(
    ownerUid: string,
    deviceId: string,
    mutation: ParsedMutation,
    accepted: AcceptedMutation,
    entity: { serverRevision: number; lastServerSequence: number },
    now: number,
  ): Promise<SyncMutationResult> {
    await this.repository.recordConverged({
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
