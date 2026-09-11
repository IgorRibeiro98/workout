import { Module } from '@nestjs/common';
import { SocialModule } from '../modules/social/social.module';
import { BackupModule } from '../modules/backup/backup.module';
import { AccountDeletionModule } from '../modules/account-deletion/account-deletion.module';
import { MaintenanceCoordinator } from './maintenance.coordinator';

/**
 * Só providers — nenhum controller (T18.2 §33).
 *
 * `AppModule` importa este módulo para que `MaintenanceCoordinator` exista no mesmo grafo de DI da
 * API — é o que permite `NestFactory.createApplicationContext(AppModule.forRoot(config))`
 * resolvê-lo em `maintenance-main.ts` sem duplicar wiring. Nenhuma rota HTTP nasce daqui: quem
 * expõe `POST /internal/maintenance/run` é `MaintenanceHttpModule`, um módulo separado e menor,
 * montado só pelo entrypoint de manutenção — a API nunca ganha essa rota.
 */
@Module({
  imports: [SocialModule, BackupModule, AccountDeletionModule],
  providers: [MaintenanceCoordinator],
  exports: [MaintenanceCoordinator],
})
export class MaintenanceModule {}
