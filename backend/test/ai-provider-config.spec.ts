import { AppConfig, ConfigValidationError } from '../src/config/app-config';
import { AiProviderConfig, AiProviderConfigError } from '../src/config/ai-provider-settings';

const base = {
  NODE_ENV: 'test',
  LOG_LEVEL: 'silent',
  DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
};

const config = (overrides: Record<string, string> = {}) =>
  AppConfig.fromEnv({ ...base, ...overrides });

/** Valores fora do formato real de qualquer chave: o que importa é existir, não parecer real. */
const GROQ_KEY = 'chave-de-teste-da-groq';
const GEMINI_KEY = 'chave-de-teste-do-gemini';

/**
 * T19.H4 §10–§14, §41, §54, §62 — a escolha de provider é configuração validada no startup.
 *
 * O que estes testes protegem é "nunca em silêncio": provider desconhecido, modelo sem perfil,
 * esforço de raciocínio que o modelo não suporta, modelo Preview em produção e a variável antiga
 * `REQUIRE_GEMINI` mudando de sentido — cada um derruba o processo com uma mensagem que diz o que
 * está errado, em vez de virar "o provider usa o default dele".
 */
describe('Configuração multi-provider do Coach', () => {
  // ------------------------------------------------------------------------ AI_PROVIDER

  it('o default é gemini até o benchmark decidir, e a chave da Groq não é exigida', () => {
    const defaults = config();
    expect(defaults.aiProvider).toBe('gemini');
    expect(defaults.groqApiKey).toBeUndefined();
    expect(defaults.groqModel).toBe('openai/gpt-oss-120b');
    expect(defaults.missingRequirements()).toEqual([]);
  });

  it('AI_PROVIDER=gemini e AI_PROVIDER=groq são aceitos', () => {
    expect(config({ AI_PROVIDER: 'gemini' }).aiProvider).toBe('gemini');
    expect(config({ AI_PROVIDER: 'groq' }).aiProvider).toBe('groq');
  });

  it('provider desconhecido derruba o startup', () => {
    for (const invalid of ['openai', 'GROQ', 'gemini,groq', '']) {
      expect(() => config({ AI_PROVIDER: invalid })).toThrow(ConfigValidationError);
    }
  });

  // ------------------------------------------------------------------------ credenciais

  it('REQUIRE_AI_PROVIDER exige a chave do provider SELECIONADO — groq', () => {
    const missing = config({ AI_PROVIDER: 'groq', REQUIRE_AI_PROVIDER: 'true' });
    expect(missing.missingRequirements().join()).toContain('GROQ_API_KEY');

    // A chave do outro provider não conta.
    const wrongKey = config({
      AI_PROVIDER: 'groq',
      REQUIRE_AI_PROVIDER: 'true',
      GEMINI_API_KEY: GEMINI_KEY,
    });
    expect(wrongKey.missingRequirements().join()).toContain('GROQ_API_KEY');

    const ok = config({ AI_PROVIDER: 'groq', REQUIRE_AI_PROVIDER: 'true', GROQ_API_KEY: GROQ_KEY });
    expect(ok.missingRequirements()).toEqual([]);
  });

  it('REQUIRE_AI_PROVIDER exige a chave do provider SELECIONADO — gemini', () => {
    const missing = config({
      AI_PROVIDER: 'gemini',
      REQUIRE_AI_PROVIDER: 'true',
      GROQ_API_KEY: GROQ_KEY,
    });
    expect(missing.missingRequirements().join()).toContain('GEMINI_API_KEY');

    const ok = config({
      AI_PROVIDER: 'gemini',
      REQUIRE_AI_PROVIDER: 'true',
      GEMINI_API_KEY: GEMINI_KEY,
    });
    expect(ok.missingRequirements()).toEqual([]);
  });

  it('REQUIRE_AI_PROVIDER com AI_ENABLED=false é contradição declarada', () => {
    const contradictory = config({
      AI_PROVIDER: 'groq',
      REQUIRE_AI_PROVIDER: 'true',
      GROQ_API_KEY: GROQ_KEY,
      AI_ENABLED: 'false',
    });
    expect(contradictory.missingRequirements().join()).toContain('contraditórios');
  });

  it('chave gravada com quebra de linha no fim (Enter antes do Ctrl-D) é aparada', () => {
    const tuned = config({ GROQ_API_KEY: `${GROQ_KEY}\n`, GEMINI_API_KEY: `  ${GEMINI_KEY}\r\n` });
    expect(tuned.groqApiKey).toBe(GROQ_KEY);
    expect(tuned.geminiApiKey).toBe(GEMINI_KEY);
    // Só espaço não é chave: continua configuração inválida.
    expect(() => config({ GROQ_API_KEY: ' \n' })).toThrow(ConfigValidationError);
  });

  it('chave vazia é configuração inválida, nunca "chave ausente" silenciosa', () => {
    expect(() => config({ AI_PROVIDER: 'groq', GROQ_API_KEY: '' })).toThrow(ConfigValidationError);
  });

  // ------------------------------------------------------------------------ REQUIRE_GEMINI (legado)

  it('REQUIRE_GEMINI continua significando o que significava com AI_PROVIDER=gemini', () => {
    expect(config({ REQUIRE_GEMINI: 'true' }).missingRequirements().join()).toContain(
      'GEMINI_API_KEY',
    );
    expect(
      config({ REQUIRE_GEMINI: 'true', GEMINI_API_KEY: GEMINI_KEY }).missingRequirements(),
    ).toEqual([]);
  });

  it('REQUIRE_GEMINI=true com AI_PROVIDER=groq é ambíguo e derruba o startup apontando a variável nova', () => {
    const ambiguous = config({
      AI_PROVIDER: 'groq',
      REQUIRE_GEMINI: 'true',
      GROQ_API_KEY: GROQ_KEY,
      GEMINI_API_KEY: GEMINI_KEY,
    });
    expect(ambiguous.missingRequirements().join()).toContain('REQUIRE_AI_PROVIDER');
    // `false` nunca atrapalha: é o que o deploy antigo declarava.
    expect(
      config({
        AI_PROVIDER: 'groq',
        REQUIRE_GEMINI: 'false',
        GROQ_API_KEY: GROQ_KEY,
      }).missingRequirements(),
    ).toEqual([]);
  });

  // ------------------------------------------------------------------------ GROQ_MODEL

  it('GROQ_MODEL vazio derruba o startup', () => {
    expect(() => config({ GROQ_MODEL: '' })).toThrow(ConfigValidationError);
    expect(() => config({ AI_PROVIDER: 'groq', GROQ_MODEL: '   ' })).toThrow(ConfigValidationError);
  });

  it('GROQ_MODEL fora dos modelos avaliados derruba o startup só quando a Groq é o provider', () => {
    expect(() => config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'llama-9-900b' })).toThrow(
      /não está entre os modelos avaliados/,
    );
    // Com o Gemini atendendo, um GROQ_MODEL qualquer não derruba nada.
    expect(config({ AI_PROVIDER: 'gemini', GROQ_MODEL: 'llama-9-900b' }).aiProvider).toBe('gemini');
  });

  it('os dois candidatos da T19.H4 são aceitos', () => {
    expect(config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'openai/gpt-oss-120b' }).groqModel).toBe(
      'openai/gpt-oss-120b',
    );
    expect(config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'qwen/qwen3.8-27b' }).groqModel).toBe(
      'qwen/qwen3.8-27b',
    );
  });

  // ------------------------------------------------------------------------ raciocínio

  it('esforço de raciocínio que o modelo não suporta derruba o startup — nunca vira default', () => {
    // O raciocínio do GPT-OSS não desliga e não tem "minimal".
    for (const level of ['OFF', 'MINIMAL']) {
      expect(() =>
        config({
          AI_PROVIDER: 'groq',
          GROQ_MODEL: 'openai/gpt-oss-120b',
          AI_THINKING_LEVEL: level,
        }),
      ).toThrow(/AI_THINKING_LEVEL/);
    }
    for (const level of ['LOW', 'MEDIUM', 'HIGH']) {
      expect(
        config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'openai/gpt-oss-120b', AI_THINKING_LEVEL: level })
          .aiThinkingLevel,
      ).toBe(level);
    }
    // O Qwen desliga (none), mas também não tem "minimal".
    expect(
      config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'qwen/qwen3.8-27b', AI_THINKING_LEVEL: 'OFF' })
        .aiThinkingLevel,
    ).toBe('OFF');
    expect(() =>
      config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'qwen/qwen3.8-27b', AI_THINKING_LEVEL: 'MINIMAL' }),
    ).toThrow(/AI_THINKING_LEVEL/);
  });

  it('o mapeamento do Gemini continua aceitando todos os níveis (inclusive OFF)', () => {
    for (const level of ['MINIMAL', 'LOW', 'MEDIUM', 'HIGH', 'OFF']) {
      expect(config({ AI_PROVIDER: 'gemini', AI_THINKING_LEVEL: level }).aiThinkingLevel).toBe(
        level,
      );
    }
  });

  // ------------------------------------------------------------------------ teto de saída (§13)

  it('o teto de saída é por provider: cada um sobrepõe o compartilhado só para si', () => {
    const shared = config({ AI_MAX_OUTPUT_TOKENS: '2048' });
    expect(shared.geminiMaxOutputTokens).toBe(2048);
    expect(shared.groqMaxOutputTokens).toBe(2048);

    const tuned = config({
      AI_MAX_OUTPUT_TOKENS: '2048',
      GEMINI_MAX_OUTPUT_TOKENS: '8192',
      GROQ_MAX_OUTPUT_TOKENS: '3000',
    });
    // O Gemini com raciocínio MEDIUM truncava em 2 048; a Groq free paga o teto no TPM do plano.
    expect(tuned.geminiMaxOutputTokens).toBe(8192);
    expect(tuned.groqMaxOutputTokens).toBe(3000);
    expect(tuned.aiMaxOutputTokens).toBe(2048);

    // Vazio é ausente (o que o Compose injeta para variável não definida).
    expect(config({ GEMINI_MAX_OUTPUT_TOKENS: '' }).geminiMaxOutputTokens).toBe(2048);
    expect(() => config({ GROQ_MAX_OUTPUT_TOKENS: '100' })).toThrow(ConfigValidationError);
    expect(() => config({ GEMINI_MAX_OUTPUT_TOKENS: 'muito' })).toThrow(ConfigValidationError);
  });

  // ------------------------------------------------------------------------ Preview (§41)

  it('modelo Preview em produção exige "preview risk accepted" escrito na configuração', () => {
    const production = {
      ...base,
      NODE_ENV: 'production',
      AI_PROVIDER: 'groq',
      GROQ_MODEL: 'qwen/qwen3.8-27b',
    };
    expect(() => AppConfig.fromEnv(production)).toThrow(/GROQ_ALLOW_PREVIEW_MODEL/);
    expect(AppConfig.fromEnv({ ...production, GROQ_ALLOW_PREVIEW_MODEL: 'true' }).groqModel).toBe(
      'qwen/qwen3.8-27b',
    );
    // Fora de produção (benchmark, desenvolvimento), Preview é livre: é para isso que ele existe.
    expect(config({ AI_PROVIDER: 'groq', GROQ_MODEL: 'qwen/qwen3.8-27b' }).groqModel).toBe(
      'qwen/qwen3.8-27b',
    );
  });

  it('modelo Production não precisa de nenhuma flag em produção', () => {
    expect(
      AppConfig.fromEnv({
        ...base,
        NODE_ENV: 'production',
        AI_PROVIDER: 'groq',
        GROQ_MODEL: 'openai/gpt-oss-120b',
      }).groqModel,
    ).toBe('openai/gpt-oss-120b');
  });

  // ------------------------------------------------------------------------ ferramentas

  it('a configuração das ferramentas não exige banco e aplica as mesmas regras', () => {
    const tool = AiProviderConfig.fromEnv({ AI_PROVIDER: 'groq', GROQ_API_KEY: GROQ_KEY });
    expect(tool.aiProvider).toBe('groq');
    expect(tool.groqApiKey).toBe(GROQ_KEY);
    expect(tool.aiThinkingLevel).toBe('MEDIUM');

    expect(() =>
      AiProviderConfig.fromEnv({ AI_PROVIDER: 'groq', AI_THINKING_LEVEL: 'OFF' }),
    ).toThrow(AiProviderConfigError);
    expect(() => AiProviderConfig.fromEnv({ AI_PROVIDER: 'mistral' })).toThrow(
      AiProviderConfigError,
    );
  });
});
