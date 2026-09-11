import { Controller, Get, Post, VERSION_NEUTRAL } from '@nestjs/common';
import {
  MaintenanceCoordinator,
  type MaintenanceCycleResult,
  type MaintenanceStatus,
} from './maintenance.coordinator';

/**
 * As duas rotas HTTP do serviço `spark-maintenance` (T18.2 §33/§37; status na T18.3 §12).
 *
 * Fora de `/v1` de propósito, como `/health/*`: isto não é uma API de produto, é a superfície que
 * o Cloud Scheduler chama — e a que o operador consulta para saber se o Scheduler continua
 * chamando. Não confundir com `MAINTENANCE_MODE` (`common/maintenance.middleware.ts`) — aquele é
 * o interruptor que pausa `/v1` inteiro por decisão operacional; este é o nome do serviço que roda
 * os workers de segundo plano. Os dois "manutenção" são conceitos vizinhos e deliberadamente
 * diferentes.
 *
 * Não verifica Firebase Bearer: a proteção deste serviço é o IAM do Cloud Run (`allow
 * unauthenticated=false` + só `spark-maintenance-scheduler` com `roles/run.invoker`) — a mesma
 * fronteira que já decide se a requisição chega ao container. O operador chama `GET /status` com o
 * próprio identity token (`gcloud auth print-identity-token`). Ver
 * `docs/operations/OBSERVABILITY.md`.
 */
@Controller({ path: 'internal/maintenance', version: VERSION_NEUTRAL })
export class MaintenanceController {
  constructor(private readonly coordinator: MaintenanceCoordinator) {}

  @Post('run')
  async run(): Promise<MaintenanceCycleResult> {
    return this.coordinator.runCycle();
  }

  /**
   * O heartbeat persistido: último início, fim, sucesso, falha, duração, `stale`, tamanho do
   * banco e frescor do backup de DR. Sempre `200` — o veredito está no corpo (`stale`), e o alerta
   * vive nos logs/métricas, não neste código de status.
   */
  @Get('status')
  async status(): Promise<MaintenanceStatus> {
    return this.coordinator.status();
  }
}
