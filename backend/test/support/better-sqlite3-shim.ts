/* eslint-disable @typescript-eslint/no-explicit-any */
import { createPostgresSyncDb } from './postgres-sync-db';

// eslint-disable-next-line @typescript-eslint/no-require-imports
const RealBetterSqlite3 = require(require.resolve('better-sqlite3', { paths: [__dirname] }));

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
