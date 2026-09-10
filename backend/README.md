# Spark Backend

Fronteira online do Spark. Monólito modular em NestJS sobre PostgreSQL / Neon (migrado do SQLite na T18.0/T18.0.1), pensado para rodar em
VPS com Docker Compose ou na nuvem com Neon Database.

> **Estado (T18.1): fundação + identidade + Coach IA + backup + restore + sync + tombstones +
> prontidão de produção com PostgreSQL hardened + Object Storage (GCS privado por ADC; provider
> local para desenvolvimento e CI).**

## Stack

| Papel | Escolha | Por quê |
| --- | --- | --- |
| Runtime | Node.js 22 LTS | LTS atual; `fetch` global dispensa cliente HTTP no healthcheck |
| Framework | NestJS 11 | Módulos, DI e ciclo de vida (shutdown hooks) prontos |
| Banco | PostgreSQL / Neon via `pg` | Pool de alta performance, advisory locks de sessão, transações ACID estritas e CAS atômico |
| Migrations | runner próprio (`src/database/postgres-migration-runner.ts`) | DDL transacional, locking por schema via `pg_advisory_lock`, checksum SHA-256 |
| Validação | `zod` | Valida o ambiente no startup, com fail-fast |
| Logging | `pino` | JSON estruturado, com `redact` para campos sensíveis |
| Testes | Jest + Supertest | Padrão do NestJS; offline e determinístico |
| Identidade | `firebase-admin` | Verificação oficial de Firebase ID Token; exige Node >= 22, que já é o runtime |
| Modelo | `@google/genai` | SDK server-side oficial da Google para a Gemini API. O SDK do Firebase AI Logic é de cliente e não entra aqui |
| Object Storage | `@google-cloud/storage` | SDK oficial do Google Cloud Storage, autenticado por ADC (T18.1). Bucket privado para fotos e documentos de backup; o provider `local` (disco) serve desenvolvimento, teste e CI |

**Persistência:** O backend opera exclusivamente com PostgreSQL como autoridade de runtime. O Android continua utilizando Room / SQLite localmente, mantendo o funcionamento offline integral do aplicativo.

**Bytes pesados (T18.1):** o PostgreSQL guarda metadata, índices, ownership, hashes e estado; as
fotos dos check-ins e o documento canônico de cada backup vivem no **Object Storage** — um bucket
privado do Google Cloud Storage em produção, o disco local em desenvolvimento e CI.

```text
                           ┌──────────────────────┐
                           │ PostgreSQL / Neon    │  metadata · ownership · hashes · estado
Android ──▶ Spark Backend ─┤                      │
                           └──────────┬───────────┘
                                      │ storageKey
                           ┌──────────▼───────────┐
                           │ Object Storage       │  social/checkins/…webp · backups/…json
                           │ (GCS privado | disco)│
                           └──────────────────────┘
```

O Android nunca recebe credencial do bucket e nunca fala com ele: todo byte passa pelo backend,
depois da autorização em SQL. Não existe URL pública, URL assinada nem ACL pública — há teste
estrutural.

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

Nenhum teste toca rede, Firebase, Gemini, GCS ou VPS. A suíte de autenticação usa
`FakeAuthTokenVerifier`, a do Coach usa `FakeAiProviderGateway` e a de Object Storage usa o
provider `local` ou o `InMemoryObjectStorageClient` (todos em `test/support/`, e só lá), então o CI
roda sem service account, sem chave do Gemini, sem projeto GCP, sem ADC e sem internet — e sem
gastar cota a cada commit.

O bucket real tem um smoke próprio, executado por uma pessoa com ADC válida, fora da suíte:

```bash
npm run build
OBJECT_STORAGE_PROVIDER=gcs GCS_BUCKET_NAME=spark-private-assets-prod npm run smoke:object-storage
```

Ele grava, lê, compara bytes e SHA-256, prova a recusa de sobrescrita, apaga e confirma a ausência
— tudo sob `_smoke/`, nunca em `social/` ou `backups/`, e nunca deixa objeto para trás.

## Configuração

Toda configuração vem do ambiente e é validada no startup. Configuração obrigatória inválida
**derruba o processo** em vez de subir pela metade. Ver [`.env.example`](./.env.example).

