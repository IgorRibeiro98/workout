import { Inject, Injectable, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import {
  OBJECT_STORAGE_ORPHAN_GRACE_MS,
  OBJECT_STORAGE_ORPHAN_SCAN_PAGES,
} from '../../object-storage/object-storage.limits';
import { MEDIA_CLEANUP_BATCH } from './social-media.limits';
import { SocialMediaRepository } from './social-media.repository';
import { SOCIAL_MEDIA_STORE, type SocialMediaStore } from './social-media.store';

/**
 * A limpeza de mídia (T17.9 §39/§100/§140).
 *
 * ## O que ela recolhe, e por quê
 *
 * 1. **`PENDING` expirada** (§38/§39): alguém enviou uma foto, fechou o app e nunca publicou. A
 *    linha e o arquivo somem depois de uma hora. Sem isto, cada composição abandonada deixaria
 *    disco ocupado para sempre — e, como a quota conta `PENDING` (§30), a própria pessoa acabaria
 *    sem espaço por uploads que ela nem lembra de ter feito.
 * 2. **`DELETED`** (§99/§100): a publicação foi excluída, ou a conta foi. A visibilidade já caiu
 *    no instante da exclusão — é o `status` que a política consulta —, e o arquivo sai aqui. É
 *    isto que permite ao `DELETE` responder sem esperar I/O de sistema de arquivos.
 * 3. **órfãos** (§140): objeto no armazenamento sem linha em metadata. Ele nasce da janela entre
 *    gravar o objeto e inserir a linha — a ordem é deliberada (ver `SocialMediaService.upload`),
 *    porque o erro oposto seria metadata apontando para um objeto que nunca existiu, e aí **toda**
 *    leitura precisaria tratar o caso. Sem esta varredura, os órfãos cresceriam indefinidamente.
 *
 * ## O período de carência (T18.1 §15/§16)
 *
 * A mesma janela que produz órfãos produz falsos órfãos: um upload que já gravou o objeto e
 * ainda não commitou a linha é, para uma listagem, indistinguível de um resto de processo morto.
 * Até a T18.1 a varredura lia o banco **antes** de listar o disco e confiava nessa ordem — que
 * não fecha a janela: um upload que grava o objeto depois da leitura do banco e antes da
 * listagem aparecia no disco, não aparecia no conjunto conhecido, e era apagado logo antes de a
 * pessoa publicar a foto.
 *
 * Agora só é órfão o que não tem linha **e** é mais antigo que [OBJECT_STORAGE_ORPHAN_GRACE_MS].
 * A ordem das leituras deixou de importar: um objeto recente nunca é recolhido, tenha ou não
 * linha ainda — e um objeto antigo sem linha é, com certeza, resto.
 *
 * ## Por que não um scheduler novo
 *
 * §39 pede algo bounded e simples, e é o que isto é: um `setInterval` com lote fixo, no mesmo
 * desenho que `AccountDeletionReconciler` (T17.6) e `NotificationDispatcher` (T17.5) já usam neste
 * processo. Um processo, uma VPS (ADR-0001) — não há o que coordenar entre instâncias, e uma fila
 * externa seria infraestrutura nova para apagar arquivos.
 *
 * `isProcessing` impede sobreposição: uma varredura lenta não pode acumular execuções em cima de
 * si mesma. E toda passagem é **bounded** — por [MEDIA_CLEANUP_BATCH] remoções e por
 * [OBJECT_STORAGE_ORPHAN_SCAN_PAGES] páginas de listagem. Se houver muito a recolher, ela leva
 * vários ciclos, em vez de um ciclo que segura o event loop; e a listagem de órfãos continua de
 * onde parou (`orphanCursor`), em janela deslizante, para que um armazenamento grande seja
 * percorrido inteiro ao longo das varreduras sem nunca ser carregado de uma vez.
 */
@Injectable()
export class SocialMediaCleaner implements OnModuleInit, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private isProcessing = false;
  /** Onde a varredura de órfãos parou. Ausente = do começo do namespace. */
  private orphanCursor?: string;

  constructor(
    private readonly repository: SocialMediaRepository,
    @Inject(SOCIAL_MEDIA_STORE) private readonly store: SocialMediaStore,
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    @Inject(CLOCK) private readonly clock: Clock,
    private readonly logger: SparkLogger,
  ) {}

  onModuleInit(): void {
    this.timer = setInterval(() => {
      void this.sweep();
    }, this.config.socialMediaCleanupIntervalMs);
    // `unref` para que o intervalo não segure o processo vivo no shutdown — o mesmo motivo de o
    // Nest chamar `onApplicationShutdown`, com uma rede de segurança a mais.
    this.timer.unref?.();
  }

  onApplicationShutdown(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
  }

  /**
   * Uma passagem. Devolve quantos objetos foram removidos — é o que o teste observa.
   *
   * Pública de propósito: o teste a chama diretamente, com um relógio injetado, em vez de esperar
   * o intervalo. Um teste que dormisse para exercitar limpeza seria lento e instável.
   */
  async sweep(): Promise<number> {
    if (this.isProcessing) {
      return 0;
    }
    this.isProcessing = true;
    try {
      const collected = await this.collectExpiredAndDeleted();
      const orphans = await this.collectOrphans();
      return collected + orphans;
    } catch (error) {
      // Uma varredura que falha não pode derrubar o processo: ela é manutenção, e o Feed continua
      // funcionando sem ela. A mensagem descreve a falha, nunca a chave nem o caminho (§161).
      this.logger.warn('social.media.cleanup_failed', {
        error: error instanceof Error ? error.name : 'UNKNOWN',
      });
      return 0;
    } finally {
      this.isProcessing = false;
    }
  }

  private async collectExpiredAndDeleted(): Promise<number> {
    const due = await this.repository.findCollectable(this.clock.now(), MEDIA_CLEANUP_BATCH);
    let removed = 0;

    for (const item of due) {
      // O objeto primeiro, a linha depois: a ordem inversa deixaria um objeto sem metadata, que
      // é o órfão que a segunda varredura teria de recolher — trabalho a mais para nada.
      await this.store.remove(item.storageKey).catch(() => undefined);
      await this.repository.deleteRow(item.id);
      removed += 1;
    }

    if (removed > 0) {
      this.logger.info('social.media.collected', { removed });
    }
    return removed;
  }

  /**
   * Objetos sem metadata (§140), mais antigos que o período de carência (T18.1 §16).
   *
   * Roda depois da coleta acima de propósito: as linhas que acabaram de sair já levaram os
   * objetos junto, então esta varredura só encontra o que realmente ficou órfão.
   *
   * Página a página, e por página: a listagem é prefixada (`social/checkins/`) e bounded, e o
   * banco é consultado só pelas chaves daquela página — nunca "todas as chaves" de um lado ou do
   * outro. O cursor sobrevive entre varreduras; quando o namespace acaba, a próxima recomeça.
   */
  private async collectOrphans(): Promise<number> {
    const now = this.clock.now();
    let removed = 0;

    for (let pages = 0; pages < OBJECT_STORAGE_ORPHAN_SCAN_PAGES; pages += 1) {
      const page = await this.store.listObjects(this.orphanCursor);
      const candidates = page.objects.filter(
        (object) => now - object.createdAt >= OBJECT_STORAGE_ORPHAN_GRACE_MS,
      );
      const known = await this.repository.findExistingStorageKeys(
        candidates.map((object) => object.storageKey),
      );

      for (const object of candidates) {
        if (known.has(object.storageKey)) {
          continue;
        }
        if (removed >= MEDIA_CLEANUP_BATCH) {
          break;
        }
        await this.store.remove(object.storageKey).catch(() => undefined);
        removed += 1;
      }

      // A página inteira foi examinada (ou o lote encheu): o cursor avança de qualquer jeito —
      // o que sobrou continua órfão e continua antigo, e a próxima passagem pelo namespace o pega.
      this.orphanCursor = page.nextPageToken;
      if (this.orphanCursor === undefined || removed >= MEDIA_CLEANUP_BATCH) {
        break;
      }
    }

    if (removed > 0) {
      // Contagem, nunca chave nem caminho (§161/§162).
      this.logger.info('social.media.orphans_collected', { removed });
    }
    return removed;
  }
}
