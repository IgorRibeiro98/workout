// Os testes nunca herdam configuração da máquina: cada suíte define o que precisa explicitamente.
process.env.NODE_ENV = 'test';
process.env.LOG_LEVEL = 'silent';
process.env.DATABASE_URL =
  process.env.DATABASE_URL || 'postgresql://spark:spark@localhost:5432/spark_dev';
