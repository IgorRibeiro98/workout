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
  },
  collectCoverageFrom: ['src/**/*.ts'],
  coverageDirectory: './coverage',
  testEnvironment: 'node',
  setupFiles: ['<rootDir>/test/setup-env.ts'],
};
