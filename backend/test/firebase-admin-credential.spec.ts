import { chmodSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generateKeyPairSync } from 'node:crypto';
import { AppConfig, ConfigValidationError } from '../src/config/app-config';
import {
  FirebaseAdminCredentialError,
  verifyFirebaseAdminCredential,
} from '../src/modules/auth/firebase-auth-token-verifier';

/**
 * A exigência declarada `REQUIRE_FIREBASE_ADMIN=true`, verificada de verdade (T16.8.1 §8).
 *
 * O defeito que esta suíte fecha era silencioso da pior forma: o servidor subia, `/health/ready`
 * respondia `200`, e **toda** requisição autenticada devolvia `503`. `503` significa "tente de
 * novo", então o operador procurava instabilidade de rede por horas — para um problema que nunca
 * ia passar sozinho.
 *
 * Nada aqui toca rede. A credencial sintética é gerada no próprio teste, com uma chave RSA
 * descartável: ela é estruturalmente válida para o Admin SDK e não existe em projeto nenhum.
 */
describe('Verificação da credencial do Firebase Admin no startup', () => {
  let directory: string;

  /** Uma service account sintética: forma real, chave real, projeto que não existe. */
  const syntheticServiceAccount = (overrides: Record<string, unknown> = {}) => {
    const { privateKey } = generateKeyPairSync('rsa', {
      modulusLength: 2048,
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
      publicKeyEncoding: { type: 'spki', format: 'pem' },
    });
    return {
      type: 'service_account',
      project_id: 'ci-only-not-a-real-firebase-project',
      private_key_id: '0'.repeat(40),
      private_key: privateKey,
      client_email: 'ci@ci-only-not-a-real-firebase-project.iam.gserviceaccount.com',
      client_id: '000000000000000000000',
      token_uri: 'https://oauth2.googleapis.com/token',
      ...overrides,
    };
  };

  const write = (name: string, contents: string): string => {
    const path = join(directory, name);
    writeFileSync(path, contents);
    return path;
  };

  beforeEach(() => {
    directory = mkdtempSync(join(tmpdir(), 'spark-firebase-preflight-'));
  });

  afterEach(() => {
    rmSync(directory, { recursive: true, force: true });
  });

  it('aceita uma service account completa e utilizável', async () => {
    const path = write('ok.json', JSON.stringify(syntheticServiceAccount()));

    // Sem `.rejects`: o sucesso é justamente não lançar. E a inicialização real do Admin SDK
    // acontece aqui — não é uma inspeção de campos disfarçada de verificação.
    await expect(verifyFirebaseAdminCredential(path)).resolves.toBeUndefined();
  });

  it('recusa o caminho ausente — string vazia não é credencial', async () => {
    await expect(verifyFirebaseAdminCredential(undefined)).rejects.toThrow(
      FirebaseAdminCredentialError,
    );
  });

  it('recusa um arquivo que não existe', async () => {
    // Este é o caso que a T16.8 deixava passar: o caminho estava preenchido, então
    // `missingRequirements()` ficava satisfeito.
    const missing = join(directory, 'nao-existe.json');
    await expect(verifyFirebaseAdminCredential(missing)).rejects.toThrow(/ENOENT/);
  });

  it('recusa um arquivo sem permissão de leitura', async () => {
    // O modo de falha real da montagem: a service account entra somente-leitura no container, e
    // se o grupo estiver errado o processo (uid 1000, não root) simplesmente não a abre.
    const path = write('sem-permissao.json', JSON.stringify(syntheticServiceAccount()));
    chmodSync(path, 0o000);

    if (process.getuid?.() === 0) {
      // root ignora o bit de permissão; o teste não teria o que provar.
      return;
    }
    await expect(verifyFirebaseAdminCredential(path)).rejects.toThrow(/EACCES/);
  });

  it('recusa um JSON inválido', async () => {
    const path = write('truncado.json', '{ "type": "service_accou');
    await expect(verifyFirebaseAdminCredential(path)).rejects.toThrow(/JSON válido/);
  });

  it('recusa um JSON válido que não é uma service account', async () => {
    const path = write('outro.json', JSON.stringify({ apiKey: 'nao-sou-uma-credencial' }));
    await expect(verifyFirebaseAdminCredential(path)).rejects.toThrow(/campo type/);
  });

  it('recusa uma service account sem os campos que a tornam utilizável', async () => {
    const incomplete = syntheticServiceAccount();
    delete (incomplete as Record<string, unknown>).private_key;
    const path = write('incompleta.json', JSON.stringify(incomplete));

    await expect(verifyFirebaseAdminCredential(path)).rejects.toThrow(/private_key/);
  });

  it('recusa uma chave privada que não é uma chave', async () => {
    // Passa em toda checagem de forma e ainda assim é inutilizável: só o Admin SDK sabe dizer.
    //
    // O texto é reconhecivelmente falso e **não** carrega o cabeçalho PEM literal: a varredura de
    // `test/auth-config.spec.ts` procura exatamente esse cabeçalho na árvore versionada, e uma
    // fixture que o contivesse ensinaria o detector a conviver com o padrão que ele existe para
    // achar. A chave real deste arquivo é gerada em tempo de execução, nunca escrita nele.
    const path = write(
      'chave-falsa.json',
      JSON.stringify(syntheticServiceAccount({ private_key: 'isto-nao-e-uma-chave' })),
    );
    await expect(verifyFirebaseAdminCredential(path)).rejects.toThrow(FirebaseAdminCredentialError);
  });

  it('nenhuma mensagem de recusa carrega o caminho ou o conteúdo da credencial', async () => {
    // A mensagem do Admin SDK ("Failed to parse service account json file: ...") cita o caminho, e
    // a do `JSON.parse` cita um trecho do arquivo — que é uma chave privada. As duas vão para
    // stderr, que vai para o journal.
    const chave = syntheticServiceAccount().private_key;
    const casos = [
      write('a.json', '{ quebrado'),
      write('b.json', JSON.stringify({ type: 'outro' })),
      write('c.json', JSON.stringify(syntheticServiceAccount({ private_key: 'nao-e-pem' }))),
      join(directory, 'ausente.json'),
    ];

    for (const path of casos) {
      const erro = await verifyFirebaseAdminCredential(path).catch((e: unknown) => e);
      expect(erro).toBeInstanceOf(FirebaseAdminCredentialError);
      const texto = (erro as Error).message;
      expect(texto).not.toContain(path);
      expect(texto).not.toContain(directory);
      expect(texto).not.toContain(chave.slice(0, 40));
    }
  });

  it('a exigência é opcional: sem ela, credencial ausente continua sendo estado normal', () => {
    // O comportamento das T16.1–T16.7, preservado. Sem `REQUIRE_FIREBASE_ADMIN`, o processo sobe
    // sem credencial, `/health/*` responde e rota autenticada devolve 503 — nunca 200 sem verificar.
    const config = AppConfig.fromEnv({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
    });

    expect(config.requireFirebaseAdmin).toBe(false);
    expect(config.googleApplicationCredentials).toBeUndefined();
    expect(config.missingRequirements()).toEqual([]);
  });

  it('o Gemini continua opcional, e não vira pré-condição de startup junto', () => {
    // T16.8.1 §16: a IA é capacidade opcional. Endurecer a identidade não pode arrastar o Coach
    // para o caminho crítico — o Coach fora nunca pode derrubar backup e sync.
    const config = AppConfig.fromEnv({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
      REQUIRE_FIREBASE_ADMIN: 'true',
      GOOGLE_APPLICATION_CREDENTIALS: join(directory, 'qualquer.json'),
    });

    expect(config.requireGemini).toBe(false);
    expect(config.missingRequirements()).toEqual([]);
  });
});

