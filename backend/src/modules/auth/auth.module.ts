import { Module, OnApplicationShutdown } from '@nestjs/common';
import { AUTH_TOKEN_VERIFIER } from './auth-token-verifier';
import { AuthController } from './auth.controller';
import { BearerAuthGuard } from './bearer-auth.guard';
import { FirebaseAuthTokenVerifier } from './firebase-auth-token-verifier';

/**
 * Módulo de identidade (T16.1).
 *
 * O verificador real é registrado sob o token `AUTH_TOKEN_VERIFIER`; guard e controller conhecem
 * apenas a interface. É isso que permite aos testes trocarem a implementação por um dublê e
 * rodarem sem service account, sem projeto Firebase e sem rede — sem que exista qualquer chave
 * de configuração capaz de desligar a autenticação em produção.
 *
 * O módulo **não** registra guard global. `/health/live` e `/health/ready` continuam públicos, e
 * cada rota declara sua própria proteção.
 */
@Module({
  controllers: [AuthController],
  providers: [
    FirebaseAuthTokenVerifier,
    { provide: AUTH_TOKEN_VERIFIER, useExisting: FirebaseAuthTokenVerifier },
    BearerAuthGuard,
  ],
  exports: [AUTH_TOKEN_VERIFIER],
})
export class AuthModule implements OnApplicationShutdown {
  constructor(private readonly firebaseVerifier: FirebaseAuthTokenVerifier) {}

  /** Libera o app do Admin SDK junto com o resto do processo, como o SQLite já faz. */
  async onApplicationShutdown(): Promise<void> {
    await this.firebaseVerifier.dispose();
  }
}
