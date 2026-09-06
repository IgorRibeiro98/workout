import {
  Controller,
  Get,
  INestApplication,
  NotFoundException,
  VERSION_NEUTRAL,
} from '@nestjs/common';
import { Test } from '@nestjs/testing';
import request from 'supertest';
import { AllExceptionsFilter } from '../src/common/all-exceptions.filter';
import { SparkLogger } from '../src/common/logger';
import { REQUEST_ID_HEADER, resolveRequestId } from '../src/common/request-id.middleware';
import { RequestIdMiddleware } from '../src/common/request-id.middleware';
import { HttpLoggerMiddleware } from '../src/common/http-logger.middleware';
import { APP_CONFIG, AppConfig } from '../src/config/app-config';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

@Controller({ path: 'boom', version: VERSION_NEUTRAL })
class BoomController {
  @Get('unhandled')
  unhandled(): never {
    throw new Error('segredo-interno-do-servidor: /var/lib/spark/spark.db');
  }

  @Get('missing')
  missing(): never {
    throw new NotFoundException('recurso não encontrado');
  }
}

async function buildApp(config: AppConfig): Promise<INestApplication> {
  const moduleRef = await Test.createTestingModule({
    controllers: [BoomController],
    providers: [
      { provide: APP_CONFIG, useValue: config },
      SparkLogger,
      RequestIdMiddleware,
      HttpLoggerMiddleware,
    ],
  }).compile();

  const app = moduleRef.createNestApplication({ logger: false });
  const requestId = app.get(RequestIdMiddleware);
  const httpLogger = app.get(HttpLoggerMiddleware);
  app.use(requestId.use.bind(requestId));
  app.use(httpLogger.use.bind(httpLogger));
  app.useGlobalFilters(new AllExceptionsFilter(config, app.get(SparkLogger)));
  await app.init();
  return app;
}

describe('Infraestrutura HTTP (request ID, envelope de erro, vazamento)', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  describe('resolveRequestId', () => {
    it('preserva um request ID do cliente que respeite o contrato', () => {
      expect(resolveRequestId('abc-123_XYZ.456')).toBe('abc-123_XYZ.456');
    });

    it('gera um novo quando o valor do cliente é ausente, curto ou perigoso', () => {
      const generated = [
        resolveRequestId(undefined),
        resolveRequestId('curto'),
        resolveRequestId('tem espaço e \n newline'),
        resolveRequestId('x'.repeat(200)),
        resolveRequestId(['a', 'b']),
      ];

      for (const id of generated) {
        expect(id).toMatch(/^[0-9a-f-]{36}$/);
      }
    });
  });

  it('devolve um request ID no header mesmo quando o cliente não envia nenhum', async () => {
    const app = await buildApp(configFor(temp.path));

    const response = await request(app.getHttpServer()).get('/boom/missing');

    expect(response.headers[REQUEST_ID_HEADER]).toMatch(/^[0-9a-f-]{36}$/);
    await app.close();
  });

  it('preserva o request ID enviado pelo cliente e o repete no envelope de erro', async () => {
    const app = await buildApp(configFor(temp.path));

    const response = await request(app.getHttpServer())
      .get('/boom/missing')
      .set(REQUEST_ID_HEADER, 'cliente-android-0001');

    expect(response.headers[REQUEST_ID_HEADER]).toBe('cliente-android-0001');
    expect(response.body.error.requestId).toBe('cliente-android-0001');
    await app.close();
  });

  it('responde erro no envelope único', async () => {
    const app = await buildApp(configFor(temp.path));

    const response = await request(app.getHttpServer()).get('/boom/missing');

    expect(response.status).toBe(404);
    expect(response.body).toEqual({
      error: {
        code: expect.any(String),
        message: expect.any(String),
        requestId: expect.any(String),
      },
    });
    await app.close();
  });

  it('em produção não expõe stack trace nem a mensagem interna do erro', async () => {
    const app = await buildApp(configFor(temp.path, { NODE_ENV: 'production' }));

    const response = await request(app.getHttpServer()).get('/boom/unhandled');
    const raw = JSON.stringify(response.body);

    expect(response.status).toBe(500);
    expect(response.body.error.code).toBe('INTERNAL_ERROR');
    expect(response.body.error.message).toBe('Unexpected server error');
    expect(raw).not.toContain('segredo-interno-do-servidor');
    expect(raw).not.toContain('/var/lib/spark');
    expect(raw).not.toMatch(/\bat \w+.*\(.*:\d+:\d+\)/);
    expect(response.body.error).not.toHaveProperty('stack');
    await app.close();
  });

  it('nunca devolve stack trace, mesmo fora de produção', async () => {
    const app = await buildApp(configFor(temp.path, { NODE_ENV: 'development' }));

    const response = await request(app.getHttpServer()).get('/boom/unhandled');

    expect(response.status).toBe(500);
    expect(response.body.error).not.toHaveProperty('stack');
    expect(JSON.stringify(response.body)).not.toMatch(/\bat \w+.*\(.*:\d+:\d+\)/);
    await app.close();
  });

  it('não registra header Authorization nem corpo da requisição', async () => {
    const config = configFor(temp.path);
    const logger = new SparkLogger(config);
    const captured: Array<Record<string, unknown>> = [];
    jest.spyOn(logger, 'info').mockImplementation((event, fields) => {
      captured.push({ event, ...fields });
    });

    const moduleRef = await Test.createTestingModule({
      controllers: [BoomController],
      providers: [
        { provide: APP_CONFIG, useValue: config },
        { provide: SparkLogger, useValue: logger },
        HttpLoggerMiddleware,
        RequestIdMiddleware,
      ],
    }).compile();

    const app = moduleRef.createNestApplication({ logger: false });
    const requestId = app.get(RequestIdMiddleware);
    const httpLogger = app.get(HttpLoggerMiddleware);
    app.use(requestId.use.bind(requestId));
    app.use(httpLogger.use.bind(httpLogger));
    app.useGlobalFilters(new AllExceptionsFilter(config, logger));
    await app.init();

    await request(app.getHttpServer())
      .get('/boom/missing')
      .set('Authorization', 'Bearer firebase-id-token-secreto')
      .set('Cookie', 'sessao=valor-secreto');

    const serialized = JSON.stringify(captured);
    expect(captured.length).toBeGreaterThan(0);
    expect(serialized).not.toContain('firebase-id-token-secreto');
    expect(serialized).not.toContain('valor-secreto');
    expect(serialized.toLowerCase()).not.toContain('authorization');
    expect(serialized.toLowerCase()).not.toContain('cookie');

    const access = captured.find((entry) => entry.event === 'http.request');
    expect(access).toMatchObject({
      method: 'GET',
      status: 404,
      requestId: expect.any(String),
      durationMs: expect.any(Number),
    });

    await app.close();
  });
});
