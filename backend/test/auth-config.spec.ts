import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { SparkLogger } from '../src/common/logger';
import { AppConfig } from '../src/config/app-config';
import { VerifierUnavailableError } from '../src/modules/auth/auth-token-verifier';
import { FirebaseAuthTokenVerifier } from '../src/modules/auth/firebase-auth-token-verifier';

const BACKEND_ROOT = join(__dirname, '..');

function configWith(overrides: Record<string, string> = {}): AppConfig {
  return AppConfig.fromEnv({
    NODE_ENV: 'test',
    LOG_LEVEL: 'silent',
    DATABASE_PATH: '/tmp/spark-auth-config.db',
    ...overrides,
  });
}

describe('Configuração da credencial do Firebase Admin', () => {
  it('a credencial é opcional: o processo sobe sem ela', () => {
    const config = configWith();

    expect(config.googleApplicationCredentials).toBeUndefined();
    expect(config.firebaseProjectId).toBeUndefined();
  });

  it('a configuração carrega apenas o caminho da credencial, nunca a chave', () => {
    const config = configWith({
      GOOGLE_APPLICATION_CREDENTIALS: '/run/secrets/spark-firebase-admin.json',
      FIREBASE_PROJECT_ID: 'spark-exemplo',
    });

    expect(config.googleApplicationCredentials).toBe('/run/secrets/spark-firebase-admin.json');
    expect(config.firebaseProjectId).toBe('spark-exemplo');
  });

  it('sem credencial o verificador reporta indisponibilidade — nunca autentica', async () => {
    const config = configWith();
    const verifier = new FirebaseAuthTokenVerifier(config, new SparkLogger(config));

    await expect(verifier.verify('qualquer-token')).rejects.toBeInstanceOf(
      VerifierUnavailableError,
    );
  });

  it('sem credencial nem um JWT bem formado passa', async () => {
    const config = configWith();
    const verifier = new FirebaseAuthTokenVerifier(config, new SparkLogger(config));
    const payload = Buffer.from(JSON.stringify({ sub: 'uid-forjado' })).toString('base64url');

    await expect(verifier.verify(`eyJhbGciOiJub25lIn0.${payload}.`)).rejects.toBeInstanceOf(
      VerifierUnavailableError,
    );
  });

  it('não existe chave de configuração capaz de desligar a autenticação', () => {
    const schema = readFileSync(join(BACKEND_ROOT, 'src', 'config', 'env.schema.ts'), 'utf8');

    for (const forbidden of ['AUTH_DISABLED', 'DISABLE_AUTH', 'SKIP_AUTH', 'AUTH_BYPASS']) {
      expect(schema).not.toContain(forbidden);
    }
  });
});

describe('Nenhuma credencial versionada', () => {
  const textFiles = collectTextFiles(BACKEND_ROOT);

  it('encontra os arquivos que precisa inspecionar', () => {
    expect(textFiles.length).toBeGreaterThan(20);
  });

  it('nenhum arquivo versionado contém chave privada, service account ou token', () => {
    const patterns = [
      /-----BEGIN [A-Z ]*PRIVATE KEY/,
      /"private_key"\s*:\s*"-----BEGIN/,
      /"type"\s*:\s*"service_account"/,
      /AIza[0-9A-Za-z_-]{20,}/,
    ];

    const offenders = textFiles.filter((file) => {
      const content = readFileSync(file, 'utf8');
      return patterns.some((pattern) => pattern.test(content));
    });

    expect(offenders.map((file) => file.slice(BACKEND_ROOT.length))).toEqual([]);
  });

  it('nenhum arquivo de service account existe na árvore do backend', () => {
    const offenders = textFiles.filter((file) => /service-account|firebase-adminsdk/.test(file));

    expect(offenders).toEqual([]);
  });

  it('.env.example documenta o caminho da credencial sem trazer segredo', () => {
    const example = readFileSync(join(BACKEND_ROOT, '.env.example'), 'utf8');

    expect(example).toContain('GOOGLE_APPLICATION_CREDENTIALS');
    expect(example).not.toMatch(/-----BEGIN/);
    expect(example).not.toMatch(/private_key/);
    // O caminho documentado precisa continuar sendo um caminho, não um valor colado.
    expect(example).not.toMatch(/GOOGLE_APPLICATION_CREDENTIALS\s*=\s*\{/);
  });

  it('o .gitignore e o .dockerignore barram service account e chaves', () => {
    const gitignore = readFileSync(join(BACKEND_ROOT, '.gitignore'), 'utf8');
    const dockerignore = readFileSync(join(BACKEND_ROOT, '.dockerignore'), 'utf8');

    for (const pattern of ['service-account*.json', 'firebase-adminsdk*.json', '*.pem', '*.key']) {
      expect(gitignore).toContain(pattern);
      expect(dockerignore).toContain(pattern);
    }
  });

  it('o docker-compose não carrega chave privada, só caminho e montagem', () => {
    const compose = readFileSync(join(BACKEND_ROOT, 'docker-compose.yml'), 'utf8');

    expect(compose).not.toMatch(/-----BEGIN/);
    expect(compose).not.toMatch(/private_key/);
  });

  it('o Dockerfile não copia credencial para a imagem', () => {
    const dockerfile = readFileSync(join(BACKEND_ROOT, 'Dockerfile'), 'utf8');
    const copies = dockerfile.split('\n').filter((line) => /^\s*COPY\b/.test(line));

    expect(copies.length).toBeGreaterThan(0);
    for (const line of copies) {
      expect(line).not.toMatch(/service-account|firebase-adminsdk|\.env|\.pem|\.key/);
    }
  });
});

/** Arquivos de texto do backend, ignorando o que não é versionado. */
function collectTextFiles(root: string): string[] {
  const skipped = new Set(['node_modules', 'dist', 'coverage', '.git', 'data']);
  const allowed = new Set(['.ts', '.js', '.json', '.yml', '.yaml', '.md', '.example', '.mjs']);
  const found: string[] = [];

  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory)) {
      if (skipped.has(entry)) {
        continue;
      }
      const full = join(directory, entry);
      if (statSync(full).isDirectory()) {
        walk(full);
        continue;
      }
      const dot = entry.lastIndexOf('.');
      const extension = dot >= 0 ? entry.slice(dot) : '';
      if (allowed.has(extension) || entry.startsWith('.env') || entry === 'Dockerfile') {
        found.push(full);
      }
    }
  };

  if (existsSync(root)) {
    walk(root);
  }
  return found;
}
