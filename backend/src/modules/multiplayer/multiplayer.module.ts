import { Module } from '@nestjs/common';
import { AuthModule } from '../auth/auth.module';
import { SocialModule } from '../social/social.module';
import { MultiplayerController } from './multiplayer.controller';
import { MultiplayerRepository } from './multiplayer.repository';
import { MultiplayerService } from './multiplayer.service';

/**
 * Multiplayer remoto (T19.5): salas, membership e o log ordenado de eventos.
 *
 * Um módulo à parte do Social de propósito. Ele **usa** o social — identidade pública
 * (`socialId`), amizade como autorização do convite e o bloqueio como veto — mas não é social: não
 * publica nada, não alimenta feed, não dá XP. E não é sync nem backup: não importa nenhum dos dois,
 * e não escreve em `sync_entities`. A única dependência entre módulos é `BlockRepository`, que o
 * `SocialModule` já exporta para o compartilhamento de treino.
 */
@Module({
  imports: [AuthModule, SocialModule],
  controllers: [MultiplayerController],
  providers: [MultiplayerRepository, MultiplayerService],
  exports: [MultiplayerRepository],
})
export class MultiplayerModule {}
