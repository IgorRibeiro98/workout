import { AppConfig, ConfigValidationError } from '../src/config/app-config';

describe('AppConfig (bootstrap de configuração)', () => {
  const validEnv = {
    NODE_ENV: 'test',
    PORT: '8080',
    DATABASE_PATH: '/tmp/spark-test.db',
  };

  it('carrega configuração válida e aplica os defaults documentados', () => {
    const config = AppConfig.fromEnv(validEnv);

    expect(config.port).toBe(8080);
    expect(config.databasePath).toBe('/tmp/spark-test.db');
    expect(config.nodeEnv).toBe('test');
    expect(config.logLevel).toBe('info');
    expect(config.sqliteBusyTimeoutMs).toBe(5000);
    expect(config.isProduction).toBe(false);
  });

  it('falha quando DATABASE_PATH está ausente — não existe default silencioso', () => {
    expect(() => AppConfig.fromEnv({ NODE_ENV: 'test' })).toThrow(ConfigValidationError);
  });

  it('falha quando DATABASE_PATH está vazio', () => {
    expect(() => AppConfig.fromEnv({ ...validEnv, DATABASE_PATH: '' })).toThrow(
      ConfigValidationError,
    );
  });

  it('falha quando PORT não é uma porta válida', () => {
    expect(() => AppConfig.fromEnv({ ...validEnv, PORT: '70000' })).toThrow(ConfigValidationError);
    expect(() => AppConfig.fromEnv({ ...validEnv, PORT: 'abc' })).toThrow(ConfigValidationError);
  });

  it('falha quando NODE_ENV é desconhecido', () => {
    expect(() => AppConfig.fromEnv({ ...validEnv, NODE_ENV: 'staging' })).toThrow(
      ConfigValidationError,
    );
  });

  it('falha quando LOG_LEVEL é desconhecido', () => {
    expect(() => AppConfig.fromEnv({ ...validEnv, LOG_LEVEL: 'verbose' })).toThrow(
      ConfigValidationError,
    );
  });

  it('reporta o campo inválido sem expor o valor recebido', () => {
    try {
      AppConfig.fromEnv({ ...validEnv, DATABASE_PATH: '' });
      fail('esperava ConfigValidationError');
    } catch (error) {
      expect(error).toBeInstanceOf(ConfigValidationError);
      expect((error as ConfigValidationError).issues.join()).toContain('DATABASE_PATH');
    }
  });

  it('marca produção corretamente', () => {
    expect(AppConfig.fromEnv({ ...validEnv, NODE_ENV: 'production' }).isProduction).toBe(true);
  });
});
