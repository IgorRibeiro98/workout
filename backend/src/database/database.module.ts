import { Global, Module } from '@nestjs/common';
import { PostgresService } from './postgres.service';
import { SqliteService } from './sqlite.service';

@Global()
@Module({
  providers: [PostgresService, SqliteService],
  exports: [PostgresService, SqliteService],
})
export class DatabaseModule {}
