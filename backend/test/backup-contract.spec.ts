import { readdirSync } from 'node:fs';
import {
  canonicalize,
  CanonicalJsonError,
  parseCanonical,
  sha256Hex,
} from '../src/modules/backup/canonical-json';
import { BACKUP_ENTITY_TYPES, BACKUP_SCHEMA_VERSION } from '../src/modules/backup/backup.contract';
import { validateBackupRequest } from '../src/modules/backup/backup.validator';
import { FIXTURES_DIR, fixtureText } from './support/backup-fixtures';

/**
 * A forma canônica e as fixtures compartilhadas (T16.4).
 *
 * As fixtures moram em `contracts/backup/v1/fixtures/`, fora de `backend/`, e são as mesmas que o
 * teste do Android lê. Este arquivo prova o lado TypeScript do contrato; o Kotlin prova o dele
 * contra os mesmos bytes.
 */
describe('Forma canônica do backup', () => {
  it('ignora espaço e ordem de chave', () => {
    const a = '{"b":1,"a":{"y":true,"x":null}}';
    const b = '  {\n  "a" : { "x" : null , "y" : true } ,\n  "b" : 1 }  ';

    expect(canonicalize(a)).toBe('{"a":{"x":null,"y":true},"b":1}');
    expect(canonicalize(b)).toBe(canonicalize(a));
    expect(sha256Hex(canonicalize(b))).toBe(sha256Hex(canonicalize(a)));
  });

  it('preserva a ordem dos arrays', () => {
    // Um array é dado de domínio — a ordem dos exercícios de um treino é a ordem deles.
    expect(canonicalize('[3,1,2]')).toBe('[3,1,2]');
    expect(canonicalize('[1,2,3]')).not.toBe(canonicalize('[3,1,2]'));
  });

  it('copia o token do número verbatim', () => {
    // É esta cópia literal que faz Kotlin e TypeScript fecharem o mesmo hash sem que um precise
    // imitar o formatador de ponto flutuante do outro. `60.0` do Kotlin não vira `60`.
    expect(canonicalize('{"w":60.0}')).toBe('{"w":60.0}');
    expect(canonicalize('{"w":6.0e1}')).toBe('{"w":6.0e1}');
    expect(canonicalize('{"w":-0.5}')).toBe('{"w":-0.5}');
    // E, portanto, formas diferentes do mesmo valor **não** colidem — o hash descreve o texto.
    expect(canonicalize('{"w":60.0}')).not.toBe(canonicalize('{"w":60}'));
  });

  it('copia o token da string verbatim', () => {
    expect(canonicalize('{"n":"caf\\u00e9"}')).toBe('{"n":"caf\\u00e9"}');
    expect(canonicalize('{"n":"café"}')).toBe('{"n":"café"}');
    expect(canonicalize('{"n":"a\\"b"}')).toBe('{"n":"a\\"b"}');
  });

  it('recusa chave repetida no mesmo objeto', () => {
    expect(() => canonicalize('{"a":1,"a":2}')).toThrow(CanonicalJsonError);
  });

  it('recusa JSON malformado', () => {
    for (const bad of ['', '{', '{"a"}', '[1,]', '{"a":01}', 'nulo', '{"a":1} lixo', '"aberta']) {
      expect(() => canonicalize(bad)).toThrow(CanonicalJsonError);
    }
  });

  it('a árvore devolve valor e texto do mesmo nó', () => {
    const node = parseCanonical('{"items":[{"payload":{"b":2,"a":1}}]}');
    const payload = node.members?.get('items')?.elements?.[0]?.members?.get('payload');

    // O texto guardado é o do cliente, canonicalizado — não uma reserialização do valor parseado.
    expect(payload?.text).toBe('{"a":1,"b":2}');
    expect(payload?.value).toEqual({ a: 1, b: 2 });
  });
});

describe('Fixtures compartilhadas do contrato', () => {
  it('o diretório de contrato tem exatamente as fixtures documentadas', () => {
    expect(readdirSync(FIXTURES_DIR).sort()).toEqual([
      'backup-v1-complete.json',
      'backup-v1-duplicate-item.json',
      'backup-v1-invalid-id.json',
      'backup-v1-minimal.json',
      'backup-v1-unsupported-version.json',
    ]);
  });

  it('a fixture completa cobre todos os tipos do registry', () => {
    const snapshot = validateBackupRequest(fixtureText('backup-v1-complete'));

    expect(snapshot.items.map((item) => item.entityType).sort()).toEqual(
      [...BACKUP_ENTITY_TYPES].sort(),
    );
    expect(snapshot.backupSchemaVersion).toBe(BACKUP_SCHEMA_VERSION);
  });

  it('a fixture mínima é válida e não tem item', () => {
    const snapshot = validateBackupRequest(fixtureText('backup-v1-minimal'));

    expect(snapshot.items).toHaveLength(0);
    expect(snapshot.payloadHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('as fixtures inválidas são recusadas', () => {
    for (const name of [
      'backup-v1-invalid-id',
      'backup-v1-duplicate-item',
      'backup-v1-unsupported-version',
    ] as const) {
      expect(() => validateBackupRequest(fixtureText(name))).toThrow();
    }
  });

  it('o hash da fixture é estável — é o valor que o Android precisa reproduzir', () => {
    // Estes hashes são o contrato, não um detalhe: o teste equivalente no Android
    // (`BackupContractFixturesTest`) fixa exatamente os mesmos valores, calculados pela sua
    // própria implementação da forma canônica. Se a canonicalização de um lado mudar, os dois
    // testes quebram juntos — que é a razão de o número estar escrito e não derivado aqui.
    expect(validateBackupRequest(fixtureText('backup-v1-minimal')).payloadHash).toBe(
      '4caa9793e9441f2f23a7874c19bda66b6e11b79dc9ca89c74e5f8bd802dd58fd',
    );
    expect(validateBackupRequest(fixtureText('backup-v1-complete')).payloadHash).toBe(
      '432b0f20b96d3b1ba21a38ba554ff5fde6e0bc04bdca732d352494ff9dac3d9a',
    );
    // E continuam sendo o SHA-256 da forma canônica, não um valor guardado em outro lugar.
    expect(sha256Hex(canonicalize(fixtureText('backup-v1-complete')))).toBe(
      '432b0f20b96d3b1ba21a38ba554ff5fde6e0bc04bdca732d352494ff9dac3d9a',
    );
  });
});
