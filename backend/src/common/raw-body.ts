import type { Request } from 'express';

/**
 * O corpo cru da requisição, preservado pelo body parser (T16.4).
 *
 * Existe por um motivo só: o hash de integridade do backup é calculado sobre a **forma canônica
 * do texto recebido**, preservando os tokens numéricos originais. Um corpo já parseado teria
 * perdido essa informação, e Kotlin e TypeScript passariam a precisar formatar ponto flutuante
 * exatamente igual para o hash fechar — ver `modules/backup/canonical-json.ts`.
 *
 * Ele **não** vai para log: o `SparkLogger` já redige `req.body`/`body`, e nenhum ponto do código
 * registra este campo. Ele vive na requisição pelo tempo dela e some com ela.
 */
export interface RequestWithRawBody extends Request {
  rawBody?: string;
}
