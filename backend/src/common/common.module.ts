import { Global, Module } from '@nestjs/common';
import { SparkLogger } from './logger';

@Global()
@Module({
  providers: [SparkLogger],
  exports: [SparkLogger],
})
export class CommonModule {}
