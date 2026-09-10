import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { AppConfig, ConfigValidationError } from '../src/config/app-config';
import { DEVELOPMENT_DELETION_HMAC_KEY } from '../src/config/env.schema';
import {
  configFor,
  createTempDb,
  postgresFor,
  MIGRATIONS_DIR,
  type TempDb,
} from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeAiProviderGateway } from './support/fake-ai-provider';
import { withClientBackupId } from './support/backup-fixtures';
import { pushBody } from './support/sync-fixtures';

const TOKEN = 'token-da-conta-a';
const UID = 'uid-da-conta-a';

/**
 * Prontidão de produção (T16.8).
 *
 * O que esta suíte protege são invariantes de **operação**, e todos eles quebram em silêncio:
 * um `synchronous` herdado do driver, uma flag booleana que vira `true` por coerção, um
 * interruptor de manutenção que derruba o healthcheck junto, um limite de requisições que barra o
 * restore legítimo. Nada aqui toca rede, Firebase, Gemini ou VPS.
 */
describe('Configuração de produção', () => {
  const base = {
    NODE_ENV: 'test',
    DATABASE_PATH: '/tmp/spark-hardening.db',
    DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
  };

  it('configura pool padrão e aceita valores customizados explicitamente', () => {
    const config = AppConfig.fromEnv(base);
    expect(config.databasePoolMin).toBe(2);
    expect(config.databasePoolMax).toBe(10);
    expect(config.databaseStatementTimeoutMs).toBe(30_000);

    const custom = AppConfig.fromEnv({
      ...base,
      DATABASE_POOL_MAX: '25',
      DATABASE_POOL_MIN: '4',
    });
    expect(custom.databasePoolMax).toBe(25);
    expect(custom.databasePoolMin).toBe(4);
  });

  it('recusa tamanho de pool inválido', () => {
    expect(() => AppConfig.fromEnv({ ...base, DATABASE_POOL_MAX: '0' })).toThrow(
      ConfigValidationError,
    );
    expect(() => AppConfig.fromEnv({ ...base, DATABASE_POOL_MAX: '500' })).toThrow(
      ConfigValidationError,
    );
  });

  it('as flags booleanas leem "false" como falso — não como string não vazia', () => {
    // `z.coerce.boolean()` transformaria qualquer string não vazia em `true`, e um interruptor de
    // emergência que liga quando você o desliga é pior que não existir.
    const off = AppConfig.fromEnv({
      ...base,
      AI_ENABLED: 'false',
      SYNC_WRITE_ENABLED: '0',
      MAINTENANCE_MODE: 'true',
    });
    expect(off.aiEnabled).toBe(false);
    expect(off.syncWriteEnabled).toBe(false);
    expect(off.maintenanceMode).toBe(true);
  });

  it('recusa um valor booleano fora do vocabulário em vez de escolher um default', () => {
    expect(() => AppConfig.fromEnv({ ...base, AI_ENABLED: 'sim' })).toThrow(ConfigValidationError);
    expect(() => AppConfig.fromEnv({ ...base, MAINTENANCE_MODE: 'yes' })).toThrow(
      ConfigValidationError,
    );
  });

  it('os defaults preservam o comportamento das fases anteriores', () => {
    const config = AppConfig.fromEnv(base);
    expect(config.aiEnabled).toBe(true);
    expect(config.syncWriteEnabled).toBe(true);
    expect(config.maintenanceMode).toBe(false);
    expect(config.requireFirebaseAdmin).toBe(false);
    expect(config.requireGemini).toBe(false);
  });

  it('REQUIRE_FIREBASE_ADMIN sem credencial é falha de startup, não 503 silencioso', () => {
    const config = AppConfig.fromEnv({ ...base, REQUIRE_FIREBASE_ADMIN: 'true' });
    expect(config.missingRequirements()).toHaveLength(1);
    expect(config.missingRequirements()[0]).toContain('GOOGLE_APPLICATION_CREDENTIALS');

    const withCredential = AppConfig.fromEnv({
      ...base,
      REQUIRE_FIREBASE_ADMIN: 'true',
      GOOGLE_APPLICATION_CREDENTIALS: '/run/secrets/spark-firebase-admin.json',
    });
    expect(withCredential.missingRequirements()).toEqual([]);
  });

  it('produção sem SOCIAL_MEDIA_ROOT é falha de startup, e nunca um diretório derivado (T17.9 §28)', () => {
    // O derivado é seguro em desenvolvimento e desastroso em produção: ele acompanha
    // `DATABASE_PATH`, e um deploy que monte o banco sem montar a mídia perderia todas as fotos na
    // primeira recriação de container — em silêncio, porque escrever num diretório efêmero
    // funciona perfeitamente até alguém reiniciar.
    const production = AppConfig.fromEnv({ ...base, NODE_ENV: 'production' });
    expect(production.socialMediaRootIsExplicit).toBe(false);
    expect(production.missingRequirements().join()).toContain('SOCIAL_MEDIA_ROOT');

    const configured = AppConfig.fromEnv({
      ...base,
      NODE_ENV: 'production',
      SOCIAL_MEDIA_ROOT: '/media',
      // Produção também exige a chave própria de tombstone (T17.10 §136) — sem ela a lista de
      // pendências não fica vazia, e é isso que o teste abaixo cobre.
      ACCOUNT_DELETION_HMAC_KEY: 'chave-de-producao-de-teste-com-tamanho-suficiente',
    });
    expect(configured.socialMediaRoot).toBe('/media');
    expect(configured.socialMediaRootIsExplicit).toBe(true);
    expect(configured.missingRequirements()).toEqual([]);
  });

  it('produção com a chave de tombstone de desenvolvimento é falha de startup (T17.10 §136/§137)', () => {
    // A chave liga o tombstone ao uid. Subir com o default do repositório significa duas coisas
    // ruins ao mesmo tempo: qualquer pessoa com o código confirma um uid conhecido a partir da
    // tabela, e trocá-la depois faz **todas** as exclusões já feitas deixarem de casar — conta
    // excluída voltando a passar pelo guard, e a reconciliação de DR deixando de reconhecê-la.
    const withDefault = AppConfig.fromEnv({
      ...base,
      NODE_ENV: 'production',
      SOCIAL_MEDIA_ROOT: '/media',
    });
    expect(withDefault.accountDeletionHmacKey).toBe(DEVELOPMENT_DELETION_HMAC_KEY);
    expect(withDefault.missingRequirements().join()).toContain('ACCOUNT_DELETION_HMAC_KEY');

    const withOwnKey = AppConfig.fromEnv({
      ...base,
      NODE_ENV: 'production',
      SOCIAL_MEDIA_ROOT: '/media',
      ACCOUNT_DELETION_HMAC_KEY: 'uma-chave-longa-o-suficiente-de-producao',
    });
    expect(withOwnKey.missingRequirements()).toEqual([]);

    // Fora de produção o default continua servindo: teste e `start:dev` sobem sem configuração.
    const development = AppConfig.fromEnv(base);
    expect(development.missingRequirements()).toEqual([]);
  });

  it('fora de produção a raiz de mídia é derivada, e o processo sobe sem configuração', () => {
    // Teste e desenvolvimento precisam funcionar sem uma variável a mais; ali o armazenamento
    // efêmero é exatamente o que se quer.
    const development = AppConfig.fromEnv(base);
    expect(development.missingRequirements()).toEqual([]);
    expect(development.socialMediaRoot).toBe(join(process.cwd(), '.spark-media'));
  });

  it('REQUIRE_GEMINI com AI_ENABLED=false é contradição declarada, e não passa despercebida', () => {
    const config = AppConfig.fromEnv({
      ...base,
      REQUIRE_GEMINI: 'true',
      GEMINI_API_KEY: 'chave-de-teste-nao-real',
      AI_ENABLED: 'false',
    });
    expect(config.missingRequirements().join()).toContain('contraditórios');
  });

  it('os tetos de tempo HTTP são declarados e generosos o bastante para um backup', () => {
    const config = AppConfig.fromEnv(base);
    // Um snapshot de 4 MiB em rede móvel ruim não cabe em 30 s. Um teto único e curto para tudo
    // transformaria backup legítimo em falha recorrente.
    expect(config.httpRequestTimeoutMs).toBeGreaterThanOrEqual(120_000);
    // Precisa ser maior que o keep-alive do proxy à frente, senão o Caddy reaproveita conexão
    // fechada e o cliente vê 502 esporádico.
    expect(config.httpKeepAliveTimeoutMs).toBeGreaterThan(60_000);
  });
});

