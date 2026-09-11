import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { parse as parseConnectionString } from 'pg-connection-string';
import { AppConfig } from '../src/config/app-config';
import {
  libpqEnvironment,
  normalizeSslMode,
  parsePostgresUrl,
  postgresUrlIdentity,
  PostgresUrlIdentityError,
  productionSslViolation,
} from '../src/database/postgres-url';

const PASSWORD = 'segredo-que-nunca-aparece';
const NEON = `postgresql://spark:${PASSWORD}@ep-cool-darkness-12345.us-east-2.aws.neon.tech/spark?sslmode=require`;

/**
 * T18.3 §4/§20 — a connection string como identidade e como política.
 *
 * ## Identidade falha fechada
 *
 * `postgres://host` e `postgres://host/` são aceitas pelo driver (cai no banco default do papel) e
 * recusadas aqui: backup, restore e migration precisam declarar qual banco manipulam.
 *
 * ## TLS explícito, e o guarda-chuva contra a próxima major do `pg`
 *
 * O `pg` 8 trata `sslmode=require` como `verify-full` e avisa que a 9 vai adotar a semântica
 * libpq (sem verificação). `normalizeSslMode` torna a intenção explícita na string efetiva, e os
 * testes abaixo provam duas coisas que não podem regredir em silêncio: a string normalizada não
 * dispara o SECURITY WARNING do parser atual, e ela declara `verify-full` — o único modo cujo
 * significado é o mesmo antes e depois da mudança de major.
 */
