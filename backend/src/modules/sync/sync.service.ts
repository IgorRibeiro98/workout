import { Inject, Injectable } from '@nestjs/common';
import { hashAccountUid } from '../../common/account-uid-hash';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { AccountMutationFencedError } from '../../database/account-mutation-fence';
import type { AuthenticatedPrincipal } from '../auth/authenticated-principal';
import { accountDeletedException, uidPrefix } from '../auth/bearer-auth.guard';
import {
  type SyncEntityStateResponse,
  type SyncMutationResult,
  type SyncPullResponse,
  type SyncPushResponse,
} from './sync.contract';
import { SyncErrors } from './sync.errors';
import { SyncRateLimiter } from './sync.rate-limit';
import { SyncRepository } from './sync.repository';
import {
  parseCursor,
  parseEntityLookup,
  parseLimit,
  parsePushRequest,
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
 * ## Resultado por item, isolamento por item
 *
 * Cada mutação é julgada e aplicada sozinha, no próprio `SAVEPOINT` dentro da transação do push.
 * Um lote não é atômico de propósito: as mutações da Outbox são independentes entre si (agregados
 * diferentes), e recusar as quatro válidas porque a quinta ficou stale faria o aparelho reenviar
 * tudo para sempre. O que **é** atômico é cada aplicação — entidade, mudança e ledger, ou nada.
 */
@Injectable()
export class SyncService {
  constructor(
    private readonly repository: SyncRepository,
    private readonly rateLimiter: SyncRateLimiter,
    private readonly logger: SparkLogger,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
  ) {}

  async push(
    principal: AuthenticatedPrincipal,
    requestId: string,
    rawBody: string,
  ): Promise<SyncPushResponse> {
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

    // Em ordem: a Outbox despacha por `id` crescente, e duas mutações do mesmo agregado precisam
    // chegar na ordem em que a intenção nasceu. Aplicar a segunda antes da primeira produziria
    // uma revision que descreve um estado intermediário abandonado. Quem aplica é o repositório,
    // numa transação com um savepoint por mutação — o lote continua não sendo atômico.
    const results = await this.applyAll(principal.uid, request.deviceId, request.mutations);

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
    // O teto do cursor é o da **conta**, e não o do servidor: o global vazava quantas mudanças
    // todas as contas somadas já produziram, descobrível por busca binária sobre "aceito/recusado".
    const maxSeq = await this.repository.maxSequenceForOwner(principal.uid);
    const oldestSeq = await this.repository.oldestSequence(principal.uid);
    const cursor = parseCursor(rawCursor, maxSeq, oldestSeq);
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

  private async applyAll(
    ownerUid: string,
    deviceId: string,
    mutations: readonly ParsedMutation[],
  ): Promise<SyncMutationResult[]> {
    try {
      return await this.repository.executeAtomicMutations(
        ownerUid,
        deviceId,
        mutations,
        Date.now(),
        hashAccountUid(this.config, ownerUid),
      );
    } catch (error) {
      // A conta foi excluída entre o `BearerAuthGuard` e este push (T18.1.1 §2): o Account
      // Mutation Fence recusou a escrita. A resposta é a mesma que uma requisição nova receberia.
      if (error instanceof AccountMutationFencedError) {
        throw accountDeletedException();
      }
      throw error;
    }
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
