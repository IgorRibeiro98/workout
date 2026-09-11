import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { AppConfig } from '../src/config/app-config';
import { SparkLogger } from '../src/common/logger';
import { LocalObjectStorageClient } from '../src/object-storage/local-object-storage.client';
import {
  createObjectStorageClient,
  ObjectStorageConfigurationError,
} from '../src/object-storage/object-storage.factory';

const BACKEND_ROOT = join(__dirname, '..');
const SRC = join(BACKEND_ROOT, 'src');

const DATABASE_URL = 'postgresql://spark:spark@localhost:5432/spark_dev';

/**
 * T18.1 — as invariantes que não podem depender de code review.
 *
 * Cada `it` aqui protege uma decisão que quebra em silêncio: um `getSignedUrl` "só para o app
 * carregar mais rápido", um `credentials:` "só para testar", um bucket "por enquanto" no código,
 * um `payload` de volta no `INSERT`. Nenhum deles falharia um teste de comportamento.
 */
describe('T18.1 — invariantes estruturais do Object Storage', () => {
  const sources = collectSources(SRC);
  const read = (file: string) => readFileSync(file, 'utf8');
  const relative = (file: string) => file.slice(BACKEND_ROOT.length);

  // ================================================================ configuração

  describe('configuração (§3/§4/§5)', () => {
    const base = { DATABASE_URL };

    it('o provider default é `local`, e ele não exige bucket, credencial nem GCP', () => {
      const config = AppConfig.fromEnv(base);
      expect(config.objectStorageProvider).toBe('local');
      expect(config.gcsBucketName).toBeUndefined();
      expect(config.missingRequirements()).toEqual([]);
    });

    it('`gcs` sem GCS_BUCKET_NAME é configuração inconsistente: falha no startup', () => {
      const config = AppConfig.fromEnv({ ...base, OBJECT_STORAGE_PROVIDER: 'gcs' });
      expect(config.missingRequirements().join()).toContain('GCS_BUCKET_NAME');

      // Vazio é ausente — é o que o Compose injeta para uma variável não definida.
      const empty = AppConfig.fromEnv({
        ...base,
        OBJECT_STORAGE_PROVIDER: 'gcs',
        GCS_BUCKET_NAME: '   ',
      });
      expect(empty.gcsBucketName).toBeUndefined();
      expect(empty.missingRequirements().join()).toContain('GCS_BUCKET_NAME');
    });

    it('`gcs` com bucket é aceito, e o bucket nunca vem de um default no código', () => {
      const config = AppConfig.fromEnv({
        ...base,
        OBJECT_STORAGE_PROVIDER: 'gcs',
        GCS_BUCKET_NAME: 'spark-private-assets-prod',
      });
      expect(config.gcsBucketName).toBe('spark-private-assets-prod');
      expect(config.missingRequirements()).toEqual([]);

      for (const file of sources) {
        expect(read(file)).not.toContain('spark-private-assets-prod');
      }
    });

    it('um provider desconhecido ou um nome de bucket inválido derrubam o startup', () => {
      expect(() => AppConfig.fromEnv({ ...base, OBJECT_STORAGE_PROVIDER: 's3' })).toThrow();
      expect(() =>
        AppConfig.fromEnv({
          ...base,
          OBJECT_STORAGE_PROVIDER: 'gcs',
          GCS_BUCKET_NAME: 'Bucket Com Espaço',
        }),
      ).toThrow();
      expect(() => AppConfig.fromEnv({ ...base, OBJECT_STORAGE_TIMEOUT_MS: '100' })).toThrow();
    });

    it('produção com `gcs` não exige SOCIAL_MEDIA_ROOT; com `local` continua exigindo (T17.9 §28)', () => {
      const production = {
        ...base,
        NODE_ENV: 'production',
        ACCOUNT_DELETION_HMAC_KEY: 'chave-de-producao-de-teste-com-tamanho-suficiente',
      };
      const gcs = AppConfig.fromEnv({
        ...production,
        OBJECT_STORAGE_PROVIDER: 'gcs',
        GCS_BUCKET_NAME: 'spark-private-assets-prod',
      });
      expect(gcs.missingRequirements()).toEqual([]);

      const local = AppConfig.fromEnv({ ...production, OBJECT_STORAGE_PROVIDER: 'local' });
      expect(local.missingRequirements().join()).toContain('SOCIAL_MEDIA_ROOT');
    });

    it('a factory é a segunda barreira: `gcs` sem bucket não monta cliente nenhum', async () => {
      const config = AppConfig.fromEnv({ ...base, OBJECT_STORAGE_PROVIDER: 'gcs' });
      await expect(
        createObjectStorageClient(config, new SparkLogger(AppConfig.fromEnv(base))),
      ).rejects.toThrow(ObjectStorageConfigurationError);
    });

    it('com `local`, a factory entrega o provider local — sem tocar no SDK do Google', async () => {
      const config = AppConfig.fromEnv({ ...base, SOCIAL_MEDIA_ROOT: '/tmp/spark-os-structure' });
      const client = await createObjectStorageClient(config, new SparkLogger(config));
      expect(client).toBeInstanceOf(LocalObjectStorageClient);
      expect(client.provider).toBe('local');
    });

    it('nenhuma credencial GCS existe como variável da aplicação (§3/§48)', () => {
      const schema = stripComments(read(join(SRC, 'config', 'env.schema.ts')));
      for (const forbidden of [
        'GCS_PRIVATE_KEY',
        'GCS_CLIENT_EMAIL',
        'GCS_SERVICE_ACCOUNT_JSON',
        'GCS_CREDENTIALS',
        'GCS_KEY_FILE',
      ]) {
        expect(schema).not.toContain(forbidden);
      }
    });
  });

  // ================================================================ o SDK e a autenticação

  describe('SDK, ADC e privacidade (§2/§3/§11)', () => {
    it('só o cliente GCS importa @google-cloud/storage', () => {
      const importers = sources.filter((file) => /from '@google-cloud\/storage'/.test(read(file)));
      expect(importers.map(relative)).toEqual(['/src/object-storage/gcs-object-storage.client.ts']);
    });

    it('o cliente GCS é construído por ADC: nenhuma credencial explícita, nenhum keyFilename', () => {
      const code = stripComments(read(join(SRC, 'object-storage', 'gcs-object-storage.client.ts')));
      expect(code).toContain('new Storage(');
      for (const forbidden of [
        'credentials:',
        'keyFilename',
        'keyFile:',
        'private_key',
        'client_email',
      ]) {
        expect(code).not.toContain(forbidden);
      }
      // E em lugar nenhum do backend uma credencial GCP é montada à mão.
      const builders = sources.filter((file) =>
        /keyFilename|credentials:\s*\{/.test(stripComments(read(file))),
      );
      expect(builders.map(relative)).toEqual([]);
    });

    it('nenhum arquivo do backend sabe tornar um objeto público ou assinar URL', () => {
      // Sobre o **código**, e não sobre os comentários: o cliente GCS explica, em prosa, por que
      // essas chamadas não existem — e um teste que proibisse a palavra proibiria a explicação.
      const violations: string[] = [];
      for (const file of sources) {
        const code = stripComments(read(file));
        for (const forbidden of [
          'getSignedUrl',
          'makePublic',
          'makePrivate',
          'predefinedAcl',
          'allUsers',
          'allAuthenticatedUsers',
          'publicRead',
          '.acl.',
          'signedUrl',
        ]) {
          if (code.includes(forbidden)) {
            violations.push(`${relative(file)}: ${forbidden}`);
          }
        }
      }
      expect(violations).toEqual([]);
    });

    it('a escrita no GCS é create-only e verificada pelo SDK', () => {
      const source = read(join(SRC, 'object-storage', 'gcs-object-storage.client.ts'));
      expect(source).toContain('ifGenerationMatch: 0');
      expect(source).toContain("validation: 'crc32c'");
      expect(source).toContain('autoPaginate: false');
    });

    it('o SDK do Google só entra no grafo com o provider `gcs`: o import é dinâmico, dentro do ramo', () => {
      const factory = read(join(SRC, 'object-storage', 'object-storage.factory.ts'));
      expect(factory).not.toMatch(/^import .* from '\.\/gcs-object-storage\.client';/m);
      expect(factory).toContain("await import('./gcs-object-storage.client')");
    });
  });

  // ================================================================ composição

  describe('um único ponto de composição (§39)', () => {
    it('só a factory instancia os providers; CLIs e módulos passam por ela', () => {
      const instantiators = sources.filter((file) =>
        /new (LocalObjectStorageClient|GcsObjectStorageClient)\(/.test(read(file)),
      );
      // `migrate-social-media-to-object-storage.ts` é a exceção deliberada (T18.1.1): a migração
      // **é**, por definição, a ponte entre o disco legado e o bucket configurado, então ela
      // precisa construir a origem `local` explicitamente — a origem nunca é "o que a factory
      // escolher", só o destino é. Nenhum outro arquivo de domínio ganha essa liberdade.
      expect(instantiators.map(relative).sort()).toEqual([
        '/src/cli/migrate-social-media-to-object-storage.ts',
        '/src/object-storage/object-storage.factory.ts',
      ]);

      // Os comandos operacionais usam a factory (para o destino, quando aplicável) — nunca um
      // provider escolhido à mão para onde os bytes finais moram, e nunca um `if` sobre a variável
      // de ambiente.
      for (const cli of [
        'reconcile-account-deletions.ts',
        'migrate-backup-payloads-to-object-storage.ts',
        'migrate-social-media-to-object-storage.ts',
        'object-storage-smoke.ts',
        // T18.3 — os comandos de DR e o auditor passam pela mesma factory: o backup de DR vai
        // para o MESMO provider que guarda foto, backup pessoal e ledger, nunca para um escolhido
        // à mão.
        'db-backup.ts',
        'db-restore-drill.ts',
        'storage-audit.ts',
      ]) {
        const source = stripComments(read(join(SRC, 'cli', cli)));
        expect(source).toContain('createObjectStorageClient(');
        expect(source).not.toContain('LocalSocialMediaStore');
        expect(source).not.toContain('OBJECT_STORAGE_PROVIDER ===');
      }
    });

    it('nenhum arquivo de domínio decide entre `local` e `gcs`', () => {
      const deciders = sources
        .filter((file) => !file.includes('/object-storage/') && !file.includes('/config/'))
        // `migrate-social-media-to-object-storage.ts` é a mesma exceção deliberada de acima: ele
        // lê `objectStorageProvider` só para **recusar rodar** sem `gcs` configurado (uma
        // pré-condição da migração), nunca para escolher entre construir um cliente `local` ou
        // `gcs` — a origem é sempre `local`, construída à mão; o destino sempre vem da factory.
        //
        // `deletion-tombstone-ledger.factory.ts` e `migrate-deletion-ledger-to-object-storage.ts`
        // são a exceção deliberada da T18.2 §26: o ledger anti-ressurreição escolhe entre disco e
        // Object Storage pela **mesma** `OBJECT_STORAGE_PROVIDER` — nunca constroem
        // `LocalObjectStorageClient`/`GcsObjectStorageClient` por conta própria, e o CLI de
        // migração do ledger só lê a variável para recusar rodar fora de `gcs`, no mesmo espírito
        // do CLI de mídia acima.
        .filter(
          (file) =>
            !file.endsWith('/cli/migrate-social-media-to-object-storage.ts') &&
            !file.endsWith('/modules/account-deletion/deletion-tombstone-ledger.factory.ts') &&
            !file.endsWith('/cli/migrate-deletion-ledger-to-object-storage.ts'),
        )
        .filter((file) =>
          /objectStorageProvider|OBJECT_STORAGE_PROVIDER/.test(stripComments(read(file))),
        );
      expect(deciders.map(relative)).toEqual([]);
    });
  });

  // ================================================================ fronteiras de domínio

  describe('Social e Backup continuam sem se conhecer (§7)', () => {
    it('nenhum arquivo de modules/social importa de modules/backup, e vice-versa', () => {
      const social = collectSources(join(SRC, 'modules', 'social')).filter((file) =>
        /from '\.\.\/backup\//.test(read(file)),
      );
      const backup = collectSources(join(SRC, 'modules', 'backup')).filter((file) =>
        /from '\.\.\/social\//.test(read(file)),
      );
      expect(social.map(relative)).toEqual([]);
      expect(backup.map(relative)).toEqual([]);
    });

    it('SocialModule não importa BackupModule, SyncModule nem AiModule', () => {
      const source = read(join(SRC, 'modules', 'social', 'social.module.ts'));
      for (const forbidden of ['BackupModule', 'SyncModule', 'AiModule']) {
        expect(source.includes(`imports: [${forbidden}`)).toBe(false);
        expect(source).not.toMatch(new RegExp(`import .*${forbidden}.* from`));
      }
    });

    it('os dois stores se apoiam na mesma camada neutra, e só nela', () => {
      const media = read(join(SRC, 'modules', 'social', 'social-media.store.ts'));
      const backup = read(join(SRC, 'modules', 'backup', 'backup-payload.store.ts'));
      expect(media).toContain("from '../../object-storage/object-storage.client'");
      expect(backup).toContain("from '../../object-storage/object-storage.client'");
      for (const source of [media, backup]) {
        expect(source).not.toContain('@google-cloud/storage');
        expect(source).not.toContain('node:fs');
      }
    });
  });

  // ================================================================ o banco não carrega o documento

  describe('o PostgreSQL não recebe o documento de um backup novo (§18/§19)', () => {
    it('o INSERT de backup_snapshots não escreve `payload`, e o de backup_items também não', () => {
      const source = read(join(SRC, 'modules', 'backup', 'backup.repository.ts'));
      const inserts = source.match(/INSERT INTO backup_(snapshots|items)[\s\S]*?VALUES/g) ?? [];
      expect(inserts).toHaveLength(2);
      for (const insert of inserts) {
        // `payload_hash` é metadata e pode; a coluna `payload` não.
        expect(insert.replace(/payload_hash/g, '')).not.toMatch(/\bpayload\b/);
      }
      expect(source).not.toContain('snapshot.canonicalText');
      expect(source).not.toContain('item.canonicalPayload');
    });

    it('a migration 0001 não foi editada; a 0002 acrescenta storage_key e libera backup_items.payload', () => {
      const migrations = join(BACKEND_ROOT, 'migrations', 'postgres');
      const files = readdirSync(migrations).sort();
      expect(files[0]).toBe('0001_t17_13_baseline.sql');
      expect(files).toContain('0002_object_storage.sql');

      const baseline = read(join(migrations, '0001_t17_13_baseline.sql'));
      expect(baseline).not.toContain('storage_key TEXT');
      expect(baseline).toMatch(/backup_items \([\s\S]*?payload\s+TEXT\s+NOT NULL/);

      const objectStorage = read(join(migrations, '0002_object_storage.sql'));
      expect(objectStorage).toContain('ADD COLUMN storage_key TEXT');
      expect(objectStorage).toMatch(
        /CREATE UNIQUE INDEX[\s\S]*storage_key[\s\S]*WHERE storage_key IS NOT NULL/,
      );
      expect(objectStorage).toContain('ALTER COLUMN payload DROP NOT NULL');
      // Nada destrutivo: nenhum dado existente é apagado por SQL.
      expect(objectStorage).not.toMatch(/\bDROP (TABLE|COLUMN)\b/);
      expect(objectStorage).not.toMatch(/\bDELETE FROM\b/);
      expect(objectStorage).not.toMatch(/\bUPDATE backup_/);
    });
  });
});

/** O código sem comentários de bloco e de linha — URLs em strings (`://`) sobrevivem. */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|\s)\/\/.*$/gm, '$1');
}

function collectSources(root: string): string[] {
  const found: string[] = [];
  const walk = (directory: string): void => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        walk(path);
      } else if (entry.name.endsWith('.ts')) {
        found.push(path);
      }
    }
  };
  walk(root);
  return found.sort();
}
