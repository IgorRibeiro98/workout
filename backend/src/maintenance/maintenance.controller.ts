import { Controller, Post, VERSION_NEUTRAL } from '@nestjs/common';
import { MaintenanceCoordinator, type MaintenanceCycleResult } from './maintenance.coordinator';

/**
 * A única rota HTTP do serviço `spark-maintenance` (T18.2 §33/§37).
 *
 * Fora de `/v1` de propósito, como `/health/*`: isto não é uma API de produto, é a superfície que
 * o Cloud Scheduler chama. Não confundir com `MAINTENANCE_MODE` (`common/maintenance.middleware.ts`)
 * — aquele é o interruptor que pausa `/v1` inteiro por decisão operacional; este é o nome do
 * serviço que roda os workers de segundo plano. Os dois "manutenção" são conceitos vizinhos e
 * deliberadamente diferentes.
 *
 * Não verifica Firebase Bearer: a proteção deste serviço é o IAM do Cloud Run (`allow
 * unauthenticated=false` + só `spark-maintenance-scheduler` com `roles/run.invoker`) — a mesma
 * fronteira que já decide se a requisição chega ao container. Ver
 * `docs/operations/CLOUD_RUN_DEPLOYMENT.md`.
 */
@Controller({ path: 'internal/maintenance', version: VERSION_NEUTRAL })
export class MaintenanceController {
  constructor(private readonly coordinator: MaintenanceCoordinator) {}

  @Post('run')
  async run(): Promise<MaintenanceCycleResult> {
    return this.coordinator.runCycle();
  }
}
