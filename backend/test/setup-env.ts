// Os testes nunca herdam configuração da máquina: cada suíte define o que precisa explicitamente.
process.env.NODE_ENV = 'test';
process.env.LOG_LEVEL = 'silent';