describe('Configurações efetivas e saúde do banco PostgreSQL', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  it('conecta com sucesso e reporta saúde no pool', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    const health = await postgres.checkHealth();
    expect(health.reachable).toBe(true);
    expect(health.migrationsUpToDate).toBe(true);
    await postgres.close();
  });

  it('pool respeita configurações de DATABASE_POOL_MAX e MIN', () => {
    const config = configFor(temp.path, { DATABASE_POOL_MAX: '25', DATABASE_POOL_MIN: '3' });
    expect(config.databasePoolMax).toBe(25);
    expect(config.databasePoolMin).toBe(3);
  });

  it('statement_timeout e timeouts de conexão são configurados', () => {
    const config = configFor(temp.path, {
      DATABASE_STATEMENT_TIMEOUT_MS: '15000',
      DATABASE_CONNECTION_TIMEOUT_MS: '5000',
    });
    expect(config.databaseStatementTimeoutMs).toBe(15000);
    expect(config.databaseConnectionTimeoutMs).toBe(5000);
  });

  it('executa consultas e transações atômicas com rollback em erro', async () => {
    const postgres = postgresFor(configFor(temp.path));
    await postgres.initialize(MIGRATIONS_DIR);

    const res = await postgres.query('SELECT 1 AS n');
    expect(Number(res.rows[0].n)).toBe(1);

    await expect(
      postgres.transaction(async (client) => {
        await client.query('CREATE TEMPORARY TABLE test_txn (val int)');
        throw new Error('falha intencional');
      }),
    ).rejects.toThrow('falha intencional');

    await postgres.close();
  });
});

