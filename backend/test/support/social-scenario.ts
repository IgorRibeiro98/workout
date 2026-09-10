import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import type { INestApplication } from '@nestjs/common';
import BetterSqlite3 from 'better-sqlite3';
import request from 'supertest';
import { configFor, createPostgresSyncDb, createTempDb, type TempDb } from './temp-db';
import { createTestApp } from './create-test-app';
import { FakeAuthTokenVerifier } from './fake-auth-token-verifier';
import { FakeClock } from './fake-clock';
import { pushBody, sessionPayload, uuid } from './sync-fixtures';

/**
 * O cenário A/B/C da auditoria final (T17.10 §12/§143).
 *
 * ## Por que ele existe como suporte, e não dentro de um `describe`
 *
 * Porque a T17.10 pede o **mesmo** trio em cenários que não cabem no mesmo arquivo: a matriz de
 * acesso, a exclusão de conta, o DR e o bloqueio de terceiro. Reescrever "ativa A, ativa B,
 * faz amizade, sincroniza uma sessão, publica um check-in" quatro vezes é o desenho em que uma
 * das quatro cópias diverge — e é sempre a que testa a coisa mais nova.
 *
 * ## Tudo entra pelo caminho real
 *
 * Nenhum `INSERT` de fixture. As sessões sobem por `POST /v1/sync/push`, a amizade por pedido e
 * aceite, o check-in pela rota de publicação. Semear as tabelas à mão provaria as afirmações
 * contra a fixture em vez de contra o protocolo — e é exatamente o protocolo que esta tarefa
 * precisa auditar.
 */

export const AUDIT_NOW = Date.parse('2026-09-09T12:00:00Z');

export interface AuditAccount {
  readonly token: string;
  readonly uid: string;
  readonly email: string;
  readonly name: string;
}

export const ACCOUNT_A: AuditAccount = {
  token: 'audit-token-a',
  uid: 'audit-uid-a',
  email: 'a@example.com',
  name: 'Alice',
};
export const ACCOUNT_B: AuditAccount = {
  token: 'audit-token-b',
  uid: 'audit-uid-b',
  email: 'b@example.com',
  name: 'Bruno',
};
export const ACCOUNT_C: AuditAccount = {
  token: 'audit-token-c',
  uid: 'audit-uid-c',
  email: 'c@example.com',
  name: 'Carla',
};

export const AUDIT_ACCOUNTS = [ACCOUNT_A, ACCOUNT_B, ACCOUNT_C] as const;

export interface SocialScenario {
  readonly app: INestApplication;
  readonly clock: FakeClock;
  readonly temp: TempDb;
  readonly mediaRoot: string;
  readonly tombstonesFile: string;
  /**
   * O verificador de token, exposto para que um teste possa registrar contas **além** de A/B/C.
   *
   * A T17.11 precisa disso: um Squad tem 20 vagas (§11), e provar que o vigésimo primeiro é
   * recusado exige vinte e uma pessoas. Registrar contas sob demanda é melhor do que declarar
   * vinte constantes que só um arquivo usa.
   */
  readonly verifier: FakeAuthTokenVerifier;

  server(): ReturnType<INestApplication['getHttpServer']>;
  auth(token: string): string;

  /** Uma leitura direta do arquivo SQLite, fora da conexão do app. */
  inDatabase<T>(read: (db: BetterSqlite3.Database) => T): T;

  /** Todos os arquivos presentes na raiz de mídia, caminho absoluto. */
  filesOnDisk(): string[];

  activate(account: AuditAccount): Promise<string>;
  makeFriends(one: AuditAccount, two: AuditAccount): Promise<void>;
  socialIdOf(account: AuditAccount): Promise<string>;
  pushSession(account: AuditAccount, at?: number): Promise<string>;
  publishCheckIn(
    account: AuditAccount,
    sessionSyncId: string,
    extra?: Record<string, unknown>,
  ): Promise<string>;
  uploadPhoto(account: AuditAccount, sessionSyncId: string, bytes: Buffer): Promise<string>;

  // ---------------------------------------------------------------- T17.11 (Squads)

  /** Registra e ativa uma conta extra, para cenários que precisam de mais que A/B/C. */
  extraAccount(index: number): AuditAccount;

  /** Cria um Squad e devolve o `groupId`. */
  createGroup(account: AuditAccount, name?: string): Promise<string>;

  /** Convida `recipient` para `groupId` e devolve o `invitationId`. */
  invite(owner: AuditAccount, groupId: string, recipient: AuditAccount): Promise<string>;

  /** O caminho inteiro: amizade, convite e aceite. Devolve o `groupId`. */
  addMember(owner: AuditAccount, groupId: string, member: AuditAccount): Promise<void>;

  close(): Promise<void>;
}

export interface ScenarioOptions {
  readonly now?: number;
  readonly env?: Record<string, string>;
}

