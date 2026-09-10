import { execFileSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const BACKEND_ROOT = join(__dirname, '..');

/**
 * A postura de segurança das dependências (T16.8 §93–§97).
 *
 * O que estes testes protegem é a **decisão**, não um número: a T16.8 tratou as vulnerabilidades
 * de runtime subindo `@nestjs/*` deliberadamente, e aceitou uma cadeia moderada de `firebase-admin`
 * **porque ela não é alcançável**. As duas partes precisam continuar verdadeiras, e as duas
 * quebram em silêncio — a primeira quando alguém volta uma versão, a segunda quando alguém importa
 * um produto novo do Admin SDK.
 *
 * `npm audit` em si é gate de CI (`backend.yml`), não de teste unitário: ele depende de rede, e
 * uma suíte offline não pode depender do registro do npm.
 */
describe('Postura de dependências', () => {
  it('apenas os arquivos autorizados importam firebase-admin, e apenas produtos estritos', () => {
    // A cadeia moderada de `firebase-admin` (`@google-cloud/storage@7` → teeny-request/
    // retry-request → uuid) não é alcançável pelo caminho de identidade e de mensagens
    // **porque** nada aqui importa `firebase-admin/storage`.
    //
    // Desde a T18.1 o backend depende de `@google-cloud/storage@8` **diretamente**, pelo seu
    // próprio caminho (`object-storage/gcs-object-storage.client.ts`) — ver o teste seguinte e
    // `object-storage-structure.spec.ts` para a justificativa do que continua aberto ali.
    // Na T16.1, apenas auth verifier importava firebase-admin.
    // Na T17.5, firebase-push-gateway também importa, restrito a messaging.
    const importers = collectSources(join(BACKEND_ROOT, 'src')).filter((file) =>
      /from '(firebase-admin[^']*)'/.test(readFileSync(file, 'utf8')),
    );

    expect(importers.map((file) => file.slice(BACKEND_ROOT.length)).sort()).toEqual(
      [
        '/src/modules/auth/firebase-auth-token-verifier.ts',
        '/src/modules/social/firebase-push-gateway.ts',
      ].sort(),
    );

    // Auth verifier só pode importar app e auth; nunca storage/firestore/database/messaging/functions
    const authSource = readFileSync(
      join(BACKEND_ROOT, 'src/modules/auth/firebase-auth-token-verifier.ts'),
      'utf8',
    );
    for (const forbidden of [
      'firebase-admin/storage',
      'firebase-admin/firestore',
      'firebase-admin/database',
      'firebase-admin/messaging',
      'firebase-admin/functions',
    ]) {
      expect(authSource).not.toContain(forbidden);
    }

    // Push gateway só pode importar app e messaging; nunca storage/firestore/database/auth/functions
    const pushSource = readFileSync(
      join(BACKEND_ROOT, 'src/modules/social/firebase-push-gateway.ts'),
      'utf8',
    );
    for (const forbidden of [
      'firebase-admin/storage',
      'firebase-admin/firestore',
      'firebase-admin/database',
      'firebase-admin/auth',
      'firebase-admin/functions',
    ]) {
      expect(pushSource).not.toContain(forbidden);
    }
  });

  it('a dependência direta do SDK de Storage é deliberada, fixada e justificada (T18.1)', () => {
    // ## O que este teste protege
    //
    // `@google-cloud/storage@8` entrou na T18.1 como dependência **direta**, para o bucket
    // privado das fotos e dos documentos de backup. O `npm audit --omit=dev --audit-level=high`
    // do CI continua verde: o que a cadeia dele carrega hoje é **moderado** — `gaxios@6` → `uuid@9`
    // (GHSA-w5hq-g745-h8pq: falta de checagem de limites em `uuid.v3/v5/v6` quando o chamador
    // passa um `buf`). A justificativa verificável para aceitá-lo: `gaxios` só chama `uuid.v4()`,
    // para gerar a fronteira de um corpo multipart — a função vulnerável nunca é invocada, e este
    // backend não a invoca por conta própria.
    //
    // O que muda se alguém trocar a versão: a justificativa precisa ser revisada, e é isto que
    // avisa. Uma versão da série `legacy-18` (7.x) traria de volta `teeny-request`/`retry-request`
    // vulneráveis; uma major nova precisa ser lida antes de entrar.
    const pkg = JSON.parse(
      readFileSync(join(BACKEND_ROOT, 'package.json'), 'utf8'),
    ) as PackageManifest;
    expect(pkg.dependencies['@google-cloud/storage']).toBe('8.1.0');

    const gaxios = readFileSync(
      join(BACKEND_ROOT, 'node_modules', 'gaxios', 'build', 'src', 'gaxios.js'),
      'utf8',
    );
    // Só `v4`: nenhuma das funções do aviso.
    expect(gaxios).toMatch(/uuid_1\.v4\)\(\)/);
    expect(gaxios).not.toMatch(/uuid_1\.v[356]\b/);

    // E ninguém no backend usa `uuid` com `buf` por conta própria: os identificadores vêm de
    // `node:crypto`.
    const uuidImporters = collectSources(join(BACKEND_ROOT, 'src')).filter((file) =>
      /from 'uuid'/.test(readFileSync(file, 'utf8')),
    );
    expect(uuidImporters).toEqual([]);
  });

  it('o caminho de identidade e de mensagens não carrega o SDK de Storage em runtime', () => {
    // A prova, e não a suposição: importar exatamente o que o backend importa e conferir que o
    // grafo de módulos resultante não contém os pacotes da cadeia vulnerável.
    const script = `
      const before = new Set(Object.keys(require.cache));
      require('firebase-admin/app');
      require('firebase-admin/auth');
      require('firebase-admin/messaging');
      const loaded = Object.keys(require.cache).filter((k) => !before.has(k));
      const reachable = ['@google-cloud/storage', 'teeny-request', 'retry-request']
        .filter((pkg) => loaded.some((k) => k.includes(pkg)));
      process.stdout.write(JSON.stringify(reachable));
    `;
    const output = execFileSync(process.execPath, ['-e', script], {
      cwd: BACKEND_ROOT,
      encoding: 'utf8',
    });

    expect(JSON.parse(output)).toEqual([]);
  });

  it('as dependências de runtime são fixadas em versão exata', () => {
    // Um `^` numa dependência de produção significa que a imagem construída amanhã pode não ser a
    // que foi testada hoje. O lockfile já garante reprodutibilidade; o `package.json` exato torna
    // a intenção legível e o diff de uma atualização visível na revisão.
    const pkg = JSON.parse(
      readFileSync(join(BACKEND_ROOT, 'package.json'), 'utf8'),
    ) as PackageManifest;

    const loose = Object.entries(pkg.dependencies).filter(([, range]) => /^[\^~]/.test(range));

    expect(loose).toEqual([]);
  });

  it('o multipart não é alcançável, e é por isso que o override do multer é a correção certa', () => {
    // ## O que este teste protege (T17.9 §143/§144)
    //
    // `@nestjs/platform-express` depende de `multer@2.2.0`, que tem quatro avisos de negação de
    // serviço em aberto. O backend **não usa multipart em lugar nenhum**: o upload de mídia da
    // T17.9 recebe os bytes crus (`express.raw`, escopado só naquela rota), e os dois
    // identificadores vão no query string.
    //
    // A correção foi um `overrides` para `multer@2.3.0` — uma subida de minor dentro do mesmo
    // major que o `platform-express` declara, revisada e explícita. Não foi `npm audit fix
    // --force`, que é proibido (§95 da T16.8) porque sobe major sem revisão.
    //
    // Este teste é a metade que o `npm audit` não cobre: ele garante que ninguém **passe** a usar
    // multipart depois. No dia em que um `FileInterceptor` aparecer, a cadeia deixa de ser
    // inalcançável e a decisão precisa ser revisada — e é isto que avisa.
    const pkg = JSON.parse(
      readFileSync(join(BACKEND_ROOT, 'package.json'), 'utf8'),
    ) as PackageManifest;

    expect(pkg.overrides?.multer).toBe('2.3.0');

    const multipartUsers = collectSources(join(BACKEND_ROOT, 'src')).filter((file) => {
      const source = readFileSync(file, 'utf8');
      // Os quatro sinais de **uso**, e não a string `multipart/form-data`: ela aparece em
      // comentário (o de `SocialModule` explica por que multipart ficou de fora), e um teste que
      // proibisse a palavra proibiria também a explicação de por que ela não é usada.
      return (
        source.includes('FileInterceptor') ||
        source.includes('FilesInterceptor') ||
        /from 'multer'/.test(source) ||
        /@UploadedFile/.test(source)
      );
    });

    expect(multipartUsers.map((file) => file.slice(BACKEND_ROOT.length))).toEqual([]);
  });

  it('a biblioteca de processamento de imagem é a escolhida, e ninguém a contorna', () => {
    // §14/§144 — decodificar de verdade é o que impede o `Content-Type` do cliente de decidir o
    // que é armazenado. Um segundo caminho de imagem — outra biblioteca, ou um `writeFile` dos
    // bytes recebidos — seria exatamente o bypass que §195 lista como bloqueante.
    const sharpImporters = collectSources(join(BACKEND_ROOT, 'src')).filter((file) =>
      /from 'sharp'/.test(readFileSync(file, 'utf8')),
    );

    expect(sharpImporters.map((file) => file.slice(BACKEND_ROOT.length))).toEqual([
      '/src/modules/social/social-media.processor.ts',
    ]);

    // Nenhuma outra biblioteca de imagem entrou na árvore de produção junto.
    const pkg = JSON.parse(
      readFileSync(join(BACKEND_ROOT, 'package.json'), 'utf8'),
    ) as PackageManifest;
    for (const rival of ['jimp', 'gm', 'imagemagick', 'canvas', 'lwip', 'images']) {
      expect(pkg.dependencies[rival]).toBeUndefined();
    }
  });
});

interface PackageManifest {
  readonly dependencies: Record<string, string>;
  readonly overrides?: Record<string, string>;
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
