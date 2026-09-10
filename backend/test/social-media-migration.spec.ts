import { randomUUID } from 'node:crypto';
import { configFor, createTempDb, postgresFor, type TempDb } from './support/temp-db';
import { InMemoryObjectStorageClient } from './support/fake-object-storage';
import { PostgresService } from '../src/database/postgres.service';
import { LocalObjectStorageClient } from '../src/object-storage/local-object-storage.client';
import { ObjectAlreadyExistsError } from '../src/object-storage/object-storage.client';
import {
  ObjectStorageSocialMediaStore,
  contentHashOf,
  type SocialMediaStore,
} from '../src/modules/social/social-media.store';
import { SocialMediaRepository } from '../src/modules/social/social-media.repository';
import { migrateSocialMedia } from '../src/cli/migrate-social-media-to-object-storage';

const UID = 'uid-da-conta';

/**
 * T18.1.1 requisito 4 — o migrador `local → GCS` de mídia social legada.
 *
 * A origem é sempre `ObjectStorageSocialMediaStore` sobre `LocalObjectStorageClient` (o disco
 * legado de verdade); o destino é o dublê em memória, para injetar exatamente as falhas que um
 * bucket real produziria sem precisar de rede nem GCP.
 */
describe('T18.1.1 — migração de mídia social legada (local → GCS)', () => {
  let temp: TempDb;
  let postgres: PostgresService;
  let repository: SocialMediaRepository;
  let source: SocialMediaStore;
  let sourceClient: LocalObjectStorageClient;
  let destinationClient: InMemoryObjectStorageClient;
  let destination: SocialMediaStore;

  beforeEach(async () => {
    temp = createTempDb();
    postgres = postgresFor(configFor(temp.path));
    await postgres.initialize();
    repository = new SocialMediaRepository(postgres);

    sourceClient = new LocalObjectStorageClient(`${temp.directory}/legacy`);
    source = new ObjectStorageSocialMediaStore(sourceClient);
    destinationClient = new InMemoryObjectStorageClient();
    destination = new ObjectStorageSocialMediaStore(destinationClient);

    // A FK de `social_checkin_media.owner_uid` exige um perfil social existente.
    const now = Date.now();
    await postgres.query(
      `INSERT INTO social_profiles (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
       VALUES ($1, $2, $3, 'Igor', 'ACTIVE', $4, $4)`,
      [UID, 'social-da-conta', 'SPK-AAAAAAAA', now],
    );
  });

  afterEach(async () => {
    await postgres.close();
    temp.cleanup();
  });

  /** Uma linha de `social_checkin_media` com o `content_hash` real dos bytes dados. */
  async function seedRow(bytes: Buffer): Promise<{ id: string; storageKey: string }> {
    const id = randomUUID();
    const storageKey = `checkins/${id.slice(0, 2)}/${id.slice(2, 4)}/${id}.webp`;
    const now = Date.now();
    await postgres.query(
      `INSERT INTO social_checkin_media
         (id, owner_uid, source_session_sync_id, client_upload_id, storage_key, mime_type,
          byte_size, width, height, content_hash, input_content_hash, status, created_at,
          expires_at, attached_checkin_id, deleted_at)
       VALUES ($1, $2, $3, $4, $5, 'image/webp', $6, 10, 10, $7, NULL, 'PENDING', $8, NULL, NULL, NULL)`,
      [id, UID, randomUUID(), randomUUID(), storageKey, bytes.length, contentHashOf(bytes), now],
    );
    return { id, storageKey };
  }

  it('arquivo local válido e íntegro → MIGRATED, e o destino recebe os mesmos bytes', async () => {
    const bytes = Buffer.from('conteúdo real da foto');
    const { storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);

    const report = await migrateSocialMedia(repository, source, destination);
    expect(report).toEqual({ migrated: 1, converged: 0, failed: 0 });
    expect((await destination.read(storageKey))!.equals(bytes)).toBe(true);
  });

  it('arquivo ausente no disco legado → FAILED (SOURCE_MISSING), nada sobe', async () => {
    const bytes = Buffer.from('nunca chega a existir no disco');
    const { storageKey } = await seedRow(bytes);
    // Sem `source.write(...)`: a linha existe, o arquivo não.

    const report = await migrateSocialMedia(repository, source, destination);
    expect(report).toEqual({ migrated: 0, converged: 0, failed: 1 });
    expect(await destination.exists(storageKey)).toBe(false);
  });

  it('hash divergente entre o arquivo local e content_hash → FAILED (fail closed), nada sobe', async () => {
    const declaredBytes = Buffer.from('o que a metadata diz que é');
    const { storageKey } = await seedRow(declaredBytes);
    // O arquivo real no disco é outra coisa — corrompido, ou pertence a outra mídia.
    await source.write(storageKey, Buffer.from('bytes reais, diferentes'));

    const report = await migrateSocialMedia(repository, source, destination);
    expect(report).toEqual({ migrated: 0, converged: 0, failed: 1 });
    expect(await destination.exists(storageKey)).toBe(false);
  });

  it('objeto já existe no destino, idêntico → CONVERGED, sem reenviar', async () => {
    const bytes = Buffer.from('já migrado numa execução anterior');
    const { storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);
    await destination.write(storageKey, bytes);

    const report = await migrateSocialMedia(repository, source, destination);
    expect(report).toEqual({ migrated: 0, converged: 1, failed: 0 });
  });

  it('objeto já existe no destino, com OUTRO conteúdo → FAILED, nunca sobrescreve', async () => {
    const bytes = Buffer.from('bytes de origem');
    const { storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);
    const incompatible = Buffer.from('bytes completamente diferentes no destino');
    await destinationClient.write(`social/${storageKey}`, incompatible, {
      contentType: 'image/webp',
    });

    const report = await migrateSocialMedia(repository, source, destination);
    expect(report).toEqual({ migrated: 0, converged: 0, failed: 1 });
    expect((await destination.read(storageKey))!.equals(incompatible)).toBe(true);
  });

  it('upload interrompido por uma corrida (destino nasce entre a leitura e a escrita) → FAILED (OBJECT_RACE)', async () => {
    const bytes = Buffer.from('corrida com outra execução do migrador');
    const { storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);

    // Um destino que sempre lê "ausente" mas sempre colide ao escrever — a corrida em que outra
    // execução do migrador grava o objeto exatamente entre a leitura e a escrita desta.
    const racing: SocialMediaStore = {
      newStorageKey: () => destination.newStorageKey(),
      write: () => Promise.reject(new ObjectAlreadyExistsError()),
      openRead: (key) => destination.openRead(key),
      read: () => Promise.resolve(null),
      exists: (key) => destination.exists(key),
      remove: (key) => destination.remove(key),
      listObjects: (token) => destination.listObjects(token),
    };

    const report = await migrateSocialMedia(repository, source, racing);
    expect(report).toEqual({ migrated: 0, converged: 0, failed: 1 });
  });

  it('rodar duas vezes é seguro: a segunda execução converge tudo o que a primeira migrou', async () => {
    const bytes = Buffer.from('idempotência entre execuções');
    const { storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);

    const first = await migrateSocialMedia(repository, source, destination);
    expect(first).toEqual({ migrated: 1, converged: 0, failed: 0 });

    const second = await migrateSocialMedia(repository, source, destination);
    expect(second).toEqual({ migrated: 0, converged: 1, failed: 0 });
    expect((await destination.read(storageKey))!.equals(bytes)).toBe(true);
  });

  it('não apaga a origem: o arquivo legado continua no disco depois de migrar', async () => {
    const bytes = Buffer.from('não pode sumir do disco legado');
    const { storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);

    await migrateSocialMedia(repository, source, destination);

    expect((await source.read(storageKey))!.equals(bytes)).toBe(true);
  });

  it('não altera a linha de social_checkin_media: storage_key e demais colunas continuam iguais', async () => {
    const bytes = Buffer.from('sem escrita no PostgreSQL');
    const { id, storageKey } = await seedRow(bytes);
    await source.write(storageKey, bytes);

    await migrateSocialMedia(repository, source, destination);

    const row = await repository.findById(id);
    expect(row?.storageKey).toBe(storageKey);
    expect(row?.status).toBe('PENDING');
  });

  it('várias linhas, um lote só: cada uma converge pro seu próprio desfecho', async () => {
    const migrated = await seedRow(Buffer.from('vai migrar'));
    await source.write(migrated.storageKey, Buffer.from('vai migrar'));

    const missing = await seedRow(Buffer.from('nunca existiu'));

    const report = await migrateSocialMedia(repository, source, destination);
    expect(report).toEqual({ migrated: 1, converged: 0, failed: 1 });
    expect(await destination.exists(migrated.storageKey)).toBe(true);
    expect(await destination.exists(missing.storageKey)).toBe(false);
  });
});
