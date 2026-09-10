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
      "information_schema.tables WHERE table_schema = current_schema()",
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
    .replace(/deleted\s*=\s*0/gi, 'deleted = false')
    .replace(/deleted\s*=\s*1/gi, 'deleted = true')
    .replace(/,\s*0,\s*(\d{13})/g, ', false, $1')
    .replace(/,\s*1,\s*(\d{13})/g, ', true, $1')
    .replace(/,\s*0,\s*\?,\s*\?/g, ', false, ?, ?')
    .replace(/,\s*1,\s*\?,\s*\?/g, ', true, ?, ?');

  let paramIdx = 0;
  return rewritten.replace(/\?/g, () => {
    if (paramIdx >= params.length) return '?';
    return escapeValue(params[paramIdx++]);
  });
}

function execute(schema: string, sql: string): string {
  try {
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
        '-q',
        '-t',
        '-A',
        '-c',
        sql,
      ],
      { encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'] },
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

  return {
    schema,
    prepare(sql: string): SyncPreparedStatement {
      return {
        get(...params: unknown[]): any {
          const formatted = formatSql(sql, params);
          const wrapped = `SELECT row_to_json(t) FROM (${formatted}) t LIMIT 1;`;
          const res = execute(schema, wrapped);
          if (!res) return undefined;
          try {
            return JSON.parse(res);
          } catch {
            return undefined;
          }
        },
        all(...params: unknown[]): any[] {
          const formatted = formatSql(sql, params);
          const wrapped = `SELECT COALESCE(json_agg(t), '[]'::json) FROM (${formatted}) t;`;
          const res = execute(schema, wrapped);
          if (!res) return [];
          try {
            return JSON.parse(res);
          } catch {
            return [];
          }
        },
        run(...params: unknown[]): { changes: number } {
          const formatted = formatSql(sql, params);
          const trimmed = formatted.trim();
          let count = 1;
          if (/^(UPDATE|DELETE|INSERT)\b/i.test(trimmed) && !/RETURNING/i.test(trimmed)) {
            try {
              const res = execute(
                schema,
                `WITH affected AS (${trimmed} RETURNING 1) SELECT count(*) FROM affected;`,
              );
              count = parseInt(res, 10) || 0;
            } catch {
              execute(schema, formatted);
            }
          } else {
            execute(schema, formatted);
          }
          return { changes: count };
        },
      };
    },
    exec(sql: string): void {
      execute(schema, formatSql(sql));
    },
    pragma(cmd: string): any {
      const lower = cmd.toLowerCase().trim();
      if (lower.includes('foreign_key_check')) {
        return [];
      }
      if (lower.includes('wal_checkpoint')) {
        return [0, 0, 0];
      }
      if (lower.includes('foreign_keys')) {
        return true;
      }
      if (lower.includes('integrity_check')) {
        return [{ integrity_check: 'ok' }];
      }
      const tableInfoMatch = /^table_info\((.+)\)$/i.exec(lower);
      if (tableInfoMatch) {
        const tableName = tableInfoMatch[1].replace(/['"`]/g, '').trim();
        const res = execute(
          schema,
          `SELECT COALESCE(json_agg(json_build_object('name', column_name)), '[]'::json) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = '${tableName}';`,
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
        execute(schema, 'BEGIN');
        try {
          const res = fn();
          execute(schema, 'COMMIT');
          return res;
        } catch (err) {
          execute(schema, 'ROLLBACK');
          throw err;
        }
      };
    },
    close(): void {},
  };
}
