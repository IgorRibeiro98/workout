import { INestApplication } from '@nestjs/common';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

/**
 * O contrato do snapshot de compartilhamento, lido da **mesma** fixture que o Android lê
 * (`contracts/social/v1/workout-share-snapshot.json`).
 *
 * ## Por que este arquivo existe
 *
 * Duas vezes seguidas o mesmo defeito apareceu com cara diferente. Na H1.2, o app não conhecia as
 * faixas de série/repetição/descanso do servidor e o usuário só descobria pela recusa. Na H2.6, o
 * corpo que o app enviava saía **sem** `snapshotVersion` — `kotlinx.serialization` não escreve um
 * campo igual ao default declarado — e o servidor recusava **toda** oferta com `INVALID_SNAPSHOT`.
 *
 * Nos dois casos nada ficava vermelho: os testes do Android afirmavam sobre objetos Kotlin e os
 * daqui montavam o corpo à mão em TypeScript. Ninguém confrontava os dois lados.
 *
 * A fixture é a amarra. `WorkoutShareContractTest` (Android) afirma que o app conhece estes
 * números e lê estas formas; este arquivo afirma que o servidor aplica exatamente os mesmos.
 * Mudar um lado só deixa o teste do outro vermelho.
 */

interface Fixture {
  readonly versions: Record<string, { snapshotVersion: number }>;
  readonly limits: Record<string, number>;
  readonly canonicalExerciseIdPattern: string;
  readonly customExerciseRefPattern: string;
  readonly forbiddenFields: string[];
  readonly fixtures: Record<string, Record<string, unknown>>;
  readonly rejected: { case: string; why: string; snapshot: Record<string, unknown> }[];
}

const FIXTURE: Fixture = JSON.parse(
  readFileSync(
    join(__dirname, '..', '..', 'contracts', 'social', 'v1', 'workout-share-snapshot.json'),
    'utf8',
  ),
) as Fixture;

const ACCOUNTS = {
  A: { token: 'token-a', uid: 'uid-a', email: 'a@example.com', name: 'Alice' },
  B: { token: 'token-b', uid: 'uid-b', email: 'b@example.com', name: 'Bob' },
} as const;

