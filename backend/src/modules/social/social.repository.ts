import { Injectable } from '@nestjs/common';
import { SqliteService } from '../../database/sqlite.service';
import type { SocialDiscoverability, SocialProfileStatus } from './social.contract';

/** O perfil social como ele está gravado. `ownerUid` existe aqui e **nunca** em um DTO. */
export interface StoredSocialProfile {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly friendCode: string;
  readonly displayName: string;
  readonly status: SocialProfileStatus;
  readonly createdAt: number;
  readonly updatedAt: number;
}

export interface StoredSocialPrivacy {
  readonly discoverability: SocialDiscoverability;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly updatedAt: number;
}

/** Perfil e privacidade juntos: as duas linhas nascem na mesma transação e são lidas juntas. */
export interface StoredSocialAccount {
  readonly profile: StoredSocialProfile;
  readonly privacy: StoredSocialPrivacy;
}

export interface CreateSocialAccountInput {
  readonly ownerUid: string;
  readonly socialId: string;
  readonly friendCode: string;
  readonly displayName: string;
  readonly discoverability: SocialDiscoverability;
  readonly friendRequestsEnabled: boolean;
  readonly activitySharingEnabled: boolean;
  readonly now: number;
}

export interface UpdateSocialPrivacyInput {
  readonly discoverability?: SocialDiscoverability;
  readonly friendRequestsEnabled?: boolean;
  readonly activitySharingEnabled?: boolean;
  readonly now: number;
}

/** Um `friendCode` já em uso. Sinaliza colisão para o retry limitado do serviço. */
export class FriendCodeCollisionError extends Error {
  constructor() {
    super('friendCode já existe');
    this.name = 'FriendCodeCollisionError';
  }
}

/**
 * A persistência do domínio social (T17.0).
 *
 * Três garantias vivem aqui, e nenhuma delas é "o código toma cuidado":
 *
 * 1. **isolamento por conta** — `owner_uid` está na cláusula `WHERE` de toda consulta, e não numa
 *    verificação depois da leitura. A consulta que não pode devolver o perfil de outra conta é a
 *    que nunca o carrega. Não existe método que leia por `social_id` sozinho, e não existe método
 *    que liste perfis: enumeração é impossível porque a consulta não foi escrita;
 * 2. **atomicidade da criação** — perfil, privacidade e compartilhamento de progresso (T17.2)
 *    entram na **mesma** transação. Um perfil sem essas linhas seria um perfil cujos defaults
 *    ninguém escolheu, e a primeira leitura teria de inventar um;
 * 3. **unicidade pelo banco** — `social_id` e `friend_code` são `UNIQUE` no schema. A colisão é
 *    detectada pela constraint, e nunca por um `SELECT` anterior ao `INSERT`: entre a consulta e a
 *    escrita cabe outra ativação.
 */
@Injectable()
export class SocialRepository {
  constructor(private readonly sqlite: SqliteService) {}

  /** O perfil **daquela conta**, com a privacidade. `null` quando a conta nunca ativou. */
  find(ownerUid: string): StoredSocialAccount | null {
    const row = this.sqlite.connection
      .prepare(
        `SELECT p.owner_uid, p.social_id, p.friend_code, p.display_name, p.status,
                p.created_at, p.updated_at,
                s.discoverability, s.friend_requests_enabled, s.activity_sharing_enabled,
                s.updated_at AS privacy_updated_at
         FROM social_profiles p
         JOIN social_privacy_settings s ON s.owner_uid = p.owner_uid
         WHERE p.owner_uid = ?`,
      )
      .get(ownerUid) as AccountRow | undefined;

    return row ? toAccount(row) : null;
  }

  /**
   * Cria o perfil, a privacidade e o compartilhamento de progresso, em uma transação.
   *
   * Concorrência: duas ativações simultâneas da **mesma** conta chegam aqui e a segunda esbarra na
   * chave primária `owner_uid`. Em vez de propagar o erro, ela relê e devolve o que a primeira
   * criou — o resultado é um perfil, um `socialId` e um `friendCode`, que é exatamente o que a
   * convergência exige. Devolver erro faria um dos dois aparelhos mostrar falha por uma ativação
   * que **funcionou**.
   *
   * @throws {FriendCodeCollisionError} o código sorteado já pertence a outra conta. Quem trata é
   * o serviço, com um retry limitado: aqui não se sorteia nada, porque uma transação não é lugar
   * de laço de geração.
   */
  create(input: CreateSocialAccountInput): StoredSocialAccount {
    const db = this.sqlite.connection;

    const insert = db.transaction((): StoredSocialAccount | null => {
      const existing = this.find(input.ownerUid);
      if (existing) {
        // Já ativado por outra requisição. Não é erro, e nada é sobrescrito.
        return existing;
      }

      db.prepare(
        `INSERT INTO social_profiles
           (owner_uid, social_id, friend_code, display_name, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)`,
      ).run(
        input.ownerUid,
        input.socialId,
        input.friendCode,
        input.displayName,
        input.now,
        input.now,
      );

      db.prepare(
        `INSERT INTO social_privacy_settings
           (owner_uid, discoverability, friend_requests_enabled, activity_sharing_enabled,
            updated_at)
         VALUES (?, ?, ?, ?, ?)`,
      ).run(
        input.ownerUid,
        input.discoverability,
        input.friendRequestsEnabled ? 1 : 0,
        input.activitySharingEnabled ? 1 : 0,
        input.now,
      );

      // T17.2 — compartilhamento de progresso, tudo desligado, na **mesma** transação.
      //
      // Um perfil sem esta linha seria um perfil cujo default ninguém escolheu, e a primeira
      // leitura teria de inventar um. É a mesma razão pela qual a privacidade nasce junto com a
      // identidade desde a T17.0 — e o motivo de os quatro nascerem `0` é o §14: ativar o Social
      // não pode publicar progresso.
      db.prepare(
        `INSERT INTO social_progress_settings
           (owner_uid, share_level, share_consistency_streak, share_weekly_workout_count,
            share_highlighted_achievements, week_time_zone, updated_at)
         VALUES (?, 0, 0, 0, 0, NULL, ?)`,
      ).run(input.ownerUid, input.now);

      return null;
    });

    let existing: StoredSocialAccount | null;
    try {
      existing = insert();
    } catch (error) {
      if (isUniqueViolation(error, 'social_profiles.friend_code')) {
        throw new FriendCodeCollisionError();
      }
      if (isUniqueViolation(error, 'social_profiles.owner_uid')) {
        // A corrida perdida: outra requisição da mesma conta criou o perfil entre a leitura e a
        // escrita. A resposta certa é o perfil que existe, não um erro.
        const created = this.find(input.ownerUid);
        if (created) {
          return created;
        }
      }
      throw error;
    }

    if (existing) {
      return existing;
    }
    // Relido do banco, e não montado a partir do input: o que a API devolve é o que ficou gravado.
    const created = this.find(input.ownerUid);
    if (!created) {
      throw new Error('perfil social não encontrado imediatamente após a criação');
    }
    return created;
  }

