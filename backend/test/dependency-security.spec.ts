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
  it('só um arquivo importa firebase-admin, e só os produtos de identidade', () => {
    // A cadeia moderada aberta hoje (@google-cloud/storage → teeny-request/retry-request → uuid)
    // não é alcançável **porque** nada aqui importa `firebase-admin/storage`. Se isso mudar, a
    // avaliação de risco muda junto — e precisa ser refeita, não herdada.
    // Import de verdade, e não menção: o contrato de identidade cita o Admin SDK em comentário, e
    // um `includes` cru transformaria documentação em falha de teste.
    const importers = collectSources(join(BACKEND_ROOT, 'src')).filter((file) =>
      /from '(firebase-admin[^']*)'/.test(readFileSync(file, 'utf8')),
    );

    expect(importers.map((file) => file.slice(BACKEND_ROOT.length))).toEqual([
      '/src/modules/auth/firebase-auth-token-verifier.ts',
    ]);

    const source = readFileSync(importers[0], 'utf8');
    for (const forbidden of [
      'firebase-admin/storage',
      'firebase-admin/firestore',
      'firebase-admin/database',
      'firebase-admin/messaging',
      'firebase-admin/functions',
    ]) {
      expect(source).not.toContain(forbidden);
    }
  });

  it('o caminho de identidade não carrega o SDK de Storage em runtime', () => {
    // A prova, e não a suposição: importar exatamente o que o backend importa e conferir que o
    // grafo de módulos resultante não contém os pacotes da cadeia vulnerável.
    const script = `
      const before = new Set(Object.keys(require.cache));
      require('firebase-admin/app');
      require('firebase-admin/auth');
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
});

interface PackageManifest {
  readonly dependencies: Record<string, string>;
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
