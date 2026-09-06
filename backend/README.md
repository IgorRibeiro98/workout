# Spark Backend

Fronteira online do Spark. Monólito modular em NestJS sobre SQLite, pensado para rodar em **uma**
VPS com Docker Compose.

> **Estado (T16.0): fundação.** Não existe autenticação, sincronização, backup, restore ou proxy do
> Gemini. `/v1` está deliberadamente vazio. O que existe é a infraestrutura que as fases T16.1+ vão
> usar. Ver [ADR-0001](../docs/architecture/ADR-0001-spark-online-architecture.md).

O Spark Android **não depende deste backend**. Sem ele — e sem internet — treino, execução,
histórico, templates e gamificação continuam funcionando normalmente sobre Room.

## Stack

| Papel | Escolha | Por quê |
| --- | --- | --- |
| Runtime | Node.js 22 LTS | LTS atual; `fetch` global dispensa cliente HTTP no healthcheck |
| Framework | NestJS 11 | Módulos, DI e ciclo de vida (shutdown hooks) prontos |
| Banco | SQLite via `better-sqlite3` | Maduro, síncrono, prepared statements, transações e `PRAGMA` diretos, sem ORM |
| Migrations | runner próprio (`src/database/migration-runner.ts`) | ~100 linhas versionadas e testadas, contra um ORM inteiro por uma tabela |
| Validação | `zod` | Valida o ambiente no startup, com fail-fast |
| Logging | `pino` | JSON estruturado, com `redact` para campos sensíveis |
| Testes | Jest + Supertest | Padrão do NestJS; offline e determinístico |

**Por que não um ORM:** o backend tem hoje uma tabela e terá poucas. Prisma, TypeORM ou Drizzle
trariam geração de código, engine própria e um modelo de migrations opinativo — em troca de nada que
`better-sqlite3` não resolva com SQL direto. Se o schema crescer a ponto de justificar, é uma
decisão nova.

## Rodar

### Local

```bash
npm ci
cp .env.example .env      # nenhuma credencial é necessária na T16.0
npm run build
npm start
```

### Docker Compose

```bash
docker compose up -d
curl -s http://127.0.0.1:8080/health/live
curl -s http://127.0.0.1:8080/health/ready
```

O banco vive no volume `spark-data`, montado em `/data`. Derrubar e recriar o container **não**
apaga o banco:

```bash
docker compose down      # container removido, volume preservado
docker compose up -d     # o dado continua lá
```

Só `docker compose down -v` apaga o volume — é destrutivo por definição.

### Verificação

```bash
npm run lint
npm test
npm run build
```

Nenhum teste toca rede, Firebase, Gemini ou VPS.

## Configuração

Toda configuração vem do ambiente e é validada no startup. Configuração obrigatória inválida
**derruba o processo** em vez de subir pela metade. Ver [`.env.example`](./.env.example).

| Variável | Obrigatória | Default | Observação |
| --- | --- | --- | --- |
| `NODE_ENV` | não | `development` | `development` \| `test` \| `production` |
| `PORT` | não | `8080` | TLS é do Caddy, não deste processo |
| `DATABASE_PATH` | **sim** | — | Sem default de propósito: em container precisa apontar para o volume |
| `LOG_LEVEL` | não | `info` | |
| `SQLITE_BUSY_TIMEOUT_MS` | não | `5000` | Único lugar que decide o `busy_timeout` |
| `SHUTDOWN_TIMEOUT_MS` | não | `10000` | Drenagem em SIGTERM/SIGINT |

Nenhuma credencial é versionada. `.env`, chaves, service accounts e Caddyfile real estão no
`.gitignore`.

## Endpoints

| Rota | O que faz |
| --- | --- |
| `GET /health/live` | O processo está vivo. Não consulta nada externo. |
| `GET /health/ready` | Configuração carregada + SQLite acessível + migrations aplicadas. `503` quando algo falta. |

Health é infraestrutura e fica **fora** de `/v1`. Toda API de produto futura nasce sob `/v1` —
o versionamento por URI já está configurado, então um `@Controller('sync')` responde em `/v1/sync`
sem ninguém precisar lembrar do prefixo.

A resposta de readiness é deliberadamente pobre (booleanos por verificação): não expõe caminho de
arquivo, variável de ambiente, credencial nem stack trace.

## Estrutura

```text
backend/
├── src/
│   ├── main.ts                  entrada: config → banco → HTTP, e graceful shutdown
│   ├── bootstrap/create-app.ts  montagem (usada igual em produção e nos testes)
│   ├── config/                  schema do ambiente + AppConfig
│   ├── database/                conexão SQLite, PRAGMAs, runner de migrations
│   ├── common/                  logger, request ID, log de acesso, envelope de erro
│   └── modules/health/          liveness e readiness
├── migrations/                  NNNN_nome.sql, versionadas
└── test/
```

Os módulos futuros (`auth` T16.1, `ai` T16.2, `sync` T16.3+, `backup` T16.4, `social` T17) entram
em `src/modules/`, no mesmo processo e no mesmo banco. Não haverá `auth-service`, `sync-service` etc.

## Banco

- Arquivo persistente — nunca `:memory:` em runtime, nunca só na camada do container.
- `PRAGMA journal_mode = WAL`
- `PRAGMA foreign_keys = ON`
- `PRAGMA busy_timeout` centralizado na configuração

Os três são verificados por teste, e o de `foreign_keys` prova o comportamento (uma FK órfã é
recusada), não só o valor reportado.

Migrations são `migrations/NNNN_nome.sql`, aplicadas em ordem, cada uma na mesma transação do seu
registro em `schema_migrations`. Rodar de novo não reaplica nada; alterar o conteúdo de uma
migration já aplicada é erro, não reaplicação silenciosa.

**O schema remoto não é cópia do Room.** O Room é otimizado para a execução local do app; o servidor
precisa de ownership, versionamento, idempotência e tombstones. Cada fase da T16 cria o que precisa.
Ver a [matriz de dados](../docs/architecture/data-classification-matrix.md).

## Produção (ainda não provisionada)

```text
Internet → Caddy (TLS / Let's Encrypt) → Spark Backend → SQLite
```

[`Caddyfile.example`](./Caddyfile.example) traz o exemplo. A T16.0 **não** provisiona VPS, domínio,
DNS nem certificado, e não cria nenhum recurso pago.

## Documentação

- [ADR-0001 — Spark Online Architecture](../docs/architecture/ADR-0001-spark-online-architecture.md)
- [Matriz de dados](../docs/architecture/data-classification-matrix.md)
- [Contrato de identidade](../docs/architecture/identity-contract.md)
- [Protocolo de sincronização](../docs/architecture/sync-protocol.md)
