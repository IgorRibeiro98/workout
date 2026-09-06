import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { AppModule } from '../../src/app.module';
import { configureApp } from '../../src/bootstrap/create-app';
import { AppConfig } from '../../src/config/app-config';
import {
  AUTH_TOKEN_VERIFIER,
  type AuthTokenVerifier,
} from '../../src/modules/auth/auth-token-verifier';

/**
 * A aplicação real com **um** provedor trocado: o verificador de token.
 *
 * O resto — versionamento `/v1`, middlewares, filtro de erro, banco — vem de `configureApp`, a
 * mesma função que a produção usa. Nenhuma aproximação: se o bootstrap mudar, o teste muda junto.
 */
export async function createTestApp(
  config: AppConfig,
  verifier: AuthTokenVerifier,
): Promise<INestApplication> {
  const moduleRef = await Test.createTestingModule({ imports: [AppModule.forRoot(config)] })
    .overrideProvider(AUTH_TOKEN_VERIFIER)
    .useValue(verifier)
    .compile();

  const app = moduleRef.createNestApplication({ logger: false });
  configureApp(app, config);
  await app.init();
  return app;
}
