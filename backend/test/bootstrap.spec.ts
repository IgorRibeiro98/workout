import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createApp } from '../src/bootstrap/create-app';
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
    // do SQLite podem dar certo, em qualquer sistema de arquivos. O erro acontece na montagem,
    // antes de o HTTP existir — que é exatamente o comportamento desejado.
    const blocker = join(temp.directory, 'isto-e-um-arquivo');
    writeFileSync(blocker, 'nao sou um diretorio');

    await expect(createApp(configFor(join(blocker, 'spark.db')))).rejects.toThrow();
  });
});
