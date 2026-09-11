import { DynamicModule, Module } from '@nestjs/common';
import { MaintenanceController } from './maintenance.controller';
import { MaintenanceCoordinator } from './maintenance.coordinator';

/**
 * O módulo HTTP mínimo do serviço `spark-maintenance` (T18.2 §33).
 *
 * Só existe para `maintenance-main.ts`. Recebe um `MaintenanceCoordinator` **já construído** — via
 * `useValue` — em vez de reconstruir o grafo inteiro de `AppModule` (Social, Backup,
 * AccountDeletion, Auth, Database, Object Storage...) sob um segundo adaptador HTTP: fazer isso
 * registraria de volta todas as rotas de produto (`/v1/social/*`, `/v1/backups`, `/v1/account`...)
 * no serviço privado, exatamente a superfície larga que §33 pede para não ter. A instância real
 * vem de um `NestFactory.createApplicationContext(AppModule.forRoot(config))` — que **não** tem
 * adaptador HTTP e por isso não expõe controller nenhum — resolvida uma vez em `maintenance-main.ts`
 * e passada para aqui.
 */
@Module({})
export class MaintenanceHttpModule {
  static forRoot(coordinator: MaintenanceCoordinator): DynamicModule {
    return {
      module: MaintenanceHttpModule,
      controllers: [MaintenanceController],
      providers: [{ provide: MaintenanceCoordinator, useValue: coordinator }],
    };
  }
}
