# Spark Backend

Fronteira online do Spark. Monólito modular em NestJS sobre SQLite, pensado para rodar em **uma**
VPS com Docker Compose.

> **Estado (T16.2): fundação + identidade + Coach IA.** Existe verificação de Firebase ID Token
> (`GET /v1/auth/me`) e a fronteira com o Gemini (`POST /v1/ai/coach`). **Não** existe
> sincronização, backup, restore nem qualquer persistência de dado de treino — sob `/v1` há
> `auth` e `ai`, e a única tabela de produto é `ai_usage_daily` (contagem de uso, sem conteúdo).
> Ver [ADR-0001](../docs/architecture/ADR-0001-spark-online-architecture.md).

O Spark Android **não depende deste backend**. Sem ele — e sem internet — treino, execução,
histórico, templates e gamificação continuam funcionando normalmente sobre Room. O que passa a
depender dele, desde a T16.2, é o **Coach IA**: ele é uma capacidade online autenticada, e o
núcleo não é.

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
| Modelo | `@google/genai` | SDK server-side oficial da Google para a Gemini API. O SDK do Firebase AI Logic é de cliente e não entra aqui |

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
`FakeAuthTokenVerifier` e a do Coach usa `FakeAiProviderGateway` (ambos em `test/support/`, e só
lá), então o CI roda sem service account, sem chave do Gemini, sem conta Google e sem internet —
e sem gastar cota a cada commit.

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
| `GEMINI_API_KEY` | não | — | Credencial do Gemini. **Server-only.** Sem ela, `/v1/ai/coach` responde `503` e o núcleo do Spark segue intacto |
| `GEMINI_MODEL` | não | `gemini-3.6-flash` | O mesmo modelo que a T14 usava; trocar é decisão explícita |
| `AI_TIMEOUT_MS` | não | `30000` | Teto de uma chamada ao provider |
| `AI_TEMPERATURE` | não | `0.2` | Análise pede consistência, não criatividade |
| `AI_MAX_OUTPUT_TOKENS` | não | `2048` | Teto de saída |
| `AI_THINKING_LEVEL` | não | `MEDIUM` | `MINIMAL` \| `LOW` \| `MEDIUM` \| `HIGH` \| `OFF` |
| `AI_MAX_REQUESTS_PER_USER_DAY` | não | `50` | Quota diária por conta (UTC) |
| `AI_MAX_REQUESTS_GLOBAL_DAY` | não | `500` | Quota diária do servidor inteiro |
| `AI_MAX_CONCURRENT_REQUESTS_PER_USER` | não | `1` | Chamadas simultâneas ao provider por conta |

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
| `POST /v1/ai/coach` | **Bearer** | Coach IA: recebe contexto + intenção, decide prompt e modelo, chama o Gemini e devolve a resposta validada. |

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

### Coach IA (T16.2)

O Android **não fala mais com o Gemini**. Ele manda contexto e intenção; prompt, modelo e
credencial são decisão do servidor.

```text
POST /v1/ai/coach
Authorization: Bearer <Firebase ID Token>

{ "clientRequestId": "cli-<uuid>", "requestType": "ANALYZE_WORKOUT",
  "schemaVersion": 1, "context": { ... } }
        ↓  BearerAuthGuard        uid do token verificado, nunca do corpo
        ↓  contrato (zod)         requestType, schemaVersion, tetos de array/string/payload
        ↓  concorrência           1 chamada ativa por conta + dedupe por clientRequestId
        ↓  quota                  por conta e global, por dia (UTC)
        ↓  AiCoachPromptRegistry  prompt + promptVersion (o único lugar do Spark com prompt)
        ↓  AiProviderGateway  →  GeminiAiProviderGateway  →  Gemini (structured output)
        ↓  validação              estrutural + semântica
        ↓
{ "requestId": "...", "clientRequestId": "...", "schemaVersion": 1,
  "promptVersion": 1, "model": "...", "result": { ... } }
```

`requestType` aceita `ANALYZE_WORKOUT`, `GENERATE_WORKOUT`, `ADAPT_WORKOUT` e os quatro
`EXPLAIN_*`. Um endpoint só, e não cinco: o gateway do Android já trabalha com request
discriminado por tipo, e uma rota mantém uma fronteira, um guard, um lugar de quota e um de log.

| Situação | Resposta |
| --- | --- |
| Sem token / token inválido | `401 UNAUTHENTICATED` |
| Corpo fora do contrato | `400 INVALID_AI_REQUEST` |
| `schemaVersion` desconhecida | `400 UNSUPPORTED_SCHEMA_VERSION` |
| Corpo acima de 128 KB | `413 PAYLOAD_TOO_LARGE` |
| Chamada equivalente já em andamento | `409 AI_REQUEST_CONFLICT` |
| Quota da conta / do servidor | `429 AI_USER_QUOTA_EXCEEDED` / `429 AI_GLOBAL_QUOTA_EXCEEDED` |
| Resposta do modelo recusada na validação | `422 INVALID_AI_RESPONSE` |
| Provider indisponível ou sem credencial | `503 AI_PROVIDER_UNAVAILABLE` |
| Provider não respondeu a tempo | `504 AI_PROVIDER_TIMEOUT` |

**A validação é obrigatória e não configurável.** Structured output garante a forma; o validador
garante a semântica — `exerciseId` fora do contexto, id fora dos candidatos, substituto não
autorizado, valor atual que não bate com o treino enviado e `dataQuality` acima da evidência
recebida invalidam a resposta inteira. Não há *fuzzy matching* e nada é corrigido por aproximação.
O Android valida **de novo** contra o domínio atual: as duas camadas existem de propósito.

**Custo.** Uma requisição válida produz no máximo uma invocação do modelo. Não há retry
automático, crítica, reescrita nem provider alternativo. A quota é registrada **antes** da
chamada — uma tentativa que falhou no provider já pode ter custado, então ela conta; uma
requisição recusada antes do provider não consome nada.

**O que o servidor guarda.** Só `ai_usage_daily`: uid, dia (UTC), tipo de request, contagem e
tokens. Prompt, contexto, histórico de treino, resposta e texto do usuário **não são
persistidos** nem registrados em log — há teste que captura a saída real do logger e procura por
eles.

**O que o servidor não faz.** Ele não decide nem persiste alteração de treino: geração continua
*draft-first*, adaptação continua *confirmation-first*, `EXPLAIN_*` é read-only e a sessão
concluída é imutável. Não existe tabela de treino, sessão ou série aqui — e há teste que verifica
isso.

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
│   ├── modules/auth/            verificação de Firebase ID Token, guard e principal
│   └── modules/ai/              Coach IA: contrato, prompts, validação, quota e provider
├── migrations/                  NNNN_nome.sql, versionadas
└── test/
```

Os módulos futuros (`sync` T16.3+, `backup` T16.4, `social` T17) entram em `src/modules/`, no
mesmo processo e no mesmo banco. Não haverá `auth-service`, `sync-service` etc.

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