| Variável | Obrigatória | Default | Observação |
| --- | --- | --- | --- |
| `NODE_ENV` | não | `development` | `development` \| `test` \| `production` |
| `PORT` | não | `8080` | TLS é do Caddy, não deste processo |
| `DATABASE_URL` | **sim** | — | Connection string do PostgreSQL (pooled / Neon). Obrigatório, sem default. |
| `DATABASE_URL_DIRECT` | não | `DATABASE_URL` | Connection string direta para migrations e operações administrativas (Neon direct connection). Vazia = ausente (é como o Compose representa "não definido"). |
| `DATABASE_POOL_MIN` | não | `2` | Mínimo de conexões no pool pg |
| `DATABASE_POOL_MAX` | não | `10` | Máximo de conexões no pool pg |
| `DATABASE_STATEMENT_TIMEOUT_MS` | não | `30000` | Timeout por instrução SQL |
| `LOG_LEVEL` | não | `info` | |
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
| `BACKUP_RETENTION_COUNT` | não | `5` | Quantos snapshots guardar por conta. Cinco porque cada backup já basta sozinho para restaurar — guardar vários é janela de arrependimento, não redundância |
| `HTTP_REQUEST_TIMEOUT_MS` | não | `120000` | Teto de uma requisição. Não são 30 s: um backup de 4 MiB em rede móvel ruim não cabe |
| `HTTP_KEEP_ALIVE_TIMEOUT_MS` | não | `65000` | Precisa ser maior que o keep-alive do proxy, senão o Caddy reaproveita conexão fechada e o cliente vê 502 |
| `REQUIRE_FIREBASE_ADMIN` | não | `false` | `true` derruba o processo no startup sem credencial — falha visível em vez de 503 em toda requisição |
| `REQUIRE_GEMINI` | não | `false` | Idem para o Gemini. Default `false`: o Coach é opcional, identidade não é |
| `AI_ENABLED` | não | `true` | `false` desliga o Coach sem tocar em backup e sync |
| `SYNC_WRITE_ENABLED` | não | `true` | `false` pausa `POST /v1/sync/push`; o pull continua |
| `MAINTENANCE_MODE` | não | `false` | `true` faz todo `/v1` responder `503`; `/health/*` continua |
| `OBJECT_STORAGE_PROVIDER` | não | `local` | `local` (disco sob `SOCIAL_MEDIA_ROOT`) \| `gcs` (bucket privado, ADC). A escolha mora em `object-storage.factory.ts`, e só lá (T18.1) |
| `GCS_BUCKET_NAME` | com `gcs` | — | Obrigatório quando `OBJECT_STORAGE_PROVIDER=gcs`; a ausência derruba o startup. Nunca há bucket default no código. Vazio = ausente |
| `OBJECT_STORAGE_TIMEOUT_MS` | não | `30000` | Teto de uma requisição ao bucket; o retry do SDK é bounded por cima dele |
| `BACKUP_PAYLOAD_CLEANUP_INTERVAL_MS` | não | `21600000` | Coleta de objetos de backup órfãos (carência de 24 h). Longo: cada varredura é uma listagem paga |
| `SOCIAL_MEDIA_ROOT` | produção com `local` | derivado fora de produção | Raiz do provider `local`: `checkins/…` (mídia, layout de sempre) e `backups/…`. Não participa de nada com `gcs` |

Nenhuma credencial é versionada. `.env`, chaves, service accounts e Caddyfile real estão no
`.gitignore` e no `.dockerignore`, e há teste que varre a árvore procurando chave privada,
service account e API key.

O bucket do GCS **não tem credencial na aplicação**: o SDK autentica por Application Default
Credentials — a service account anexada ao serviço no Cloud Run (T18.2) ou o
`gcloud auth application-default login` do operador. Não existe `GCS_PRIVATE_KEY`,
`GCS_CLIENT_EMAIL` nem JSON de service account, e há teste estrutural sobre isso.

A credencial do Admin entra por **caminho**, nunca por valor: o arquivo vive fora do repositório e
é montado somente-leitura no container. Passo a passo em
[`docs/FIREBASE_AUTH_SETUP.md`](../docs/FIREBASE_AUTH_SETUP.md).

## Endpoints

