import type { Request } from 'express';

/**
 * A requisição é para `/health/live` ou `/health/ready`?
 *
 * `req.originalUrl`, e **não** `req.path`: um middleware registrado pelo `MiddlewareConsumer` é
 * montado sob um padrão de rota, e a partir dali `req.path` é o resto relativo ao ponto de
 * montagem — não o caminho que o cliente pediu. Usar `req.path` aqui fazia o healthcheck receber
 * `Cache-Control: no-store` e, pior, ser barrado junto com a API durante a manutenção, que é
 * exatamente o contrário do desenho: quem faz healthcheck precisa distinguir "em manutenção" de
 * "morto".
 */
export function isHealthRoute(req: Request): boolean {
  const path = (req.originalUrl ?? '').split('?')[0];
  return path === '/health' || path.startsWith('/health/');
}
