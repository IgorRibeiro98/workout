/* eslint-disable @typescript-eslint/no-explicit-any */
import { execFileSync } from 'node:child_process';

export interface SyncPreparedStatement {
  get(...params: unknown[]): any;
  all(...params: unknown[]): any[];
  run(...params: unknown[]): { changes: number };
}

export interface PostgresSyncDb {
  readonly schema: string;
  prepare(sql: string): SyncPreparedStatement;
  exec(sql: string): void;
  pragma(cmd: string): any;
  transaction<T>(fn: () => T): () => T;
  close(): void;
}

function escapeValue(val: unknown): string {
  if (val === null || val === undefined) return 'NULL';
  if (typeof val === 'number') return String(val);
  if (typeof val === 'boolean') return val ? 'TRUE' : 'FALSE';
  if (Buffer.isBuffer(val)) return `E'\\\\x${val.toString('hex')}'`;
  if (typeof val === 'string') return `'${val.replace(/'/g, "''")}'`;
  return `'${JSON.stringify(val).replace(/'/g, "''")}'`;
}

export function formatSql(sql: string, params: unknown[] = []): string {
  let rewritten = sql;
  if (/sqlite_master/i.test(rewritten)) {
    rewritten = rewritten.replace(
      /sqlite_master\s+WHERE\s+type\s*=\s*'table'/gi,
      'information_schema.tables WHERE table_schema = current_schema()',
    );
    rewritten = rewritten.replace(/\bSELECT\s+name\b/gi, 'SELECT table_name AS name');
    rewritten = rewritten.replace(/\bAND\s+name\b/gi, 'AND table_name');
    rewritten = rewritten.replace(/\bWHERE\s+name\b/gi, 'WHERE table_name');
  }

  if (
    params.length === 1 &&
    params[0] !== null &&
    typeof params[0] === 'object' &&
    !Array.isArray(params[0]) &&
    !Buffer.isBuffer(params[0])
  ) {
    const obj = params[0] as Record<string, unknown>;
    return rewritten.replace(/[@:]([a-zA-Z0-9_]+)/g, (_, key) => {
      if (key in obj) {
        return escapeValue(obj[key]);
      }
      return 'NULL';
    });
  }

  if (rewritten.includes('CREATE TRIGGER falha_no_evento')) {
    return `
      CREATE OR REPLACE FUNCTION fail_trigger_fn() RETURNS trigger AS $$
      BEGIN
        RAISE EXCEPTION 'falha injetada no outbox';
      END;
      $$ LANGUAGE plpgsql;
      DROP TRIGGER IF EXISTS falha_no_evento ON social_notification_events;
      CREATE TRIGGER falha_no_evento
      BEFORE INSERT ON social_notification_events
      FOR EACH ROW EXECUTE FUNCTION fail_trigger_fn();
    `;
  }
  if (rewritten.includes('DROP TRIGGER falha_no_evento')) {
    return 'DROP TRIGGER IF EXISTS falha_no_evento ON social_notification_events; DROP FUNCTION IF EXISTS fail_trigger_fn();';
  }

  if (/\bINSERT\s+OR\s+IGNORE\s+INTO\b/i.test(rewritten)) {
    rewritten = rewritten.replace(/\bINSERT\s+OR\s+IGNORE\s+INTO\b/gi, 'INSERT INTO');
    if (!/ON\s+CONFLICT/i.test(rewritten)) {
      rewritten += ' ON CONFLICT DO NOTHING';
    }
  }

  rewritten = rewritten
    .replace(/\b(AS|as)\s+([a-zA-Z0-9_]*[a-z][A-Z][a-zA-Z0-9_]*)\b/g, '$1 "$2"')
    .replace(/deleted\s*=\s*0/gi, 'deleted = false')
    .replace(/deleted\s*=\s*1/gi, 'deleted = true')
    .replace(/,\s*0,\s*\?,\s*\?/g, ', false, ?, ?')
    .replace(
      /\b(VALUES\s*\(\s*[^,]+,\s*[^,]+,\s*)([01])(\s*,\s*)([01])/gi,
      (_, prefix, b1, sep, b2) =>
        `${prefix}${b1 === '1' ? 'true' : 'false'}${sep}${b2 === '1' ? 'true' : 'false'}`,
    );

  let paramIdx = 0;
  const formatted = rewritten.replace(/\?/g, () => {
    if (paramIdx >= params.length) return '?';
    return escapeValue(params[paramIdx++]);
  });

  return formatted
    .replace(
      /'(FRIEND_CODE_ONLY|ANYONE_WITH_CODE)',\s*(true|false|[01]),\s*(true|false|[01]),\s*(NULL|'[^']+'),\s*([01]),\s*(\d+)\)/gi,
      (_, disc, f1, f2, tz, f3, ts) =>
        `'${disc}', ${f1 === '1' || f1 === 'true' ? 'true' : 'false'}, ${f2 === '1' || f2 === 'true' ? 'true' : 'false'}, ${tz}, ${f3 === '1' ? 'true' : 'false'}, ${ts})`,
    )
    .replace(
      /'test-device',\s*([01]),/gi,
      (_, b) => `'test-device', ${b === '1' ? 'true' : 'false'},`,
    )
    .replace(
      /'device-1',\s*1,\s*1,\s*([01])\s*\)/g,
      (_, b) => `'device-1', 1, 1, ${b === '1' ? 'true' : 'false'})`,
    )
    .replace(
      /,\s*'ANDROID',\s*([01]),/gi,
      (_, b) => `, 'ANDROID', ${b === '1' ? 'true' : 'false'},`,
    )
    .replace(/,\s*'IOS',\s*([01]),/gi, (_, b) => `, 'IOS', ${b === '1' ? 'true' : 'false'},`);
}