export async function createSocialScenario(options: ScenarioOptions = {}): Promise<SocialScenario> {
  const temp = createTempDb();
  const mediaRoot = join(temp.directory, 'media');
  const tombstonesFile = join(temp.directory, 'deletion_tombstones.tsv');
  const clock = new FakeClock(options.now ?? AUDIT_NOW);

  const verifier = new FakeAuthTokenVerifier();
  for (const account of AUDIT_ACCOUNTS) {
    verifier.accept(account.token, { uid: account.uid, email: account.email });
  }

  const app = await createTestApp(
    configFor(temp.path, {
      SOCIAL_MEDIA_ROOT: mediaRoot,
      DELETION_TOMBSTONES_FILE_PATH: tombstonesFile,
      ACCOUNT_DELETION_HMAC_KEY: 'chave-hmac-de-teste-para-exclusao-de-conta',
      ...(options.env ?? {}),
    }),
    verifier,
    undefined,
    clock,
  );

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  const scenario: SocialScenario = {
    app,
    clock,
    temp,
    mediaRoot,
    tombstonesFile,
    verifier,
    server,
    auth,

    inDatabase<T>(read: (db: any) => T): T {
      const db = createPostgresSyncDb(temp.schema);
      try {
        return read(db);
      } finally {
        db.close();
      }
    },

    filesOnDisk(): string[] {
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
    },

    async activate(account: AuditAccount): Promise<string> {
      const res = await request(server())
        .post('/v1/social/me/activate')
        .set('Authorization', auth(account.token))
        .send({ displayName: account.name })
        .expect(200);
      return res.body.profile.socialId as string;
    },

    async socialIdOf(account: AuditAccount): Promise<string> {
      const res = await request(server())
        .get('/v1/social/me')
        .set('Authorization', auth(account.token))
        .expect(200);
      return res.body.profile.socialId as string;
    },

    async makeFriends(one: AuditAccount, two: AuditAccount): Promise<void> {
      const targetSocialId = await scenario.socialIdOf(two);
      const sent = await request(server())
        .post('/v1/social/friend-requests')
        .set('Authorization', auth(one.token))
        .send({ socialId: targetSocialId })
        .expect(200);
      await request(server())
        .post(`/v1/social/friend-requests/${sent.body.request.requestId}/accept`)
        .set('Authorization', auth(two.token))
        .expect(200);
    },

    async pushSession(account: AuditAccount, at = clock.now()): Promise<string> {
      const syncId = uuid();
      const res = await request(server())
        .post('/v1/sync/push')
        .set('Authorization', auth(account.token))
        .set('Content-Type', 'application/json')
        .send(
          pushBody([
            {
              entityType: 'WORKOUT_SESSION',
              entitySyncId: syncId,
              payload: sessionPayload(syncId, {
                startedAt: at - 60 * 60 * 1000,
                finishedAt: at - 30 * 60 * 1000,
              }),
            },
          ]),
        )
        .expect(200);
      expect(res.body.results[0].status).toBe('APPLIED');
      return syncId;
    },

    async publishCheckIn(
      account: AuditAccount,
      sessionSyncId: string,
      extra: Record<string, unknown> = {},
    ): Promise<string> {
      const res = await request(server())
        .post('/v1/social/workout-checkins')
        .set('Authorization', auth(account.token))
        .send({ sessionSyncId, clientRequestId: uuid(), ...extra })
        .expect(201);
      return res.body.checkInId as string;
    },

    async uploadPhoto(
      account: AuditAccount,
      sessionSyncId: string,
      bytes: Buffer,
    ): Promise<string> {
      const res = await request(server())
        .post('/v1/social/checkin-media')
        .query({ sessionSyncId, clientUploadId: uuid() })
        .set('Authorization', auth(account.token))
        .set('Content-Type', 'image/jpeg')
        .send(bytes)
        .expect(201);
      return res.body.mediaId as string;
    },

    // ---------------------------------------------------------------- T17.11 (Squads)

    extraAccount(index: number): AuditAccount {
      const account: AuditAccount = {
        token: `audit-token-extra-${index}`,
        uid: `audit-uid-extra-${index}`,
        email: `extra${index}@example.com`,
        name: `Extra ${index}`,
      };
      verifier.accept(account.token, { uid: account.uid, email: account.email });
      return account;
    },

    async createGroup(account: AuditAccount, name = 'Os Monstros'): Promise<string> {
      const res = await request(server())
        .post('/v1/social/groups')
        .set('Authorization', auth(account.token))
        .send({ name, clientRequestId: uuid() })
        .expect(201);
      return res.body.groupId as string;
    },

    async invite(owner: AuditAccount, groupId: string, recipient: AuditAccount): Promise<string> {
      const socialId = await scenario.socialIdOf(recipient);
      const res = await request(server())
        .post(`/v1/social/groups/${groupId}/invitations`)
        .set('Authorization', auth(owner.token))
        .send({ socialId, clientRequestId: uuid() })
        .expect(201);
      return res.body.invitationId as string;
    },

    async addMember(owner: AuditAccount, groupId: string, member: AuditAccount): Promise<void> {
      const invitationId = await scenario.invite(owner, groupId, member);
      await request(server())
        .post(`/v1/social/group-invitations/${invitationId}/accept`)
        .set('Authorization', auth(member.token))
        .expect(200);
    },

    async close(): Promise<void> {
      await app?.close();
      temp.cleanup();
    },
  };

  return scenario;
}
