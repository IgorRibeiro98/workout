import { Injectable, NestMiddleware } from '@nestjs/common';
import type { NextFunction, Request, Response } from 'express';
import { isHealthRoute } from './health-route';

/**
 * Os poucos headers de resposta que fazem sentido para **esta** API (T16.8 §90).
 *
 * O Spark Backend serve JSON para um app Android nativo. Ele não serve HTML, não tem sessão por
 * cookie, não tem formulário e não é aberto em navegador — então um pacote genérico de headers web
 * (CSP, HSTS por aplicação, `X-Frame-Options`, `Permissions-Policy`) seria copiar proteção contra
 * ameaças que esta superfície não tem, e criar a impressão de que ela está protegida por eles.
 *
 * O que fica, e por quê:
 *
 * - `X-Content-Type-Options: nosniff` — impede que um cliente intermediário reinterprete uma
 *   resposta JSON como outra coisa. Barato e sempre correto.
 * - `Cache-Control: no-store` — **toda** resposta desta API é dado de conta autenticada. Um proxy
 *   ou cliente que guarde em cache um snapshot de backup ou uma página de sync é vazamento entre
 *   contas no mesmo aparelho ou na mesma rede. `/health/*` fica de fora: é infraestrutura pública
 *   e sem dado.
 * - `Referrer-Policy: no-referrer` — irrelevante para o app oficial, relevante se alguma resposta
 *   for aberta em navegador durante diagnóstico.
 *
 * HSTS **não** é emitido aqui: quem termina o TLS é o Caddy, e é ele que deve declará-lo. O
 * processo Node fala HTTP puro atrás do proxy e não tem informação para prometer nada sobre TLS.
 */
@Injectable()
export class SecurityHeadersMiddleware implements NestMiddleware {
  use(req: Request, res: Response, next: NextFunction): void {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    if (!isHealthRoute(req)) {
      res.setHeader('Cache-Control', 'no-store');
    }
    next();
  }
}
