import { readFileSync } from 'node:fs';
import { Inject, Injectable } from '@nestjs/common';
import {
  applicationDefault,
  type App,
  cert,
  type Credential,
  deleteApp,
  getApps,
  initializeApp,
} from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { SparkLogger } from '../../common/logger';
import { APP_CONFIG, AppConfig } from '../../config/app-config';
import type { SparkEnv } from '../../config/env.schema';
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
 *
 * Quando o deploy declara `REQUIRE_FIREBASE_ADMIN=true`, a credencial é verificada **antes** disso,
 * no startup — ver [verifyFirebaseAdminCredential], no fim deste arquivo. Ela mora aqui, e não em
 * um módulo próprio, porque `test/dependency-security.spec.ts` exige que **um** arquivo importe
 * `firebase-admin`: é essa fronteira que mantém a cadeia vulnerável de `@google-cloud/storage`
 * inalcançável, e ela vale mais que a separação estética entre "verificar no startup" e
 * "verificar por requisição".
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

  /**
   * Exclui o usuário do Firebase Auth pelo Firebase Admin SDK (T17.6).
   * Trata auth/user-not-found como sucesso convergente.
   */
  async deleteUser(uid: string): Promise<void> {
    const app = this.adminApp();
    try {
      await getAuth(app).deleteUser(uid);
      this.logger.info('auth.user.deleted', { uidPrefix: uid.slice(0, 6) });
    } catch (error: unknown) {
      const authError = error as { code?: string };
      if (authError?.code === 'auth/user-not-found') {
        this.logger.info('auth.user.already_deleted', { uidPrefix: uid.slice(0, 6) });
        return;
      }
      this.logger.error('auth.user.delete.failed', {
        uidPrefix: uid.slice(0, 6),
        errorCode: authError?.code,
      });
      throw error;
    }
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

    const mode = this.config.firebaseAdminCredentialMode;
    let credential: Credential;
    if (mode === 'adc') {
      // Cloud Run (T18.2 §15/§16): nenhum arquivo, nenhuma `GOOGLE_APPLICATION_CREDENTIALS`. O SDK
      // resolve a identidade anexada ao serviço por conta própria — o mesmo mecanismo que
      // `GcsObjectStorageClient` já usa desde a T18.1.
      credential = applicationDefault();
    } else {
      const credentialsPath = this.config.googleApplicationCredentials;
      if (!credentialsPath) {
        // Configuração ausente é uma condição estável: registra uma vez e não tenta de novo a cada
        // requisição só para falhar igual.
        this.initializationFailure = 'credencial do Firebase Admin não configurada';
        this.logger.error('auth.verifier.unconfigured');
        throw new VerifierUnavailableError(this.initializationFailure);
      }
      // `cert` aceita o caminho do arquivo: a chave privada é lida do disco, nunca de uma
      // variável de ambiente e nunca de dentro da imagem.
      credential = cert(credentialsPath);
    }

    try {
      const existing = getApps().find((candidate) => candidate.name === ADMIN_APP_NAME);
      this.app =
        existing ??
        initializeApp({ credential, projectId: this.config.firebaseProjectId }, ADMIN_APP_NAME);
      this.logger.info('auth.verifier.ready', { credentialMode: mode });
      return this.app;
    } catch (error) {
      this.initializationFailure = 'credencial do Firebase Admin inválida';
      // Só o nome do erro: a mensagem do Admin SDK pode carregar o caminho do arquivo de
      // credencial no modo `file`.
      this.logger.error('auth.verifier.init.failed', {
        errorName: error instanceof Error ? error.name : 'UnknownError',
        credentialMode: mode,
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

/** Nome próprio do app usado só na verificação de startup. Nunca é o app que serve requisições. */
const PREFLIGHT_APP_NAME = 'spark-backend-preflight';

/**
 * A credencial declarada obrigatória não serve.
 *
 * `reason` é uma frase curta e **sem o caminho do arquivo** — a mesma decisão que
 * `FirebaseAuthTokenVerifier` já tomava ao registrar apenas `error.name`: a mensagem do Admin SDK
 * carrega o caminho da service account, e ela vai para stderr, que vai para o journal.
 */
export class FirebaseAdminCredentialError extends Error {
  constructor(readonly reason: string) {
    super(`credencial do Firebase Admin inutilizável: ${reason}`);
    this.name = 'FirebaseAdminCredentialError';
  }
}

/** Os campos sem os quais uma service account não é uma service account. */
const REQUIRED_FIELDS = ['project_id', 'client_email', 'private_key'] as const;

/**
 * Verificação de startup da credencial do Firebase Admin (T16.8.1 §8).
 *
 * ## O que ela conserta
 *
 * `REQUIRE_FIREBASE_ADMIN=true` declara "este servidor não sobe sem conseguir verificar
 * identidade". Até a T16.8 o startup checava apenas se `GOOGLE_APPLICATION_CREDENTIALS` era uma
 * string não vazia — o que é verdade para um caminho digitado errado, para um arquivo que não
 * existe, para um arquivo sem permissão de leitura e para um JSON truncado. Em todos esses casos o
 * processo subia, `/health/ready` respondia `200`, e o defeito só aparecia na **primeira
 * requisição autenticada**, como uma sequência de `503`.
 *
 * `503` é a resposta certa para indisponibilidade transitória, e é isso que a torna enganosa aqui:
 * o operador procura instabilidade de rede por horas, porque o servidor está dizendo "tente de
 * novo" para um problema que nunca vai passar sozinho.
 *
 * ## O que ela verifica, e o que ela deliberadamente não verifica
 *
 * Verifica, nesta ordem: caminho declarado → arquivo legível → JSON válido → forma de service
 * account → `cert()` + `initializeApp()` do Admin SDK de verdade. A última etapa é a que importa:
 * é a mesma chamada que `FirebaseAuthTokenVerifier` fará depois, então "passou aqui" significa
 * "vai inicializar lá".
 *
 * **Não** faz chamada de rede. Nada de emitir token, listar usuários ou consultar as chaves
 * públicas do Google: isso tornaria o startup do Spark dependente da disponibilidade de um
 * terceiro, que é exatamente o que §13.7 proíbe ("readiness é sobre servir, não sobre terceiros").
 * A verificação é local, determinística e offline — e o que ela não pode cobrir (o projeto ter
 * sido apagado no console, por exemplo) continua sendo `503` em runtime, como sempre foi.
 *
 * ## Por que no startup, e não no readiness
 *
 * Porque `/health/ready` continua **sem consultar Firebase** (§13.7): o Coach ou a identidade fora
 * do ar não podem derrubar backup e sync. A credencial obrigatória impede o readiness de outro
 * jeito, e mais forte — o processo não chega a escutar a porta, então não existe janela em que
 * `/health/ready` responda `200` num servidor que prometeu verificar identidade e não consegue.
 *
 * ## Modo `adc` (T18.2 §18)
 *
 * Com `mode === 'adc'` nada disto se aplica: não há arquivo para ler, e a única verificação
 * possível sem chamada de rede é a que o Admin SDK já faz ao montar a credencial —
 * `applicationDefault()` + `initializeApp()`. Se a Application Default Credential não existir no
 * ambiente (nenhuma service account anexada, nenhum `gcloud auth application-default login`), o
 * SDK recusa na hora, local e offline, e cai no mesmo `catch` de baixo.
 *
 * @throws {FirebaseAdminCredentialError} quando a credencial não existe ou não é utilizável.
 */
export async function verifyFirebaseAdminCredential(
  credentialsPath: string | undefined,
  projectId?: string,
  mode: SparkEnv['FIREBASE_ADMIN_CREDENTIAL_MODE'] = 'file',
): Promise<void> {
  if (mode === 'adc') {
    let adcApp: App | undefined;
    try {
      const stale = getApps().find((candidate) => candidate.name === PREFLIGHT_APP_NAME);
      if (stale) {
        await deleteApp(stale);
      }
      adcApp = initializeApp({ credential: applicationDefault(), projectId }, PREFLIGHT_APP_NAME);
    } catch {
      // Sem detalhe da causa: mesmo raciocínio do modo `file` — o que sobra depois de "ADC
      // ausente"/"ADC malformada" não vale um vazamento na mensagem.
      throw new FirebaseAdminCredentialError(
        'a Application Default Credential não pôde ser resolvida',
      );
    } finally {
      if (adcApp) {
        await deleteApp(adcApp);
      }
    }
    return;
  }

  if (!credentialsPath) {
    throw new FirebaseAdminCredentialError('GOOGLE_APPLICATION_CREDENTIALS não está definido');
  }

  let contents: string;
  try {
    contents = readFileSync(credentialsPath, 'utf8');
  } catch (error) {
    // O código do errno, e não a mensagem: `ENOENT` e `EACCES` levam a ações diferentes do
    // operador (arquivo ausente × montagem somente-leitura com o grupo errado — T16.8.1 §3), e
    // nenhum dos dois precisa do caminho para ser entendido.
    const code =
      typeof error === 'object' && error !== null && 'code' in error
        ? String((error as { code: unknown }).code)
        : 'desconhecido';
    throw new FirebaseAdminCredentialError(`o arquivo não pôde ser lido (${code})`);
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(contents);
  } catch {
    // Sem a mensagem do parser: ela cita um trecho do arquivo, e o arquivo é uma chave privada.
    throw new FirebaseAdminCredentialError('o conteúdo não é JSON válido');
  }

  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new FirebaseAdminCredentialError('o conteúdo não é um objeto de service account');
  }

  const account = parsed as Record<string, unknown>;
  if (account.type !== 'service_account') {
    throw new FirebaseAdminCredentialError(
      // Escrito sem o par chave/valor literal de propósito: `test/auth-config.spec.ts` varre a
      // árvore versionada procurando exatamente essa forma, e é assim que ele encontraria uma
      // service account de verdade commitada por engano. Uma mensagem de erro não pode ensinar o
      // detector a ignorar o padrão que ele existe para achar.
      'o campo type não identifica uma service account',
    );
  }

  const missing = REQUIRED_FIELDS.filter(
    (field) => typeof account[field] !== 'string' || account[field].length === 0,
  );
  if (missing.length > 0) {
    // Nomes de campo ausentes não são segredo — o valor deles é que seria.
    throw new FirebaseAdminCredentialError(`faltam campos obrigatórios: ${missing.join(', ')}`);
  }

  let app: App | undefined;
  try {
    // Um app anterior com este nome só existiria se uma verificação tivesse sido interrompida no
    // meio; `initializeApp` recusaria o nome duplicado e a falha diria a coisa errada.
    const stale = getApps().find((candidate) => candidate.name === PREFLIGHT_APP_NAME);
    if (stale) {
      await deleteApp(stale);
    }

    app = initializeApp({ credential: cert(credentialsPath), projectId }, PREFLIGHT_APP_NAME);
  } catch {
    // A mensagem do Admin SDK ("Failed to parse service account json file: ...") carrega o caminho
    // do arquivo. As causas comuns já foram classificadas acima com mensagem própria; o que sobra
    // aqui é o resto, e o resto não vale um vazamento.
    throw new FirebaseAdminCredentialError('o Admin SDK recusou a credencial');
  } finally {
    if (app) {
      // O app da verificação não sobrevive a ela: quem serve requisições é o do
      // `FirebaseAuthTokenVerifier`, com nome próprio e ciclo de vida ligado ao módulo.
      await deleteApp(app);
    }
  }
}
