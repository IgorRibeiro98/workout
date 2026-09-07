# ADR-0001 — Spark Online Architecture

- **Status:** aceito e **em vigor**
- **Data da decisão:** 2026-09-06 (T16.0 — Fundação do Spark Backend)
- **Estado revisado em:** 2026-09-07 (T16.7.1 — fechamento técnico da sincronização)
- **Substitui:** nada. Complementa `ARCHITECTURE.md`, que continua sendo a autoridade sobre o app Android.

> **Como ler este documento.** A **decisão** (contexto, autoridades, invariantes, política de custo)
> é de 2026-09-06 e não mudou. A seção *Estado: implementado x planejado* descreve o que existe
> **hoje** e é atualizada a cada fase — a T16.7.1 a reescreveu porque ela ainda dizia que o sync
> não existia e que o Coach falava com o Firebase, duas coisas que deixaram de ser verdade nas
> T16.6 e T16.2. Quando este documento e o código divergirem, o código vence e o documento é
> corrigido.

## Contexto

Até a T15 o Spark era inteiramente local-first: Room e DataStore são as autoridades, e a única
fronteira online era o Firebase AI Logic do Coach IA (T14), que é opcional e não participa de
nenhum caminho crítico.

A partir da T16 o Spark precisa de estado de conta que atravesse dispositivos: backup, restore,
sync e, mais tarde, features sociais. Isso exige uma fronteira online **de verdade** — e exige que
ela não destrua a propriedade que torna o app utilizável hoje.

## Decisão

Adotar três autoridades distintas, com responsabilidades que não se sobrepõem.

```text
                        FIREBASE
                        └── Auth  ──────────────┐
                                                │ Firebase ID Token  [T16.1]
                                                ▼
┌─────────────────────────┐          ┌─────────────────────────────────┐
│ Spark Android           │          │ Spark Backend / VPS             │
│                         │          │                                 │
│ UI                      │          │ API HTTPS  (/v1)                │
│ Domain                  │   push   │ Auth boundary       [T16.1]     │
│ Room  ◄── autoridade    │◄────────►│ Gemini Gateway      [T16.2]     │
│ DataStore   operacional │   pull   │ Sync   [T16.6 / T16.7 / T16.7.1]│
│ Outbox        [T16.3]   │          │ Backup [T16.4] / Restore [T16.5]│
│ Conflitos     [T16.7]   │          │ Social              [T17]       │
│ AiCoachGateway          │          │ SQLite                          │
└─────────────────────────┘          └─────────────────────────────────┘
```

### Autoridades

| Autoridade | Responsabilidade | O que ela **não** é |
| --- | --- | --- |
| **Android / Room + DataStore** | Autoridade operacional local. Treino, execução, histórico, templates, catálogo, gamificação, preferências. | Não é réplica do servidor. |
| **Spark Backend** | Estado remoto da conta e convergência entre dispositivos. | Não é a autoridade de execução. Não decide se um treino pode começar. |
| **Firebase** | Identidade e autenticação (`Firebase Auth`). | Não é banco de dados do Spark. Nada de Firestore, RTDB, Storage ou Cloud Functions. |
| **Gemini** | Serviço probabilístico consumido pelo Coach IA. | Nunca é autoridade de domínio. Saída do modelo é entrada não confiável. |

### Direção de fluxo obrigatória

O fluxo correto, implementado na T16.6:

```text
ação do usuário → domínio → Room → outbox → sync → Spark Backend
Spark Backend → sync → validação → Room → UI observa Room
```

O fluxo proibido, em qualquer fase da T16:

```text
UI → API → servidor → "se o servidor responder, o app funciona"
```

A UI observa Room. Sempre. Um dado que chega do servidor entra pelo sync, é validado e é escrito
no Room; a UI reage à mudança do Room, não à resposta HTTP.

### Invariantes que a T16 inteira precisa preservar

1. **Conta é opcional.** Sem conta, o núcleo funciona por completo: treino, execução, histórico,
   templates, catálogo e gamificação. Nenhuma fase da T16 pode introduzir requisito de login para
   iniciar ou concluir um treino.