describe('T18.3 — connection string PostgreSQL: identidade e política de TLS', () => {
  describe('parsePostgresUrl / postgresUrlIdentity (§4)', () => {
    it('extrai host, porta, database, usuário, senha e parâmetros', () => {
      const parsed = parsePostgresUrl(NEON);
      expect(parsed.host).toBe('ep-cool-darkness-12345.us-east-2.aws.neon.tech');
      expect(parsed.port).toBe(5432);
      expect(parsed.database).toBe('spark');
      expect(parsed.user).toBe('spark');
      expect(parsed.password).toBe(PASSWORD);
      expect(parsed.params.get('sslmode')).toBe('require');
    });

    it('decodifica senha e database percent-encoded', () => {
      const parsed = parsePostgresUrl('postgres://u:p%40ss%2Fw@127.0.0.1:6543/spark_drill');
      expect(parsed.password).toBe('p@ss/w');
      expect(parsed.port).toBe(6543);
      expect(parsed.database).toBe('spark_drill');
    });

    it.each([
      'postgres://host',
      'postgres://host/',
      'postgresql://spark:x@127.0.0.1:5432',
      'postgresql://spark:x@127.0.0.1:5432/',
    ])('falha fechada sem database explícito: %s', (url) => {
      expect(() => parsePostgresUrl(url)).toThrow(PostgresUrlIdentityError);
      expect(() => postgresUrlIdentity(url)).toThrow(/sem database explícito/);
    });

    it('recusa esquema que não é PostgreSQL, host ausente e nome de banco malformado', () => {
      expect(() => parsePostgresUrl('mysql://spark:x@host/spark')).toThrow(
        PostgresUrlIdentityError,
      );
      expect(() => parsePostgresUrl('postgres:///spark')).toThrow(PostgresUrlIdentityError);
      expect(() => parsePostgresUrl('postgres://host/a/b')).toThrow(PostgresUrlIdentityError);
      expect(() => parsePostgresUrl('isto não é uma url')).toThrow(PostgresUrlIdentityError);
    });

    it('a identidade nunca carrega usuário nem senha', () => {
      const identity = postgresUrlIdentity(NEON);
      expect(identity).toEqual({
        host: 'ep-cool-darkness-12345.us-east-2.aws.neon.tech',
        port: 5432,
        database: 'spark',
      });
      expect(JSON.stringify(identity)).not.toContain(PASSWORD);
    });
  });

  describe('normalizeSslMode (§20)', () => {
    it.each(['require', 'prefer', 'verify-ca'])(
      'sslmode=%s vira verify-full explícito — o que o pg 8 já fazia por baixo dos panos',
      (mode) => {
        const result = normalizeSslMode(`postgres://u:p@host/db?sslmode=${mode}`);
        expect(result.normalized).toBe(true);
        expect(result.effectiveSslMode).toBe('verify-full');
        expect(new URL(result.connectionString).searchParams.get('sslmode')).toBe('verify-full');
      },
    );

    it('preserva o resto da URL (credencial, host, banco, outros parâmetros) ao normalizar', () => {
      const result = normalizeSslMode(`${NEON}&channel_binding=require`);
      const url = new URL(result.connectionString);
      expect(url.username).toBe('spark');
      expect(url.password).toBe(PASSWORD);
      expect(url.hostname).toBe('ep-cool-darkness-12345.us-east-2.aws.neon.tech');
      expect(url.pathname).toBe('/spark');
      expect(url.searchParams.get('channel_binding')).toBe('require');
    });

    it('sem sslmode (banco local de dev/CI) nada muda', () => {
      const raw = 'postgresql://spark:spark@localhost:5432/spark_dev';
      expect(normalizeSslMode(raw)).toEqual({
        connectionString: raw,
        effectiveSslMode: undefined,
        normalized: false,
      });
    });

    it('verify-full já explícito, disable, no-verify e uselibpqcompat ficam como estão', () => {
      for (const query of [
        'sslmode=verify-full',
        'sslmode=disable',
        'sslmode=no-verify',
        'uselibpqcompat=true&sslmode=require',
      ]) {
        const raw = `postgres://u:p@host/db?${query}`;
        const result = normalizeSslMode(raw);
        expect(result.connectionString).toBe(raw);
        expect(result.normalized).toBe(false);
      }
    });

    it('a string normalizada não dispara o SECURITY WARNING do pg-connection-string atual', () => {
      const warnings: string[] = [];
      const original = process.emitWarning.bind(process);
      process.emitWarning = ((message: string | Error) => {
        warnings.push(typeof message === 'string' ? message : message.message);
      }) as typeof process.emitWarning;
      try {
        const normalized = parseConnectionString(normalizeSslMode(NEON).connectionString);
        expect(warnings).toEqual([]);
        expect(normalized.ssl).toEqual({});
        expect(normalized.sslmode).toBe('verify-full');
      } finally {
        process.emitWarning = original;
      }
    });

    it('anti-regressão: a política é verify-full, e o driver instalado ainda é a major 8', () => {
      // Quando o `pg` subir para a major 9, `sslmode=require` passará a significar "sem
      // verificação". Este teste existe para a atualização não passar despercebida: ele falha
      // até alguém confirmar que `normalizeSslMode` continua produzindo `verify-full` explícito
      // (o único modo estável entre as duas majors) e atualizar a major esperada aqui.
      const pgVersion = JSON.parse(
        readFileSync(join(__dirname, '..', 'node_modules', 'pg', 'package.json'), 'utf8'),
      ).version as string;
      expect(pgVersion.split('.')[0]).toBe('8');
      expect(normalizeSslMode(NEON).effectiveSslMode).toBe('verify-full');
    });
  });

  describe('productionSslViolation e AppConfig.missingRequirements (§20)', () => {
    const production = {
      NODE_ENV: 'production',
      ACCOUNT_DELETION_HMAC_KEY: 'chave-de-producao-de-teste-com-tamanho-suficiente',
      SOCIAL_MEDIA_ROOT: '/media',
    };

    it('require, verify-full e ausência de sslmode são aceitos em produção', () => {
      for (const url of [
        NEON,
        `${NEON.replace('require', 'verify-full')}`,
        'postgres://u:p@host/db',
      ]) {
        expect(productionSslViolation(url)).toBeUndefined();
        expect(
          AppConfig.fromEnv({ ...production, DATABASE_URL: url }).missingRequirements(),
        ).toEqual([]);
      }
    });

    it.each(['sslmode=disable', 'sslmode=no-verify', 'uselibpqcompat=true&sslmode=require'])(
      'produção recusa uma URL que declara não verificar o servidor: %s',
      (query) => {
        const url = `postgres://u:p@host/db?${query}`;
        expect(productionSslViolation(url)).toBeDefined();
        const missing = AppConfig.fromEnv({
          ...production,
          DATABASE_URL: url,
        }).missingRequirements();
        expect(missing.join('\n')).toContain('DATABASE_URL');
        expect(missing.join('\n')).toContain('verify-full');
      },
    );

    it('a URL direta também é conferida em produção', () => {
      const missing = AppConfig.fromEnv({
        ...production,
        DATABASE_URL: NEON,
        DATABASE_URL_DIRECT: 'postgres://u:p@host-direct/db?sslmode=disable',
      }).missingRequirements();
      expect(missing.join('\n')).toContain('DATABASE_URL_DIRECT');
    });

    it('fora de produção nada é recusado — dev e CI falam com um PostgreSQL local sem TLS', () => {
      const config = AppConfig.fromEnv({ DATABASE_URL: 'postgres://u:p@host/db?sslmode=disable' });
      expect(config.missingRequirements()).toEqual([]);
    });
  });

  describe('libpqEnvironment (§2 — pg_dump/pg_restore sem connection string no argv)', () => {
    it('traduz a URL para as variáveis da libpq, com verify-full e a cadeia do sistema', () => {
      expect(libpqEnvironment(NEON)).toEqual({
        PGHOST: 'ep-cool-darkness-12345.us-east-2.aws.neon.tech',
        PGPORT: '5432',
        PGDATABASE: 'spark',
        PGUSER: 'spark',
        PGPASSWORD: PASSWORD,
        PGSSLMODE: 'verify-full',
        PGSSLROOTCERT: 'system',
      });
    });

    it('sem sslmode não impõe TLS (banco local), e channel_binding é propagado', () => {
      expect(libpqEnvironment('postgresql://spark:spark@127.0.0.1:5432/spark_dev')).toEqual({
        PGHOST: '127.0.0.1',
        PGPORT: '5432',
        PGDATABASE: 'spark_dev',
        PGUSER: 'spark',
        PGPASSWORD: 'spark',
      });
      expect(
        libpqEnvironment('postgres://u:p@h/db?sslmode=verify-full&channel_binding=require'),
      ).toMatchObject({
        PGSSLMODE: 'verify-full',
        PGSSLROOTCERT: 'system',
        PGCHANNELBINDING: 'require',
      });
    });

    it('falha fechada sem database, como o resto', () => {
      expect(() => libpqEnvironment('postgres://u:p@h')).toThrow(PostgresUrlIdentityError);
    });
  });
});
