import { INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { AppModule } from '../../src/app.module';
import { configureApp } from '../../src/bootstrap/create-app';
import { AppConfig } from '../../src/config/app-config';
import {
  AUTH_TOKEN_VERIFIER,
  type AuthTokenVerifier,
} from '../../src/modules/auth/auth-token-verifier';
import {
  AI_PROVIDER_GATEWAY,
  type AiProviderGateway,
} from '../../src/modules/ai/provider/ai-provider.gateway';

/**
 * A aplicação real com os provedores de fronteira trocados: o verificador de token e, quando o
 * teste precisa, o provider de IA.
 *
 * O resto — versionamento `/v1`, middlewares, filtro de erro, banco — vem de `configureApp`, a
 * mesma função que a produção usa. Nenhuma aproximação: se o bootstrap mudar, o teste muda junto.
 */
export async function createTestApp(
  config: AppConfig,
  verifier: AuthTokenVerifier,
  /**
   * Provider de IA (T16.2). Quando ausente, o módulo real é montado — e sem `GEMINI_API_KEY` ele
   * responde indisponível sem abrir conexão nenhuma. Nenhum teste chama o Gemini de verdade.
   */
  aiProvider?: AiProviderGateway,
): Promise<INestApplication> {
  let builder = Test.createTestingModule({ imports: [AppModule.forRoot(config)] })
    .overrideProvider(AUTH_TOKEN_VERIFIER)
    .useValue(verifier);

  if (aiProvider) {
    builder = builder.overrideProvider(AI_PROVIDER_GATEWAY).useValue(aiProvider);
  }

  const moduleRef = await builder.compile();

  const app = moduleRef.createNestApplication({ logger: false });
  configureApp(app, config);
  await app.init();
  return app;
}