2. **Indisponibilidade não quebra o app.** Sem internet, sem VPS, sem Firebase e sem Gemini, o
   Spark continua funcionando exatamente como hoje.
3. **Histórico concluído é imutável.** Uma `WorkoutSession` `COMPLETED` não é sobrescrevível por
   sincronização. Conteúdo histórico divergente para o mesmo `syncId` é **conflito de integridade**,
   nunca *last write wins*.
4. **Uma autoridade por coisa.** O backend não vira segunda fonte operacional de verdade.
5. **Ownership é decidido pelo servidor.** O `uid` vem do token verificado, nunca do payload.

## Política de custo

O Spark é um app privado para um grupo pequeno. A infraestrutura é deliberadamente mínima e de
custo fixo:

```text
1 VPS + Docker Compose + 1 aplicação backend + 1 SQLite + Caddy (TLS)
```

Ficam **fora** por decisão, não por falta de tempo: Kubernetes, Redis, Kafka, RabbitMQ, PostgreSQL
gerenciado, Cloud SQL, RDS, Cloud Functions, Cloud Run, load balancer gerenciado, fila gerenciada,
object storage obrigatório, service mesh e microservices.

Se uma necessidade futura parecer exigir qualquer um desses itens, a regra é **documentar e parar**,
não introduzir.

### Monólito modular

O backend nasce como monólito modular: `auth`, `ai`, `sync`, `backup` e `social` são módulos dentro
do mesmo processo e do mesmo banco. Não existem `auth-service`, `sync-service` etc. como processos
separados — não há necessidade operacional que justifique o custo.

## Estado: implementado x planejado

### IMPLEMENTADO EM T16.0

- Fundação do backend (NestJS, monólito modular) em `backend/`.
- Configuração validada com fail-fast no startup.
- SQLite com arquivo persistente, `journal_mode=WAL`, `foreign_keys=ON`, `busy_timeout` centralizado.
- Migrations versionadas, transacionais e idempotentes.
- `GET /health/live` e `GET /health/ready`.
- Request ID, envelope de erro único, logging estruturado sem segredo.
- Graceful shutdown (SIGTERM/SIGINT).
- Dockerfile multi-stage não-root, Docker Compose com volume persistente, `Caddyfile.example`.
- Versionamento de API configurado: toda API de produto futura nasce sob `/v1`.
- Contratos arquiteturais documentados (este ADR, matriz de dados, identidade, protocolo de sync).

### IMPLEMENTADO EM T16.1

- Conta **opcional** no Android: Firebase Authentication + Sign in with Google via Credential
  Manager, atrás de `AuthGateway` / `FirebaseAuthGateway`.
- Estado de autenticação explícito e derivado do Firebase (`SignedOut`, `SigningIn`, `SignedIn`,
  `SigningOut`, `Error`); sessão existente é restaurada sozinha, sem abrir seletor de contas.
- Área de Conta Spark dentro do Perfil, com login por ação explícita e logout que limpa também o
  estado de credencial do Credential Manager.
- `AuthTokenProvider` + `SparkAuthInterceptor` + `SparkBackendClient`: Firebase ID Token obtido sob
  demanda, enviado como `Authorization: Bearer`, nunca persistido e nunca registrado em log.
- Backend: Firebase Admin SDK, `AuthTokenVerifier` / `FirebaseAuthTokenVerifier`, `BearerAuthGuard`,
  `AuthenticatedPrincipal` e `GET /v1/auth/me`.
- Credencial do Admin SDK por caminho externo (`GOOGLE_APPLICATION_CREDENTIALS`), fora do Git e
  fora da imagem. Sem ela, rota autenticada responde 503 — nunca 200 sem verificação.
- Testes offline dos dois lados: nenhum exige Firebase real, conta Google real, service account
  ou rede.

O que a T16.1 **não** fez, deliberadamente: nenhum dado pessoal é enviado ou baixado, entrar/sair
não tocam em Room ou DataStore, trocar de conta não reassocia nada, e não existe tabela de usuários
no servidor.

