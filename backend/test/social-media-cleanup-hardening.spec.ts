import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';
import { FakeClock } from './support/fake-clock';
import { pushBody, sessionPayload, uuid } from './support/sync-fixtures';
import { jpeg } from './support/image-fixtures';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { SocialMediaCleaner } from '../src/modules/social/social-media.cleaner';
import { SocialMediaRepository } from '../src/modules/social/social-media.repository';
import { MEDIA_PENDING_TTL_MS } from '../src/modules/social/social-media.limits';
import { OBJECT_STORAGE_ORPHAN_GRACE_MS } from '../src/object-storage/object-storage.limits';

const TOKEN = 'token-da-conta';
const UID = 'uid-da-conta';
const NOW = Date.parse('2026-09-10T12:00:00Z');

/**
 * T18.1.1 §8/§9 — a reivindicação atômica de `SocialMediaCleaner` (a race PENDING × ATTACHED) e a
 * contagem honesta de remoções, contra um Object Storage em memória controlável.
 */
describe('T18.1.1 — SocialMediaCleaner endurecido', () => {
  let temp: TempDb;
  let app: INestApplication;
  let clock: FakeClock;
  let fake: InMemoryObjectStorageClient;

  beforeEach(() => {
    temp = createTempDb();
    clock = new FakeClock(NOW);
    fake = new InMemoryObjectStorageClient();
  });

  afterEach(async () => {
    await app?.close();
    app = undefined as unknown as INestApplication;
    temp.cleanup();
  });

  const start = async () => {
    fake.setClock(() => clock.now());
    app = await createTestApp(
      configFor(temp.path, { SOCIAL_MEDIA_ROOT: `${temp.directory}/objects` }),
      FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID }),
      undefined,
      clock,
      undefined,
      { objectStorageClient: fake },
    );
  };

  const server = () => app.getHttpServer();

  const setupSession = async (): Promise<string> => {
    await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', `Bearer ${TOKEN}`)
      .send({ displayName: 'Igor' })
      .expect(200);
    const syncId = uuid();
    await request(server())
      .post('/v1/sync/push')
      .set('Authorization', `Bearer ${TOKEN}`)
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
    return syncId;
  };

  // ================================================================ PENDING × ATTACHED

  describe('a reivindicação nunca perde mídia que ganhou a corrida do ATTACH (§8)', () => {
    it(
      'cleaner "seleciona" PENDING expirada, mas a requisição consegue ATTACH antes: ' +
        'a mídia publicada permanece intacta e servível',
      async () => {
        await start();
        const syncId = await setupSession();

        const upload = await request(server())
          .post('/v1/social/checkin-media')
          .query({ sessionSyncId: syncId, clientUploadId: uuid() })
          .set('Authorization', `Bearer ${TOKEN}`)
          .set('Content-Type', 'image/jpeg')
          .send(await jpeg())
          .expect(201);
        const mediaId = upload.body.mediaId as string;

        // O prazo natural da mídia PENDING já passou — exatamente o que o cleaner trataria como
        // candidata — mas a linha continua `PENDING` até alguém realmente tocar nela.
        clock.advance(MEDIA_PENDING_TTL_MS + 1);

        // A publicação vence a corrida: o `ATTACH` transiciona a linha antes de o cleaner rodar.
        await request(server())
          .post('/v1/social/workout-checkins')
          .set('Authorization', `Bearer ${TOKEN}`)
          .send({ sessionSyncId: syncId, clientRequestId: uuid(), mediaId })
          .expect(201);

        // O cleaner "continua" depois — e não encontra mais nada para reivindicar: a reivindicação
        // releu o estado (agora `ATTACHED`) atomicamente, não uma fotografia anterior ao ATTACH.
        const cleaner = app.get(SocialMediaCleaner);
        expect(await cleaner.sweep()).toBe(0);

        // A mídia publicada continua intacta e servível.
        expect(fake.names()).toHaveLength(1);
        await request(server())
          .get(`/v1/social/media/${mediaId}`)
          .set('Authorization', `Bearer ${TOKEN}`)
          .expect(200);
      },
    );

    it('sem a corrida, uma PENDING realmente expirada continua sendo removida normalmente', async () => {
      await start();
      const syncId = await setupSession();

      const upload = await request(server())
        .post('/v1/social/checkin-media')
        .query({ sessionSyncId: syncId, clientUploadId: uuid() })
        .set('Authorization', `Bearer ${TOKEN}`)
        .set('Content-Type', 'image/jpeg')
        .send(await jpeg())
        .expect(201);

      clock.advance(MEDIA_PENDING_TTL_MS + 1);

      const cleaner = app.get(SocialMediaCleaner);
      expect(await cleaner.sweep()).toBe(1);
      expect(fake.names()).toEqual([]);

      await request(server())
        .get(`/v1/social/media/${upload.body.mediaId}`)
        .set('Authorization', `Bearer ${TOKEN}`)
        .expect(404);
    });

    it(
      'prova direta no repositório: reivindicar primeiro faz o ATTACH concorrente falhar, ' +
        'e nunca o contrário',
      async () => {
        await start();
        await request(server())
          .post('/v1/social/me/activate')
          .set('Authorization', `Bearer ${TOKEN}`)
          .send({ displayName: 'Igor' })
          .expect(200);
        const repository = app.get(SocialMediaRepository);

        const media = {
          id: uuid(),
          ownerUid: UID,
          sourceSessionSyncId: uuid(),
          clientUploadId: uuid(),
          storageKey: `checkins/ab/cd/${uuid()}.webp`,
          mimeType: 'image/webp',
          byteSize: 10,
          width: 10,
          height: 10,
          contentHash: 'hash',
          inputContentHash: null,
          status: 'PENDING' as const,
          createdAt: NOW,
          expiresAt: NOW - 1, // já expirada, sem tocar no relógio
          attachedCheckInId: null,
          deletedAt: null,
        };
        await repository.create(media, 'hash-sem-tombstone');

        const claimed = await repository.claimCollectable(NOW, 10);
        expect(claimed.map((c) => c.id)).toEqual([media.id]);

        // A reivindicação já transicionou a linha: o ATTACH concorrente não encontra mais `PENDING`.
        const attached = await repository.attach(
          media.id,
          media.ownerUid,
          media.sourceSessionSyncId,
          uuid(),
        );
        expect(attached).toBe(false);
      },
    );
  });

  // ================================================================ contagem honesta

  describe('removed/failed honestos (§4)', () => {
    it(
      'um lote com um órfão removível e outro cuja remoção falha: `sweep()` só conta o que ' +
        'realmente saiu, e a falha não interrompe o lote',
      async () => {
        await start();

        const removable = 'social/checkins/aa/aa/aaaaaaaa-0000-4000-8000-000000000000.webp';
        const stuck = 'social/checkins/bb/bb/bbbbbbbb-0000-4000-8000-000000000000.webp';
        for (const key of [removable, stuck]) {
          await fake.write(key, Buffer.from('img'), { contentType: 'image/webp' });
        }
        clock.advance(OBJECT_STORAGE_ORPHAN_GRACE_MS + 1);
        fake.failNextRemoveFor(stuck);

        const cleaner = app.get(SocialMediaCleaner);
        expect(await cleaner.sweep()).toBe(1);
        expect(fake.names()).toEqual([stuck]);

        // A falha não é permanente: a próxima varredura converge.
        expect(await cleaner.sweep()).toBe(1);
        expect(fake.names()).toEqual([]);
      },
    );
  });

  // ================================================================ timestamp desconhecido

  describe('idade não provada nunca é elegível para remoção (§9)', () => {
    it('um objeto sem linha e com createdAt desconhecido (null) nunca é recolhido como órfão', async () => {
      await start();

      const key = 'social/checkins/cc/cc/cccccccc-0000-4000-8000-000000000000.webp';
      await fake.write(key, Buffer.from('img'), { contentType: 'image/webp' });
      fake.setCreatedAt(key, null);
      clock.advance(OBJECT_STORAGE_ORPHAN_GRACE_MS * 100);

      const cleaner = app.get(SocialMediaCleaner);
      expect(await cleaner.sweep()).toBe(0);
      expect(fake.names()).toEqual([key]);
    });
  });
});