describe('Superfície HTTP endurecida', () => {
  let temp: TempDb;
  let app: INestApplication;

  const start = async (overrides: Record<string, string> = {}) => {
    temp = createTempDb();
    app = await createTestApp(
      configFor(temp.path, overrides),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
      // O provider dublê nunca é chamado nesta suíte: os testes exercitam o que acontece
      // **antes** dele — interruptor desligado, manutenção, limites. Se algum caminho o alcançar,
      // o teste falha aqui, que é o sinal certo.
      FakeAiProviderGateway.respondingWith({ inesperado: true }),
    );
    return app;
  };

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  it('não abre CORS: um app Android nativo não precisa, e liberar por hábito é superfície de graça', async () => {
    await start();

    const response = await request(app.getHttpServer())
      .get('/health/live')
      .set('Origin', 'https://exemplo.invalid');

    expect(response.headers['access-control-allow-origin']).toBeUndefined();
    expect(response.headers['access-control-allow-credentials']).toBeUndefined();
  });

  it('não anuncia o framework', async () => {
    await start();
    const response = await request(app.getHttpServer()).get('/health/live');
    expect(response.headers['x-powered-by']).toBeUndefined();
  });

  it('marca as respostas de produto como não-cacheáveis, e deixa health de fora', async () => {
    await start();

    const api = await request(app.getHttpServer())
      .get('/v1/auth/me')
      .set('Authorization', `Bearer ${TOKEN}`);
    // Toda resposta de `/v1` é dado de conta autenticada: um cache intermediário guardando um
    // snapshot de backup é vazamento entre contas.
    expect(api.headers['cache-control']).toBe('no-store');
    expect(api.headers['x-content-type-options']).toBe('nosniff');

    const health = await request(app.getHttpServer()).get('/health/live');
    expect(health.headers['cache-control']).toBeUndefined();
    expect(health.headers['x-content-type-options']).toBe('nosniff');
  });

  it('em manutenção, /v1 responde 503 e o healthcheck continua verde', async () => {
    await start({ MAINTENANCE_MODE: 'true' });

    const api = await request(app.getHttpServer())
      .get('/v1/auth/me')
      .set('Authorization', `Bearer ${TOKEN}`);
    expect(api.status).toBe(503);
    expect(api.body.error.code).toBe('SERVICE_UNAVAILABLE');
    expect(api.body.error.requestId).toBeTruthy();

    // O ponto do interruptor: quem faz healthcheck precisa distinguir "em manutenção" de "morto".
    // Um readiness falso reiniciaria o container no meio da manutenção.
    await request(app.getHttpServer()).get('/health/live').expect(200);
    const ready = await request(app.getHttpServer()).get('/health/ready');
    expect(ready.status).toBe(200);
    expect(ready.body.status).toBe('ok');
  });

  it('AI_ENABLED=false desliga o Coach sem tocar em backup e sync', async () => {
    await start({ AI_ENABLED: 'false' });

    const coach = await request(app.getHttpServer())
      .post('/v1/ai/coach')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send({ clientRequestId: 'cli-1', requestType: 'ANALYZE_WORKOUT', schemaVersion: 1 });

    expect(coach.status).toBe(503);
    // O código é o que o Android já entende desde a T16.2: desligar o Coach não exige APK novo.
    expect(coach.body.error.code).toBe('AI_PROVIDER_UNAVAILABLE');

    // O que protege dado do usuário continua no ar.
    const backup = await request(app.getHttpServer())
      .post('/v1/backups')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(JSON.stringify(withClientBackupId('backup-v1-minimal', 'cli-ai-off')));
    expect(backup.status).toBe(201);

    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN}`);
    expect(pull.status).toBe(200);
  });

  it('SYNC_WRITE_ENABLED=false pausa o push e mantém o pull', async () => {
    await start({ SYNC_WRITE_ENABLED: 'false' });

    const push = await request(app.getHttpServer())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${TOKEN}`)
      .set('Content-Type', 'application/json')
      .send(pushBody([]));

    // 503, e não 4xx: a mutação não tem defeito, e o aparelho precisa manter a Outbox pendente.
    expect(push.status).toBe(503);
    expect(push.body.error.code).toBe('SYNC_WRITE_DISABLED');

    const pull = await request(app.getHttpServer())
      .get('/v1/sync/pull?cursor=0')
      .set('Authorization', `Bearer ${TOKEN}`);
    expect(pull.status).toBe(200);
  });

  it('o backup tem teto por conta, e ele recusa com 429 em vez de 500', async () => {
    await start();

    const upload = (n: number) =>
      request(app.getHttpServer())
        .post('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'application/json')
        .send(JSON.stringify(withClientBackupId('backup-v1-minimal', `cli-limite-${n}`)));

    const statuses: number[] = [];
    for (let n = 0; n < 12; n += 1) {
      statuses.push((await upload(n)).status);
    }

    // Os primeiros passam; o laço é barrado. O número exato mora em `backup.limits.ts`.
    expect(statuses[0]).toBe(201);
    const limited = statuses.filter((status) => status === 429);
    expect(limited.length).toBeGreaterThan(0);

    const last = await upload(99);
    expect(last.body.error.code).toBe('BACKUP_RATE_LIMITED');
  });

  it('a leitura para restore não é barrada por um restore legítimo', async () => {
    await start();

    // Um restore inteiro faz três requisições: listar, metadata, conteúdo. Um teto que as
    // alcançasse pararia o usuário exatamente quando ele mais precisa do servidor.
    for (let n = 0; n < 12; n += 1) {
      const response = await request(app.getHttpServer())
        .get('/v1/backups')
        .set('Authorization', `Bearer ${TOKEN}`);
      expect(response.status).toBe(200);
    }
  });
});

