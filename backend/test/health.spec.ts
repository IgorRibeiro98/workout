import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { createApp } from '../src/bootstrap/create-app';
import { SqliteService } from '../src/database/sqlite.service';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

describe('Health (liveness e readiness)', () => {
  let temp: TempDb;
  let app: INestApplication;
  let sqlite: SqliteService;

  beforeEach(async () => {
    temp = createTempDb();
    const created = await createApp(configFor(temp.path));
    app = created.app;
    sqlite = created.sqlite;
    await app.init();
  });

  afterEach(async () => {
    await app.close();
    temp.cleanup();
  });

  it('GET /health/live responde 200 sem consultar serviço externo', async () => {
    const response = await request(app.getHttpServer()).get('/health/live');

    expect(response.status).toBe(200);
    expect(response.body).toEqual({ status: 'ok' });
  });

  it('GET /health/ready responde 200 com banco disponível e migrations aplicadas', async () => {
    const response = await request(app.getHttpServer()).get('/health/ready');

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      status: 'ok',
      checks: { config: true, database: true, migrations: true },
    });
  });

  it('GET /health/ready responde 503 quando o banco não está disponível', async () => {
    sqlite.close();

    const response = await request(app.getHttpServer()).get('/health/ready');

    expect(response.status).toBe(503);
    expect(response.body).toEqual({
      status: 'unavailable',
      // `config` continua true: a configuração foi carregada com sucesso. O que caiu foi o banco,
      // e o readiness precisa dizer exatamente qual verificação falhou.
      checks: { config: true, database: false, migrations: false },
    });
  });

  it('readiness não expõe path interno, variável de ambiente, credencial ou stack trace', async () => {
    const ok = JSON.stringify((await request(app.getHttpServer()).get('/health/ready')).body);
    sqlite.close();
    const failed = JSON.stringify((await request(app.getHttpServer()).get('/health/ready')).body);

    for (const body of [ok, failed]) {
      expect(body).not.toContain(temp.path);
      expect(body).not.toContain(temp.directory);
      expect(body).not.toContain('DATABASE_PATH');
      expect(body).not.toMatch(/\bat \w+.*\(.*:\d+:\d+\)/);
      expect(body).not.toContain('.db');
    }
  });

  it('não anuncia o framework no header X-Powered-By', async () => {
    const response = await request(app.getHttpServer()).get('/health/live');

    expect(response.headers['x-powered-by']).toBeUndefined();
  });

  it('health fica fora de /v1, que é reservado para as APIs de produto', async () => {
    expect((await request(app.getHttpServer()).get('/health/live')).status).toBe(200);
    expect((await request(app.getHttpServer()).get('/v1/health/live')).status).toBe(404);
  });

  it('sync continua ausente de /v1; auth, IA e backup existem e nasceram fechados', async () => {
    // Sync incremental é T16.6/T16.7. Backup (T16.4) **não** é sync: ele sobe um snapshot
    // completo e não tem push, pull, cursor nem merge.
    for (const path of ['/v1/sync/push', '/v1/sync/pull', '/v1/ai/analyze']) {
      expect((await request(app.getHttpServer()).get(path)).status).toBe(404);
    }

    // 401 (e não 404) é a prova de que a rota existe e nasceu protegida.
    expect((await request(app.getHttpServer()).get('/v1/auth/me')).status).toBe(401);
    expect((await request(app.getHttpServer()).get('/v1/backups/latest')).status).toBe(401);
    expect((await request(app.getHttpServer()).post('/v1/backups').send({})).status).toBe(401);
  });

  it('a leitura do backup para restore existe e nasceu fechada (T16.5)', async () => {
    // Este teste substitui o da T16.4, que exigia a **ausência** destas rotas. Elas existem agora,
    // e 401 — não 404 — é a prova de que nasceram protegidas.
    for (const path of ['/v1/backups', '/v1/backups/abc', '/v1/backups/abc/content']) {
      expect((await request(app.getHttpServer()).get(path)).status).toBe(401);
    }
  });

  it('restore não virou um endpoint de importação genérica', async () => {
    // O servidor aceita **um** formato, em **uma** rota de escrita: `POST /v1/backups`, validado
    // contra o registry fechado. Não existe "mande qualquer JSON e importe", e restaurar é leitura
    // do lado do servidor — quem escreve é o Room do aparelho.
    expect((await request(app.getHttpServer()).post('/v1/restore').send({})).status).toBe(404);
    expect(
      (await request(app.getHttpServer()).post('/v1/backups/abc/restore').send({})).status,
    ).toBe(404);
    expect((await request(app.getHttpServer()).post('/v1/import').send({})).status).toBe(404);
    expect((await request(app.getHttpServer()).put('/v1/backups/abc').send({})).status).toBe(404);
    expect((await request(app.getHttpServer()).delete('/v1/backups/abc')).status).toBe(404);
  });
});