| Rota | Auth | O que faz |
| --- | --- | --- |
| `GET /health/live` | pública | O processo está vivo. Não consulta nada externo. |
| `GET /health/ready` | pública | Configuração carregada + PostgreSQL acessível + migrations aplicadas. `503` quando algo falta. |
| `GET /v1/auth/me` | **Bearer** | Devolve `{ "uid": ... }` derivado do token verificado. |
| `POST /v1/ai/coach` | **Bearer** | Coach IA: recebe contexto + intenção, decide prompt e modelo, chama o Gemini e devolve a resposta validada. |
| `POST /v1/backups` | **Bearer** | Recebe um snapshot completo do estado pessoal, valida por inteiro, guarda em uma transação e devolve **metadata**. |
| `GET /v1/backups/latest` | **Bearer** | Metadata do backup mais recente **daquela conta**. `404` quando não há nenhum. |
| `GET /v1/backups` | **Bearer** | Metadata dos backups retidos daquela conta, na ordem do servidor (T16.5). |
| `GET /v1/backups/{id}` | **Bearer** | Metadata de um backup daquela conta (T16.5). |
| `GET /v1/backups/{id}/content` | **Bearer** | O snapshot canônico, verbatim, para o restore (T16.5). Baixar não marca, não consome e não apaga. |
| `POST /v1/sync/push` | **Bearer** | Mutações deste aparelho. Resultado **por item**: `APPLIED`, `STALE`, `REMOTE_DELETED`... (T16.6/T16.7). |
| `GET /v1/sync/pull` | **Bearer** | Mudanças da conta depois do cursor, em ordem de `serverSequence` (T16.6). |
| `GET /v1/sync/entities/{entityType}/{entitySyncId}` | **Bearer** | O estado **atual** de um agregado. **Somente leitura**: não gasta revision, não anexa mudança ao change log, não escreve no ledger e não move cursor (T16.7.1). |
| `GET /v1/social/me` | **Bearer** | O perfil social da conta. `{ "enabled": false }` — e **não** `404` — para quem nunca ativou. Ler não cria nada (T17.0). |
| `POST /v1/social/me/activate` | **Bearer** | Cria a identidade social (`socialId`, `friendCode`, privacidade padrão). **Idempotente**: a segunda chamada devolve o mesmo perfil (T17.0). |
| `PATCH /v1/social/me` | **Bearer** | Altera o nome social. Identidade **não** muda por aqui (T17.0). |
| `PATCH /v1/social/me/privacy` | **Bearer** | Altera privacidade, parcialmente (T17.0). |
| `POST /v1/social/me/disable` | **Bearer** | Desativa. Não apaga conta, backup, sync, treino nem histórico (T17.0). |
| `POST /v1/social/me/enable` | **Bearer** | Reativa, com o **mesmo** `socialId` e o **mesmo** `friendCode` (T17.0). |
| `POST /v1/social/friends/lookup` | **Bearer** | Resolve um `friendCode` **exato** → preview mínimo, `SELF` ou `NOT_FOUND`. Teto próprio (20/min). O código vai no corpo, nunca na URL (T17.1). |
| `POST /v1/social/friend-requests` | **Bearer** | Envia um pedido para um `socialId`. Idempotente; pedido cruzado vira amizade na mesma transação. Teto próprio (15/min) (T17.1). |
| `GET /v1/social/friend-requests/incoming` | **Bearer** | Pedidos recebidos, pendentes, mais recentes primeiro (T17.1). |
| `GET /v1/social/friend-requests/outgoing` | **Bearer** | Pedidos enviados, pendentes (T17.1). |
| `POST /v1/social/friend-requests/:id/accept` | **Bearer** | Só o destinatário. Transacional: marca `ACCEPTED` e cria a amizade juntos (T17.1). |
| `POST /v1/social/friend-requests/:id/reject` | **Bearer** | Só o destinatário. Idempotente (T17.1). |
| `POST /v1/social/friend-requests/:id/cancel` | **Bearer** | Só quem enviou. Idempotente (T17.1). |
| `GET /v1/social/friends` | **Bearer** | Meus amigos: `socialId`, `displayName`, `friendsSince`. Sem `friendCode`, sem uid (T17.1). |
| `POST /v1/social/friends/remove` | **Bearer** | Desfaz a amizade. Qualquer um do par; não bloqueia e não apaga mais nada (T17.1). |

Desde a T16.8, toda rota autenticada tem um teto por conta (`API_RATE_LIMITED`, 429), o backup tem
tetos próprios de escrita e leitura (`BACKUP_RATE_LIMITED`), e as respostas de `/v1` saem com
`Cache-Control: no-store` e `X-Content-Type-Options: nosniff`. **CORS continua fechado**: um app
Android nativo não precisa dele, e liberar `*` por hábito é superfície de graça.

