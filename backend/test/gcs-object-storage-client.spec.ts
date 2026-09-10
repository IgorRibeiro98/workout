import {
  parseObjectCreatedAt,
  resolvePreconditionConflict,
} from '../src/object-storage/gcs-object-storage.client';
import {
  ObjectAlreadyExistsError,
  ObjectStorageUnavailableError,
} from '../src/object-storage/object-storage.client';

/**
 * T18.1.1 §5/§6/§9 — as duas decisões do cliente GCS extraídas como funções puras, exatamente
 * para que a matriz de falhas de `412` e de `timeCreated` inválido seja testável sem o SDK do
 * Google e sem rede: um `read` fake devolve bytes, `null` ou lança, como o cliente real faria.
 */
describe('T18.1.1 — GcsObjectStorageClient: decisões puras', () => {
  describe('resolvePreconditionConflict — a decisão de um 412 (§5)', () => {
    const BYTES = Buffer.from('conteúdo-original');

    it('read devolve os mesmos bytes → converge, sem lançar (retry seguro da mesma escrita)', async () => {
      await expect(
        resolvePreconditionConflict(() => Promise.resolve(Buffer.from(BYTES)), BYTES),
      ).resolves.toBeUndefined();
    });

    it('read devolve bytes diferentes → ObjectAlreadyExistsError (colisão real)', async () => {
      await expect(
        resolvePreconditionConflict(() => Promise.resolve(Buffer.from('outro-conteúdo')), BYTES),
      ).rejects.toThrow(ObjectAlreadyExistsError);
    });

    it('read devolve null → ObjectAlreadyExistsError (única leitura coerente com um 412)', async () => {
      await expect(resolvePreconditionConflict(() => Promise.resolve(null), BYTES)).rejects.toThrow(
        ObjectAlreadyExistsError,
      );
    });

    it(
      'read lança ObjectStorageUnavailableError (timeout) → propaga tal como está, ' +
        'NUNCA vira ObjectAlreadyExistsError',
      async () => {
        const timeout = new ObjectStorageUnavailableError('read', new Error('ETIMEDOUT'));
        await expect(
          resolvePreconditionConflict(() => Promise.reject(timeout), BYTES),
        ).rejects.toBe(timeout);
      },
    );

    it(
      'read lança ObjectStorageUnavailableError (permissão negada) → propaga tal como está, ' +
        'NUNCA vira ObjectAlreadyExistsError',
      async () => {
        const forbidden = new ObjectStorageUnavailableError(
          'read',
          Object.assign(new Error('Forbidden'), { code: 403 }),
        );
        await expect(
          resolvePreconditionConflict(() => Promise.reject(forbidden), BYTES),
        ).rejects.toBe(forbidden);
      },
    );

    it('qualquer outro erro do read também propaga sem virar colisão', async () => {
      const weird = new Error('erro inesperado');
      await expect(resolvePreconditionConflict(() => Promise.reject(weird), BYTES)).rejects.toBe(
        weird,
      );
    });
  });

  describe('parseObjectCreatedAt — timestamp desconhecido nunca é idade zero (§9)', () => {
    it('uma data ISO válida vira epoch millis', () => {
      expect(parseObjectCreatedAt('2026-01-01T00:00:00.000Z')).toBe(
        Date.parse('2026-01-01T00:00:00.000Z'),
      );
    });

    it('ausente (undefined) vira null, nunca 0', () => {
      expect(parseObjectCreatedAt(undefined)).toBeNull();
    });

    it('string vazia vira null, nunca 0', () => {
      expect(parseObjectCreatedAt('')).toBeNull();
    });

    it('uma data ilegível vira null, nunca 0', () => {
      expect(parseObjectCreatedAt('não-é-uma-data')).toBeNull();
    });
  });
});