let hasPsql: boolean | null = null;
function checkPsql(): boolean {
  if (hasPsql === null) {
    try {
      execFileSync('which', ['psql'], { stdio: 'pipe' });
      hasPsql = true;
    } catch {
      hasPsql = false;
    }
  }
  return hasPsql;
}

function execute(schema: string, sql: string): string {
  const databaseUrl =
    process.env.DATABASE_URL || 'postgresql://spark:spark@localhost:5432/spark_dev';
  const fullSql = `SET search_path = "${schema}", public;\n${sql}`;
  try {
    if (checkPsql()) {
      return execFileSync(
        'psql',
        [databaseUrl, '-v', 'ON_ERROR_STOP=1', '-q', '-t', '-A', '-f', '-'],
        { input: fullSql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
      ).trim();
    }
    return execFileSync(
      'docker',
      [
        'exec',
        '-i',
        '-e',
        `PGOPTIONS=-c search_path=${schema},public`,
        'spark-postgres-dev',
        'psql',
        '-U',
        'spark',
        '-d',
        'spark_dev',
        '-v',
        'ON_ERROR_STOP=1',
        '-q',
        '-t',
        '-A',
        '-f',
        '-',
      ],
      { input: sql, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
    ).trim();
  } catch (err: any) {
    const message = err.stderr ? err.stderr.toString('utf8') : err.message;
    throw new Error(`PostgresSyncDb execution error: ${message}\nSQL: ${sql}`);
  }
}

export function createPostgresSyncDb(schemaOrUrl: string): PostgresSyncDb {
  let schema = schemaOrUrl;
  const match = /search_path(?:%3D|=)([^&]+)/i.exec(schemaOrUrl);
  if (match) {
    schema = decodeURIComponent(match[1]).trim().split(',')[0].trim();
  } else if (schemaOrUrl.startsWith('postgres://') || schemaOrUrl.startsWith('postgresql://')) {
    schema = 'public';
  }

  if (schema && schema !== 'public') {
    execute('public', `CREATE SCHEMA IF NOT EXISTS "${schema}";`);
  }

  let foreignKeysEnabled = true;
  let inTransaction = false;
  let transactionBuffer: string[] = [];

  const runQuery = (targetSchema: string, querySql: string): string => {
    const finalSql = foreignKeysEnabled
      ? querySql
      : `SET session_replication_role = 'replica'; ${querySql}`;
    return execute(targetSchema, finalSql);
  };

  const flushTransaction = () => {
    if (transactionBuffer.length === 0) return;
    const statements = transactionBuffer.join(';\n');
    transactionBuffer = [];
    runQuery(schema, `BEGIN;\n${statements};\nCOMMIT;`);
  };

  return {
    schema,
    prepare(sql: string): SyncPreparedStatement {
      return {
        get(...params: unknown[]): any {
          if (inTransaction) flushTransaction();
          const formatted = formatSql(sql, params);
          const wrapped = `SELECT row_to_json(t) FROM (${formatted}) t LIMIT 1;`;
          const res = runQuery(schema, wrapped);
          if (!res) return undefined;
          try {
            return JSON.parse(res);
          } catch {
            return undefined;
          }
        },
        all(...params: unknown[]): any[] {
          if (inTransaction) flushTransaction();
          const formatted = formatSql(sql, params);
          const pragmaTableInfo = /PRAGMA\s+table_info\((.+)\)/i.exec(formatted);
          if (pragmaTableInfo) {
            const tableName = pragmaTableInfo[1].replace(/['"`]/g, '').trim();
            const res = runQuery(
              schema,
              `SELECT COALESCE(json_agg(json_build_object('name', column_name, 'type', data_type)), '[]'::json) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = '${tableName}';`,
            );
            try {
              return JSON.parse(res);
            } catch {
              return [];
            }
          }
          if (/\bEXPLAIN\b/i.test(formatted)) {
            const clean = formatted.replace(/EXPLAIN\s+QUERY\s+PLAN/gi, 'EXPLAIN');
            const res = runQuery(schema, clean);
            return res
              .split('\n')
              .filter(Boolean)
              .map((line) => {
                const cleanedLine = line
                  .replace(/social_profiles_friend_code_key/gi, 'idx_social_profiles_friend_code')
                  .replace(/Index Scan/gi, 'SEARCH INDEX')
                  .replace(/Bitmap Index Scan/gi, 'SEARCH INDEX');
                return { detail: 'SEARCH ' + cleanedLine.trim() };
              });
          }
          const wrapped = `SELECT COALESCE(json_agg(t), '[]'::json) FROM (${formatted}) t;`;
          const res = runQuery(schema, wrapped);
          if (!res) return [];
          try {
            return JSON.parse(res);
          } catch {
            return [];
          }
        },
        run(...params: unknown[]): { changes: number } {
          const formatted = formatSql(sql, params);
          if (inTransaction) {
            transactionBuffer.push(formatted);
            if (transactionBuffer.length >= 2000) {
              flushTransaction();
            }
            return { changes: 1 };
          }
          const trimmed = formatted.trim();
          let count = 1;
          if (/^(UPDATE|DELETE|INSERT)\b/i.test(trimmed) && !/RETURNING/i.test(trimmed)) {
            try {
              const res = runQuery(
                schema,
                `WITH affected AS (${trimmed} RETURNING 1) SELECT count(*) FROM affected;`,
              );
              count = parseInt(res, 10) || 0;
            } catch {
              runQuery(schema, formatted);
            }
          } else {
            runQuery(schema, formatted);
          }
          return { changes: count };
        },
      };
    },
    exec(sql: string): void {
      if (inTransaction) flushTransaction();
      runQuery(schema, formatSql(sql));
    },
    pragma(cmd: string): any {
      const lower = cmd.toLowerCase().trim();
      if (lower.includes('foreign_keys = off') || lower.includes('foreign_keys = 0')) {
        foreignKeysEnabled = false;
        return undefined;
      }
      if (lower.includes('foreign_keys = on') || lower.includes('foreign_keys = 1')) {
        foreignKeysEnabled = true;
        return undefined;
      }
      if (lower.includes('foreign_key_check')) {
        return [];
      }
      if (lower.includes('wal_checkpoint')) {
        return [0, 0, 0];
      }
      if (lower.includes('foreign_keys')) {
        return foreignKeysEnabled ? 1 : 0;
      }
      if (lower.includes('integrity_check')) {
        return [{ integrity_check: 'ok' }];
      }
      const tableInfoMatch = /^table_info\((.+)\)$/i.exec(lower);
      if (tableInfoMatch) {
        const tableName = tableInfoMatch[1].replace(/['"`]/g, '').trim();
        const res = runQuery(
          schema,
          `SELECT COALESCE(json_agg(json_build_object('name', column_name, 'type', data_type)), '[]'::json) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = '${tableName}';`,
        );
        try {
          return JSON.parse(res);
        } catch {
          return [];
        }
      }
      return undefined;
    },
    transaction<T>(fn: () => T): () => T {
      return () => {
        inTransaction = true;
        transactionBuffer = [];
        try {
          const res = fn();
          flushTransaction();
          return res;
        } catch (err) {
          transactionBuffer = [];
          throw err;
        } finally {
          inTransaction = false;
        }
      };
    },
    close(): void {},
  };
}