  /** Renomeia. `social_id`, `friend_code` e `owner_uid` não aparecem no `SET`, e é o contrato. */
  updateDisplayName(ownerUid: string, displayName: string, now: number): void {
    this.sqlite.connection
      .prepare(`UPDATE social_profiles SET display_name = ?, updated_at = ? WHERE owner_uid = ?`)
      .run(displayName, now, ownerUid);
  }

  /**
   * Muda o status preservando a identidade.
   *
   * `social_id` e `friend_code` não são tocados: desativar e reativar precisa devolver a **mesma**
   * identidade, senão nenhuma relação futura sobrevive a um toque acidental no interruptor.
   */
  updateStatus(ownerUid: string, status: SocialProfileStatus, now: number): void {
    this.sqlite.connection
      .prepare(`UPDATE social_profiles SET status = ?, updated_at = ? WHERE owner_uid = ?`)
      .run(status, now, ownerUid);
  }

  /** Atualização parcial da privacidade: só o que veio no corpo é escrito. */
  updatePrivacy(ownerUid: string, input: UpdateSocialPrivacyInput): void {
    const assignments: string[] = [];
    const values: unknown[] = [];

    if (input.discoverability !== undefined) {
      assignments.push('discoverability = ?');
      values.push(input.discoverability);
    }
    if (input.friendRequestsEnabled !== undefined) {
      assignments.push('friend_requests_enabled = ?');
      values.push(input.friendRequestsEnabled ? 1 : 0);
    }
    if (input.activitySharingEnabled !== undefined) {
      assignments.push('activity_sharing_enabled = ?');
      values.push(input.activitySharingEnabled ? 1 : 0);
    }
    if (assignments.length === 0) {
      return;
    }

    assignments.push('updated_at = ?');
    values.push(input.now, ownerUid);

    this.sqlite.connection
      .prepare(`UPDATE social_privacy_settings SET ${assignments.join(', ')} WHERE owner_uid = ?`)
      .run(...values);
  }
}

interface AccountRow {
  owner_uid: string;
  social_id: string;
  friend_code: string;
  display_name: string;
  status: string;
  created_at: number;
  updated_at: number;
  discoverability: string;
  friend_requests_enabled: number;
  activity_sharing_enabled: number;
  privacy_updated_at: number;
}

function toAccount(row: AccountRow): StoredSocialAccount {
  return {
    profile: {
      ownerUid: row.owner_uid,
      socialId: row.social_id,
      friendCode: row.friend_code,
      displayName: row.display_name,
      status: row.status as SocialProfileStatus,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    },
    privacy: {
      discoverability: row.discoverability as SocialDiscoverability,
      friendRequestsEnabled: row.friend_requests_enabled === 1,
      activitySharingEnabled: row.activity_sharing_enabled === 1,
      updatedAt: row.privacy_updated_at,
    },
  };
}

/**
 * A violação de `UNIQUE` **daquela** coluna.
 *
 * `better-sqlite3` traz `code = 'SQLITE_CONSTRAINT_PRIMARYKEY' | 'SQLITE_CONSTRAINT_UNIQUE'` e uma
 * mensagem que nomeia a coluna. Sem olhar a coluna, um `friendCode` repetido e uma segunda
 * ativação da mesma conta seriam o mesmo erro — e o retry de geração ficaria sorteando códigos
 * novos para um problema que não é de código.
 */
function isUniqueViolation(error: unknown, column: string): boolean {
  if (typeof error !== 'object' || error === null) {
    return false;
  }
  const code = 'code' in error ? String((error as { code: unknown }).code) : '';
  if (code !== 'SQLITE_CONSTRAINT_UNIQUE' && code !== 'SQLITE_CONSTRAINT_PRIMARYKEY') {
    return false;
  }
  const message = error instanceof Error ? error.message : '';
  return message.includes(column);
}