### IMPLEMENTADO EM T16.2

- **O Coach IA deixou de falar com o Firebase.** `POST /v1/ai/coach` no Spark Backend: o servidor
  decide prompt e modelo, chama o Gemini, valida a resposta e devolve o resultado já validado.
- No Android sobrou **um** gateway, `SparkBackendAiCoachGateway`, atrás da mesma interface
  `AiCoachGateway`. `FirebaseAiCoachGateway` **não existe mais**, e há teste estrutural que falha
  se o Firebase AI Logic voltar ao aplicativo.
- A credencial do Gemini é **server-only**: ela não entra no APK, no `BuildConfig`, em resource,
  em DataStore, no Git nem em teste. Nome de modelo também não existe no app.
- Quota diária por conta e global, uma chamada ativa por conta, dedupe por `clientRequestId`, sem
  retry automático em nenhum dos dois lados.
- Consequência aceita e registrada: uma chamada **nova** ao modelo passou a exigir Conta Spark.
  Isso não torna a conta necessária para usar o Spark — torna-a necessária só para o que depende
  do servidor.

### IMPLEMENTADO EM T16.3

- **Identidade global dos dados** no Android: `syncId` (UUID v4) nas raízes dos agregados pessoais,
  gerado offline na criação e imutável. Room `version = 31`, com migração aditiva e índice único.
- **Outbox transacional** (`sync_outbox`): a intenção de sincronizar é gravada na **mesma**
  transação Room da alteração de domínio, atrás de `SyncMutationCoordinator.mutate { }`.
- `deviceId` (DataStore) e `clientMutationId` por mutação.
- Nada disso envia dado: com a nuvem desligada — o padrão — **nenhuma entrada é produzida**.

### IMPLEMENTADO EM T16.4

- **Adoção explícita** do conjunto de dados local por uma Conta Spark: `cloud_data_binding` no Room
  (`version = 32`). Login, sozinho, continua não adotando nada; trocar de conta não transfere.
- **Snapshot completo e autocontido** do estado pessoal, com registry fechado de nove agregados,
  identidades portáteis e `entitySchemaVersion` por agregado.
- **Tentativa durável e imutável** (`backup_attempts`): `clientBackupId` estável, payload congelado,
  corte da Outbox capturado na mesma transação.
- **`POST /v1/backups`** e **`GET /v1/backups/latest`**, com ownership derivado do token, validação
  integral antes de qualquer escrita, transação única para snapshot + itens, idempotência por
  `(ownerUid, clientBackupId)`, tetos de tamanho e retenção configurável.
- **Contrato compartilhado** em `contracts/backup/v1/`: README, JSON de fixtures e a forma canônica
  cujo SHA-256 os dois lados reproduzem — com teste em Kotlin e em TypeScript fixando os mesmos
  hashes.

O que a T16.4 **não** fez, deliberadamente: restore, download do conteúdo do backup, sync
incremental, pull, convergência multi-device, conflito, tombstone remoto, backup automático em
background e backup off-site da VPS.

### IMPLEMENTADO — T16.5 (restore seguro)

- **Leitura no servidor**: `GET /v1/backups` (metadata dos backups retidos daquela conta, na ordem
  do servidor), `GET /v1/backups/{id}` e `GET /v1/backups/{id}/content` (o snapshot canônico,
  verbatim). Ownership derivado do token; backup de outra conta é indistinguível de inexistente.
  As três são **read-only**: baixar não marca, não consome e não apaga o snapshot.
- **`backup_snapshots.payload`** (migration `0004`): o servidor passou a guardar o documento exato
  que `payload_hash` resume, porque remontá-lo a partir das colunas seria uma segunda
  canonicalização — capaz de divergir da primeira no ponto em que a divergência aparece como
  "backup corrompido" no aparelho de um usuário.
- **No Android**: download em streaming para arquivo privado, SHA-256 conferido contra a metadata,
  validação integral (versão, schema, semântica, referências), `RestorePlan`, preview com contagens
  reais, confirmação explícita (dupla quando há dado local), snapshot de segurança local,
  substituição transacional do dataset, vínculo com a conta no mesmo commit, Outbox zerada no
  commit, fase das preferências e recuperação determinística na abertura do app.
