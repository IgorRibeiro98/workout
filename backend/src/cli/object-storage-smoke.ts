import 'reflect-metadata';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { SparkLogger } from '../common/logger';
import { AppConfig, ConfigValidationError } from '../config/app-config';
import {
  ObjectAlreadyExistsError,
  ObjectStorageUnavailableError,
} from '../object-storage/object-storage.client';
import { createObjectStorageClient } from '../object-storage/object-storage.factory';
import { OBJECT_STORAGE_SMOKE_PREFIX } from '../object-storage/object-storage.limits';

/**
 * `object-storage-smoke` — o smoke operacional do Object Storage real (T18.1).
 *
 * ```bash
 * OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=<bucket> node dist/cli/object-storage-smoke.js
 * ```
 *
 * Independente da suíte de testes de propósito: `npm test` nunca toca o GCS, nunca exige ADC,
 * projeto GCP ou internet. Este comando é o que **prova** que o bucket provisionado, a service
 * account e a ADC do ambiente funcionam — e é executado por uma pessoa, com credencial real, em
 * um ambiente real. Sem credencial ele falha, e a resposta certa é `NOT VERIFIED`, nunca gerar
 * uma chave só para ele passar.
 *
 * ```text
 * uuid → write `_smoke/<uuid>.bin` → read → bytes e SHA-256 iguais → exists → colisão recusada
 *      → delete → exists = false
 * ```
 *
 * Só o prefixo `_smoke/`: nunca `social/` nem `backups/`, que carregam dado real. E nunca deixa
 * objeto para trás — a remoção roda num `finally`, inclusive quando um passo falha.
 */
export async function runObjectStorageSmoke(): Promise<number> {
  let config: AppConfig;
  try {
    config = AppConfig.fromEnv();
  } catch (error) {
    if (error instanceof ConfigValidationError) {
      process.stderr.write(`${error.message}\n`);
      return 1;
    }
    throw error;
  }

  const missing = config.missingRequirements();
  if (missing.length > 0) {
    process.stderr.write(
      `Configuração incompleta:\n${missing.map((m) => `  - ${m}`).join('\n')}\n`,
    );
    return 1;
  }

  const logger = new SparkLogger(config);
  const client = await createObjectStorageClient(config, logger);
  const name = `${OBJECT_STORAGE_SMOKE_PREFIX}${randomUUID()}.bin`;
  const bytes = randomBytes(64 * 1024);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const out = (line: string) => process.stdout.write(`${line}\n`);

  out(`smoke: provider=${client.provider} bytes=${bytes.length}`);

  try {
    await client.write(name, bytes, {
      contentType: 'application/octet-stream',
      metadata: { 'spark-sha256': sha256, 'spark-smoke': 'true' },
    });
    out('write: ok');

    const read = await client.read(name);
    if (read === null) {
      throw new Error('read: o objeto recém-gravado não foi encontrado');
    }
    if (!read.equals(bytes) || createHash('sha256').update(read).digest('hex') !== sha256) {
      throw new Error('read: os bytes lidos divergem dos gravados');
    }
    out('read: ok (bytes e sha256 iguais)');

    if (!(await client.exists(name))) {
      throw new Error('exists: false para um objeto que existe');
    }
    out('exists: ok');

    let collided = false;
    try {
      await client.write(name, Buffer.from('outro conteúdo'), {
        contentType: 'application/octet-stream',
      });
    } catch (error) {
      collided = error instanceof ObjectAlreadyExistsError;
    }
    if (!collided) {
      throw new Error('write: a segunda gravação no mesmo nome não foi recusada');
    }
    const afterCollision = await client.read(name);
    if (afterCollision === null || !afterCollision.equals(bytes)) {
      throw new Error('write: a colisão alterou o objeto existente');
    }
    out('collision: ok (objeto existente intacto)');

    await client.remove(name);
    if (await client.exists(name)) {
      throw new Error('delete: o objeto continua existindo');
    }
    out('delete: ok');
    // Idempotente: apagar de novo é sucesso.
    await client.remove(name);
    out('delete (repetido): ok');

    out('SMOKE PASS');
    return 0;
  } catch (error) {
    process.stderr.write(
      `SMOKE FAIL: ${error instanceof Error ? `${error.name}: ${error.message}` : String(error)}\n`,
    );
    // A causa do provider, para o operador que está olhando o terminal — nunca para o log do
    // servidor. "Could not load the default credentials" é a diferença entre "o bucket não
    // existe" e "esta máquina não tem ADC", e é o que decide o próximo passo.
    if (error instanceof ObjectStorageUnavailableError && error.cause instanceof Error) {
      process.stderr.write(`  causa: ${error.cause.name}: ${error.cause.message}\n`);
    }
    return 1;
  } finally {
    // Nunca deixar objeto para trás — nem quando um passo anterior falhou.
    await client.remove(name).catch(() => undefined);
  }
}

if (require.main === module) {
  runObjectStorageSmoke()
    .then((code) => {
      process.exitCode = code;
    })
    .catch((error: unknown) => {
      process.stderr.write(
        `SMOKE FAIL: ${error instanceof Error ? error.message : String(error)}\n`,
      );
      process.exitCode = 1;
    });
}
