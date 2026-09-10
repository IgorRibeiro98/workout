import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { INestApplication } from '@nestjs/common';
import request from 'supertest';
import { configFor, createTempDb, type TempDb } from './support/temp-db';
import { createTestApp } from './support/create-test-app';
import { FakeAuthTokenVerifier } from './support/fake-auth-token-verifier';

const TOKEN = 'token-secreto-da-conta-social-xyz789';
const UID = 'uid-completo-da-conta-social-1234';
const EMAIL = 'atleta.social@example.com';
const DISPLAY_NAME = 'Igor Ribeiro Sobrenome';

const SOCIAL_SRC = join(__dirname, '..', 'src', 'modules', 'social');

/**
 * O que o domínio social registra — e o que ele nunca registra.
 *
 * O log precisa responder "qual conta (por prefixo), qual operação, como terminou". Ele não pode
 * responder "quem é essa pessoa": nem o nome social, nem o código de amigo, nem o `socialId`, nem
 * o e-mail, nem o Firebase UID inteiro.
 *
 * O `friendCode` merece a mesma proteção que o resto mesmo não sendo credencial: ele é o dado que
 * uma pessoa compartilha para ser encontrada, e um log que o carrega transforma qualquer cópia de
 * log em uma lista de convites válidos.
 */