- **O contrato é o mesmo do backup.** Não existe um formato de restore ao lado do formato de
  backup: o documento validado é exatamente o que `BackupSnapshotBuilder` produziu.

O que a T16.5 **não** fez, deliberadamente: merge, sync incremental, push/pull, cursor,
convergência multi-device ao vivo, conflito, tombstone remoto, rebind de dataset entre contas,
mídia e backup automático.

### IMPLEMENTADO — T16.6 (sync incremental multi-device)

- **`POST /v1/sync/push`** e **`GET /v1/sync/pull`**: dois aparelhos da mesma Conta Spark convergem
  sozinhos, por mudança e não por snapshot.
- Estado remoto por agregado (`sync_entities`) com `serverRevision`; ledger de idempotência por
  `clientMutationId` (`sync_mutations`); change log append-only com sequência global
  (`sync_changes`).
- No Android: cursor durável por conta (`sync_cursor`), revision conhecida por agregado
  (`sync_entity_metadata`), apply remoto transacional fora do coordenador de mutações
  (`SyncRemoteApplier`) e **um** trabalho único de `WorkManager` com restrição de rede. Room
  `version = 34`.
- **`revision` decide, relógio não.** Escrita stale é `STALE`, e um `STALE` **não** atualiza a
  revision conhecida — fazer isso seria *last write wins* com outro nome.
- Conflito é **detectado, isolado e preservado** (`sync_conflicts` + Outbox `BLOCKED`). A T16.6 não
  resolvia nenhum.

### IMPLEMENTADO — T16.7 (conflitos, deletes e tombstones)

- **Resolução explícita de conflito**: o usuário escolhe entre a versão deste aparelho e a da
  nuvem, na Conta Spark dentro do Perfil. A escolha é durável e idempotente (escrita condicional
  no banco, `sync_conflicts.status`). Room `version = 35`.
- **Exclusão propaga como mudança versionada**: `DELETE` sobe da Outbox, o servidor cria tombstone
  (`sync_entities.deleted`, migration `0006_sync_tombstones.sql`), gasta uma `revision` e anexa a
  mudança ao change log.
- **Tombstone impede ressurreição**: `UPSERT` contra tombstone é `REMOTE_DELETED`, sempre, e a
  garantia é do banco (`AND sync_entities.deleted = 0`). Recriar produz `syncId` novo.
- **Política por agregado sem `default`**: `SyncEntityPolicies` (app) e `SyncEntityPolicyRegistry`
  (`sync.policy.ts`). `LAST_WRITE_WINS_ALLOWED` existe como valor e **nenhum agregado o usa**.
- `CURSOR_EXPIRED` com orientação de rebaseline — nunca `cursor = 0` em silêncio.

### IMPLEMENTADO — T16.7.1 (fechamento técnico da sincronização)

- **`GET /v1/sync/entities/{entityType}/{entitySyncId}`**: leitura **somente leitura** do estado
  atual de um agregado, autenticada, com ownership derivado do token. Ela não gasta revision, não
  anexa mudança ao change log, não escreve no ledger e não move cursor.
- **"Usar a versão da nuvem" confirma antes de sobrescrever.** A cópia remota guardada em um
  conflito foi validada no passado; se a revision remota tiver avançado, o conflito é **atualizado**
  e o usuário decide de novo — a revision antiga não é aplicada, e a decisão anterior não é
  reaproveitada.
- **A conta é revalidada depois da resposta**, não antes da requisição: uma resposta autenticada
  como B nunca entra no dataset de A.
- Manter o local, excluir mesmo assim e confirmar uma exclusão que a nuvem já fez **continuam
  funcionando offline** — nenhuma delas grava conteúdo vindo do servidor.
- **CI do Android no GitHub** (`.github/workflows/android.yml`): `:app:testDebugUnitTest` e
  `:app:assembleDebug` em checkout limpo, sem Firebase real, sem Gemini e sem VPS.