**Nenhuma rota de dado pessoal tem parâmetro de usuário.** O dono sai do token verificado, e um
`ownerUid` no corpo ou na query string não influencia a resposta. Dado de outra conta é
indistinguível de inexistente: `404`, nunca `403`.

Health é infraestrutura e fica **fora** de `/v1`. Toda API de produto nasce sob `/v1` — o
versionamento por URI está configurado, então um `@Controller('sync')` responde em `/v1/sync` sem
ninguém precisar lembrar do prefixo.

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
isso. O backup da T16.4 não muda essa regra: ele guarda o snapshot como payload opaco, e não
transforma treino em tabela consultável.

### Backup (T16.4)

```text
POST /v1/backups          snapshot completo, autocontido, imutável
GET  /v1/backups/latest   metadata — nunca o conteúdo
```

O caminho é o mesmo do Coach até o guard: `Bearer <Firebase ID Token>` → `AuthenticatedPrincipal`.
Depois dele:

```text
teto de corpo → forma canônica → envelope → item a item (registry fechado)
→ identidade portátil → duplicidade → relações → hash → idempotência → transação → retenção
```

Cinco decisões que valem ser sabidas antes de mexer aqui:

1. **O dono sai do token.** O contrato não tem `ownerUid`, e campo desconhecido no corpo é
   recusado. Query string com `uid` não influencia nada.
2. **Ou tudo, ou nada.** A validação é integral e acontece **antes** de qualquer escrita; snapshot
   e itens entram na mesma transação. Não existe caminho que grave 97 itens e recuse 3.
3. **Idempotência é do banco.** `UNIQUE (owner_uid, client_backup_id)`. Mesmo id + mesmo conteúdo
   devolve `200` com o backup existente; mesmo id + conteúdo diferente é `409`.
4. **O hash é calculado aqui.** SHA-256 sobre a forma canônica do **texto recebido**, com tokens
   escalares copiados verbatim — é o que faz Kotlin e TypeScript fecharem o mesmo valor sem que um
   precise imitar o formatador de ponto flutuante do outro. Hash declarado pelo cliente não existe
   no contrato.
5. **Retenção vem depois do commit.** O backup novo é gravado e confirmado antes de qualquer
   limpeza; uma limpeza que falhe deixa backup a mais, nunca a menos.

Desde a **T18.1** o documento canônico não mora mais no PostgreSQL:

```text
validar → idempotência → backupId + storageKey (do servidor)
       → objeto no Object Storage (create-only, CRC32C do SDK)
       → transação: backup_snapshots (metadata, hashes, storage_key) + backup_items (identidade, hash)
       → retenção: linhas antigas saem no commit; os objetos delas, depois
```

- `backup_snapshots.payload` e `backup_items.payload` ficam `NULL` em todo backup novo — há teste
  estrutural e de comportamento. Os anteriores continuam válidos e restauráveis a partir da coluna
  até o migrador (`npm run migrate:backup-payloads`) movê-los, um a um, verificando o hash antes de
  esvaziar o banco;
- `GET /v1/backups/{id}/content` confere `size_bytes` e `payload_hash` contra o objeto **antes** de
  devolver: objeto ausente, truncado ou alterado é `410 BACKUP_CONTENT_UNAVAILABLE`, e bucket fora
  do ar é `503 BACKUP_STORAGE_UNAVAILABLE` — que o Android trata como "tente de novo";
- objeto sem linha (processo morto entre o upload e o commit; delete de retenção que falhou) é
  órfão, e `BackupPayloadCleaner` o recolhe depois de 24 h de carência — nunca antes.

Log: `requestId`, prefixo do uid, `clientBackupId`, `itemCount`, `sizeBytes`, duração e status.
Corpo, payload, nome de treino, nota, medida e token **não** aparecem — e há teste que envia uma
fixture com marcas reconhecíveis e varre a saída do logger procurando por elas.

## Estrutura

