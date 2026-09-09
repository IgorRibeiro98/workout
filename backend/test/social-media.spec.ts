import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import sharp from 'sharp';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import {
  animatedGif,
  decompressionBomb,
  fakeJpeg,
  jpeg,
  jpegWithExifGps,
  metadataOf,
  noisyPhoto,
  oversizedEdge,
  png,
  staticGif,
  webp,
} from './support/image-fixtures';
import {
  MAX_OUTPUT_BYTES,
  MAX_OUTPUT_EDGE_PX,
  MEDIA_PENDING_TTL_MS,
} from '../src/modules/social/social-media.limits';
import { SocialMediaCleaner } from '../src/modules/social/social-media.cleaner';
import { assertSafeStorageKey } from '../src/modules/social/social-media.store';

/**
 * T17.9 — mídia dos check-ins: pipeline, privacidade, autorização, quota e limpeza.
 *
 * As sessões entram pelo **caminho real** (`POST /v1/sync/push`), como na T17.8: o que a T17.9
 * afirma é que só uma sessão canônica sincronizada aceita foto, e semear a tabela à mão provaria a
 * afirmação contra uma fixture em vez de contra o protocolo.
 */

const TOKEN_A = 'token-da-conta-a';
const UID_A = 'uid-da-conta-a';
const TOKEN_B = 'token-da-conta-b';
const UID_B = 'uid-da-conta-b';
const TOKEN_C = 'token-da-conta-c';
const UID_C = 'uid-da-conta-c';

const NOW = Date.parse('2026-09-08T15:00:00Z');

