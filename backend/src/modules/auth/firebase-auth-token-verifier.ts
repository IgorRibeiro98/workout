import { Inject, Injectable } from '@nestjs/common';
import { type App, cert, deleteApp, getApps, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import type { AuthenticatedPrincipal } from './authenticated-principal';
import {
  type AuthTokenVerifier,
  InvalidTokenError,
  VerifierUnavailableError,
} from './auth-token-verifier';

/** Nome próprio do app Admin: o Spark Backend não disputa o app default com nada. */
const ADMIN_APP_NAME = 'spark-backend-auth';

/**
 * Códigos do Admin SDK que significam "este token não serve".
 *
 * Tudo o que não estiver aqui é tratado como indisponibilidade (503) em vez de credencial
 * inválida (401): errar para o lado de 503 faz o cliente tentar de novo; errar para o lado de
 * 401 faria o Android concluir que a sessão morreu por um problema que era do servidor.
 */
const INVALID_TOKEN_CODES = new Set([
  'auth/argument-error',
  'auth/id-token-expired',
  'auth/id-token-revoked',
  'auth/invalid-id-token',
  'auth/invalid-argument',
  'auth/user-disabled',
  'auth/user-not-found',
  'auth/session-cookie-expired',
  'auth/session-cookie-revoked',
]);

/**
 * Verificação real do Firebase ID Token, pelo Firebase Admin SDK.
 *
 * `verifyIdToken` valida assinatura, emissor, audiência e expiração contra as chaves públicas do
 * Google — é a única forma suportada de o servidor descobrir quem é o usuário autenticado pelo
 * Firebase. Este arquivo é o **único** lugar do backend que importa `firebase-admin`.
 *
 * A credencial nunca vive no código: vem do caminho configurado em
 * `GOOGLE_APPLICATION_CREDENTIALS`, um arquivo fora do repositório e fora da imagem, montado
 * somente-leitura no container (docs/FIREBASE_AUTH_SETUP.md).
 *
 * A inicialização é **tardia** e não fatal: sem credencial o processo sobe, `/health/*` continua
 * público e as rotas autenticadas respondem 503. Não existe caminho que devolva 200 sem ter
 * verificado o token.
 */
@Injectable()
export class FirebaseAuthTokenVerifier implements AuthTokenVerifier {
  private app: App | null = null;
  private initializationFailure: string | null = null;

  constructor(
    @Inject(APP_CONFIG) private readonly config: AppConfig,
    private readonly logger: SparkLogger,
  ) {}

  async verify(idToken: string): Promise<AuthenticatedPrincipal> {
    const app = this.adminApp();

    let decoded;
    try {
      decoded = await getAuth(app).verifyIdToken(idToken);
    } catch (error) {
      throw this.translate(error);
    }

    if (typeof decoded.uid !== 'string' || decoded.uid.length === 0) {
      // Não deveria acontecer com um token verificado; se acontecer, não inventamos identidade.
      throw new InvalidTokenError('token verificado sem uid');
    }

    // Recorte mínimo: o resto das claims fica no token e não circula pelo processo.
    const provider =
      typeof decoded.firebase?.sign_in_provider === 'string'
        ? decoded.firebase.sign_in_provider
        : undefined;

    return {
      uid: decoded.uid,
      email: typeof decoded.email === 'string' ? decoded.email : undefined,
      provider,
    };
  }

  /** Fecha o app Admin no shutdown do processo. Chamado pelo módulo, via `onModuleDestroy`. */
  async dispose(): Promise<void> {
    const app = this.app;
    this.app = null;
    if (app) {
      await deleteApp(app);
    }
  }

  private adminApp(): App {
    if (this.app) {
      return this.app;
    }
    if (this.initializationFailure) {
      throw new VerifierUnavailableError(this.initializationFailure);
    }

    const credentialsPath = this.config.googleApplicationCredentials;
    if (!credentialsPath) {
      // Configuração ausente é uma condição estável: registra uma vez e não tenta de novo a cada
      // requisição só para falhar igual.
      this.initializationFailure = 'credencial do Firebase Admin não configurada';
      this.logger.error('auth.verifier.unconfigured');
      throw new VerifierUnavailableError(this.initializationFailure);
    }

    try {
      const existing = getApps().find((candidate) => candidate.name === ADMIN_APP_NAME);
      this.app =
        existing ??
        initializeApp(
          {
            // `cert` aceita o caminho do arquivo: a chave privada é lida do disco, nunca de uma
            // variável de ambiente e nunca de dentro da imagem.
            credential: cert(credentialsPath),
            projectId: this.config.firebaseProjectId,
          },
          ADMIN_APP_NAME,
        );
      this.logger.info('auth.verifier.ready');
      return this.app;
    } catch (error) {
      this.initializationFailure = 'credencial do Firebase Admin inválida';
      // Só o nome do erro: a mensagem do Admin SDK carrega o caminho do arquivo de credencial.
      this.logger.error('auth.verifier.init.failed', {
        errorName: error instanceof Error ? error.name : 'UnknownError',
      });
      throw new VerifierUnavailableError(this.initializationFailure);
    }
  }

  private translate(error: unknown): Error {
    const code =
      typeof error === 'object' && error !== null && 'code' in error
        ? String((error as { code: unknown }).code)
        : '';

    if (INVALID_TOKEN_CODES.has(code)) {
      return new InvalidTokenError(code);
    }

    // Falha ao buscar as chaves públicas, relógio, cota: o servidor não conseguiu decidir.
    this.logger.error('auth.verifier.failed', { errorCode: code || 'unknown' });
    return new VerifierUnavailableError('não foi possível verificar o token');
  }
}