```text
backend/
├── src/
│   ├── main.ts                  entrada: config → banco → HTTP, e graceful shutdown
│   ├── bootstrap/create-app.ts  montagem (usada igual em produção e nos testes)
│   ├── config/                  schema do ambiente + AppConfig
│   ├── database/                conexão PostgreSQL (Pool), health check, runner de migrations
│   ├── object-storage/          fronteira neutra de bytes: provider local (disco) e GCS (ADC);
│   │                             a factory é o único ponto que escolhe entre os dois (T18.1)
│   ├── cli/                     comandos operacionais: reconciliação de DR, migrador de payloads
│   │                             legados, smoke do Object Storage real
│   ├── common/                  logger, request ID, log de acesso, envelope de erro
│   ├── modules/health/          liveness e readiness
│   ├── modules/auth/            verificação de Firebase ID Token, guard e principal
│   ├── modules/ai/              Coach IA: contrato, prompts, validação, quota e provider
│   ├── modules/backup/          Backup: contrato, registry, forma canônica, validação, retenção
│   ├── modules/sync/            Sync incremental: contrato, política, validação, change log
│   └── modules/social/          Social: identidade pública, privacidade, política de acesso
│                                 e o grafo (friendship.*): pedidos e amizade bilateral
├── migrations/                  NNNN_nome.sql, versionadas (PostgreSQL)
└── test/
```

Todos no mesmo processo e no mesmo banco. Não há `auth-service`, `sync-service` etc.

Estar no mesmo processo **não** os torna acoplados: `SocialModule` não importa `BackupModule`,
`SyncModule` nem `AiModule`, e há teste que varre os imports do módulo. A fronteira entre o domínio
privado e o social é a `SocialProjection` (`modules/social/social.projection.ts`), não a
proximidade dos arquivos. Fotos e documentos de backup vivem no **mesmo** bucket sem que os dois
domínios se conheçam: cada um tem a própria fronteira (`SocialMediaStore`, `BackupPayloadStore`)
sobre a mesma camada neutra (`ObjectStorageClient`).

O contrato de backup é compartilhado com o Android em
[`contracts/backup/v1/`](../contracts/backup/v1/README.md): o README é a definição, e as fixtures
de lá são lidas pelos testes dos **dois** lados. Mudar o formato de um lado só quebra os dois
testes juntos, que é o objetivo.

## Banco

- PostgreSQL 17 / Neon como autoridade server-side exclusiva de persistência remota.
- Conexões gerenciadas via pool de conexões (`pg.Pool`) com dimensionamento configurável (`DATABASE_POOL_MIN`, `DATABASE_POOL_MAX`).
- Timeouts explícitos centralizados (`DATABASE_STATEMENT_TIMEOUT_MS`, `statement_timeout`, `lock_timeout`).
- Concorrência crítica protegida por advisory locks transacionais (`pg_advisory_xact_lock`) e CAS atômico.
- Migrations versionadas em `migrations/postgres/` executadas com lock de sessão (`pg_advisory_lock`) e transação DDL atômica.

Migrations são `migrations/postgres/NNNN_nome.sql`, aplicadas em ordem, cada uma na mesma transação do seu
registro em `schema_migrations`. Rodar de novo não reaplica nada; alterar o conteúdo de uma
migration já aplicada é erro, não reaplicação silenciosa.

**O schema remoto não é cópia do Room.** O Room é otimizado para a execução local do app; o servidor
precisa de ownership, versionamento, idempotência e tombstones. Cada fase da T16 cria o que precisa.
Ver a [matriz de dados](../docs/architecture/data-classification-matrix.md).

As tabelas de hoje: `server_metadata` (estado técnico), `ai_usage_daily` (contagem de uso do Coach,
sem conteúdo), `backup_snapshots` + `backup_items` (T16.4/T16.5; desde a T18.1 só metadata,
hashes e `storage_key` — o documento vive no Object Storage), `sync_entities` + `sync_changes` +
`sync_mutations` (T16.6/T16.7) e, desde a T17.0, `social_profiles` + `social_privacy_settings` +
`friend_requests` + `friendships`.

As de sync guardam o payload do agregado como texto — o servidor **não** desmonta treino em colunas
consultáveis, porque isso o tornaria uma segunda autoridade operacional sobre o dado que o Room já
possui. As de backup deixaram de guardá-lo (T18.1): `backup_snapshots.payload` e
`backup_items.payload` continuam existindo só para os snapshots anteriores, até o migrador
esvaziá-las.

