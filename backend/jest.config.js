/**
 * Testes do Spark Backend.
 *
 * `runInBand` porque cada suíte cria e derruba um schema próprio no PostgreSQL de teste
 * (`DATABASE_URL`, default `postgresql://spark:spark@localhost:5432/spark_dev`), e paralelismo aqui
 * só traria disputa por conexões sem ganho.
 *
 * Nenhum teste toca rede além do PostgreSQL local, nem Firebase, Gemini ou VPS.
 */
module.exports = {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  testRegex: '.*\\.spec\\.ts$',
  transform: {
    '^.+\\.ts$': ['ts-jest', { tsconfig: 'tsconfig.json' }],
    // `jose` (abaixo) é ESM puro e precisa ser transpilado para o runtime CJS do Jest.
    '^.+\\.m?js$': [
      'ts-jest',
      { tsconfig: { allowJs: true, module: 'commonjs', target: 'es2022', esModuleInterop: true } },
    ],
  },
  /**
   * `firebase-admin` chega em `jose` (via `jwks-rsa`), que a partir da v6 publica só ESM. O Node 22
   * carrega isso sozinho em produção; o runtime CJS do Jest não. Transpilar apenas `jose` mantém o
   * grafo de módulos real no teste — a alternativa seria um mock, que esconderia um import quebrado.
   */
  transformIgnorePatterns: ['node_modules/(?!jose/)'],
  collectCoverageFrom: ['src/**/*.ts'],
  coverageDirectory: './coverage',
  testEnvironment: 'node',
  testTimeout: 60000,
  setupFiles: ['<rootDir>/test/setup-env.ts'],
  /**
   * `better-sqlite3` é devDependency e só existe para os testes históricos das migrations SQLite
   * (`social-migrations.spec.ts` e afins). O shim redireciona qualquer "abertura" que na verdade
   * aponta para o PostgreSQL de teste (connection string / schema `test_*`) para um adaptador
   * síncrono sobre `psql`, e deixa os arquivos `.db` legados irem para a biblioteca real. Nada
   * disso entra em `dist/` nem na imagem.
   */
  moduleNameMapper: {
    '^better-sqlite3$': '<rootDir>/test/support/better-sqlite3-shim.ts',
  },
};