describe('T17.9 — mídia dos check-ins', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let mediaRoot: string;

  beforeEach(async () => {
    temp = createTempDb();
    mediaRoot = join(temp.directory, 'media');
    clock = new FakeClock(NOW);
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: mediaRoot }),
      new FakeAuthTokenVerifier()
        .accept(TOKEN_A, { uid: UID_A, email: 'a@example.com' })
        .accept(TOKEN_B, { uid: UID_B, email: 'b@example.com' })
        .accept(TOKEN_C, { uid: UID_C, email: 'c@example.com' }),
      undefined,
      clock,
    );
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  const inDatabase = <T>(read: (db: BetterSqlite3.Database) => T): T => {
    const db = new BetterSqlite3(temp.path);
    try {
      return read(db);
    } finally {
      db.close();
    }
  };

  const activate = async (token: string, displayName: string): Promise<string> => {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId as string;
  };

  const makeFriends = async (tokenOne: string, tokenTwo: string) => {
    const other = await request(server())
      .get('/v1/social/me')
      .set('Authorization', auth(tokenTwo))
      .expect(200);
    const sent = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenOne))
      .send({ socialId: other.body.profile.socialId })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
      .set('Authorization', auth(tokenTwo))
      .expect(200);
  };

  const pushSession = async (token: string): Promise<string> => {
    const syncId = uuid();
    const res = await request(server())
      .post('/v1/sync/push')
      .set('Authorization', auth(token))
      .set('Content-Type', 'application/json')
      .send(
        pushBody([
          {
            entityType: 'WORKOUT_SESSION',
            entitySyncId: syncId,
            payload: sessionPayload(syncId, {
              startedAt: NOW - 60 * 60 * 1000,
              finishedAt: NOW - 30 * 60 * 1000,
            }),
          },
        ]),
      )
      .expect(200);
    expect(res.body.results[0].status).toBe('APPLIED');
    return syncId;
  };

  const upload = (
    token: string,
    sessionSyncId: string,
    bytes: Buffer,
    options: { clientUploadId?: string; contentType?: string } = {},
  ) =>
    request(server())
      .post('/v1/social/checkin-media')
      .query({ sessionSyncId, clientUploadId: options.clientUploadId ?? uuid() })
      .set('Authorization', auth(token))
      .set('Content-Type', options.contentType ?? 'image/jpeg')
      .send(bytes);

  const createCheckIn = (
    token: string,
    sessionSyncId: string,
    extra: Record<string, unknown> = {},
  ) =>
    request(server())
      .post('/v1/social/workout-checkins')
      .set('Authorization', auth(token))
      .send({ sessionSyncId, clientRequestId: uuid(), ...extra });

  /** Uma conta ativa com sessão sincronizada, pronta para enviar foto. */
  const setupAuthor = async (token = TOKEN_A, name = 'Alice') => {
    await activate(token, name);
    return pushSession(token);
  };

  /** Todos os arquivos presentes na raiz de mídia. */
  const filesOnDisk = (): string[] => {
    const out: string[] = [];
    const walk = (dir: string) => {
      if (!existsSync(dir)) return;
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) walk(path);
        else out.push(path);
      }
    };
    walk(mediaRoot);
    return out;
  };

  // =====================================================================
  // §13/§14/§20 — o pipeline decodifica de verdade
  // =====================================================================

  describe('formatos aceitos e recusados (§13/§14/§167)', () => {
    it.each([
      ['JPEG', () => jpeg()],
      ['PNG', () => png()],
      ['WebP estático', () => webp()],
    ])('aceita %s e armazena a saída sanitizada', async (_label, make) => {
      const syncId = await setupAuthor();

      const res = await upload(TOKEN_A, syncId, await make()).expect(201);

      expect(typeof res.body.mediaId).toBe('string');
      expect(res.body.width).toBeGreaterThan(0);
      expect(res.body.byteSize).toBeLessThanOrEqual(MAX_OUTPUT_BYTES);

      // §13/§18 — a saída é sempre WebP produzido por **este** servidor, e nunca os bytes
      // recebidos. Um PNG entra e um WebP fica guardado.
      const stored = inDatabase(
        (db) =>
          db.prepare(`SELECT mime_type, storage_key FROM social_checkin_media LIMIT 1`).get() as {
            mime_type: string;
            storage_key: string;
          },
      );
      expect(stored.mime_type).toBe('image/webp');
      expect(stored.storage_key.endsWith('.webp')).toBe(true);
    });

    it('recusa bytes que não são imagem, mesmo com Content-Type de JPEG (§14)', async () => {
      const syncId = await setupAuthor();

      // O cabeçalho diz `image/jpeg` e os primeiros bytes são a assinatura de JPEG. É exatamente o
      // arquivo que uma verificação de MIME ou de magic number aceitaria.
      const res = await upload(TOKEN_A, syncId, fakeJpeg()).expect(400);

      expect(res.body.error.code).toBe('INVALID_IMAGE');
      expect(
        inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_checkin_media`).get()),
      ).toEqual({ n: 0 });
      expect(filesOnDisk()).toEqual([]);
    });

    it('recusa uma imagem animada, e diz que o problema é a animação (§3)', async () => {
      const syncId = await setupAuthor();

      const res = await upload(TOKEN_A, syncId, animatedGif(), {
        contentType: 'image/png',
      }).expect(400);

      expect(res.body.error.code).toBe('INVALID_IMAGE');
      expect(res.body.error.message).toContain('animadas');
    });

    it('recusa GIF estático por formato (§13)', async () => {
      const syncId = await setupAuthor();

      const res = await upload(TOKEN_A, syncId, await staticGif(), {
        contentType: 'image/png',
      }).expect(400);

      expect(res.body.error.code).toBe('INVALID_IMAGE');
      expect(res.body.error.message).toContain('formato');
    });

    it('recusa uma bomba de descompressão: arquivo pequeno, pixels demais (§20)', async () => {
      const syncId = await setupAuthor();
      const bomb = await decompressionBomb();

      // O ponto: o arquivo é minúsculo. Um teto que só olhasse bytes o aceitaria.
      expect(bomb.length).toBeLessThan(100_000);

      const res = await upload(TOKEN_A, syncId, bomb, { contentType: 'image/png' }).expect(400);
      expect(res.body.error.code).toBe('INVALID_IMAGE');
    });

    it('recusa uma aresta absurda mesmo com poucos pixels no total (§20)', async () => {
      const syncId = await setupAuthor();

      const res = await upload(TOKEN_A, syncId, await oversizedEdge(), {
        contentType: 'image/png',
      }).expect(400);
      expect(res.body.error.code).toBe('INVALID_IMAGE');
      expect(res.body.error.message).toContain('dimensões');
    });

    it('recusa um upload acima do teto de bytes (§18)', async () => {
      const syncId = await setupAuthor();
      // Ruído aleatório de 11 MiB: não comprime, e o teto é 10 MiB.
      const huge = Buffer.alloc(11 * 1024 * 1024);
      for (let i = 0; i < huge.length; i += 1) huge[i] = i % 251;

      // O parser recusa antes do serviço: o corpo nem chega a ser bufferizado inteiro.
      const res = await upload(TOKEN_A, syncId, huge);
      expect(res.status).toBeGreaterThanOrEqual(400);
      expect(filesOnDisk()).toEqual([]);
    });

    it('recusa um corpo vazio', async () => {
      const syncId = await setupAuthor();
      const res = await upload(TOKEN_A, syncId, Buffer.alloc(0)).expect(400);
      expect(res.body.error.code).toBe('INVALID_IMAGE');
    });
  });

  // =====================================================================
  // §15/§16/§17/§168 — privacidade da imagem
  // =====================================================================

  describe('EXIF, GPS e o original (§16/§17/§168)', () => {
    it('a imagem armazenada não preserva EXIF, GPS, modelo do aparelho nem data original', async () => {
      const syncId = await setupAuthor();
      const original = await jpegWithExifGps();

      // A fixture **tem** metadata — senão este teste não provaria nada.
      const before = await metadataOf(original);
      expect(before.exif).toBeDefined();
      expect(before.exif!.length).toBeGreaterThan(0);

      const res = await upload(TOKEN_A, syncId, original).expect(201);
      await createCheckIn(TOKEN_A, syncId, { mediaId: res.body.mediaId }).expect(201);

      const bytes = await request(server())
        .get(`/v1/social/media/${res.body.mediaId}`)
        .set('Authorization', auth(TOKEN_A))
        .buffer(true)
        .parse((response, callback) => {
          const chunks: Buffer[] = [];
          response.on('data', (chunk: Buffer) => chunks.push(chunk));
          response.on('end', () => callback(null, Buffer.concat(chunks)));
        })
        .expect(200);

      const stored = bytes.body as Buffer;
      const after = await metadataOf(stored);

      // O bloqueante de privacidade (§16): nenhum metadata sobrevive ao re-encode.
      expect(after.exif).toBeUndefined();
      expect(after.icc).toBeUndefined();
      expect(after.iptc).toBeUndefined();
      expect(after.xmp).toBeUndefined();

      // E a varredura textual, que pega o que a API de metadata não expõe: nenhum vestígio dos
      // valores que estavam no original.
      const text = stored.toString('latin1');
      for (const forbidden of ['SparkPhone', 'SparkCam', 'GPS', '2026:09:08', 'Exif']) {
        expect(text).not.toContain(forbidden);
      }
    });

    it('o arquivo original nunca é escrito em disco — só a representação sanitizada (§17)', async () => {
      const syncId = await setupAuthor();
      const original = await jpegWithExifGps();

      await upload(TOKEN_A, syncId, original).expect(201);

      const files = filesOnDisk();
      expect(files).toHaveLength(1);

      const written = readFileSync(files[0]);
      expect(written.equals(original)).toBe(false);
      expect((await metadataOf(written)).format).toBe('webp');
      expect((await metadataOf(written)).exif).toBeUndefined();
    });

    it('reduz a maior aresta e nunca amplia (§18)', async () => {
      const syncId = await setupAuthor();

      const big = await upload(TOKEN_A, syncId, await jpeg(4000, 3000)).expect(201);
      expect(Math.max(big.body.width, big.body.height)).toBe(MAX_OUTPUT_EDGE_PX);

      const smallSession = await pushSession(TOKEN_A);
      const small = await upload(TOKEN_A, smallSession, await jpeg(200, 150)).expect(201);
      expect(small.body.width).toBe(200);
      expect(small.body.height).toBe(150);
    });

    it('o arquivo processado respeita o teto de bytes (§19)', async () => {
      const syncId = await setupAuthor();
      // Ruído fotográfico: comprime mal, e é o caso que força a degradação de qualidade em
      // degraus. As dimensões cabem no teto de upload — o que se mede aqui é a **saída**.
      const noisy = await sharp({
        create: {
          width: 2400,
          height: 2400,
          channels: 3,
          background: { r: 0, g: 0, b: 0 },
          noise: { type: 'gaussian', mean: 128, sigma: 90 },
        },
      })
        .jpeg({ quality: 92 })
        .toBuffer();
      expect(noisy.length).toBeLessThan(10 * 1024 * 1024);

      const res = await upload(TOKEN_A, syncId, noisy).expect(201);
      expect(res.body.byteSize).toBeLessThanOrEqual(MAX_OUTPUT_BYTES);
    });
  });

  // =====================================================================
  // §23/§24/§25 — a chave é opaca e o caminho é impossível de forjar
  // =====================================================================

  describe('chave de armazenamento (§23/§24/§25)', () => {
    it('o nome do arquivo não deriva de uid, socialId, friendCode nem do nome original', async () => {
      const socialId = await activate(TOKEN_A, 'Alice');
      const syncId = await pushSession(TOKEN_A);
      const profile = await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      const friendCode = profile.body.profile.friendCode as string;

      await upload(TOKEN_A, syncId, await jpeg()).expect(201);

      const key = inDatabase(
        (db) =>
          (db.prepare(`SELECT storage_key AS k FROM social_checkin_media`).get() as { k: string })
            .k,
      );

      for (const forbidden of [UID_A, socialId, friendCode, 'Alice', 'a@example.com', syncId]) {
        expect(key).not.toContain(forbidden);
      }
      expect(key).toMatch(/^checkins\/[0-9a-f]{2}\/[0-9a-f]{2}\/[0-9a-f-]{36}\.webp$/);
    });

    it('nenhuma chave fora da forma canônica é aceita — path traversal é impossível (§25)', () => {
      // A função é pura e é a **única** tradução de chave para caminho no servidor. Testá-la
      // diretamente é o que permite cobrir as codificações sem precisar de uma rota que as aceite
      // — e nenhuma rota aceita, porque a chave nunca vem do cliente.
      for (const hostile of [
        '../../etc/passwd',
        'checkins/../../etc/passwd',
        '/etc/passwd',
        'checkins/ab/cd/../../../../etc/passwd.webp',
        'checkins\\ab\\cd\\file.webp',
        'checkins/ab/cd/arquivo.webp',
        'checkins/ab/cd/00000000-0000-0000-0000-000000000000.png',
        'checkins/AB/CD/00000000-0000-0000-0000-000000000000.webp',
        '',
      ]) {
        expect(() => assertSafeStorageKey(hostile)).toThrow();
      }

      expect(() =>
        assertSafeStorageKey('checkins/ab/cd/8f14e45f-ea6d-4b1e-9b3a-2c1d5e6f7a8b.webp'),
      ).not.toThrow();
    });

    it('a imagem não entra no SQLite (§21)', async () => {
      const syncId = await setupAuthor();
      await upload(TOKEN_A, syncId, await jpeg()).expect(201);

      const columns = inDatabase(
        (db) =>
          db.prepare(`PRAGMA table_info(social_checkin_media)`).all() as Array<{
            name: string;
            type: string;
          }>,
      );
      expect(columns.some((column) => column.type.toUpperCase().includes('BLOB'))).toBe(false);

      // E a prova concreta: o banco não cresceu com o tamanho da imagem.
      const stored = inDatabase(
        (db) =>
          (db.prepare(`SELECT byte_size AS s FROM social_checkin_media`).get() as { s: number }).s,
      );
      expect(stored).toBeGreaterThan(0);
      expect(statSync(temp.path).size).toBeLessThan(2_000_000);
    });
  });

  // =====================================================================
  // §32/§34/§35/§36 — vínculo, propriedade e idempotência
  // =====================================================================

  describe('vínculo e propriedade (§32/§34/§35/§36/§149)', () => {
    it('não aceita upload sem sessão elegível — o endpoint não é armazenamento genérico (§32)', async () => {
      await activate(TOKEN_A, 'Alice');

      const res = await upload(TOKEN_A, uuid(), await jpeg()).expect(404);
      expect(res.body.error.code).toBe('SESSION_NOT_FOUND');
      expect(filesOnDisk()).toEqual([]);
    });

    it('a sessão de outra conta é indistinguível de inexistente (§149)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      const sessionOfB = await pushSession(TOKEN_B);

      const res = await upload(TOKEN_A, sessionOfB, await jpeg()).expect(404);
      expect(res.body.error.code).toBe('SESSION_NOT_FOUND');
    });

    it('uma conta não anexa a mídia de outra (§34/§148)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      const sessionA = await pushSession(TOKEN_A);
      const sessionB = await pushSession(TOKEN_B);

      const mediaOfA = await upload(TOKEN_A, sessionA, await jpeg()).expect(201);

      // B tenta publicar o próprio check-in com o `mediaId` de A — o cenário de §149 (conta trocou
      // durante o upload) e o de um cliente hostil que descobriu o identificador.
      const res = await createCheckIn(TOKEN_B, sessionB, { mediaId: mediaOfA.body.mediaId }).expect(
        404,
      );
      expect(res.body.error.code).toBe('MEDIA_NOT_FOUND');

      // §43 — a publicação não aconteceu **sem** a foto: ela simplesmente não aconteceu.
      const feed = await request(server())
        .get('/v1/social/feed')
        .set('Authorization', auth(TOKEN_B))
        .expect(200);
      expect(feed.body.items).toEqual([]);
    });

    it('uma mídia de outra sessão da mesma conta não anexa (§34)', async () => {
      await activate(TOKEN_A, 'Alice');
      const sessionOne = await pushSession(TOKEN_A);
      const sessionTwo = await pushSession(TOKEN_A);

      const media = await upload(TOKEN_A, sessionOne, await jpeg()).expect(201);

      const res = await createCheckIn(TOKEN_A, sessionTwo, {
        mediaId: media.body.mediaId,
      }).expect(404);
      expect(res.body.error.code).toBe('MEDIA_NOT_FOUND');
    });

    it('um mediaId não é reutilizável em um segundo check-in (§35)', async () => {
      await activate(TOKEN_A, 'Alice');
      const sessionOne = await pushSession(TOKEN_A);
      const sessionTwo = await pushSession(TOKEN_A);

      const media = await upload(TOKEN_A, sessionOne, await jpeg()).expect(201);
      await createCheckIn(TOKEN_A, sessionOne, { mediaId: media.body.mediaId }).expect(201);

      const res = await createCheckIn(TOKEN_A, sessionTwo, {
        mediaId: media.body.mediaId,
      }).expect(404);
      expect(res.body.error.code).toBe('MEDIA_NOT_FOUND');
    });

    it('retry do mesmo upload devolve o mesmo mediaId e não duplica arquivo (§36)', async () => {
      const syncId = await setupAuthor();
      const clientUploadId = uuid();
      const bytes = await jpeg();

      const first = await upload(TOKEN_A, syncId, bytes, { clientUploadId }).expect(201);
      const second = await upload(TOKEN_A, syncId, bytes, { clientUploadId }).expect(201);

      expect(second.body.mediaId).toBe(first.body.mediaId);
      expect(filesOnDisk()).toHaveLength(1);
      expect(
        inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_checkin_media`).get()),
      ).toEqual({ n: 1 });
    });

    it('o mesmo clientUploadId em outra sessão é recusado (§36)', async () => {
      await activate(TOKEN_A, 'Alice');
      const sessionOne = await pushSession(TOKEN_A);
      const sessionTwo = await pushSession(TOKEN_A);
      const clientUploadId = uuid();

      await upload(TOKEN_A, sessionOne, await jpeg(), { clientUploadId }).expect(201);
      const res = await upload(TOKEN_A, sessionTwo, await jpeg(), { clientUploadId }).expect(404);
      expect(res.body.error.code).toBe('MEDIA_NOT_FOUND');
    });

    it('o hash da representação sanitizada é gravado, e não autoriza nada (§37)', async () => {
      const syncId = await setupAuthor();
      const res = await upload(TOKEN_A, syncId, await jpeg()).expect(201);

      const hash = inDatabase(
        (db) =>
          (db.prepare(`SELECT content_hash AS h FROM social_checkin_media`).get() as { h: string })
            .h,
      );
      expect(hash).toMatch(/^[0-9a-f]{64}$/);

      // O hash não aparece em resposta nenhuma: ele é integridade, não credencial.
      expect(JSON.stringify(res.body)).not.toContain(hash);
    });
  });

  // =====================================================================
  // §29/§30/§31 — quota e teto
  // =====================================================================

  describe('quota e teto (§29/§30/§31/§157)', () => {
    it('a quota por conta é respeitada, e o upload abandonado conta nela (§29/§30)', async () => {
      await app.close();
      // 1 MiB — o menor valor que a configuração aceita. O teste exercita a **regra**, e não o
      // número de produção (250 MB), que exigiria centenas de uploads para ser alcançado.
      app = await createTestApp(
        configFor(temp.path, {
          SOCIAL_MEDIA_ROOT: mediaRoot,
          SOCIAL_MEDIA_MAX_USER_BYTES: String(1024 * 1024),
        }),
        new FakeAuthTokenVerifier().accept(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, { uid: UID_B }),
        undefined,
        clock,
      );

      await activate(TOKEN_A, 'Alice');
      const sessions = [await pushSession(TOKEN_A), await pushSession(TOKEN_A)];

      // O primeiro passa; nenhum dos dois é anexado a check-in nenhum — os dois ficam `PENDING`,
      // e §30 diz que `PENDING` ocupa quota, porque ocupa disco.
      await upload(TOKEN_A, sessions[0], await noisyPhoto()).expect(201);
      const second = await upload(TOKEN_A, sessions[1], await noisyPhoto());

      expect(second.status).toBe(422);
      expect(second.body.error.code).toBe('MEDIA_QUOTA_EXCEEDED');
    });

    it('a quota é por conta: encher a de A não afeta B (§29)', async () => {
      await app.close();
      app = await createTestApp(
        configFor(temp.path, {
          SOCIAL_MEDIA_ROOT: mediaRoot,
          SOCIAL_MEDIA_MAX_USER_BYTES: String(1024 * 1024),
        }),
        new FakeAuthTokenVerifier().accept(TOKEN_A, { uid: UID_A }).accept(TOKEN_B, { uid: UID_B }),
        undefined,
        clock,
      );

      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      const sessionA1 = await pushSession(TOKEN_A);
      const sessionA2 = await pushSession(TOKEN_A);
      const sessionB = await pushSession(TOKEN_B);

      await upload(TOKEN_A, sessionA1, await noisyPhoto()).expect(201);
      await upload(TOKEN_A, sessionA2, await noisyPhoto()).expect(422);

      await upload(TOKEN_B, sessionB, await noisyPhoto()).expect(201);
    });

    it('o teto de uploads por hora contém um laço de cliente (§157)', async () => {
      await activate(TOKEN_A, 'Alice');
      const sessions: string[] = [];
      for (let i = 0; i < 21; i += 1) sessions.push(await pushSession(TOKEN_A));

      let limited = 0;
      for (const session of sessions) {
        const res = await upload(TOKEN_A, session, await jpeg(64, 64));
        if (res.status === 429) limited += 1;
      }
      expect(limited).toBeGreaterThan(0);
    });
  });

  // =====================================================================
  // §50–§54/§169 — autorização dos bytes
  // =====================================================================

  describe('autorização da imagem (§50–§54/§169)', () => {
    const publishWithPhoto = async (
      token: string,
    ): Promise<{ mediaId: string; checkInId: string }> => {
      const syncId = await pushSession(token);
      const media = await upload(token, syncId, await jpeg()).expect(201);
      const checkIn = await createCheckIn(token, syncId, { mediaId: media.body.mediaId }).expect(
        201,
      );
      return { mediaId: media.body.mediaId, checkInId: checkIn.body.checkInId };
    };

    const fetchMedia = (token: string, mediaId: string) =>
      request(server()).get(`/v1/social/media/${mediaId}`).set('Authorization', auth(token));

    it('o autor lê a própria foto', async () => {
      await activate(TOKEN_A, 'Alice');
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      const res = await fetchMedia(TOKEN_A, mediaId).expect(200);
      expect(res.headers['content-type']).toContain('image/webp');
    });

    it('o amigo lê; o desconhecido recebe 404 mesmo conhecendo o mediaId (§51)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await activate(TOKEN_C, 'Cris');
      await makeFriends(TOKEN_A, TOKEN_B);

      const { mediaId } = await publishWithPhoto(TOKEN_A);

      await fetchMedia(TOKEN_B, mediaId).expect(200);
      // C conhece o UUID — ele está escrito neste teste — e ainda assim não baixa nada.
      await fetchMedia(TOKEN_C, mediaId).expect(404);
    });

    it('sem autenticação não há imagem nenhuma (§48/§49)', async () => {
      await activate(TOKEN_A, 'Alice');
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      await request(server()).get(`/v1/social/media/${mediaId}`).expect(401);
    });

    it('desfazer a amizade revoga na requisição seguinte (§53)', async () => {
      await activate(TOKEN_A, 'Alice');
      const socialB = await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      await fetchMedia(TOKEN_B, mediaId).expect(200);

      await request(server())
        .post('/v1/social/friends/remove')
        .set('Authorization', auth(TOKEN_A))
        .send({ socialId: socialB })
        .expect(200);

      await fetchMedia(TOKEN_B, mediaId).expect(404);
    });

    it.each([
      ['A bloqueia B', TOKEN_A],
      ['B bloqueia A', TOKEN_B],
    ])('%s revoga a foto nas duas direções (§52)', async (_label, blockerToken) => {
      const socialA = await activate(TOKEN_A, 'Alice');
      const socialB = await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      await fetchMedia(TOKEN_B, mediaId).expect(200);

      await request(server())
        .post('/v1/social/blocks')
        .set('Authorization', auth(blockerToken))
        .send({ blockedSocialId: blockerToken === TOKEN_A ? socialB : socialA })
        .expect(200);

      await fetchMedia(TOKEN_B, mediaId).expect(404);
    });

    it('desativar o Social do autor esconde a foto, e reativar a devolve (§54)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      await fetchMedia(TOKEN_B, mediaId).expect(404);

      await request(server())
        .post('/v1/social/me/enable')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      await fetchMedia(TOKEN_B, mediaId).expect(200);
    });

    it('quem desativou o próprio Social para de ler a foto dos outros (§54)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      await request(server())
        .post('/v1/social/me/disable')
        .set('Authorization', auth(TOKEN_B))
        .expect(200);

      await fetchMedia(TOKEN_B, mediaId).expect(404);
    });

    it('excluir a publicação revoga a foto imediatamente (§99/§176)', async () => {
      await activate(TOKEN_A, 'Alice');
      await activate(TOKEN_B, 'Bob');
      await makeFriends(TOKEN_A, TOKEN_B);
      const { mediaId, checkInId } = await publishWithPhoto(TOKEN_A);

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      // §100 — a visibilidade cai **agora**; o arquivo sai depois, pelo cleaner.
      await fetchMedia(TOKEN_B, mediaId).expect(404);
      await fetchMedia(TOKEN_A, mediaId).expect(404);
    });

    it('mídia ainda não anexada não é servível nem para o dono (§41)', async () => {
      const syncId = await setupAuthor();
      const media = await upload(TOKEN_A, syncId, await jpeg()).expect(201);

      await fetchMedia(TOKEN_A, media.body.mediaId).expect(404);
    });

    it('a resposta é cache privado e sem armazenamento (§55)', async () => {
      await activate(TOKEN_A, 'Alice');
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      const res = await fetchMedia(TOKEN_A, mediaId).expect(200);
      expect(res.headers['cache-control']).toContain('private');
      expect(res.headers['cache-control']).toContain('no-store');
      expect(res.headers['cache-control']).not.toContain('public');
      expect(res.headers['x-content-type-options']).toBe('nosniff');
      // Sem validador condicional: um `304` responderia sem a política correr (§53/§54).
      expect(res.headers['etag']).toBeUndefined();
    });

    it('arquivo ausente após restore parcial não derruba nada, e não expõe caminho (§141)', async () => {
      await activate(TOKEN_A, 'Alice');
      const { mediaId } = await publishWithPhoto(TOKEN_A);

      // Simula o restore em que o banco veio e a mídia não.
      for (const file of filesOnDisk()) {
        rmSync(file);
      }

      const res = await fetchMedia(TOKEN_A, mediaId).expect(404);
      expect(JSON.stringify(res.body)).not.toContain(mediaRoot);
      expect(JSON.stringify(res.body)).not.toContain('checkins/');

      // E o Feed continua respondendo: o card aparece, com metadata da foto, e a tela decide.
      const feed = await request(server())
        .get('/v1/social/feed')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
      expect(feed.body.items).toHaveLength(1);
    });
  });

  // =====================================================================
  // §38/§39/§140 — expiração, limpeza e órfãos
  // =====================================================================

  describe('limpeza (§38/§39/§140)', () => {
    it('a mídia PENDING expira e some do disco (§38/§39)', async () => {
      const syncId = await setupAuthor();
      await upload(TOKEN_A, syncId, await jpeg()).expect(201);
      expect(filesOnDisk()).toHaveLength(1);

      const cleaner = app.get(SocialMediaCleaner);

      // Antes do prazo, nada acontece.
      expect(await cleaner.sweep()).toBe(0);
      expect(filesOnDisk()).toHaveLength(1);

      clock.advance(MEDIA_PENDING_TTL_MS + 1);
      expect(await cleaner.sweep()).toBe(1);

      expect(filesOnDisk()).toEqual([]);
      expect(
        inDatabase((db) => db.prepare(`SELECT COUNT(*) n FROM social_checkin_media`).get()),
      ).toEqual({ n: 0 });
    });

    it('a mídia anexada não expira (§38)', async () => {
      const syncId = await setupAuthor();
      const media = await upload(TOKEN_A, syncId, await jpeg()).expect(201);
      await createCheckIn(TOKEN_A, syncId, { mediaId: media.body.mediaId }).expect(201);

      clock.advance(MEDIA_PENDING_TTL_MS * 100);
      await app.get(SocialMediaCleaner).sweep();

      expect(filesOnDisk()).toHaveLength(1);
      await request(server())
        .get(`/v1/social/media/${media.body.mediaId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(200);
    });

    it('o arquivo de uma publicação excluída é removido pela varredura (§100)', async () => {
      const syncId = await setupAuthor();
      const media = await upload(TOKEN_A, syncId, await jpeg()).expect(201);
      const checkIn = await createCheckIn(TOKEN_A, syncId, {
        mediaId: media.body.mediaId,
      }).expect(201);

      await request(server())
        .delete(`/v1/social/workout-checkins/${checkIn.body.checkInId}`)
        .set('Authorization', auth(TOKEN_A))
        .expect(204);

      expect(filesOnDisk()).toHaveLength(1);
      await app.get(SocialMediaCleaner).sweep();
      expect(filesOnDisk()).toEqual([]);
    });

    it('arquivos órfãos não crescem indefinidamente (§140)', async () => {
      const syncId = await setupAuthor();
      await upload(TOKEN_A, syncId, await jpeg()).expect(201);

      // Um arquivo com a forma correta, mas sem metadata — o que sobra se o processo morrer entre
      // escrever o arquivo e inserir a linha.
      const orphanDir = join(mediaRoot, 'checkins', 'ff', 'ee');
      mkdirSync(orphanDir, { recursive: true });
      const orphan = join(orphanDir, 'ffeed0d0-0000-4000-8000-000000000000.webp');
      writeFileSync(orphan, await webp(10, 10));

      expect(filesOnDisk()).toHaveLength(2);
      await app.get(SocialMediaCleaner).sweep();

      // O órfão sai; o que tem metadata fica.
      expect(existsSync(orphan)).toBe(false);
      expect(filesOnDisk()).toHaveLength(1);
    });
  });

  // =====================================================================
  // §58/§59/§133/§134 — o DTO
  // =====================================================================

  describe('o Feed publica mediaId, e nunca bytes ou URL (§58/§59/§133/§134)', () => {
    it('o item traz mediaId e dimensões, e nada mais sobre a foto', async () => {
      await activate(TOKEN_A, 'Alice');
      const syncId = await pushSession(TOKEN_A);
      const media = await upload(TOKEN_A, syncId, await jpeg(1080, 1350)).expect(201);
      await createCheckIn(TOKEN_A, syncId, {
        mediaId: media.body.mediaId,
        caption: 'Hoje rendeu demais',
      }).expect(201);

      const feed = await request(server())
        .get('/v1/social/feed')
        .set('Authorization', auth(TOKEN_A))
        .expect(200);

      const [item] = feed.body.items;
      expect(Object.keys(item.media).sort()).toEqual(['height', 'mediaId', 'width']);
      expect(item.media.width).toBe(1080);
      expect(item.media.height).toBe(1350);
      expect(item.caption).toBe('Hoje rendeu demais');

      const serialized = JSON.stringify(feed.body);
      for (const forbidden of [
        'data:image',
        'base64',
        'storageKey',
        'storage_key',
        'contentHash',
        'http://',
        'https://',
        mediaRoot,
        'checkins/',
        '.webp',
      ]) {
        expect(serialized).not.toContain(forbidden);
      }
    });
  });
});
