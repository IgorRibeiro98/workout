import { Injectable } from '@nestjs/common';
import { FixedWindowRateLimiter } from '../../common/rate-limiter';
import { SOCIAL_CONTENT_RATE_LIMIT } from './social-media.limits';

/**
 * Os tetos do conteúdo social gerado por usuário (T17.9 §155/§156/§157).
 *
 * Três, e separados de propósito: eles contêm ameaças diferentes e por isso têm números diferentes
 * (§31). O upload gasta disco e uma decodificação de até 20 MP por requisição — é o caro. O
 * comentário gasta uma linha, mas é o que aparece na tela de outra pessoa, então o teto existe
 * contra enxurrada, não contra custo. A reação é reversível e barata; o teto ali é leve porque
 * apertá-lo transformaria indecisão do usuário em erro na tela.
 *
 * A chave é sempre o `uid` autenticado, **nunca** o IP: em rede móvel e atrás de NAT o IP é
 * compartilhado, e o Caddy à frente faria todo mundo parecer o mesmo cliente. Em memória e sem
 * Redis, como todo limitador deste servidor — um processo, uma VPS (ADR-0001). Reiniciar zera as
 * janelas, e isso é aceitável: o teto contém laço, não cobra cota.
 */
@Injectable()
export class SocialContentRateLimiter {
  private readonly uploads = new FixedWindowRateLimiter(SOCIAL_CONTENT_RATE_LIMIT.upload);
  private readonly comments = new FixedWindowRateLimiter(SOCIAL_CONTENT_RATE_LIMIT.comment);
  private readonly reactions = new FixedWindowRateLimiter(SOCIAL_CONTENT_RATE_LIMIT.reaction);

  tryAcquireUpload(uid: string): boolean {
    return this.uploads.tryAcquire(uid);
  }

  tryAcquireComment(uid: string): boolean {
    return this.comments.tryAcquire(uid);
  }

  tryAcquireReaction(uid: string): boolean {
    return this.reactions.tryAcquire(uid);
  }
}
