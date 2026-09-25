import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const BACKEND_ROOT = join(__dirname, '..');
const REPO_ROOT = join(BACKEND_ROOT, '..');
const SRC = join(BACKEND_ROOT, 'src');

/**
 * T19.H4 §16/§63 — as invariantes de arquitetura do multi-provider que não podem depender de
 * code review.
 *
 * Cada `it` protege uma decisão que quebra em silêncio: um `if (provider === 'groq')` "só para
 * ajustar o prompt", um `import 'groq-sdk'` "só para tipar", o nome de um modelo escrito no
 * serviço, um fallback "só quando o Gemini cair". Nenhum deles falharia um teste de comportamento.
 */
describe('T19.H4 — invariantes estruturais do multi-provider', () => {
  const sources = collect(SRC, '.ts');
  const read = (file: string) => readFileSync(file, 'utf8');
  /** Só código: documentação pode (e deve) citar `AI_PROVIDER=groq` e nomes de modelo. */
  const code = (file: string) => stripComments(read(file));
  const relative = (file: string) => file.slice(BACKEND_ROOT.length + 1);

  it('cada SDK de provider tem exatamente um importador: o gateway dele', () => {
    const importersOf = (pkg: string) =>
      sources.filter((file) => new RegExp(`from '${pkg}(/[^']*)?'`).test(read(file))).map(relative);

    expect(importersOf('@google/genai')).toEqual([
      'src/modules/ai/provider/gemini-ai-provider.gateway.ts',
    ]);
    expect(importersOf('groq-sdk')).toEqual([
      'src/modules/ai/provider/groq-ai-provider.gateway.ts',
    ]);
    // E ninguém traz um terceiro SDK de LLM "compatível com OpenAI" por fora.
    expect(importersOf('openai')).toEqual([]);
  });

  it('o SDK da Groq é fixado, não traz dependência transitiva e não vem acompanhado do da OpenAI', () => {
    const pkg = JSON.parse(read(join(BACKEND_ROOT, 'package.json'))) as {
      dependencies: Record<string, string>;
    };
    // Versão exata: a imagem de amanhã é a que foi testada hoje (mesma regra das outras deps).
    expect(pkg.dependencies['groq-sdk']).toMatch(/^\d+\.\d+\.\d+$/);
    // §18 — um SDK por provider, sem "openai SDK + groq SDK" por conveniência.
    expect(pkg.dependencies.openai).toBeUndefined();
    // Zero dependências: o SDK usa o `fetch` do Node. Se uma versão nova trouxer uma cadeia, ela
    // precisa ser lida (e o `npm audit` do CI olhado) antes de entrar.
    const installed = JSON.parse(
      read(join(BACKEND_ROOT, 'node_modules', 'groq-sdk', 'package.json')),
    ) as { version: string; dependencies?: Record<string, string> };
    expect(installed.version).toBe(pkg.dependencies['groq-sdk']);
    expect(installed.dependencies ?? {}).toEqual({});
  });

  it('só a factory conhece as implementações concretas dos gateways', () => {
    const offenders = sources.filter((file) => {
      if (file.endsWith('ai-provider.factory.ts')) return false;
      if (
        file.endsWith('gemini-ai-provider.gateway.ts') ||
        file.endsWith('groq-ai-provider.gateway.ts')
      ) {
        return false;
      }
      return /GeminiAiProviderGateway|GroqAiProviderGateway/.test(read(file));
    });
    expect(offenders.map(relative)).toEqual([]);
  });

  it('a escolha de provider mora num lugar só: nenhuma checagem de provider espalhada', () => {
    // Quem pode ler `AI_PROVIDER`/`aiProvider`: a configuração, a factory e as ferramentas que
    // recebem o provider como argumento. Serviço, controller, validador, registry de prompt,
    // quota e entitlement não.
    const allowed = new Set([
      'src/config/env.schema.ts',
      'src/config/app-config.ts',
      'src/config/ai-provider-settings.ts',
      'src/modules/ai/provider/ai-provider.factory.ts',
      'src/cli/ai-benchmark.ts',
      'src/cli/ai-provider-smoke.ts',
    ]);
    const offenders = sources
      .filter((file) => !allowed.has(relative(file)))
      .filter((file) =>
        /\baiProvider\b|AI_PROVIDER\b(?!_)|===?\s*'(groq|gemini)'|'(groq|gemini)'\s*===?/.test(
          code(file),
        ),
      );
    expect(offenders.map(relative)).toEqual([]);
  });

  it('o núcleo do Coach só conhece a interface do provider, nunca um gateway', () => {
    const core = [
      'ai-coach.service.ts',
      'ai-coach.controller.ts',
      'ai-coach.validator.ts',
      'ai-coach.response-validation.ts',
      'ai-coach-prompt.registry.ts',
      'ai-coach.provider-request.ts',
      'ai-coach.output.schema.ts',
      'ai-usage.repository.ts',
      'ai-request.registry.ts',
    ].map((name) => join(SRC, 'modules', 'ai', name));

    for (const file of core) {
      const imports = [...read(file).matchAll(/from '([^']+)'/g)].map((match) => match[1]);
      const providerImports = imports.filter((path) => path.includes('/provider/'));
      expect({ file: relative(file), providerImports }).toEqual({
        file: relative(file),
        providerImports: providerImports.filter((path) =>
          path.endsWith('/provider/ai-provider.gateway'),
        ),
      });
    }
  });

  it('os gateways não conhecem domínio: nem validador, nem contrato de entrada, nem quota', () => {
    for (const name of ['gemini-ai-provider.gateway.ts', 'groq-ai-provider.gateway.ts']) {
      const source = read(join(SRC, 'modules', 'ai', 'provider', name));
      for (const forbidden of [
        'ai-coach.validator',
        'ai-coach.request.schema',
        'ai-coach.contract',
        'ai-usage.repository',
        'entitlement',
        'AiCoachRequestType',
      ]) {
        expect({ name, forbidden, present: source.includes(forbidden) }).toEqual({
          name,
          forbidden,
          present: false,
        });
      }
    }
  });

  it('nomes de modelo só existem na configuração e na tabela de perfis avaliados', () => {
    const modelPattern = /gemini-\d|gpt-oss|qwen\//;
    const allowed = new Set([
      'src/config/env.schema.ts',
      'src/modules/ai/provider/groq-model-profiles.ts',
    ]);
    const offenders = sources
      .filter((file) => !allowed.has(relative(file)))
      .filter((file) => modelPattern.test(code(file)));
    expect(offenders.map(relative)).toEqual([]);
  });

  it('não existe fallback: nenhum arquivo além da factory monta mais de um provider', () => {
    const factory = read(join(SRC, 'modules', 'ai', 'provider', 'ai-provider.factory.ts'));
    // Um `switch` com um `return` por provider — nunca um try/catch que tenta o outro.
    expect(factory).not.toMatch(/catch\s*\(/);
    const service = read(join(SRC, 'modules', 'ai', 'ai-coach.service.ts'));
    expect(service.match(/\.generate\(/g) ?? []).toHaveLength(1);
  });

  it('o CI não roda provider real: nenhuma chave, benchmark ou smoke real nos workflows', () => {
    // Comentário de YAML pode explicar que a chave não existe ali; o que não pode é usá-la.
    const workflows = collect(join(REPO_ROOT, '.github', 'workflows'), '.yml')
      .map((file) =>
        read(file)
          .split('\n')
          .filter((line) => !line.trim().startsWith('#'))
          .join('\n'),
      )
      .join('\n');
    for (const forbidden of [
      'ai:benchmark',
      'ai:provider-smoke',
      'ai-benchmark.js',
      'ai-provider-smoke.js',
      'GROQ_API_KEY',
      'GEMINI_API_KEY',
    ]) {
      expect({ forbidden, present: workflows.includes(forbidden) }).toEqual({
        forbidden,
        present: false,
      });
    }
    const pkg = JSON.parse(read(join(BACKEND_ROOT, 'package.json'))) as {
      scripts: Record<string, string>;
    };
    expect(pkg.scripts.test).not.toMatch(/benchmark|smoke/);
  });

  it('o Android não conhece a Groq, nem chave de provider nenhuma', () => {
    const androidSources = collect(join(REPO_ROOT, 'app', 'src'), '.kt');
    const gradle = read(join(REPO_ROOT, 'app', 'build.gradle.kts'));
    const offenders = androidSources.filter((file) => /groq|GROQ_API_KEY/i.test(read(file)));
    expect(offenders).toEqual([]);
    expect(gradle).not.toMatch(/GROQ|GEMINI_API_KEY/);
  });
});

/** Remove comentários de bloco e de linha (sem tentar entender strings — basta para estes testes). */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .map((line) => line.replace(/(^|\s)\/\/.*$/, ''))
    .join('\n');
}

function collect(root: string, extension: string): string[] {
  const skipped = new Set(['node_modules', 'dist', 'coverage', '.git', 'build']);
  const found: string[] = [];
  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory)) {
      if (skipped.has(entry)) continue;
      const full = join(directory, entry);
      if (statSync(full).isDirectory()) {
        walk(full);
      } else if (entry.endsWith(extension)) {
        found.push(full);
      }
    }
  };
  walk(root);
  return found;
}