/**
 * T18.2 §15/§16/§18 — o modo `adc` do Firebase Admin (Cloud Run).
 *
 * ## O que esta suíte prova, e o que ela não pode provar
 *
 * `applicationDefault()` do Admin SDK nunca consulta rede na **construção** da credencial — a
 * resolução real (env var, arquivo bem-conhecido, metadata server do Cloud Run) só acontece no
 * primeiro uso de verdade (`verifyIdToken`, `deleteUser`), fora do preflight de propósito (§18: "não
 * fazer uma chamada remota destrutiva apenas como preflight"). Por isso `initializeApp({credential:
 * applicationDefault()})` **nunca lança**, com ou sem ADC real disponível — inclusive neste
 * container de CI, que não tem nenhuma. O que esta suíte prova é exatamente esse contrato: o modo
 * `adc` não exige arquivo, não lê `GOOGLE_APPLICATION_CREDENTIALS`, e o preflight não distingue
 * "ADC presente" de "ADC ausente" — a única coisa capaz de provar isso de verdade é uma workload
 * rodando com identidade real do Google Cloud, e isso é Cloud Run real, não teste unitário
 * (ver o relatório final da T18.2, seção 15 — "Cloud Run smoke real").
 */
describe('Firebase Admin — modo ADC (T18.2)', () => {
  it('adc não exige GOOGLE_APPLICATION_CREDENTIALS: o preflight passa sem arquivo nenhum', async () => {
    const originalAdc = process.env.GOOGLE_APPLICATION_CREDENTIALS;
    delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
    try {
      await expect(
        verifyFirebaseAdminCredential(undefined, 'projeto-qualquer', 'adc'),
      ).resolves.toBeUndefined();
    } finally {
      if (originalAdc !== undefined) {
        process.env.GOOGLE_APPLICATION_CREDENTIALS = originalAdc;
      }
    }
  });

  it('adc funciona mesmo com um caminho de arquivo definido no ambiente — ele é ignorado', async () => {
    // Configuração explícita vence detecção implícita (§16): declarar `adc` não é "tentar ADC e
    // cair para arquivo se der errado" — é usar ADC, ponto, mesmo que a variável de arquivo exista
    // por outro motivo (um resquício de configuração anterior, por exemplo).
    await expect(
      verifyFirebaseAdminCredential('/caminho/que/nao/existe.json', undefined, 'adc'),
    ).resolves.toBeUndefined();
  });

  it('o default do modo continua `file` — nenhum deploy existente muda de comportamento sem declarar', () => {
    const config = AppConfig.fromEnv({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
    });
    expect(config.firebaseAdminCredentialMode).toBe('file');
  });

  it('REQUIRE_FIREBASE_ADMIN=true com modo adc não exige GOOGLE_APPLICATION_CREDENTIALS em missingRequirements()', () => {
    const config = AppConfig.fromEnv({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
      REQUIRE_FIREBASE_ADMIN: 'true',
      FIREBASE_ADMIN_CREDENTIAL_MODE: 'adc',
    });
    expect(config.missingRequirements()).toEqual([]);
  });

  it('REQUIRE_FIREBASE_ADMIN=true com modo file (default) continua exigindo o caminho', () => {
    const config = AppConfig.fromEnv({
      NODE_ENV: 'test',
      DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
      REQUIRE_FIREBASE_ADMIN: 'true',
    });
    expect(config.missingRequirements()).toEqual([
      'REQUIRE_FIREBASE_ADMIN=true e FIREBASE_ADMIN_CREDENTIAL_MODE=file, mas GOOGLE_APPLICATION_CREDENTIALS não está definido',
    ]);
  });

  it('um valor fora de file/adc derruba o startup, como qualquer enum fechado do schema', () => {
    expect(() =>
      AppConfig.fromEnv({
        NODE_ENV: 'test',
        DATABASE_URL: 'postgresql://spark:spark@localhost:5432/spark_dev',
        FIREBASE_ADMIN_CREDENTIAL_MODE: 'metadata-server',
      }),
    ).toThrow(ConfigValidationError);
  });
});