describe('Observabilidade do social: metadata sim, identidade não', () => {
  describe('emissão de logs em rotas HTTP', () => {
    let temp: TempDb;
    let app: INestApplication;
    let written: string[];
    let restore: () => void;

    beforeEach(async () => {
      temp = createTempDb();
      written = [];
      const original = process.stdout.write.bind(process.stdout);
      process.stdout.write = ((chunk: string | Uint8Array, ...rest: unknown[]): boolean => {
        written.push(typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString('utf8'));
        return original(chunk as never, ...(rest as []));
      }) as typeof process.stdout.write;
      restore = () => {
        process.stdout.write = original;
      };

      app = await createTestApp(
        configFor(temp.path, { LOG_LEVEL: 'debug' }),
        FakeAuthTokenVerifier.withPrincipal(TOKEN, { uid: UID, email: EMAIL }),
      );
    });

    afterEach(async () => {
      restore();
      await app?.close();
      app = undefined as unknown as INestApplication;
      temp.cleanup();
    });

    const logs = () => written.join('\n');

    const exerciseEveryRoute = async (): Promise<{ friendCode: string; socialId: string }> => {
      const server = app.getHttpServer();
      const auth = `Bearer ${TOKEN}`;

      await request(server).get('/v1/social/me').set('Authorization', auth);
      const created = (
        await request(server)
          .post('/v1/social/me/activate')
          .set('Authorization', auth)
          .send({ displayName: DISPLAY_NAME })
      ).body.profile;
      await request(server)
        .post('/v1/social/me/activate')
        .set('Authorization', auth)
        .send({ displayName: DISPLAY_NAME });
      await request(server)
        .patch('/v1/social/me')
        .set('Authorization', auth)
        .send({ displayName: DISPLAY_NAME });
      await request(server)
        .patch('/v1/social/me/privacy')
        .set('Authorization', auth)
        .send({ activitySharingEnabled: true, activityTimeZoneId: 'America/Sao_Paulo' });
      await request(server).post('/v1/social/me/disable').set('Authorization', auth);
      await request(server).post('/v1/social/me/enable').set('Authorization', auth);
      // Erros também passam pelo log — e também não podem carregar conteúdo.
      await request(server)
        .patch('/v1/social/me')
        .set('Authorization', auth)
        .send({ displayName: `${DISPLAY_NAME}\nAdmin` });

      return { friendCode: created.friendCode, socialId: created.socialId };
    };

    it('o log não contém token, uid completo, e-mail, nome social, friendCode nem socialId', async () => {
      const { friendCode, socialId } = await exerciseEveryRoute();
      const output = logs();

      expect(output).not.toContain(TOKEN);
      expect(output).not.toContain(UID);
      expect(output).not.toContain(EMAIL);
      expect(output).not.toContain(DISPLAY_NAME);
      expect(output).not.toContain(friendCode);
      // Nem o código sem o prefixo: um log com o miolo do código serve para o mesmo abuso.
      expect(output).not.toContain(friendCode.replace('SPK-', ''));
      expect(output).not.toContain(socialId);
      expect(output).not.toContain('Bearer ');
    });

    it('o log registra a metadata que serve para investigar', async () => {
      await exerciseEveryRoute();
      const output = logs();

      // Prefixo do uid: correlaciona com suporte sem identificar a conta inteira.
      expect(output).toContain(UID.slice(0, 6));
      for (const event of [
        'social.me',
        'social.activated',
        'social.profile.updated',
        'social.privacy.updated',
        'social.disabled',
        'social.enabled',
      ]) {
        expect(output).toContain(event);
      }
    });

    it('o log de privacidade diz quais campos mudaram, não os valores do perfil', async () => {
      const server = app.getHttpServer();
      const auth = `Bearer ${TOKEN}`;
      await request(server)
        .post('/v1/social/me/activate')
        .set('Authorization', auth)
        .send({ displayName: DISPLAY_NAME });
      written.length = 0;

      await request(server).patch('/v1/social/me/privacy').set('Authorization', auth).send({
        friendRequestsEnabled: false,
        activitySharingEnabled: true,
        activityTimeZoneId: 'America/Sao_Paulo',
      });

      const output = logs();
      expect(output).toContain('activitySharingEnabled,activityTimeZoneId,friendRequestsEnabled');
      expect(output).not.toContain(DISPLAY_NAME);
    });
  });

  // ------------------------------------------------------------------ inspeção do código-fonte

  it('nenhum ponto do módulo social passa conteúdo de identidade para o logger', () => {
    const forbidden = [
      // O que nunca pode aparecer como campo estruturado de log.
      /logger\.(info|warn|error)\([^)]*\bdisplayName\b/s,
      /logger\.(info|warn|error)\([^)]*\bfriendCode\b/s,
      /logger\.(info|warn|error)\([^)]*\bsocialId\b/s,
      /logger\.(info|warn|error)\([^)]*\bemail\b/s,
      // `uid` inteiro: só `uidPrefix(...)` é permitido.
      /logger\.(info|warn|error)\([^)]*uid:\s*principal\.uid/s,
      /logger\.(info|warn|error)\([^)]*\bbody\b/s,
    ];

    for (const file of readdirSync(SOCIAL_SRC).filter((name) => name.endsWith('.ts'))) {
      const source = readFileSync(join(SOCIAL_SRC, file), 'utf8');
      for (const pattern of forbidden) {
        expect({ file, matches: pattern.test(source) }).toEqual({ file, matches: false });
      }
    }
  });

  it('o módulo social não alcança backup, sync nem IA', () => {
    for (const file of readdirSync(SOCIAL_SRC).filter((name) => name.endsWith('.ts'))) {
      const source = readFileSync(join(SOCIAL_SRC, file), 'utf8');
      const imports = [...source.matchAll(/^import[^;]*from\s+'([^']+)';/gm)].map(
        (match) => match[1],
      );

      for (const specifier of imports) {
        // O social depende de auth (identidade), do banco e da infraestrutura comum — e de mais
        // nada. Um import daqui para backup/sync/ai seria o caminho por onde dado de treino
        // vazaria para a superfície social sem passar por uma projeção explícita.
        expect({
          file,
          specifier,
          forbidden: /modules\/(backup|sync|ai)\//.test(specifier),
        }).toEqual({ file, specifier, forbidden: false });
      }
    }
  });

  it('o módulo social não menciona tabelas de sync, backup ou tombstone', () => {
    // A exceção declarada da T17.2: `social-progress.source.ts` é o adapter estreito de §9/§11 —
    // o único arquivo do módulo que lê estado sincronizado, e só para responder `COUNT(*)`. O
    // teste abaixo (`AGGREGATE_ONLY`) é o que o mantém honesto: nenhum `SELECT payload`, nenhuma
    // linha de treino materializada. Todo o resto do módulo continua sem conhecer as tabelas.
    // A T17.3 acrescentou o segundo, e pela mesma razão declarada: `challenge-progress.source.ts`
    // é o adapter estreito da pontuação de desafio, e ele é **separado** do de perfil de
    // propósito — a projeção da T17.2 não é autoridade de pontuação. Os dois estão sujeitos ao
    // mesmo teste de `AGGREGATE_ONLY` abaixo.
    const PROGRESS_SOURCES = [
      'social-progress.source.ts',
      'challenge-progress.source.ts',
      'canonical-training.source.ts',
    ];

    for (const file of readdirSync(SOCIAL_SRC).filter((name) => name.endsWith('.ts'))) {
      const source = readFileSync(join(SOCIAL_SRC, file), 'utf8');
      // Fora de comentário: a documentação **precisa** citar o que é proibido para explicar por
      // quê, e proibir a menção em prosa faria a explicação sumir junto com o defeito.
      const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

      for (const table of [
        'sync_entities',
        'sync_changes',
        'sync_mutations',
        'backup_snapshots',
        'backup_items',
        'backup_payloads',
        'ai_usage_daily',
      ]) {
        const allowed = PROGRESS_SOURCES.includes(file) && table === 'sync_entities';
        expect({ file, table, mentioned: code.includes(table) && !allowed }).toEqual({
          file,
          table,
          mentioned: false,
        });
      }
    }
  });

  it('o adapter de progresso só produz agregado — nenhum payload de treino sai dele (T17.2/T17.4.1)', () => {
    // `AGGREGATE_ONLY` (`social.projection.ts`). A regra que substituiu o absoluto da T17.0 não
    // pode viver só em prosa: na T17.4.1 a consulta canônica foi consolidada em CanonicalTrainingSource.
    // O adapter de progresso delega para ela e não materializa payload em JavaScript.
    const source = readFileSync(join(SOCIAL_SRC, 'social-progress.source.ts'), 'utf8');
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

    for (const forbidden of ['JSON.parse', '.payload', 'entity_sync_id', 'server_revision']) {
      expect({ forbidden, present: code.includes(forbidden) }).toEqual({
        forbidden,
        present: false,
      });
    }
  });

  it('o adapter de pontuação de desafio também só produz agregado (T17.3/T17.4.1)', () => {
    // Na T17.4.1, challenge-progress.source.ts também delega para a autoridade canônica centralizada.
    const source = readFileSync(join(SOCIAL_SRC, 'challenge-progress.source.ts'), 'utf8');
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

    for (const forbidden of [
      'JSON.parse',
      '.payload',
      'entity_sync_id',
      'server_revision',
      'INSERT',
      'UPDATE',
      'DELETE',
    ]) {
      expect({ forbidden, present: code.includes(forbidden) }).toEqual({
        forbidden,
        present: false,
      });
    }
  });

  it('a fonte canônica centralizada (CanonicalTrainingSource) só seleciona agregados e metadados mínimos (T17.4.1)', () => {
    const source = readFileSync(join(SOCIAL_SRC, 'canonical-training.source.ts'), 'utf8');
    const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');

    for (const forbidden of [
      'JSON.parse',
      'exercises',
      'loads',
      'reps',
      'notes',
      'INSERT',
      'UPDATE',
      'DELETE',
    ]) {
      expect({ forbidden, present: code.includes(forbidden) }).toEqual({
        forbidden,
        present: false,
      });
    }
  });

  it('a pontuação não é gravada em lugar nenhum do módulo social (T17.3 §82/§134)', () => {
    // O bloqueante "não existe counter client-authoritative", provado estruturalmente: nenhuma
    // tabela do social tem coluna de pontuação, e nenhum arquivo do módulo escreve uma.
    //
    // Comportamento não cobre isto. Um `UPDATE challenge_progress SET score = ?` funcionaria em
    // teste e ainda assim seria a segunda verdade que a T17.3 existe para não criar — e o dia em
    // que ela divergisse seria o dia em que um treino sincronizado tarde deixaria de contar.
    const migration = readFileSync(
      join(__dirname, '..', 'migrations', '0010_social_challenges.sql'),
      'utf8',
    ).replace(/^\s*--.*$/gm, '');

    for (const forbidden of ['challenge_progress', 'score', 'current_score', 'winner', 'rank']) {
      expect({ forbidden, present: migration.includes(forbidden) }).toEqual({
        forbidden,
        present: false,
      });
    }
  });
});
