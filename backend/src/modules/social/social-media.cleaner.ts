import { Inject, Injectable, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { CLOCK, type Clock } from '../../common/clock';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
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
 * 3. **órfãos** (§140): arquivo no disco sem linha em metadata. Ele nasce da janela entre escrever
 *    o arquivo e inserir a linha — a ordem é deliberada (ver `SocialMediaService.upload`), porque
 *    o erro oposto seria metadata apontando para um arquivo que nunca existiu, e aí **toda**
 *    leitura precisaria tratar o caso. Sem esta varredura, os órfãos cresceriam indefinidamente.
 *
 * ## Por que não um scheduler novo
 *
 * §39 pede algo bounded e simples, e é o que isto é: um `setInterval` com lote fixo, no mesmo
 * desenho que `AccountDeletionReconciler` (T17.6) e `NotificationDispatcher` (T17.5) já usam neste
 * processo. Um processo, uma VPS (ADR-0001) — não há o que coordenar entre instâncias, e uma fila
 * externa seria infraestrutura nova para apagar arquivos.
 *
 * `isProcessing` impede sobreposição: uma varredura lenta não pode acumular execuções em cima de
 * si mesma. E toda passagem é **bounded** por [MEDIA_CLEANUP_BATCH] — se houver muito a recolher,
 * ela leva vários ciclos, em vez de um ciclo que segura o event loop.
 */
@Injectable()
export class SocialMediaCleaner implements OnModuleInit, OnApplicationShutdown {
  private timer?: NodeJS.Timeout;
  private isProcessing = false;

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
   * Uma passagem. Devolve quantos arquivos foram removidos — é o que o teste observa.
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
      // funcionando sem ela. A mensagem descreve a falha, nunca o caminho do arquivo (§161).
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
      // O arquivo primeiro, a linha depois: a ordem inversa deixaria um arquivo sem metadata, que
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
   * Arquivos sem metadata (§140).
   *
   * Roda depois da coleta acima de propósito: as linhas que acabaram de sair já levaram os
   * arquivos junto, então esta varredura só encontra o que realmente ficou órfão.
   *
   * O conjunto de chaves conhecidas é lido **antes** de listar o disco. A ordem importa: listar o
   * disco primeiro e consultar o banco depois criaria uma janela em que um upload concluído entre
   * as duas leituras pareceria órfão — e seria apagado logo depois de a pessoa publicá-lo.
   */
  private async collectOrphans(): Promise<number> {
    const known = await this.repository.allStorageKeys();
    const onDisk = await this.store.listKeys();

    let removed = 0;
    for (const key of onDisk) {
      if (known.has(key)) {
        continue;
      }
      if (removed >= MEDIA_CLEANUP_BATCH) {
        break;
      }
      await this.store.remove(key).catch(() => undefined);
      removed += 1;
    }

    if (removed > 0) {
      // Contagem, nunca chave nem caminho (§161/§162).
      this.logger.info('social.media.orphans_collected', { removed });
    }
    return removed;
  }
}
