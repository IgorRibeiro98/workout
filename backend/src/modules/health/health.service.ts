import { Inject, Injectable } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import { PostgresService } from '../../database/postgres.service';

export interface LivenessResult {
  readonly status: 'ok';
}

export interface ReadinessResult {
  readonly status: 'ok' | 'unavailable';
  readonly checks: {
    readonly config: boolean;
    readonly database: boolean;
    readonly migrations: boolean;
  };
}

/**
 * Liveness responde sobre o processo; readiness responde sobre a capacidade de servir.
 *
 * Nenhuma das duas consulta serviço externo — não há Firebase nem Gemini neste caminho, e não
 * haverá: a saúde do Spark Backend não pode depender de terceiros para ser reportada.
 */
@Injectable()
export class HealthService {
  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly postgres: PostgresService,
  ) {}

  liveness(): LivenessResult {
    return { status: 'ok' };
  }

  async readiness(): Promise<ReadinessResult> {
    // Se a injeção chegou até aqui, a configuração obrigatória passou pela validação do bootstrap.
    const configLoaded = this.config.databaseUrl.length > 0;
    const database = await this.postgres.checkHealth();

    const healthy = configLoaded && database.reachable && database.migrationsUpToDate;

    return {
      status: healthy ? 'ok' : 'unavailable',
      checks: {
        config: configLoaded,
        database: database.reachable,
        migrations: database.migrationsUpToDate,
      },
    };
  }
}