### PLANEJADO — ainda **não** existe

- **T16.8** — Hardening, segurança, backup do servidor e observabilidade.
- **T17** — Amigos, convites, desafios e social.

Sob `/v1` existem hoje `auth`, `ai`, `backups` e `sync`.

## Backup dos dados do usuário ≠ backup do servidor

Duas coisas diferentes, e confundi-las daria uma falsa sensação de segurança:

```text
T16.4  protege contra perder o APARELHO
       o dado do usuário está no Spark Backend

T16.8  protege contra perder a VPS
       o SQLite do servidor está em outro lugar        ← NÃO EXISTE
```

Hoje, um `docker compose down -v` na VPS destrói os backups de todo mundo. Isso é uma pendência
registrada da fase de hardening, não um detalhe operacional esquecido — e a UI do app não promete
o contrário.

## Coach IA — migração executada na T16.2

O estado atual, e o único:

```text
AiCoachGateway                     ← a interface não mudou
└── SparkBackendAiCoachGateway     ← o gateway do app

Android  →  Spark Backend  →  Gemini
```

`FirebaseAiCoachGateway` **não existe mais**, e `AiCoachSecurityConfigTest` falha se o Firebase AI
Logic voltar ao aplicativo. Quem instala o App Check hoje é o `FirebaseAuthGateway`: ele atesta o
app perante o Firebase, que serve à **autenticação** — responsabilidade diferente da identidade de
usuário, e diferente do Coach.

O motivo pelo qual a migração valeu a pena, registrado porque ele continua sendo a razão de o
desenho ser este: a chave do Gemini e a política de uso viviam no cliente. Com o backend no
caminho, cota, rate limit e prompt são controlados no servidor — que é também onde o `uid`
autenticado existe.

> **Registro histórico.** Até a T16.1 este ADR previa a migração como futura e descrevia
> `FirebaseAiCoachGateway` como "atual, em uso". Isso era verdade quando foi escrito e deixou de
> ser na T16.2; o texto foi corrigido na T16.7.1 porque um documento vigente que descreve um
> gateway inexistente como atual convida a recriá-lo.

## Consequências

**Positivas**

- O app continua utilizável por completo sem backend, e isso é testável.
- Custo previsível e operação simples: um `docker compose up -d`.
- A fronteira de autenticação existe como desenho antes de existir como código, então a T16.1 não
  precisa reabrir decisões de arquitetura.
- O schema remoto nasce livre do formato do Room, que é otimizado para outra coisa.

**Negativas / custos aceitos**

- SQLite em uma VPS não escala horizontalmente. Aceito: o público é um grupo pequeno, e a alternativa
  contradiz a política de custo. Se isso mudar, é uma decisão nova e documentada.
- Dois esquemas (Room e servidor) evoluem em paralelo e exigem tradução explícita no sync. Esse é o
  preço de não espelhar o Room — e é menor que o preço de acoplar o schema remoto à execução local.
- Depender do Firebase Auth mantém uma dependência de terceiro na identidade. Aceito: implementar
  autenticação própria seria pior em segurança e em custo.

## Referências

O detalhamento fica nos documentos abaixo — este ADR registra a **decisão**, não cada classe.

- [`data-classification-matrix.md`](./data-classification-matrix.md) — o que sincroniza e o que não.
- [`identity-contract.md`](./identity-contract.md) — `localId`, `syncId`, `deviceId`, ownership.
- [`sync-protocol.md`](./sync-protocol.md) — push/pull, cursor, idempotência, conflitos, tombstones.
- [`../../contracts/sync/v1/README.md`](../../contracts/sync/v1/README.md) — o contrato de sync que
  os dois lados precisam cumprir, com os corpos de requisição e resposta.
- [`../../contracts/backup/v1/README.md`](../../contracts/backup/v1/README.md) — o formato do
  snapshot e a forma canônica cujo SHA-256 os dois lados reproduzem.
- [`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §17 — o desenho de cada fase da T16 no app.
- [`../../backend/README.md`](../../backend/README.md) — como rodar o backend.
