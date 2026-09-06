import { Global, Module } from '@nestjs/common';
import { APP_CONFIG, AppConfig } from './app-config';

/**
 * A configuração é validada no bootstrap (`AppConfig.fromEnv`) e injetada como valor pronto.
 * O módulo não relê o ambiente: se chegou aqui, já é válida.
 */
@Global()
@Module({})
export class ConfigModule {
  static forRoot(config: AppConfig) {
    return {
      module: ConfigModule,
      providers: [{ provide: APP_CONFIG, useValue: config }],
      exports: [APP_CONFIG],
    };
  }
}
