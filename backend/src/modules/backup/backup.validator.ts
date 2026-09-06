import { z } from 'zod';
import {
  BACKUP_ENTITY_TYPES,
  isSupportedBackupSchemaVersion,
  type BackupEntityType,
} from './backup.contract';
import {
  BackupEntityRegistry,
  exerciseRefIdentity,
  identityMismatch,
  type ExerciseRef,
} from './backup-entity.registry';
import { BackupErrors } from './backup.errors';
import { BACKUP_LIMITS, MAX_BACKUP_REQUEST_BODY_BYTES } from './backup.limits';
import { parseCanonical, sha256Hex, type CanonicalNode } from './canonical-json';

/**
 * A validação integral de um snapshot, **antes** de qualquer escrita (T16.4).
 *
 * ```text
 * tamanho → forma canônica → envelope → item a item → identidade → duplicidade → relações → hash
 * ```
 *
 * A ordem importa e o resultado é binário: ou o snapshot inteiro é válido, ou nada é persistido.
 * Não existe caminho que grave 97 itens e recuse 3 — um backup parcial seria pior que nenhum
 * backup, porque parece um.
 */

const L = BACKUP_LIMITS;

const identifier = z.string().trim().min(1).max(L.maxIdLength);

const sourceSchema = z
  .object({
    appVersionName: z.string().max(L.maxLabelLength).nullish(),
    appVersionCode: z.number().int().nullish(),
    databaseVersion: z.number().int().nullish(),
  })
  .strict();

const itemSchema = z
  .object({
    entityType: z.string().max(L.maxLabelLength),
    entitySchemaVersion: z.number().int(),
    syncId: identifier,
    // `unknown` aqui de propósito: quem decide a forma do payload é o registry, por tipo.
    payload: z.unknown(),
  })
  .strict();

const envelopeSchema = z
  .object({
    clientBackupId: identifier,
    backupSchemaVersion: z.number().int(),
    deviceId: identifier,
    capturedAt: z.number().int().nullish(),
    source: sourceSchema.nullish(),
    items: z.array(itemSchema).max(L.maxItems),
  })
  .strict();

export interface ValidatedItem {
  readonly entityType: BackupEntityType;
  readonly entitySchemaVersion: number;
  readonly entitySyncId: string;
  /** O payload como o cliente o escreveu, na forma canônica. Nunca uma reserialização. */
  readonly canonicalPayload: string;
  readonly contentHash: string;
}

export interface ValidatedSnapshot {
  readonly clientBackupId: string;
  readonly backupSchemaVersion: number;
  readonly deviceId: string;
  readonly capturedAt: number | null;
  readonly items: readonly ValidatedItem[];
  /** SHA-256 da forma canônica do corpo inteiro, calculado **aqui**, nunca recebido. */
  readonly payloadHash: string;
  readonly sizeBytes: number;
}

/**
 * Valida o corpo cru e devolve o snapshot pronto para persistir.
 *
 * Recebe **texto**, e não um objeto já parseado, porque a forma canônica preserva os tokens
 * originais — ver `canonical-json.ts`.
 */
export function validateBackupRequest(rawBody: string): ValidatedSnapshot {
  if (Buffer.byteLength(rawBody, 'utf8') > MAX_BACKUP_REQUEST_BODY_BYTES) {
    throw BackupErrors.tooLarge('corpo do backup acima do teto do servidor');
  }

  let document: CanonicalNode;
  try {
    document = parseCanonical(rawBody);
  } catch {
    throw BackupErrors.invalid('corpo não é um documento JSON válido');
  }

  const parsed = envelopeSchema.safeParse(document.value);
  if (!parsed.success) {
    throw BackupErrors.invalid(describe(parsed.error));
  }
  const envelope = parsed.data;

  if (!isSupportedBackupSchemaVersion(envelope.backupSchemaVersion)) {
    throw BackupErrors.unsupportedBackupSchemaVersion(envelope.backupSchemaVersion);
  }

  const itemNodes = document.members?.get('items')?.elements ?? [];
  const items = envelope.items.map((item, index) => validateItem(item, itemNodes[index], index));

  assertNoDuplicates(items);
  assertRelations(envelope.items, items);

  return {
    clientBackupId: envelope.clientBackupId,
    backupSchemaVersion: envelope.backupSchemaVersion,
    deviceId: envelope.deviceId,
    capturedAt: envelope.capturedAt ?? null,
    items,
    payloadHash: sha256Hex(document.text),
    sizeBytes: Buffer.byteLength(document.text, 'utf8'),
  };
}