describe('Workout Share: o contrato compartilhado do snapshot (T19.H2)', () => {
  let temp: TempDb;
  let app: INestApplication;
  let socialB: string;
  let requestCounter = 0;

  beforeEach(async () => {
    temp = createTempDb();
    const verifier = new FakeAuthTokenVerifier();
    for (const account of Object.values(ACCOUNTS)) {
      verifier.accept(account.token, { uid: account.uid, email: account.email });
    }
    app = await createTestApp(configFor(temp.path), verifier);

    await setupProfile(ACCOUNTS.A.token, 'Alice');
    socialB = await setupProfile(ACCOUNTS.B.token, 'Bob');
    await establishFriendship(ACCOUNTS.A.token, ACCOUNTS.B.token, socialB);
  });

  afterEach(async () => {
    await app?.close();
    temp.cleanup();
  });

  const server = () => app.getHttpServer();
  const auth = (token: string) => `Bearer ${token}`;

  async function setupProfile(token: string, displayName: string): Promise<string> {
    const res = await request(server())
      .post('/v1/social/me/activate')
      .set('Authorization', auth(token))
      .send({ displayName })
      .expect(200);
    return res.body.profile.socialId as string;
  }

  async function establishFriendship(
    tokenA: string,
    tokenB: string,
    socialIdB: string,
  ): Promise<void> {
    const sendRes = await request(server())
      .post('/v1/social/friend-requests')
      .set('Authorization', auth(tokenA))
      .send({ socialId: socialIdB })
      .expect(200);
    await request(server())
      .post(`/v1/social/friend-requests/${sendRes.body.request.requestId}/accept`)
      .set('Authorization', auth(tokenB))
      .send()
      .expect(200);
  }

  /** Envia um snapshot de treino e devolve a resposta crua, seja ela qual for. */
  function share(snapshot: unknown) {
    requestCounter += 1;
    return request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({ recipientSocialId: socialB, clientRequestId: `req-${requestCounter}`, snapshot });
  }

  /** Idem, para um programa. */
  function shareProgram(programSnapshot: unknown) {
    requestCounter += 1;
    return request(server())
      .post('/v1/social/workout-shares')
      .set('Authorization', auth(ACCOUNTS.A.token))
      .send({
        recipientSocialId: socialB,
        clientRequestId: `req-${requestCounter}`,
        programSnapshot,
      });
  }

  // ------------------------------------------------------------------ as fixtures aceitas

  it('aceita o treino V1 canônico da fixture, e o devolve verbatim', async () => {
    const snapshot = FIXTURE.fixtures.v1TemplateCanonical;
    const res = await share(snapshot).expect(201);

    expect(res.body.shareType).toBe('WORKOUT_TEMPLATE');
    // O snapshot volta como entrou: é ele, e não o que a tela tinha, que constrói a cópia.
    expect(res.body.snapshot).toEqual(snapshot);
  });

  it('aceita um treino vazio em V2 — um treino sem exercícios é um estado, não um erro', async () => {
    const res = await share(FIXTURE.fixtures.v2TemplateEmpty).expect(201);
    expect(res.body.snapshot.exercises).toEqual([]);
    expect(res.body.snapshot.snapshotVersion).toBe(FIXTURE.versions.v2.snapshotVersion);
  });

  it('aceita um treino com exercício CUSTOM em V2', async () => {
    const snapshot = FIXTURE.fixtures.v2TemplateCustom;
    const res = await share(snapshot).expect(201);
    expect(res.body.snapshot).toEqual(snapshot);
  });

  it('aceita um programa cujo CUSTOM é o mesmo em dois treinos', async () => {
    const snapshot = FIXTURE.fixtures.v2ProgramSharedCustom;
    const res = await shareProgram(snapshot).expect(201);

    expect(res.body.shareType).toBe('WORKOUT_PROGRAM');
    // Uma entrada em `customExercises`, duas referências: é o que faz o destinatário criar **uma**
    // cópia do exercício.
    expect(res.body.programSnapshot.customExercises).toHaveLength(1);
    const ref = res.body.programSnapshot.customExercises[0].ref as string;
    expect(
      res.body.programSnapshot.templates.map(
        (t: { exercises: { customExerciseRef?: string }[] }) => t.exercises[0].customExerciseRef,
      ),
    ).toEqual([ref, ref]);
  });

  it('aceita um programa com um treino vazio dentro', async () => {
    const res = await shareProgram(FIXTURE.fixtures.v2ProgramWithEmptyTemplate).expect(201);
    expect(res.body.programSnapshot.templates[1].exercises).toEqual([]);
  });

  // ------------------------------------------------------------------ as fixtures recusadas

  it.each(FIXTURE.rejected.map((entry) => [entry.case, entry] as const))(
    'recusa: %s',
    async (_name, entry) => {
      const res = await share(entry.snapshot).expect(400);
      expect(res.body.error.code).toBe('INVALID_SNAPSHOT');
    },
  );

  // ------------------------------------------------------------------ as faixas, uma a uma

  const base = () => JSON.parse(JSON.stringify(FIXTURE.fixtures.v1TemplateCanonical));

  it('aplica o teto do nome do treino exatamente onde a fixture diz', async () => {
    const max = FIXTURE.limits.nameMaxLength;
    await share({ ...base(), name: 'a'.repeat(max) }).expect(201);
    await share({ ...base(), name: 'a'.repeat(max + 1) }).expect(400);
  });

  it('aplica o teto da sigla exatamente onde a fixture diz', async () => {
    const max = FIXTURE.limits.shortIdentifierMaxLength;
    await share({ ...base(), shortIdentifier: 'a'.repeat(max) }).expect(201);
    await share({ ...base(), shortIdentifier: 'a'.repeat(max + 1) }).expect(400);
  });

  it('aplica a faixa de sortOrder exatamente onde a fixture diz', async () => {
    const max = FIXTURE.limits.maxSortOrder;
    const withSortOrder = (value: number) => {
      const snapshot = base();
      snapshot.exercises[0].sortOrder = value;
      return snapshot;
    };
    await share(withSortOrder(max)).expect(201);
    await share(withSortOrder(max + 1)).expect(400);
    await share(withSortOrder(-1)).expect(400);
  });

  it('aplica as faixas de série, repetição e descanso exatamente onde a fixture diz', async () => {
    const withField = (field: string, value: number) => {
      const snapshot = base();
      snapshot.exercises[0][field] = value;
      return snapshot;
    };
    await share(withField('targetSets', FIXTURE.limits.maxTargetSets)).expect(201);
    await share(withField('targetSets', FIXTURE.limits.maxTargetSets + 1)).expect(400);
    await share(withField('targetSets', FIXTURE.limits.minTargetSets - 1)).expect(400);
    await share(withField('maxReps', FIXTURE.limits.maxReps)).expect(201);
    await share(withField('maxReps', FIXTURE.limits.maxReps + 1)).expect(400);
    await share(withField('minReps', FIXTURE.limits.minReps - 1)).expect(400);
    await share(withField('restDurationSeconds', FIXTURE.limits.maxRestSeconds)).expect(201);
    await share(withField('restDurationSeconds', FIXTURE.limits.maxRestSeconds + 1)).expect(400);
  });

  it('aplica o teto de exercícios exatamente onde a fixture diz', async () => {
    const withCount = (count: number) => {
      const snapshot = base();
      snapshot.exercises = Array.from({ length: count }, (_unused, index) => ({
        ...base().exercises[0],
        sortOrder: index,
      }));
      return snapshot;
    };
    await share(withCount(FIXTURE.limits.maxExercises)).expect(201);
    await share(withCount(FIXTURE.limits.maxExercises + 1)).expect(400);
  });

  it('aplica a forma do canonicalExerciseId da fixture', async () => {
    const pattern = new RegExp(FIXTURE.canonicalExerciseIdPattern);
    expect(pattern.test('supino-reto-barra')).toBe(true);
    const withId = (value: string) => {
      const snapshot = base();
      snapshot.exercises[0].canonicalExerciseId = value;
      return snapshot;
    };
    await share(withId('supino-reto-barra')).expect(201);
    // Um id com espaço não casa com o padrão — e é recusado pelo servidor também.
    expect(pattern.test('supino reto')).toBe(false);
    await share(withId('supino reto')).expect(400);
  });

  it('aplica a forma do customExerciseRef da fixture', async () => {
    const pattern = new RegExp(FIXTURE.customExerciseRefPattern);
    expect(pattern.test('custom-1')).toBe(true);
    expect(pattern.test('550e8400-e29b-41d4-a716-446655440000')).toBe(false);

    const snapshot = JSON.parse(JSON.stringify(FIXTURE.fixtures.v2TemplateCustom));
    snapshot.customExercises[0].ref = '550e8400-e29b-41d4-a716-446655440000';
    snapshot.exercises[1].customExerciseRef = '550e8400-e29b-41d4-a716-446655440000';
    await share(snapshot).expect(400);
  });

  // ------------------------------------------------------------------ privacidade

  it('recusa por nome qualquer campo privado dentro de um CUSTOM', async () => {
    for (const field of FIXTURE.forbiddenFields) {
      const snapshot = JSON.parse(JSON.stringify(FIXTURE.fixtures.v2TemplateCustom));
      snapshot.customExercises[0][field] = 'x';
      const res = await share(snapshot);
      expect([res.status, field]).toEqual([400, field]);
      expect(res.body.error.code).toBe('INVALID_SNAPSHOT');
    }
  });

  it('a oferta devolvida nunca carrega identidade do remetente', async () => {
    const res = await share(FIXTURE.fixtures.v2TemplateCustom).expect(201);
    const text = JSON.stringify(res.body.snapshot);
    for (const field of FIXTURE.forbiddenFields) {
      expect(text).not.toContain(`"${field}"`);
    }
  });

  // ------------------------------------------------------------------ a versão

  it('o defeito da H2.6: um snapshot sem snapshotVersion é recusado, e é por isso que o app o escreve', async () => {
    const snapshot = base();
    delete snapshot.snapshotVersion;
    const res = await share(snapshot).expect(400);
    expect(res.body.error.code).toBe('INVALID_SNAPSHOT');
    expect(res.body.error.message).toContain('Versão do snapshot não suportada');
  });

  it('uma versão que este servidor não conhece é recusada por nome', async () => {
    const res = await share({ ...base(), snapshotVersion: 99 }).expect(400);
    expect(res.body.error.message).toContain('99');
  });
});
