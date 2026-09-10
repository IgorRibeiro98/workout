/**
 * Testes do Spark Backend.
 *
 * `runInBand` porque os testes de SQLite abrem arquivos temporários reais e verificam PRAGMAs e
 * persistência — paralelismo aqui só traria concorrência de disco sem ganho.
 *
 * Nenhum teste toca rede, Firebase, Gemini ou VPS. Tudo é determinístico e offline.
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
  setupFiles: ['<rootDir>/test/setup-env.ts'],
  moduleNameMapper: {
    '^better-sqlite3$': '<rootDir>/test/support/better-sqlite3-shim.ts',
  },
};