function validateItem(
  item: z.infer<typeof itemSchema>,
  node: CanonicalNode | undefined,
  index: number,
): ValidatedItem {
  if (!BackupEntityRegistry.has(item.entityType)) {
    // Registry fechado: um tipo inventado não vira linha "para o caso de servir depois".
    throw BackupErrors.invalid(`entityType desconhecido em [${index}]`);
  }
  const definition = BackupEntityRegistry.definitionOf(item.entityType);

  if (!definition.supportedSchemaVersions.includes(item.entitySchemaVersion)) {
    throw BackupErrors.unsupportedEntitySchemaVersion(item.entityType, item.entitySchemaVersion);
  }

  const payload = definition.schema.safeParse(item.payload);
  if (!payload.success) {
    throw BackupErrors.invalid(`payload inválido em [${index}]: ${describe(payload.error)}`);
  }

  const canonicalPayload = node?.members?.get('payload')?.text;
  if (canonicalPayload === undefined) {
    throw BackupErrors.invalid(`payload ausente em [${index}]`);
  }

  const itemBytes = Buffer.byteLength(canonicalPayload, 'utf8');
  if (itemBytes > L.maxItemBytes) {
    throw BackupErrors.tooLarge(`item [${index}] acima do teto de bytes`);
  }

  const mismatch = identityMismatch(definition, item.syncId, payload.data);
  if (mismatch !== null) {
    throw BackupErrors.invalid(`${mismatch} em [${index}]`);
  }

  return {
    entityType: item.entityType,
    entitySchemaVersion: item.entitySchemaVersion,
    entitySyncId: item.syncId,
    canonicalPayload,
    contentHash: sha256Hex(canonicalPayload),
  };
}

/**
 * O mesmo agregado duas vezes no mesmo snapshot.
 *
 * Não é ambiguidade tolerável: o restore teria que escolher uma das duas versões, e nada no
 * snapshot diz qual. O banco também recusa por `PRIMARY KEY`; aqui a recusa acontece antes, com
 * um erro que diz o que houve.
 */
function assertNoDuplicates(items: readonly ValidatedItem[]): void {
  const seen = new Set<string>();
  items.forEach((item, index) => {
    const key = `${item.entityType} ${item.entitySyncId}`;
    if (seen.has(key)) {
      throw BackupErrors.invalid(`item duplicado em [${index}]`);
    }
    seen.add(key);
  });
}

/**
 * As relações internas exigidas — e só elas.
 *
 * A política está em `contracts/backup/v1/README.md` §6 e vem do modelo real: o que é exigido vale
 * por construção no Android, então uma violação indica dado corrompido, não um usuário incomum.
 *
 * O que **não** é exigido está listado lá com o motivo. Em particular, referência de exercício
 * dentro de uma sessão concluída não precisa resolver: a sessão carrega `exerciseNameSnapshot`,
 * que é justamente o que preserva o passado quando o exercício é renomeado ou apagado.
 */
function assertRelations(
  raw: readonly z.infer<typeof itemSchema>[],
  items: readonly ValidatedItem[],
): void {
  const present = new Set(items.map((item) => `${item.entityType} ${item.entitySyncId}`));
  const has = (entityType: BackupEntityType, syncId: string): boolean =>
    present.has(`${entityType} ${syncId}`);

  raw.forEach((item, index) => {
    const payload = item.payload as Record<string, unknown>;

    if (item.entityType === 'WORKOUT_TEMPLATE') {
      const programSyncId = payload.programSyncId;
      if (typeof programSyncId === 'string' && !has('WORKOUT_PROGRAM', programSyncId)) {
        throw BackupErrors.invalid(`programa referenciado ausente do snapshot em [${index}]`);
      }
      const exercises = (payload.exercises ?? []) as Array<{ exercise: ExerciseRef }>;
      for (const entry of exercises) {
        if (entry.exercise.kind === 'CUSTOM' && !has('CUSTOM_EXERCISE', entry.exercise.id)) {
          throw BackupErrors.invalid(
            `exercício personalizado referenciado ausente do snapshot em [${index}]`,
          );
        }
      }
    }

    if (item.entityType === 'EXERCISE_OVERRIDE') {
      const ref = payload.exercise as ExerciseRef;
      if (ref.kind === 'CUSTOM' && !has('CUSTOM_EXERCISE', ref.id)) {
        throw BackupErrors.invalid(
          `exercício personalizado referenciado ausente do snapshot em [${index}]`,
        );
      }
      // Consistência da identidade derivada — barata e evita um item órfão no restore.
      if (exerciseRefIdentity(ref) !== item.syncId) {
        throw BackupErrors.invalid(`identidade derivada inconsistente em [${index}]`);
      }
    }
  });
}

/**
 * Uma razão curta a partir do erro do zod: caminho e código, **sem o valor recebido**.
 *
 * As mensagens prontas do zod incluem o dado que falhou. Aqui o dado é treino, medida e nota do
 * usuário — ele não sai em mensagem de erro, que acaba em tela, em log do cliente e em suporte.
 */
function describe(error: z.ZodError): string {
  const issue = error.issues[0];
  if (!issue) return 'formato inválido';
  const path = issue.path.join('.') || '(raiz)';
  return `${path}: ${issue.code}`;
}

/** Os tipos aceitos, para diagnóstico e teste. */
export const ACCEPTED_ENTITY_TYPES: readonly string[] = BACKUP_ENTITY_TYPES;
