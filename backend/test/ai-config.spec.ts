import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { AppConfig } from '../src/config/app-config';
import { AI_SCHEMA_VERSION, isSupportedSchemaVersion } from '../src/modules/ai/ai-coach.contract';
import { PROMPT_VERSION } from '../src/modules/ai/ai-coach-prompt.registry';

const BACKEND_ROOT = join(__dirname, '..');

function configWith(overrides: Record<string, string> = {}): AppConfig {
  return AppConfig.fromEnv({
    NODE_ENV: 'test',
    LOG_LEVEL: 'silent',
    DATABASE_PATH: '/tmp/spark-ai-config.db',
    ...overrides,
  });
}

/**
 * A configuração do Coach: onde ela vive, o que ela preserva e o que ela nunca versiona.
 */
describe('Configuração do provider de IA', () => {
  it('a credencial do Gemini é opcional: o processo sobe sem ela', () => {
    expect(configWith().geminiApiKey).toBeUndefined();
  });

  it('o default preserva o modelo e os parâmetros que a T14 usava', () => {
    const config = configWith();

    // A T16.2 é migração de transporte. Trocar de modelo aqui seria outra decisão.
    expect(config.geminiModel).toBe('gemini-3.6-flash');
    expect(config.aiTemperature).toBe(0.2);
    expect(config.aiMaxOutputTokens).toBe(2048);
    expect(config.aiThinkingLevel).toBe('MEDIUM');
    expect(config.aiTimeoutMs).toBe(30_000);
  });

  it('quotas e concorrência são configuráveis, com default conservador', () => {
    const defaults = configWith();
    expect(defaults.aiMaxRequestsPerUserDay).toBe(50);
    expect(defaults.aiMaxRequestsGlobalDay).toBe(500);
    expect(defaults.aiMaxConcurrentRequestsPerUser).toBe(1);

    const tuned = configWith({
      AI_MAX_REQUESTS_PER_USER_DAY: '7',
      AI_MAX_REQUESTS_GLOBAL_DAY: '11',
      AI_MAX_CONCURRENT_REQUESTS_PER_USER: '2',
    });
    expect(tuned.aiMaxRequestsPerUserDay).toBe(7);
    expect(tuned.aiMaxRequestsGlobalDay).toBe(11);
    expect(tuned.aiMaxConcurrentRequestsPerUser).toBe(2);
  });

  it('não existe chave de configuração capaz de desligar a validação da resposta', () => {
    const schema = readFileSync(join(BACKEND_ROOT, 'src', 'config', 'env.schema.ts'), 'utf8');

    for (const forbidden of [
      'AI_DISABLE_VALIDATION',
      'SKIP_AI_VALIDATION',
      'AI_VALIDATION_DISABLED',
      'AI_ALLOW_UNKNOWN_IDS',
      'AI_DISABLE_QUOTA',
    ]) {
      expect(schema).not.toContain(forbidden);
    }
  });

  it('versões de prompt e de schema são autoridade única e explícita', () => {
    expect(PROMPT_VERSION).toBeGreaterThanOrEqual(1);
    expect(AI_SCHEMA_VERSION).toBe(1);
    expect(isSupportedSchemaVersion(AI_SCHEMA_VERSION)).toBe(true);
    expect(isSupportedSchemaVersion(AI_SCHEMA_VERSION + 1)).toBe(false);
    expect(isSupportedSchemaVersion(0)).toBe(false);
  });

  it('o nome do modelo só existe na configuração', () => {
    const offenders = sourceFiles().filter(
      (file) => !file.endsWith('env.schema.ts') && /gemini-\d/.test(readFileSync(file, 'utf8')),
    );

    expect(offenders.map((file) => file.slice(BACKEND_ROOT.length))).toEqual([]);
  });

  it('só um arquivo importa o SDK do Gemini', () => {
    const importers = sourceFiles().filter((file) =>
      readFileSync(file, 'utf8').includes("from '@google/genai'"),
    );

    // Um arquivo, e um só: a fronteira `AiProviderGateway`, o registry de prompts e os schemas
    // de saída falam o vocabulário do Spark. Trocar de provider é reescrever este arquivo.
    expect(importers.map((file) => file.split('/').at(-1))).toEqual([
      'gemini-ai-provider.gateway.ts',
    ]);
  });

  it('nenhuma chave de API está versionada na árvore do backend', () => {
    const patterns = [/AIza[0-9A-Za-z_-]{20,}/, /-----BEGIN [A-Z ]*PRIVATE KEY/];
    const offenders = textFiles().filter((file) => {
      const content = readFileSync(file, 'utf8');
      return patterns.some((pattern) => pattern.test(content));
    });

    expect(offenders.map((file) => file.slice(BACKEND_ROOT.length))).toEqual([]);
  });

  it('.env.example documenta a chave do Gemini sem trazer valor', () => {
    const example = readFileSync(join(BACKEND_ROOT, '.env.example'), 'utf8');

    expect(example).toContain('GEMINI_API_KEY');
    expect(example).not.toMatch(/^GEMINI_API_KEY\s*=\s*\S/m);
    expect(example).not.toMatch(/AIza/);
  });

  it('o docker-compose versionado não carrega a chave do Gemini', () => {
    const compose = readFileSync(join(BACKEND_ROOT, 'docker-compose.yml'), 'utf8');

    expect(compose).not.toMatch(/AIza/);
    expect(compose).not.toMatch(/^\s*GEMINI_API_KEY:\s*\S/m);
  });
});

function sourceFiles(): string[] {
  return collect(join(BACKEND_ROOT, 'src'), new Set(['.ts']));
}

function textFiles(): string[] {
  return collect(
    BACKEND_ROOT,
    new Set(['.ts', '.json', '.yml', '.yaml', '.md', '.example', '.mjs']),
  );
}

function collect(root: string, extensions: Set<string>): string[] {
  const skipped = new Set(['node_modules', 'dist', 'coverage', '.git', 'data']);
  const found: string[] = [];

  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory)) {
      if (skipped.has(entry)) continue;
      const full = join(directory, entry);
      if (statSync(full).isDirectory()) {
        walk(full);
        continue;
      }
      const dot = entry.lastIndexOf('.');
      const extension = dot >= 0 ? entry.slice(dot) : '';
      if (extensions.has(extension) || entry.startsWith('.env')) {
        found.push(full);
      }
    }
  };

  walk(root);
  return found;
}
