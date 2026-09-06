import { existsSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { type CreatedApp, createApp } from '../src/bootstrap/create-app';
import { AppConfig, ConfigValidationError } from '../src/config/app-config';
import { configFor, createTempDb, type TempDb } from './support/temp-db';

describe('Bootstrap da aplicação', () => {
  let temp: TempDb;

  beforeEach(() => {
    temp = createTempDb();
  });

  afterEach(() => {
    temp.cleanup();
  });

  it('sobe com configuração válida e deixa o banco pronto antes do HTTP', async () => {
    const { app, sqlite } = await createApp(configFor(temp.path));
    await app.init();

    expect(sqlite.isOpen).toBe(true);
    expect(sqlite.appliedVersions()).toEqual(sqlite.expectedVersions());

    await app.close();
  });

  it('fecha o SQLite no shutdown da aplicação', async () => {
    const { app, sqlite } = await createApp(configFor(temp.path));
    await app.init();

    await app.close();

    expect(sqlite.isOpen).toBe(false);
  });

  it('configuração obrigatória inválida falha antes de qualquer montagem', () => {
    expect(() => AppConfig.fromEnv({ NODE_ENV: 'test' })).toThrow(ConfigValidationError);
  });

  it('falha no bootstrap quando o caminho do banco não pode ser aberto', async () => {
    // Um arquivo comum no lugar do diretório do banco: nem o `mkdir` do diretório nem a abertura
    // do SQLite podem dar certo. O erro acontece na montagem, antes de o HTTP existir — que é
    // exatamente o comportamento desejado.
    const blocker = join(temp.directory, 'isto-e-um-arquivo');
    writeFileSync(blocker, 'nao sou um diretorio');
    const impossiblePath = join(blocker, 'spark.db');

    let created: CreatedApp | undefined;
    try {
      created = await createApp(configFor(impossiblePath));
    } catch {
      return;
    }

    // Chegar aqui significa que o ambiente abriu um banco onde não deveria haver como. Em vez de
    // um "did not throw" sem contexto, o teste descreve o que o sistema de arquivos realmente fez
    // — e fecha a aplicação que criou, para não vazar conexão para as suítes seguintes.
    const diagnostics = {
      blockerIsFile: statSync(blocker).isFile(),
      databaseFileCreated: existsSync(impossiblePath),
      sqliteOpen: created.sqlite.isOpen,
      platform: process.platform,
    };
    await created.app.close();

    throw new Error(
      `bootstrap deveria ter falhado com DATABASE_PATH inacessível: ${JSON.stringify(diagnostics)}`,
    );
  });
});
