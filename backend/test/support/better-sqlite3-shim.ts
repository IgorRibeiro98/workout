/* eslint-disable @typescript-eslint/no-explicit-any */
import { createPostgresSyncDb } from './postgres-sync-db';

/**
 * A biblioteca real, carregada pelo caminho **absoluto** do pacote.
 *
 * Até o Jest 30.2 bastava `require(require.resolve('better-sqlite3', { paths: [__dirname] }))`. O
 * Jest 30.5 passou a aplicar `moduleNameMapper` também dentro de `require.resolve`, e o mapa deste
 * projeto aponta `better-sqlite3` para **este arquivo**: o shim passava a carregar a si mesmo no
 * meio da própria avaliação, recebia o `module.exports` ainda vazio e falhava com
 * `RealBetterSqlite3 is not a constructor` em todo teste de migration SQLite. Um caminho absoluto
 * não casa com `^better-sqlite3$`, então o mapa não interfere.
 */
// eslint-disable-next-line @typescript-eslint/no-require-imports
const path = require('node:path');
const realPackageDir = path.resolve(__dirname, '..', '..', 'node_modules', 'better-sqlite3');
// eslint-disable-next-line @typescript-eslint/no-require-imports
const realMain: string = require(path.join(realPackageDir, 'package.json')).main ?? 'lib/index.js';
// eslint-disable-next-line @typescript-eslint/no-require-imports
const RealBetterSqlite3 = require(path.join(realPackageDir, realMain));

function BetterSqlite3Shim(filename: string, options?: any) {
  if (
    typeof filename === 'string' &&
    (filename.startsWith('postgres://') ||
      filename.startsWith('postgresql://') ||
      filename.includes('options=-csearch_path') ||
      filename.includes('spark-postgres-dev') ||
      filename.startsWith('test_'))
  ) {
    return createPostgresSyncDb(filename);
  }
  return new RealBetterSqlite3(filename, options);
}

BetterSqlite3Shim.prototype = RealBetterSqlite3.prototype;
Object.assign(BetterSqlite3Shim, RealBetterSqlite3);
(BetterSqlite3Shim as any).default = BetterSqlite3Shim;

// eslint-disable-next-line @typescript-eslint/no-namespace
namespace BetterSqlite3Shim {
  export type Database = any;
  export type Statement = any;
}

export = BetterSqlite3Shim;
