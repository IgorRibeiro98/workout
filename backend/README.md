# Spark Backend

Fronteira online do Spark. Monólito modular em NestJS sobre SQLite, pensado para rodar em **uma**
VPS com Docker Compose.

> **Estado (T16.1): fundação + identidade.** Existe verificação de Firebase ID Token e
> `GET /v1/auth/me`. **Não** existe sincronização, backup, restore, proxy do Gemini nem qualquer
> persistência de usuário — sob `/v1` só há `auth`. Ver
> [ADR-0001](../docs/architecture/ADR-0001-spark-online-architecture.md).

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
| Identidade | `firebase-admin` | Verificação oficial de Firebase ID Token; exige Node >= 22, que já é o runtime |

**Por que não um ORM:** o backend tem hoje uma tabela e terá poucas. Prisma, TypeORM ou Drizzle
trariam geração de código, engine própria e um modelo de migrations opinativo — em troca de nada que
`better-sqlite3` não resolva com SQL direto. Se o schema crescer a ponto de justificar, é uma
decisão nova.

## Rodar

### Local

```bash
npm ci
cp .env.example .env      # nenhuma credencial é obrigatória para subir
npm run build
npm start
```

Sem `GOOGLE_APPLICATION_CREDENTIALS` o processo sobe normalmente e `/health/*` responde; só as
rotas autenticadas ficam indisponíveis (`503`).

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

Nenhum teste toca rede, Firebase, Gemini ou VPS. A suíte de autenticação usa
`FakeAuthTokenVerifier` (em `test/support/`), então o CI roda sem service account, sem conta Google
e sem internet.

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
| `GOOGLE_APPLICATION_CREDENTIALS` | não | — | **Caminho** do service account do Firebase Admin. Sem ele, rota autenticada responde `503` |
| `FIREBASE_PROJECT_ID` | não | — | Projeto esperado pelo verificador; normalmente vem do próprio arquivo de credencial |

Nenhuma credencial é versionada. `.env`, chaves, service accounts e Caddyfile real estão no
`.gitignore` e no `.dockerignore`, e há teste que varre a árvore procurando chave privada,
service account e API key.

A credencial do Admin entra por **caminho**, nunca por valor: o arquivo vive fora do repositório e
é montado somente-leitura no container. Passo a passo em
[`docs/FIREBASE_AUTH_SETUP.md`](../docs/FIREBASE_AUTH_SETUP.md).

## Endpoints

| Rota | Auth | O que faz |
| --- | --- | --- |
| `GET /health/live` | pública | O processo está vivo. Não consulta nada externo. |
| `GET /health/ready` | pública | Configuração carregada + SQLite acessível + migrations aplicadas. `503` quando algo falta. |
| `GET /v1/auth/me` | **Bearer** | Devolve `{ "uid": ... }` derivado do token verificado. |

Health é infraestrutura e fica **fora** de `/v1`. Toda API de produto futura nasce sob `/v1` —
o versionamento por URI já está configurado, então um `@Controller('sync')` responde em `/v1/sync`
sem ninguém precisar lembrar do prefixo.

A resposta de readiness é deliberadamente pobre (booleanos por verificação): não expõe caminho de
arquivo, variável de ambiente, credencial nem stack trace.

### Autenticação (T16.1)

```text
Authorization: Bearer <Firebase ID Token>
        ↓  BearerAuthGuard
        ↓  AuthTokenVerifier  →  FirebaseAuthTokenVerifier  →  Admin SDK verifyIdToken
        ↓
AuthenticatedPrincipal { uid, email?, provider? }
```

O backend é **verificador**, nunca emissor: não há usuário/senha, JWT do Spark, refresh token ou
cookie de sessão. O `uid` sai exclusivamente do token verificado — `?uid=`, `X-User-Id` e
`body.ownerUid` são ignorados, e há teste que tenta os três.

| Situação | Resposta |
| --- | --- |
| Sem `Authorization` | `401 UNAUTHENTICATED` |
| `Bearer` vazio ou malformado | `401 UNAUTHENTICATED` |
| Token inválido, expirado ou revogado | `401 UNAUTHENTICATED` |
| Credencial do Admin ausente/inválida, ou falha ao verificar | `503 AUTH_UNAVAILABLE` |
| Token válido | `200 { "uid": ... }` |

A distinção 401/503 importa: 401 diz ao Android "sua credencial não serve" — o que o levaria a
tratar a sessão como perdida —, e um problema do servidor não pode significar isso.

Nenhuma resposta de erro carrega stack trace, caminho de service account, token ou internals do
Firebase. O log registra `requestId`, resultado e, no máximo, um prefixo do `uid`; `Authorization`
e o token não passam por ele.

**Não existe modo de autenticação desligada.** Não há `AUTH_DISABLED`, e há teste que verifica que
nenhuma chave desse tipo entrou no schema de ambiente. Os testes trocam o verificador por um dublê
que vive apenas em `test/`.

## Estrutura

```text
backend/
├── src/
│   ├── main.ts                  entrada: config → banco → HTTP, e graceful shutdown
│   ├── bootstrap/create-app.ts  montagem (usada igual em produção e nos testes)
│   ├── config/                  schema do ambiente + AppConfig
│   ├── database/                conexão SQLite, PRAGMAs, runner de migrations
│   ├── common/                  logger, request ID, log de acesso, envelope de erro
│   ├── modules/health/          liveness e readiness
│   └── modules/auth/            verificação de Firebase ID Token, guard e principal
├── migrations/                  NNNN_nome.sql, versionadas
└── test/
```

Os módulos futuros (`ai` T16.2, `sync` T16.3+, `backup` T16.4, `social` T17) entram em
`src/modules/`, no mesmo processo e no mesmo banco. Não haverá `auth-service`, `sync-service` etc.

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
- [Configuração do Firebase Auth](../docs/FIREBASE_AUTH_SETUP.md)
