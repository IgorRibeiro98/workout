import { runAiProviderSmoke } from '../src/cli/ai-provider-smoke';

/**
 * `ai:provider-smoke` sem rede: o que ele recusa antes de chamar qualquer provider.
 *
 * A chamada real só acontece com a chave no ambiente — e nenhum teste tem chave. Estes casos provam
 * que a falta dela é FAIL explícito (exit 1, nomeando a variável, nunca um PASS silencioso), e que
 * uso inválido é recusado antes de qualquer configuração.
 */
describe('ai:provider-smoke (sem rede)', () => {
  const saved = { ...process.env };
  let stdout: string[];
  let stderr: string[];
  let restore: () => void;

  beforeEach(() => {
    stdout = [];
    stderr = [];
    const out = process.stdout.write.bind(process.stdout);
    const err = process.stderr.write.bind(process.stderr);
    process.stdout.write = ((chunk: string | Uint8Array): boolean => {
      stdout.push(String(chunk));
      return true;
    }) as typeof process.stdout.write;
    process.stderr.write = ((chunk: string | Uint8Array): boolean => {
      stderr.push(String(chunk));
      return true;
    }) as typeof process.stderr.write;
    restore = () => {
      process.stdout.write = out;
      process.stderr.write = err;
    };
    for (const key of ['GROQ_API_KEY', 'GEMINI_API_KEY', 'AI_PROVIDER_SMOKE_GATE']) {
      delete process.env[key];
    }
  });

  afterEach(() => {
    restore();
    process.env = { ...saved };
  });

  it('sem a chave do provider selecionado: FAIL nomeando a variável, exit 1', async () => {
    process.env.AI_PROVIDER = 'groq';
    const code = await runAiProviderSmoke([]);
    expect(code).toBe(1);
    expect(stdout.join('')).toContain(
      'AI_PROVIDER_SMOKE FAIL provider=groq model=openai/gpt-oss-120b reason=GROQ_API_KEY ausente',
    );
  });

  it('tipo ou gate desconhecidos são uso inválido (exit 2)', async () => {
    process.env.AI_PROVIDER = 'gemini';
    expect(await runAiProviderSmoke(['--type', 'tudo'])).toBe(2);
    expect(await runAiProviderSmoke(['--desconhecido'])).toBe(2);
    process.env.AI_PROVIDER_SMOKE_GATE = 'qualquer';
    expect(await runAiProviderSmoke([])).toBe(2);
  });

  it('configuração inválida (modelo sem perfil) falha antes de qualquer chamada', async () => {
    process.env.AI_PROVIDER = 'groq';
    process.env.GROQ_MODEL = 'modelo-que-ninguem-avaliou';
    process.env.GROQ_API_KEY = 'chave-de-teste-nao-real';
    expect(await runAiProviderSmoke([])).toBe(1);
    expect(stderr.join('')).toContain('não está entre os modelos avaliados');
  });
});