As duas de social são a exceção deliberada, e ela é delimitada: o perfil social **é** do servidor
(identidade pública não pode ser decidida por um aparelho offline), e por isso mesmo nenhum dado de
treino entra ali — nem XP, nem streak, nem contagem, nem peso, nem PR. Ver
[`social-domain.md`](../docs/architecture/social-domain.md).

## Produção (T16.8 / T18.0)

```text
Internet ──443──▶ Caddy (TLS automático) ──rede interna──▶ Spark Backend ──▶ PostgreSQL
                                                               │                │
                                                               ▼            ops/backup.sh ──▶ pg_dump ──▶ off-site
                                                        Object Storage                        (criptografado)
                                                   (GCS privado, ou o volume
                                                    de mídia com o provider local)
```

Com `OBJECT_STORAGE_PROVIDER=gcs`, fotos e documentos de backup **não vivem no sistema de
arquivos**: o volume de mídia e o `SPARK_MEDIA_DIR` do `ops/backup.sh` deixam de ter conteúdo, e a
durabilidade desses objetos é a do bucket. Ver
[`docs/operations/BACKUP_AND_RESTORE.md`](../docs/operations/BACKUP_AND_RESTORE.md).

- [`docker-compose.prod.yml`](./docker-compose.prod.yml) — o backend **não publica porta nenhuma**;
  quem escuta na internet é o Caddy. Conexão com PostgreSQL via `DATABASE_URL`, rotação de log, limites de
  recurso e credencial montada somente-leitura.
- [`Caddyfile.prod`](./Caddyfile.prod) — o domínio vem de `{$SPARK_DOMAIN}`, porque domínio real é
  configuração operacional e não código.
- [`../ops/`](../ops/) — snapshot consistente (`pg_dump`, T18.0.2), backup off-site, restauração
  (`pg_restore` em transação única), ensaio de restauração num banco descartável, verificação
  operacional, deploy com rollback e unidades systemd.

> **Nada disso foi verificado em VPS real.** Não há domínio, DNS, certificado, PostgreSQL
> gerenciado nem storage contratado: o que existe é `CODE READY`, exercitado no CI com Docker e um
> PostgreSQL de serviço. Ver
> [`docs/operations/PRODUCTION_DEPLOYMENT.md`](../docs/operations/PRODUCTION_DEPLOYMENT.md).

> **Backup do usuário ≠ backup do servidor.** A T16.4 protege contra a perda do **aparelho**: o
> dado do usuário passa a existir também aqui. A **T16.8/T18.0** protege contra a perda desta VPS: os
> dados do PostgreSQL passam a existir fora dela, criptografados. Restaurar o servidor **não**
> restaura o Room de nenhum aparelho. Ver
> [`docs/operations/BACKUP_AND_RESTORE.md`](../docs/operations/BACKUP_AND_RESTORE.md).

### Interruptores de operação

| Variável | Efeito | Núcleo do Spark |
| --- | --- | --- |
| `AI_ENABLED=false` | `/v1/ai/coach` → `503 AI_PROVIDER_UNAVAILABLE`, antes de quota e provider | intacto |
| `SYNC_WRITE_ENABLED=false` | `POST /v1/sync/push` → `503 SYNC_WRITE_DISABLED`; o pull continua | intacto; a Outbox fica pendente |
| `MAINTENANCE_MODE=true` | Todo `/v1` → `503`; `/health/*` continua verde | intacto |

Os três respondem `503`, que o Android já trata como indisponibilidade recuperável desde a T16.2 —
desligar uma capacidade no servidor não exige publicar APK novo.

## Documentação

- [ADR-0001 — Spark Online Architecture](../docs/architecture/ADR-0001-spark-online-architecture.md)
- [Matriz de dados](../docs/architecture/data-classification-matrix.md)
- [Contrato de identidade](../docs/architecture/identity-contract.md)
- [Protocolo de sincronização](../docs/architecture/sync-protocol.md)
- [Contrato de backup v1](../contracts/backup/v1/README.md)
- [Domínio social](../docs/architecture/social-domain.md)
- [Grafo social: amizade, pedidos e QR Code](../docs/architecture/friendship-contract.md)
- [Contrato social v1](../contracts/social/v1/README.md)
- [Configuração do Firebase Auth](../docs/FIREBASE_AUTH_SETUP.md)
- [Operações (T16.8)](../docs/operations/) — implantação, backup/restauração, recuperação de
  desastre, segurança e runbook
