import { Inject, Injectable } from '@nestjs/common';
import pino, { type Logger } from 'pino';
import { APP_CONFIG, AppConfig } from '../config/app-config';

/**
 * Logging estruturado do Spark Backend.
 *
 * Regra de conteúdo, herdada da política do Coach IA (PROJECT_RULES §13): log carrega **metadata
 * técnica**, nunca conteúdo. Não existe caminho neste logger que receba corpo de requisição,
 * header de autorização, Firebase ID Token, dado de treino, histórico ou texto livre do usuário —
 * o que for logado é escolhido explicitamente no ponto de chamada.
 *
 * O `redact` abaixo é a segunda linha de defesa: se algum código futuro passar um objeto de
 * requisição inteiro por engano, os campos sensíveis saem como `[Redacted]` em vez de vazarem.
 */
@Injectable()
export class SparkLogger {
  private readonly logger: Logger;

  constructor(@Inject(APP_CONFIG) config: AppConfig) {
    this.logger = pino({
      level: config.logLevel,
      base: { service: 'spark-backend' },
      redact: {
        paths: [
          'authorization',
          'headers.authorization',
          'req.headers.authorization',
          'headers.cookie',
          'req.headers.cookie',
          'req.body',
          'body',
          'token',
          'idToken',
          'password',
        ],
        censor: '[Redacted]',
      },
    });
  }

  info(event: string, fields: Record<string, unknown> = {}): void {
    this.logger.info({ event, ...fields });
  }

  warn(event: string, fields: Record<string, unknown> = {}): void {
    this.logger.warn({ event, ...fields });
  }

  error(event: string, fields: Record<string, unknown> = {}): void {
    this.logger.error({ event, ...fields });
  }

  child(fields: Record<string, unknown>): SparkLogger {
    const clone = Object.create(SparkLogger.prototype) as SparkLogger;
    Object.assign(clone, { logger: this.logger.child(fields) });
    return clone;
  }
}
