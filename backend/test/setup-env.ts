// Os testes nunca herdam configuração da máquina: cada suíte define o que precisa explicitamente.
process.env.NODE_ENV = 'test';
process.env.LOG_LEVEL = 'silent';
process.env.DATABASE_URL =
  process.env.DATABASE_URL || 'postgresql://spark:spark@localhost:5432/spark_dev';

/**
 * Uma rejeição de promise não tratada **reprova** a execução (T18.3.2).
 *
 * Antes ela era só impressa, e um `console.error` no meio de centenas de linhas de saída é
 * indistinguível de ruído: a suíte terminava verde com um erro real dentro dela — exatamente o
 * caso que produziu o flake de `ProfileViewModelTest` do lado do Android (um escopo que continuava
 * rodando depois do teste). Aqui o sintoma é o mesmo: um `await` esquecido, um `.catch` ausente ou
 * um worker que continua vivo depois do `afterEach`.
 *
 * O processo **não** é encerrado na hora: abortar no meio esconderia quais testes já tinham
 * passado e dificultaria achar o responsável. O que se garante é que o código de saída seja
 * diferente de zero — o CI fica vermelho, e a saída diz quantas rejeições houve.
 */
let unhandledRejections = 0;

process.on('unhandledRejection', (reason) => {
  unhandledRejections += 1;
  console.error('Test UnhandledRejection:', reason);
  process.exitCode = 1;
});

process.on('exit', () => {
  if (unhandledRejections > 0) {
    console.error(
      `${unhandledRejections} promise rejection(s) não tratada(s) durante os testes — execução reprovada.`,
    );
    process.exitCode = 1;
  }
});