describe('Artefatos de produção', () => {
  const BACKEND_ROOT = join(__dirname, '..');
  const REPO_ROOT = join(BACKEND_ROOT, '..');

  const productionArtifacts = [
    join(BACKEND_ROOT, 'docker-compose.prod.yml'),
    join(BACKEND_ROOT, 'Caddyfile.prod'),
    ...readdirSync(join(REPO_ROOT, 'ops'))
      .filter(
        (entry) => entry.endsWith('.sh') || entry.endsWith('.example') || entry.endsWith('.md'),
      )
      .map((entry) => join(REPO_ROOT, 'ops', entry)),
  ];

  it('encontra os arquivos que precisa inspecionar', () => {
    expect(productionArtifacts.length).toBeGreaterThan(5);
  });

  it('nenhum artefato de produção carrega segredo', () => {
    // A varredura da T16.1 cobria só a árvore do backend. A T16.8 acrescentou compose de
    // produção, Caddyfile e scripts operacionais — e é justamente neles que alguém "cola só para
    // testar" uma credencial e esquece.
    const patterns = [
      /-----BEGIN [A-Z ]*PRIVATE KEY/,
      /"type"\s*:\s*"service_account"/,
      /AIza[0-9A-Za-z_-]{20,}/,
      // Uma senha de backup atribuída literalmente. `RESTIC_PASSWORD=` sozinho (vazio, ou
      // comentado como exemplo) continua permitido: é documentação da variável, não um valor.
      // `[ \t]` e não `\s`: com `\s*` o `+` atravessa a quebra de linha e casa com o começo do
      // comentário seguinte — uma variável declarada vazia pareceria uma senha colada.
      /^[ \t]*RESTIC_PASSWORD[ \t]*=[ \t]*\S+/m,
      /^[ \t]*AWS_SECRET_ACCESS_KEY[ \t]*=[ \t]*\S+/m,
      /^[ \t]*B2_ACCOUNT_KEY[ \t]*=[ \t]*\S+/m,
    ];

    const offenders = productionArtifacts.filter((file) => {
      const content = readFileSync(file, 'utf8');
      return patterns.some((pattern) => pattern.test(content));
    });

    expect(offenders.map((file) => file.slice(REPO_ROOT.length))).toEqual([]);
  });

  it('o compose de produção não publica a porta do backend', () => {
    // A diferença entre "o backend fica atrás do Caddy" e "o backend está na internet na porta
    // 8080". Quem publica porta é só o Caddy.
    const compose = readFileSync(join(BACKEND_ROOT, 'docker-compose.prod.yml'), 'utf8');
    const backendSection = compose.slice(compose.indexOf('  backend:'));

    expect(backendSection).toContain('expose:');
    expect(backendSection).not.toMatch(/^\s{4}ports:/m);
    expect(compose).toContain('"443:443"');
  });

  it('o compose de produção monta a credencial somente-leitura e não a embute', () => {
    const compose = readFileSync(join(BACKEND_ROOT, 'docker-compose.prod.yml'), 'utf8');

    expect(compose).toMatch(/firebase-admin\.json:ro/);
    expect(compose).not.toMatch(/-----BEGIN/);
    expect(compose).not.toMatch(/private_key/);
    // Rotação de log: sem ela, o log é o candidato mais provável a encher o disco — e disco cheio
    // derruba o SQLite.
    expect(compose).toContain('max-size:');
    expect(compose).toContain('max-file:');
  });

  it('o Caddyfile de produção não fixa domínio e termina TLS de verdade', () => {
    const caddyfile = readFileSync(join(BACKEND_ROOT, 'Caddyfile.prod'), 'utf8');
    // Sem os comentários: o arquivo **explica** por que não usa `tls internal`, e uma verificação
    // sobre o texto cru falharia por causa da própria justificativa.
    const directives = caddyfile
      .split('\n')
      .filter((line) => !line.trim().startsWith('#'))
      .join('\n');

    // Domínio real é configuração operacional, não código (§8).
    expect(directives).toContain('{$SPARK_DOMAIN}');
    // Nada de certificado interno ou HTTPS desligado em produção.
    expect(directives).not.toContain('tls internal');
    expect(directives).not.toContain('auto_https off');
    // O log do proxy também não pode carregar credencial.
    expect(directives).toContain('Authorization delete');
  });
});
