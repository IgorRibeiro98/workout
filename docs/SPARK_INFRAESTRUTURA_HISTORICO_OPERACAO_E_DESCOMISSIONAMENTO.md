# Spark — Histórico completo de infraestrutura, operação, recuperação e descomissionamento

> **Documento de referência operacional**
>
> **Projeto:** Spark / Gym Tracker  
> **Repositório:** `IgorRibeiro98/workout`  
> **Snapshot técnico usado para esta documentação:** `main` em `3466e1e5c9d5e7aea52328666e1701ebfce61b3a`  
> **Data do snapshot:** 2026-09-11  
> **Objetivo:** registrar, em um único lugar, a evolução da infraestrutura do Spark desde o período em que o Android chamava o Gemini via Firebase AI Logic até a arquitetura atual com Spark Backend, Firebase Auth, Cloud Run, Neon PostgreSQL, Google Cloud Storage, Secret Manager, Artifact Registry, Cloud Scheduler, DR e observabilidade.
>
> Este arquivo foi escrito para ser útil mesmo daqui a meses, quando detalhes de decisões, nomes de recursos e comandos já não estiverem mais na memória.

---

## Sumário

1. [Como ler este documento](#1-como-ler-este-documento)
2. [Resumo executivo](#2-resumo-executivo)
3. [Linha do tempo da arquitetura](#3-linha-do-tempo-da-arquitetura)
4. [Fase T14 — Coach IA no Android / Firebase AI Logic](#4-fase-t14--coach-ia-no-android--firebase-ai-logic)
5. [Fase T16 — nascimento do Spark Backend e da conta online](#5-fase-t16--nascimento-do-spark-backend-e-da-conta-online)
6. [Fase T17 — social, notificações, mídia e exclusão de conta](#6-fase-t17--social-notificações-mídia-e-exclusão-de-conta)
7. [Fase T18 — infraestrutura de produção](#7-fase-t18--infraestrutura-de-produção)
8. [Arquitetura atual](#8-arquitetura-atual)
9. [Inventário atual de recursos](#9-inventário-atual-de-recursos)
10. [Firebase e autenticação](#10-firebase-e-autenticação)
11. [Gemini / Coach IA](#11-gemini--coach-ia)
12. [PostgreSQL / Neon](#12-postgresql--neon)
13. [Object Storage / GCS](#13-object-storage--gcs)
14. [Cloud Run](#14-cloud-run)
15. [Cloud Scheduler e maintenance](#15-cloud-scheduler-e-maintenance)
16. [Disaster Recovery do PostgreSQL](#16-disaster-recovery-do-postgresql)
17. [Auditoria PostgreSQL ↔ GCS](#17-auditoria-postgresql--gcs)
18. [Observabilidade e alertas](#18-observabilidade-e-alertas)
19. [Segurança, IAM e secrets](#19-segurança-iam-e-secrets)
20. [Pipeline de deploy atual](#20-pipeline-de-deploy-atual)
21. [Rollback](#21-rollback)
22. [Operação rotineira](#22-operação-rotineira)
23. [Como reconstruir tudo do zero](#23-como-reconstruir-tudo-do-zero)
24. [Como pausar sem apagar dados](#24-como-pausar-sem-apagar-dados)
25. [Como descomissionar parcialmente](#25-como-descomissionar-parcialmente)
26. [Como excluir tudo definitivamente](#26-como-excluir-tudo-definitivamente)
27. [Play Console — despublicar ou excluir o app](#27-play-console--despublicar-ou-excluir-o-app)
28. [Firebase — desligar e excluir](#28-firebase--desligar-e-excluir)
29. [Neon — desligar e excluir](#29-neon--desligar-e-excluir)
30. [Google Cloud — desligar recursos ou excluir o projeto](#30-google-cloud--desligar-recursos-ou-excluir-o-projeto)
31. [Limpeza local da máquina do desenvolvedor](#31-limpeza-local-da-máquina-do-desenvolvedor)
32. [Checklist de encerramento definitivo](#32-checklist-de-encerramento-definitivo)
33. [Arquivos do repositório que são fonte de verdade](#33-arquivos-do-repositório-que-são-fonte-de-verdade)
34. [Notas históricas e armadilhas já encontradas](#34-notas-históricas-e-armadilhas-já-encontradas)

---

# 1. Como ler este documento

O Spark é **local-first**.

A distinção mais importante para entender toda a infraestrutura é:

```text
DADO DE TREINO NO APARELHO
        │
        ▼
Room / SQLite Android
        │
        ├── funciona sem conta
        ├── funciona sem internet
        └── funciona sem backend

SERVIÇOS ONLINE
        │
        ├── conta Firebase
        ├── backup
        ├── sync
        ├── social
        ├── Coach IA
        ├── mídia
        └── notificações
```

A migração de infraestrutura **nunca transformou o backend em autoridade do treino ativo no aparelho**.

Também é importante não confundir dois SQLite/PostgreSQL diferentes:

- **Android:** continua usando Room/SQLite localmente.
- **Backend:** originalmente teve persistência local/SQLite em fases anteriores; a T18.0 migrou a autoridade do backend para PostgreSQL/Neon.

---

# 2. Resumo executivo

A arquitetura atual do Spark pode ser resumida assim:

```text
┌─────────────────────────────────────┐
│             Android Spark           │
│ Kotlin + Compose + Room + DataStore │
│           local / offline           │
└─────────────────┬───────────────────┘
                  │ HTTPS
                  │ Firebase ID Token
                  ▼
┌─────────────────────────────────────┐
│         spark-backend               │
│ Google Cloud Run                    │
│ NestJS / Node 22                    │
└──┬────────────┬──────────┬──────────┘
   │            │          │
   │            │          └──────────────► Gemini API
   │            │
   │            └─────────────────────────► Google Cloud Storage
   │                                        spark-private-assets-prod
   │
   └──────────────────────────────────────► Neon PostgreSQL 18

Firebase Auth / FCM
Projeto: spark-36b11
         ▲
         │ Firebase Admin via ADC
         │
Runtime SA do projeto de infraestrutura

Projeto GCP de infraestrutura:
project-47b17b25-909d-4ae8-943
Região: southamerica-east1
```

A separação de projetos é deliberada:

```text
spark-36b11
Firebase / identidade / Auth / FCM / App Check
                  ▲
                  │
                  │ cross-project IAM
                  │
project-47b17b25-909d-4ae8-943
Cloud Run / GCS / Secret Manager / Artifact Registry / Scheduler
```

Hoje existem cinco superfícies principais usando a mesma imagem Docker:

```text
spark-backend           Cloud Run Service
spark-maintenance       Cloud Run Service
spark-db-migrate        Cloud Run Job
spark-db-backup         Cloud Run Job
spark-storage-audit     Cloud Run Job
```

---

# 3. Linha do tempo da arquitetura

```text
T14
│
├─ Android fala diretamente com Firebase AI Logic / Gemini
│
▼
T16
│
├─ nasce Spark Backend
├─ Firebase Auth
├─ Coach IA migra para backend
├─ backup
├─ sync incremental
├─ conflitos/deletes/tombstones
└─ hardening inicial de produção
│
▼
T17
│
├─ social
├─ amizades
├─ desafios/feed
├─ FCM
├─ exclusão de conta
├─ ledger anti-ressurreição
└─ mídia social
│
▼
T18.0
│
└─ backend: SQLite → PostgreSQL / Neon
│
▼
T18.1
│
└─ payloads pesados → Object Storage / GCS
│
▼
T18.2
│
├─ Cloud Run
├─ Secret Manager
├─ Artifact Registry
├─ Cloud Scheduler
├─ ADC
└─ Firebase e GCP em projetos separados
│
▼
T18.3
   ├─ DR independente do Neon
   ├─ backup lógico no GCS
   ├─ restore drill em banco limpo
   ├─ auditor PostgreSQL ↔ GCS
   ├─ heartbeat
   ├─ thresholds de DB
   ├─ alerts
   ├─ auditoria IAM/drift/custo
   ├─ secrets pinados
   ├─ rollback drill
   └─ hardening operacional
```

---

# 4. Fase T14 — Coach IA no Android / Firebase AI Logic

## 4.1 Situação original

Até a T15, o Spark era essencialmente local-first e a única fronteira online relevante era o Coach IA.

O desenho antigo era aproximadamente:

```text
Android
  │
  ├── Context Builder
  ├── AiCoachGateway
  │
  ▼
Firebase AI Logic
  │
  ▼
Gemini
```

O Android conhecia diretamente o provider do Gemini através do SDK de cliente do Firebase AI Logic.

Esse desenho funcionava, mas tinha limitações importantes para uma fase em que o app começaria a ter conta, backup e estado remoto:

- credenciais/configuração de IA mais próximas do cliente;
- prompt e modelo acoplados ao ciclo de release do Android;
- controle de quota mais difícil;
- observabilidade central mais fraca;
- ausência de uma fronteira server-side para autorização e abuso;
- impossibilidade de centralizar rate limit, dedupe e custos por conta.

## 4.2 T14.0 / T14.1 — análise de treino

O Coach começou com análise do treino e evoluiu para separar claramente:

```text
observação = o que os dados mostram
recomendação = o que a IA sugere por causa da observação
```

A arquitetura passou a exigir:

- `exerciseId` válido;
- evidência vinculada à recomendação;
- contexto mínimo;
- rejeição de IDs inventados;
- resposta estruturada;
- nenhuma mutação automática no treino.

## 4.3 T14.2 — geração de treino

A IA passou a poder propor um treino novo.

Regra central:

```text
IA gera draft
       ↓
usuário revisa
       ↓
usuário confirma
       ↓
WorkoutRepository persiste
```

A IA nunca grava diretamente no banco.

O histórico servia como evidência para evitar carga inventada, não como autorização automática para progressão.

## 4.4 T14.3 — adaptação de treino

Foi adicionada adaptação de treino existente.

A adaptação é **confirmation-first**:

- IA propõe mudanças;
- usuário escolhe mudança a mudança;
- revisão do template é revalidada;
- proposta obsoleta não sobrescreve edição mais nova;
- sessão concluída não é alterada.

## 4.5 T14.4 — explicação contextual e política de custo

Foram adicionados fluxos `EXPLAIN_*`.

A regra de custo mais importante sobrevive até hoje:

> Se o app já possui razão e evidência suficientes para explicar algo localmente, não existe chamada de rede.

Resultado:

```text
explicação local suficiente
        ↓
zero HTTP
zero Gemini
zero custo
```

Explicações também ganharam cache em memória na sessão de uso para evitar pagar duas vezes pela mesma explicação.

## 4.6 T14.5 — versionamento, segurança e rastreabilidade

Essa fase consolidou:

- versão de schema;
- versão de prompt;
- modelo rastreável;
- avaliação determinística;
- App Check por variante;
- testes contra regressões silenciosas.

Historicamente os prompts viviam no Android, em `AiCoachPrompt`.

Mais tarde, na T16.2, esses prompts foram movidos para o backend **sem mudança semântica proposital**: a migração trocou o transporte, não o comportamento do Coach.

---

# 5. Fase T16 — nascimento do Spark Backend e da conta online

## 5.1 Decisão arquitetural

O ponto de virada foi perceber que backup, sync, social e IA precisavam de uma fronteira remota comum.

Nasceu o Spark Backend.

Stack server-side atual derivada dessa decisão:

- Node.js 22;
- NestJS;
- PostgreSQL;
- `firebase-admin`;
- `@google/genai`;
- `@google-cloud/storage`;
- Pino para logs estruturados.

## 5.2 T16.1 — Firebase Authentication

O Spark ganhou conta opcional:

```text
Google Account
    ↓
Credential Manager
    ↓
Google ID Token
    ↓
Firebase Authentication
    ↓
Firebase ID Token
```

No Android:

- `AuthGateway`;
- `FirebaseAuthGateway`;
- `AuthState`;
- `AuthTokenProvider`.

Regras importantes:

- conta continua opcional;
- abrir app não abre seletor de conta;
- login só ocorre por ação explícita;
- logout não apaga dados locais;
- sessão vem do Firebase real, não de cópia em DataStore;
- token é obtido sob demanda;
- token nunca é persistido nem logado.

## 5.3 T16.2 — Coach IA migra do Firebase AI Logic para o Spark Backend

Mudança central:

```text
ANTES

Android
  ↓
Firebase AI Logic
  ↓
Gemini


DEPOIS

Android
  ↓ Firebase ID Token
POST /v1/ai/coach
  ↓
Spark Backend
  ↓
Gemini API
```

O `FirebaseAiCoachGateway` foi removido.

O gateway atual é:

```text
SparkBackendAiCoachGateway
```

A dependência Firebase AI Logic não deve voltar ao Android.

Existe teste estrutural justamente para evitar que dois providers de IA passem a coexistir.

### Benefícios da migração

- chave Gemini server-only;
- quota por conta;
- quota global;
- uma chamada ativa por conta;
- dedupe por `clientRequestId`;
- modelo alterável sem publicar novo APK;
- prompt server-side;
- observabilidade;
- proteção contra abuso;
- timeout central;
- kill switch `AI_ENABLED`.

### Fluxo atual do Coach

```text
Room / catálogo / SettingsManager
        ↓
Context Builder Android
        ↓
Use Case
        ↓
SparkBackendAiCoachGateway
        ↓
SparkBackendClient
        ↓
Firebase ID Token
        ↓
POST /v1/ai/coach
        ↓
BearerAuthGuard
        ↓
quota / concorrência / dedupe
        ↓
AiCoachPromptRegistry
        ↓
GeminiAiProviderGateway
        ↓
Gemini
        ↓
validação backend
        ↓
validação Android
        ↓
draft / advice / explanation
        ↓
confirmação explícita
```

A validação em dois lados é proposital.

## 5.4 T16.3 — identidade global e Outbox

O Spark começou a preparar sincronização multi-device.

Foram introduzidos:

- `syncId`;
- `clientMutationId`;
- `deviceId`;
- `sync_outbox`;
- contratos de agregados;
- fronteira transacional domínio → Room → Outbox.

O backend não é uma cópia tabela-a-tabela do Room.

O protocolo remoto trabalha com **agregados**.

## 5.5 T16.4 — vínculo de dataset e backup

O vínculo do dataset com uma conta passou a ser persistido no próprio Room.

Backup passou a representar um snapshot explícito, e não uma consequência automática de login.

Regra:

> Login não adota silenciosamente dados locais.

## 5.6 T16.5 — restore

Foi adicionado fluxo de restauração controlada.

A autoridade local continua explícita, e restauração não deve se misturar com sessão de usuário sem confirmação.

## 5.7 T16.6 — sync incremental

Nasceu o fluxo completo:

```text
ação local
   ↓
Room + Outbox na mesma transação
   ↓
WorkManager
   ↓
push
   ↓
Spark Backend
   ↓
serverRevision / change log
   ↓
pull
   ↓
aplicação remota
```

Propriedades:

- idempotência;
- cursor remoto;
- ordem;
- `serverRevision`;
- retries controlados;
- offline-first;
- 5xx não apaga a Outbox.

## 5.8 T16.7 — conflitos, deletes e tombstones

A sincronização ganhou:

- conflitos explícitos;
- Outbox bloqueada até resolução;
- escolha do usuário;
- deletes remotos;
- tombstones;
- proteção contra recriação acidental;
- cursor expirado / rebaseline quando necessário.

## 5.9 T16.7.1 — CI Android

O pipeline Android passou a rodar:

- testes;
- assemble debug;
- sem Firebase real;
- sem Gemini real;
- sem backend real;
- com `google-services.json` sintético de CI.

O `google-services.json` real nunca deve ser commitado.

## 5.10 T16.8 — hardening inicial de produção

Antes do Cloud Run, foi criada uma topologia VPS/Docker Compose:

```text
Internet
  ↓
Caddy
  ↓
Spark Backend
  ↓
PostgreSQL
```

Essa topologia continua documentada como alternativa, mas **não é a topologia real recomendada hoje**.

A T16.8 consolidou:

- health;
- readiness;
- graceful shutdown;
- backup;
- restore drill;
- Caddy/TLS;
- kill switches;
- observabilidade;
- scripts operacionais;
- CI backend.

---

# 6. Fase T17 — social, notificações, mídia e exclusão de conta

Nem toda T17 é infraestrutura, mas várias decisões alteraram diretamente o backend.

## 6.1 Social

O backend ganhou autoridade de:

- identidade social;
- privacidade;
- amizades;
- pedidos;
- desafios;
- feed;
- ranking contextual.

Social não usa os mesmos tombstones do sync.

## 6.2 T17.5 — notificações FCM

Foi criado o dispatcher de notificações sociais.

No Cloud Run ele não roda via `setInterval` dentro da API.

Hoje:

```text
Cloud Scheduler
    ↓
spark-maintenance
    ↓
NotificationDispatcher
    ↓
Firebase Cloud Messaging
```

Existe kill switch:

```text
SOCIAL_PUSH_ENABLED=false
```

Seguro por padrão.

## 6.3 T17.6+ — exclusão de conta

Excluir conta passou a ser um workflow distribuído que precisa coordenar:

- PostgreSQL;
- Firebase Authentication;
- backup;
- social;
- mídia;
- sync;
- ledger de tombstones.

Apenas chamar `FirebaseUser.delete()` seria incorreto.

## 6.4 Ledger anti-ressurreição

Foi criado um ledger externo ao banco.

Objetivo:

```text
backup antigo do PostgreSQL
      +
conta excluída depois daquele backup
      ↓
restore
      ↓
conta NÃO pode voltar à vida
```

A identidade no ledger não é o UID puro.

É usada:

```text
HMAC(uid)
```

com:

```text
ACCOUNT_DELETION_HMAC_KEY
```

Essa chave é crítica e histórica.

> **Nunca rotacione ou substitua essa HMAC sem um plano explícito de migração dos tombstones.**

Trocar a chave faria tombstones históricos deixarem de casar.

## 6.5 T17.9 — mídia social

Fotos/check-ins passaram a exigir armazenamento de bytes.

Isso preparou o passo T18.1.

---

# 7. Fase T18 — infraestrutura de produção

# 7.1 T18.0 — backend SQLite → PostgreSQL / Neon

A mudança foi apenas no backend.

O Android continuou Room/SQLite.

A autoridade server-side passou para PostgreSQL.

A configuração evoluiu para dois endpoints:

```text
DATABASE_URL
    pooled
    runtime/API

DATABASE_URL_DIRECT
    conexão direta
    migrations / operações administrativas / DR
```

Na arquitetura real, o PostgreSQL é Neon.

O banco de produção atual é PostgreSQL 18.

### Hardening T18.0.x

As tarefas corretivas fecharam problemas como:

- identidade explícita do database;
- comportamento de migration;
- consistência de restore;
- teste com PostgreSQL real;
- guardas destrutivas;
- não assumir que URL sem path é segura;
- preferência pela conexão direta em operações administrativas.

---

# 7.2 T18.1 — Object Storage

Payload pesado saiu do PostgreSQL.

O padrão atual é:

```text
PostgreSQL
   ├── metadata
   ├── ownership
   ├── hashes
   ├── status
   └── storage_key

GCS
   └── bytes canônicos
```

Providers:

```text
ObjectStorageClient
├── LocalObjectStorageClient  dev/test/CI
└── GcsObjectStorageClient    produção
```

O bucket passou a armazenar:

- fotos sociais;
- documentos completos de backup pessoal;
- deletion tombstones;
- DR do PostgreSQL.

### Namespaces importantes

```text
social/...
backups/...
system/deletion-tombstones/...
system/dr/postgres/...
```

### Regra create-only

Objetos são escritos com semântica imutável.

No GCS, colisões não devem sobrescrever silenciosamente bytes existentes.

---

# 7.3 T18.2 — Cloud Run

A topologia de produção migrou para Google Cloud Run.

Foram criados:

- Artifact Registry;
- Cloud Run API;
- Cloud Run maintenance;
- migration job;
- Secret Manager;
- Service Accounts;
- Cloud Scheduler;
- ADC;
- deploy por digest.

## T18.2.1 — Firebase e infraestrutura em projetos separados

Durante o deploy real foi confirmado que:

```text
Firebase = spark-36b11

Infraestrutura =
project-47b17b25-909d-4ae8-943
```

O runtime Service Account vive no projeto de infraestrutura e recebe papéis de Firebase **no projeto `spark-36b11`**.

Isso evita duplicar Service Account ou usar chave JSON.

## T18.2.2 — primeiro deploy seguro

O primeiro deploy real revelou um detalhe do Cloud Run:

```text
--no-traffic
```

não é suportado ao criar um serviço novo.

A solução implementada foi:

```text
primeiro deploy

spark-backend ainda não existe
      ↓
spark-backend-validate PRIVADO
      ↓
smoke
      ↓
apaga validate
      ↓
cria spark-backend real
```

Nos deploys seguintes:

```text
revision candidate
      ↓
--no-traffic
      ↓
smoke
      ↓
move 100% do tráfego
```

---

# 7.4 T18.3 — hardening operacional

A T18.3 fecha a família T18 com uma camada operacional.

Ela implementou:

- backup independente do Neon;
- restore em banco limpo;
- DR drill;
- anti-ressurreição;
- gate pré-migration;
- heartbeat;
- stale detection;
- tamanho do banco;
- auditor PostgreSQL ↔ GCS;
- logs estruturados;
- Cloud Monitoring;
- auditoria de drift;
- auditoria IAM;
- auditoria de custo;
- retenção do Artifact Registry;
- pinagem das versões dos secrets;
- validação privada do primeiro deploy;
- rollback drill;
- política SSL explícita.

### Incidente real encontrado durante T18.3

O primeiro backup real falhou fechado porque:

```text
Neon server: PostgreSQL 18.6
pg_dump na imagem: 17.11
```

Erro:

```text
server version mismatch
```

Isso comprovou que o gate estava funcionando.

O commit atual corrigiu a imagem para:

```text
pg_dump / pg_restore / psql PostgreSQL 18
```

A imagem usa ferramentas PostgreSQL 18 também para os drills.

---

# 8. Arquitetura atual

```text
                                     ┌──────────────────────┐
                                     │ Firebase spark-36b11 │
                                     │ Auth / FCM / AppCheck│
                                     └──────────┬───────────┘
                                                │
                                  Firebase Admin│ADC
                                                │
┌───────────────┐ HTTPS + Firebase ID Token     │
│ Android Spark │───────────────────────────────┤
└───────┬───────┘                               ▼
        │                             ┌─────────────────────┐
        │                             │ spark-backend       │
        │                             │ Cloud Run           │
        │                             │ NestJS / Node 22    │
        │                             └─┬────────┬────────┬─┘
        │                               │        │        │
        │                               │        │        └── Gemini API
        │                               │        │
        │                               │        └────────── GCS
        │                               │
        │                               └─────────────────── Neon PostgreSQL 18
        │
        └── Room / SQLite permanece autoridade local
```

Superfícies server-side:

```text
spark-backend
spark-maintenance
spark-db-migrate
spark-db-backup
spark-storage-audit
```

Uma única imagem Docker por release.

---

# 9. Inventário atual de recursos

## 9.1 Projetos

### Firebase

```text
Project ID: spark-36b11
Project number histórico confirmado: 638822756779
```

Responsabilidades:

- Firebase Authentication;
- FCM;
- App Check / Play Integrity;
- identidade do usuário Android.

### Google Cloud de infraestrutura

```text
Project ID: project-47b17b25-909d-4ae8-943
Project number: 965678405850
Region: southamerica-east1
```

Responsabilidades:

- Cloud Run;
- GCS;
- Artifact Registry;
- Secret Manager;
- Cloud Scheduler;
- Cloud Logging;
- Cloud Monitoring;
- Service Accounts.

## 9.2 Cloud Run Services

```text
spark-backend
spark-maintenance
```

Serviço temporário usado somente em primeiro deploy:

```text
spark-backend-validate
```

Ele deve ser removido pelo deploy após smoke.

## 9.3 Cloud Run Jobs

```text
spark-db-migrate
spark-db-backup
spark-storage-audit
```

## 9.4 Service Accounts

```text
spark-backend-runtime
spark-backend-migrator
spark-maintenance-scheduler
spark-backend-backup
```

## 9.5 Secrets

```text
spark-database-url
spark-database-url-direct
spark-gemini-api-key
spark-account-deletion-hmac-key
```

## 9.6 Bucket

```text
spark-private-assets-prod
```

Região:

```text
SOUTHAMERICA-EAST1
```

Proteções esperadas pela T18.3:

- private;
- Uniform Bucket-Level Access;
- Public Access Prevention;
- soft delete de pelo menos 7 dias.

## 9.7 Artifact Registry

```text
repository: spark
image: spark-backend
host: southamerica-east1-docker.pkg.dev
```

## 9.8 Scheduler

```text
spark-maintenance-cycle
cron: * * * * *
```

```text
spark-db-backup-daily
cron: 15 3 * * *
timezone operacional: UTC
```

Ou seja, backup diário às 03:15 UTC.

## 9.9 Neon

Projeto usado para produção:

```text
spark
```

Dois endpoints devem existir:

```text
pooled  → spark-database-url
direct  → spark-database-url-direct
```

PostgreSQL de produção identificado durante T18.3:

```text
PostgreSQL 18.x
```

---

# 10. Firebase e autenticação

## 10.1 Android

Package:

```text
com.aistudio.workout.v2
```

O Android precisa de:

```text
app/google-services.json
```

Esse arquivo:

- é baixado do Firebase;
- não deve ser versionado;
- está no `.gitignore`.

## 10.2 Sign in with Google

Fluxo:

```text
Credential Manager
       ↓
Google ID Token
       ↓
Firebase Auth
       ↓
Firebase ID Token
       ↓
Spark Backend
```

O Firebase precisa conhecer SHA-1/SHA-256 das assinaturas utilizadas.

Para debug:

```bash
./gradlew :app:signingReport
```

Para distribuição Play, também cadastrar os fingerprints de **Play App Signing**.

## 10.3 App Check

Por variante:

```text
debug
  → DebugAppCheckProviderFactory

release
  → PlayIntegrityAppCheckProviderFactory
```

Nunca coloque token de debug em release.

## 10.4 Backend

No Cloud Run:

```text
FIREBASE_ADMIN_CREDENTIAL_MODE=adc
FIREBASE_PROJECT_ID=spark-36b11
```

Não existe arquivo JSON de Service Account.

A identidade é:

```text
spark-backend-runtime@
project-47b17b25-909d-4ae8-943.iam.gserviceaccount.com
```

Ela recebe os papéis Firebase no projeto `spark-36b11`.

---

# 11. Gemini / Coach IA

O Android não possui Gemini API Key.

A key vive em:

```text
Secret Manager
spark-gemini-api-key
```

Consumida pelo backend.

Configuração típica:

```text
AI_ENABLED=true
REQUIRE_GEMINI=false
```

O modelo é server-side.

No snapshot atual do repositório, o default documentado continua derivado da família T14 e vive em `backend/src/config/env.schema.ts` / `AppConfig`.

Para desligar o Coach sem derrubar o Spark:

```text
AI_ENABLED=false
```

Consequência:

```text
/v1/ai/coach
→ 503 AI_PROVIDER_UNAVAILABLE
```

Treino, backup, sync e social continuam.

---

# 12. PostgreSQL / Neon

## 12.1 Autoridade

PostgreSQL é a autoridade do estado remoto.

Room continua a autoridade local.

## 12.2 URLs

```text
DATABASE_URL
  pooled
  API / maintenance

DATABASE_URL_DIRECT
  direct
  migrate / backup / administração
```

A runtime SA não deve ler o secret direto.

## 12.3 SSL

A T18.3 tornou explícita a política SSL.

Motivação: `pg-connection-string` avisou que a semântica futura de `sslmode=require` seria diferente.

O código atual trata a intenção de segurança explicitamente, preferindo verificação completa de certificado quando compatível.

## 12.4 Ferramentas nativas

Imagem atual:

```text
pg_dump 18
pg_restore 18
psql 18
```

Não reduzir essa major enquanto o Neon estiver em PostgreSQL 18.

---

# 13. Object Storage / GCS

Bucket:

```text
gs://spark-private-assets-prod
```

Estrutura lógica:

```text
social/
    mídia social

backups/
    payload canônico de backups pessoais

system/deletion-tombstones/
    ledger anti-ressurreição

system/dr/postgres/
    DR do PostgreSQL
```

## 13.1 Regra fundamental

PostgreSQL guarda metadata.

GCS guarda bytes.

Nunca crie um segundo caminho em que ambos passem a ser autoridade do payload.

## 13.2 ADC

O backend não usa:

```text
GCS_PRIVATE_KEY
GCS_CLIENT_EMAIL
JSON de Service Account
```

Cloud Run autentica via ADC.

---

# 14. Cloud Run

## 14.1 API

```text
service: spark-backend
cpu: 1
memory: 512Mi
min instances: 0
max instances: 1
concurrency: 20
timeout: 180s
port: 8080
```

Acesso HTTP público ao transporte, mas `/v1` exige Firebase Bearer token.

Health:

```text
GET /health/live
GET /health/ready
```

## 14.2 Maintenance

```text
service: spark-maintenance
cpu: 1
memory: 512Mi
min instances: 0
max instances: 1
concurrency: 1
```

Privado.

Chamado via OIDC pelo Cloud Scheduler.

## 14.3 API não executa timers

Em Cloud Run:

```text
BACKGROUND_JOBS_MODE=disabled
```

Isso é deliberado.

CPU fora de request não é uma base segura para `setInterval`.

---

# 15. Cloud Scheduler e maintenance

## 15.1 Maintenance

Job:

```text
spark-maintenance-cycle
```

Cadência:

```text
* * * * *
```

Todo minuto.

O ciclo executa de forma bounded:

- notificações sociais, se habilitadas;
- reconciliação de account deletion;
- limpeza de mídia quando devida;
- limpeza de backup payload quando devida;
- heartbeat;
- tamanho do DB quando devido;
- frescor do DR.

## 15.2 Advisory lock

Se duas execuções colidirem:

```text
pg_try_advisory_lock
```

Uma delas desiste.

Não espera indefinidamente.

## 15.3 Heartbeat

É rastreado:

- último início;
- última conclusão;
- último sucesso;
- falha;
- duração.

Stale default:

```text
5 minutos
```

---

# 16. Disaster Recovery do PostgreSQL

## 16.1 Objetivo

Não depender exclusivamente do Neon para recuperar o banco.

Fluxo:

```text
Cloud Scheduler
spark-db-backup-daily
       ↓
Cloud Run Job
spark-db-backup
       ↓
DATABASE_URL_DIRECT
       ↓
pg_dump --format=custom
       ↓
validação com pg_restore --list
       ↓
SHA-256
       ↓
GCS
       ↓
releitura do objeto
       ↓
manifest.json por ÚLTIMO
```

## 16.2 Layout

```text
system/dr/postgres/<backupId>/
    database.dump
    manifest.json
```

Backup ID:

```text
YYYY-MM-DDTHHMMSSZ
```

## 16.3 Manifesto

Contém:

- formatVersion;
- backupId;
- createdAt;
- host/porta/database sem senha;
- git commit;
- image digest;
- formato;
- tamanho;
- SHA-256;
- versão do PostgreSQL;
- versão do pg_dump;
- TOC;
- schema version;
- migrations;
- tabelas.

Não contém:

- DATABASE_URL;
- senha;
- HMAC;
- Gemini key;
- Firebase token.

## 16.4 Backup válido

Só existe backup válido depois que:

```text
pg_dump OK
arquivo > 0
pg_restore --list OK
SHA calculado
upload OK
objeto relido
tamanho confere
hash confere
manifesto gravado
```

Se existe dump sem manifesto:

```text
INCOMPLETE_BACKUP
```

Não é backup válido.

## 16.5 Retenção

Default:

```text
7 backups válidos
```

Nunca deve apagar o último válido.

## 16.6 Gate pré-deploy

Antes de migration:

```text
backup válido mais recente <= 24h ?
```

Se não:

```text
SPARK_DR_PREDEPLOY_POLICY=run-backup
```

executa backup e espera sucesso.

Alternativa:

```text
SPARK_DR_PREDEPLOY_POLICY=fail
```

aborta deploy.

Nunca existe modo "segue sem backup".

## 16.7 Restore drill

Comando principal:

```bash
ops/gcp/dr-restore-drill.sh --record
```

Fluxo:

```text
bucket real
   ↓
PostgreSQL 18 descartável local
   ↓
database NOVO
   ↓
checksum
   ↓
pg_restore
   ↓
schema/migrations
   ↓
backend real
   ↓
/health/ready 200
/v1/* 401
   ↓
RESTORE_DRILL_PASS
```

O script não recebe `DATABASE_URL` de produção.

## 16.8 Anti-ressurreição

O drill também verifica o ledger externo.

Princípio:

```text
restore de backup antigo
   +
deletion tombstone posterior
   ↓
reconcile-account-deletions
   ↓
conta continua excluída
```

---

# 17. Auditoria PostgreSQL ↔ GCS

Job:

```text
spark-storage-audit
```

É **somente leitura**.

Classificações:

```text
MISSING_OBJECT
ORPHAN_OBJECT
RECENT_UNREFERENCED
INVALID_METADATA
HASH_MISMATCH
INCOMPLETE_BACKUP
TOMBSTONE_INCONSISTENT
UNKNOWN
```

Ele nunca deve apagar automaticamente.

Execução:

```bash
gcloud run jobs execute spark-storage-audit \
  --project project-47b17b25-909d-4ae8-943 \
  --region southamerica-east1 \
  --wait
```

---

# 18. Observabilidade e alertas

## 18.1 Logs estruturados

Eventos relevantes:

```text
db_backup_started
db_backup_completed
db_backup_failed

db_restore_started
db_restore_completed
db_restore_failed

maintenance_started
maintenance_completed
maintenance_failed
maintenance_stale

database_size_checked
database_size_threshold_exceeded

db_backup_freshness_checked
db_backup_stale

storage_audit_completed
storage_audit_issue

migration_started
migration_completed
migration_failed
```

## 18.2 Tamanho do banco

Medição:

```text
pg_database_size(...)
```

Cadência default:

```text
1 hora
```

Thresholds:

```text
< 300 MB    NORMAL
>=300 MB    ATTENTION
>=350 MB    INVESTIGATE
>=400 MB    PLAN
>=450 MB    ACTION_REQUIRED
```

## 18.3 Frescor do backup

Backup diário.

Stale operacional default:

```text
26 horas
```

Checagem default:

```text
30 minutos
```

## 18.4 Cloud Monitoring

Script:

```bash
ops/gcp/monitoring-alerts.sh
```

A implementação cria:

- canal;
- métricas baseadas em log;
- políticas de alerta.

No snapshot documentado pelo repositório:

```text
10 métricas log-based
12 políticas
```

A confirmação do e-mail do canal é manual.

Um alerta só deve ser considerado verificado depois que realmente disparar.

---

# 19. Segurança, IAM e secrets

## 19.1 Service Accounts

### runtime

```text
spark-backend-runtime
```

Precisa de:

- pooled DB secret;
- Gemini key;
- HMAC;
- bucket runtime;
- Firebase Auth Admin no projeto Firebase;
- FCM Admin quando necessário.

Não deve receber:

```text
DATABASE_URL_DIRECT
```

### migrator

```text
spark-backend-migrator
```

Precisa somente de:

```text
spark-database-url-direct
```

### scheduler

```text
spark-maintenance-scheduler
```

Precisa invocar:

- `spark-maintenance`;
- `spark-db-backup` quando scheduler dispara o job.

Não precisa ler DB, bucket, Gemini ou HMAC.

### backup

```text
spark-backend-backup
```

Precisa de:

- direct DB secret;
- listar o bucket;
- gravar/remover somente no prefixo:

```text
system/dr/postgres/
```

Não precisa de Firebase, Gemini ou HMAC.

## 19.2 Secrets pinados

Antes da T18.3 o deploy usava:

```text
secret:latest
```

Agora cada revision resolve metadata e referencia:

```text
secret:<numero_da_versao>
```

Resultado:

```text
revision X
   ↓
secret vY
```

Rotacionar secret só passa a afetar a aplicação depois de novo deploy deliberado.

## 19.3 HMAC

Secret:

```text
spark-account-deletion-hmac-key
```

Criado uma vez.

Não rotacionar automaticamente.

Perder essa chave compromete o reconhecimento do ledger histórico.

---

# 20. Pipeline de deploy atual

Comando:

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
export SPARK_FIREBASE_PROJECT=spark-36b11
export SPARK_GCP_REGION=southamerica-east1

./ops/gcp/deploy-cloud-run.sh
```

Fluxo:

```text
Git limpo
   ↓
Docker build
--provenance=false
--sbom=false
   ↓
push Artifact Registry
   ↓
resolve digest do registry
   ↓
resolve versões habilitadas dos secrets
   ↓
deploy/update:
  spark-db-backup
  spark-storage-audit
   ↓
gate de DR
   ↓
backup recente?
   ├─ sim
   └─ não → backup ou fail
   ↓
deploy spark-db-migrate
   ↓
migration --wait
   ↓
API já existe?
   │
   ├─ não
   │    ↓
   │ spark-backend-validate PRIVADO
   │    ↓
   │ identity token do operador
   │    ↓
   │ smoke
   │    ↓
   │ delete validate
   │    ↓
   │ cria spark-backend
   │
   └─ sim
        ↓
      candidate --no-traffic
        ↓
      smoke
        ↓
      tráfego 100%
   ↓
spark-maintenance
   ↓
IAM Scheduler
   ↓
Schedulers
```

---

# 21. Rollback

Listar revisions:

```bash
ops/gcp/rollback-cloud-run.sh --list
```

Voltar:

```bash
ops/gcp/rollback-cloud-run.sh <revision>
```

Drill:

```bash
ops/gcp/rollback-drill.sh --to <revision>
```

Importante:

> Rollback de aplicação não desfaz migration.

Por isso migrations devem continuar compatíveis com o código anterior no intervalo de rollout:

```text
expand
migrate
contract
```

---

# 22. Operação rotineira

## 22.1 Estado geral

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
export SPARK_FIREBASE_PROJECT=spark-36b11
export SPARK_GCP_REGION=southamerica-east1

ops/gcp/dr-status.sh
```

## 22.2 Backup agora

```bash
ops/gcp/dr-backup-now.sh
```

## 22.3 Restore drill

```bash
ops/gcp/dr-restore-drill.sh --record
```

## 22.4 Drift

```bash
ops/gcp/config-drift-audit.sh
```

## 22.5 IAM

```bash
ops/gcp/iam-audit.sh
```

## 22.6 Custo

```bash
ops/gcp/cost-audit.sh
```

## 22.7 Storage

```bash
gcloud run jobs execute spark-storage-audit \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION" \
  --wait
```

## 22.8 Retenção do registry

Verificar:

```bash
ops/gcp/artifact-registry-retention.sh
```

Aplicar deliberadamente:

```bash
ops/gcp/artifact-registry-retention.sh --apply
```

Estratégia registrada:

```text
Keep 10
tagged > 90 dias
```

Sem regra genérica para untagged herdadas de imagens antigas, porque revisions pré-T18.3 podem depender de manifests filhos.

## 22.9 Smoke API

```bash
ops/gcp/smoke-cloud-run.sh \
  https://spark-backend-965678405850.southamerica-east1.run.app
```

Sem token:

```text
/health/live   200
/health/ready  200
/v1/*          401
```

---

# 23. Como reconstruir tudo do zero

Esta seção serve como mapa mental. Os scripts do repositório continuam sendo a fonte executável.

## 23.1 Firebase

1. Criar/recuperar projeto Firebase.
2. Registrar app Android:

```text
com.aistudio.workout.v2
```

3. Habilitar Google Sign-In.
4. Cadastrar SHA-1/SHA-256:
   - debug;
   - upload/release quando aplicável;
   - Play App Signing.
5. Baixar `google-services.json`.
6. Configurar App Check:
   - debug provider para desenvolvimento;
   - Play Integrity para release.

## 23.2 Neon

Criar projeto PostgreSQL.

Criar/obter:

```text
pooled URL
direct URL
```

## 23.3 GCP infra

Projeto dedicado.

Ativar billing.

Variáveis:

```bash
export SPARK_GCP_PROJECT=<infra>
export SPARK_FIREBASE_PROJECT=<firebase>
export SPARK_GCP_REGION=southamerica-east1
```

## 23.4 Bootstrap

```bash
./ops/gcp/bootstrap-cloud-run.sh
```

Ele cria/reusa:

- APIs;
- Artifact Registry;
- Service Accounts;
- secrets;
- IAM;
- bucket bindings;
- cross-project Firebase IAM.

## 23.5 Secrets

Garantir versões habilitadas para:

```text
spark-database-url
spark-database-url-direct
spark-gemini-api-key
spark-account-deletion-hmac-key
```

Nunca imprimir os valores.

## 23.6 Deploy

```bash
./ops/gcp/deploy-cloud-run.sh
```

## 23.7 Verificações

```bash
ops/gcp/dr-status.sh
ops/gcp/config-drift-audit.sh
ops/gcp/iam-audit.sh
ops/gcp/cost-audit.sh
ops/gcp/dr-restore-drill.sh --record
```

---

# 24. Como pausar sem apagar dados

Use quando quiser parar custos/atividade temporariamente, mas pretende voltar.

## 24.1 Pausar os schedulers

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
export SPARK_GCP_REGION=southamerica-east1

gcloud scheduler jobs pause spark-maintenance-cycle \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

gcloud scheduler jobs pause spark-db-backup-daily \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"
```

Consequências:

- maintenance para;
- FCM dispatcher para;
- cleanup para;
- account deletion reconciliation para;
- backup automático para.

Não faça isso e esqueça por semanas sem entender o impacto.

## 24.2 Desligar IA sem desligar backend

Configurar:

```text
AI_ENABLED=false
```

e fazer nova revision.

## 24.3 Desligar push

```text
SOCIAL_PUSH_ENABLED=false
```

## 24.4 Despublicar o app

Na Play Console, cancelar a publicação impede novos downloads, mas usuários existentes continuam com o app.

Isso não desliga o backend.

---

# 25. Como descomissionar parcialmente

Objetivo:

- app deixa de operar online;
- dados são preservados;
- infraestrutura computacional é removida.

## Ordem segura

1. bloquear novas publicações;
2. fazer backup final;
3. rodar restore drill;
4. pausar schedulers;
5. excluir serviços/jobs Cloud Run;
6. preservar GCS;
7. preservar Neon;
8. preservar secrets críticos;
9. manter Firebase se ainda houver chance de retorno.

### Backup final

```bash
ops/gcp/dr-backup-now.sh
ops/gcp/dr-restore-drill.sh --record
```

Só depois avance.

### Remover computação

```bash
gcloud run services delete spark-backend \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"

gcloud run services delete spark-maintenance \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"
```

Se sobrou temporário:

```bash
gcloud run services delete spark-backend-validate \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"
```

Jobs:

```bash
for job in spark-db-migrate spark-db-backup spark-storage-audit; do
  gcloud run jobs delete "$job" \
    --project "$SPARK_GCP_PROJECT" \
    --region "$SPARK_GCP_REGION"
done
```

Schedulers:

```bash
gcloud scheduler jobs delete spark-maintenance-cycle \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

gcloud scheduler jobs delete spark-db-backup-daily \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"
```

Nesse modo, mantenha:

```text
Neon
GCS
HMAC
DB secrets
Firebase project
```

até decidir exclusão definitiva.

---

# 26. Como excluir tudo definitivamente

> **PERIGO — IRREVERSÍVEL**
>
> Não execute esta seção como script único.
>
> Faça item por item e confirme o alvo de cada comando.
>
> Uma exclusão definitiva deve ser tratada como procedimento de mudança, não como limpeza casual.

## 26.1 Antes de apagar

Checklist obrigatório:

```text
[ ] quero realmente encerrar o Spark?
[ ] não preciso mais dos dados dos usuários?
[ ] exportei o que precisa ser preservado?
[ ] rodei um backup final?
[ ] rodei restore drill?
[ ] guardei a documentação?
[ ] sei quais projetos GCP contêm outros recursos não relacionados?
[ ] sei se o app Play ainda precisa ser mantido?
[ ] sei se o package name precisa continuar reservado?
```

## 26.2 Ordem recomendada

```text
1. Play: despublicar
2. bloquear novas operações
3. backup final
4. restore drill
5. exportar GCS se necessário
6. exportar PostgreSQL se necessário
7. apagar computação/schedulers
8. apagar monitoramento
9. apagar Artifact Registry
10. apagar bucket
11. apagar secrets / service accounts
12. apagar Neon
13. apagar projeto GCP infra
14. apagar Firebase project
15. excluir app Play se realmente desejado e elegível
16. limpar credenciais locais
```

Não apague primeiro o Firebase ou a HMAC se ainda pretende executar reconciliação/restore.

---

# 27. Play Console — despublicar ou excluir o app

## 27.1 Cancelar publicação

Play Console:

```text
Testar e lançar
→ Configuração
→ Configurações avançadas
→ Disponibilidade do app
→ Cancelar publicação
```

Efeito:

- usuários existentes continuam usando;
- usuários existentes podem continuar recebendo atualizações;
- novos usuários não encontram/baixam.

## 27.2 Excluir o app da Play Console

As regras da Play mudam com o tempo. No snapshot de 2026-09-11, a exclusão por autoatendimento exige, entre outras condições:

- app não publicado;
- nenhuma mudança em análise;
- menos de 1.000 dispositivos ativos;
- app não rejeitado/bloqueado/suspenso;
- condições adicionais para apps monetizados;
- proprietário/admin e confirmação com dados da conta.

Caminho:

```text
Testar e lançar
→ Configurações avançadas
→ Disponibilidade do app
→ Excluir app
```

A Play informa uma janela curta de recuperação após exclusão por UI; depois a exclusão se torna permanente.

### Package name

Não assuma que:

```text
com.aistudio.workout.v2
```

poderá ser reutilizado depois.

A regra depende de instalações e estado histórico do app.

### Upload key

Se existir qualquer chance de manter o app:

> **NÃO APAGUE O KEYSTORE DE UPLOAD.**

---

# 28. Firebase — desligar e excluir

Projeto:

```text
spark-36b11
```

## 28.1 Antes de excluir

Considere:

- Firebase Auth users;
- FCM;
- App Check;
- OAuth clients;
- SHA fingerprints;
- Google Sign-In;
- eventual ligação com Play Integrity.

Depois de excluir, o app deixa de conseguir autenticar normalmente.

## 28.2 Desligamento total

Firebase é um projeto Google Cloud.

Você pode desligá-lo pelo Console ou via Resource Manager:

```bash
gcloud projects delete spark-36b11
```

No Google Cloud, o projeto entra em estado de exclusão com janela de recuperação.

No snapshot das regras oficiais consultadas em 2026-09-11:

```text
janela geral de recuperação: 30 dias
```

Mas alguns recursos podem desaparecer antes.

OAuth pode demorar para voltar mesmo se o projeto for restaurado.

## 28.3 Se quiser manter o projeto

Em vez de excluir:

- desabilite providers;
- remova App Check se apropriado;
- remova IAM cross-project;
- mantenha o Project ID.

---

# 29. Neon — desligar e excluir

Projeto:

```text
spark
```

## 29.1 Backup primeiro

Antes de apagar:

```bash
ops/gcp/dr-backup-now.sh
ops/gcp/dr-restore-drill.sh --record
```

Opcionalmente faça um dump adicional fora do bucket.

## 29.2 Excluir projeto Neon

Neon Console:

```text
Project
→ Settings
→ Delete
```

A documentação do Neon considera a exclusão do projeto **irreversível**.

Ela remove:

- computes;
- branches;
- databases;
- roles.

Se estiver em plano pago:

> apagar todos os projetos não necessariamente encerra a cobrança do plano; faça downgrade/cancelamento em Billing.

Não execute isso antes de confirmar o DR.

---

# 30. Google Cloud — desligar recursos ou excluir o projeto

Projeto:

```text
project-47b17b25-909d-4ae8-943
```

## 30.1 Inventário antes de apagar

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
export SPARK_GCP_REGION=southamerica-east1

gcloud run services list \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"

gcloud run jobs list \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"

gcloud scheduler jobs list \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

gcloud secrets list \
  --project "$SPARK_GCP_PROJECT"

gcloud iam service-accounts list \
  --project "$SPARK_GCP_PROJECT"

gcloud artifacts repositories list \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

gcloud storage buckets list \
  --project "$SPARK_GCP_PROJECT"
```

Só delete o projeto inteiro se ele for realmente dedicado ao Spark.

## 30.2 Schedulers

```bash
gcloud scheduler jobs delete spark-maintenance-cycle \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

gcloud scheduler jobs delete spark-db-backup-daily \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"
```

## 30.3 Cloud Run services

```bash
gcloud run services delete spark-backend \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"

gcloud run services delete spark-maintenance \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"
```

Verificar temporário:

```bash
gcloud run services describe spark-backend-validate \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"
```

Se existir:

```bash
gcloud run services delete spark-backend-validate \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"
```

Excluir um Cloud Run Service exclui suas revisions, mas **não apaga automaticamente as imagens no Artifact Registry**.

## 30.4 Cloud Run Jobs

```bash
for job in spark-db-migrate spark-db-backup spark-storage-audit; do
  gcloud run jobs delete "$job" \
    --project "$SPARK_GCP_PROJECT" \
    --region "$SPARK_GCP_REGION"
done
```

Excluir Job encerra execuções em andamento.

## 30.5 Monitoring

Liste antes:

```bash
gcloud monitoring policies list \
  --project "$SPARK_GCP_PROJECT"

gcloud logging metrics list \
  --project "$SPARK_GCP_PROJECT"
```

Exclua individualmente apenas políticas e métricas do Spark.

Para notification channels, prefira revisar pelo Cloud Monitoring Console antes de remover, porque o mesmo canal pode estar sendo reutilizado por outro produto.

Se o projeto for excluído inteiro, essa limpeza individual deixa de ser necessária.

## 30.6 Artifact Registry

Antes:

```bash
gcloud artifacts docker images list \
  southamerica-east1-docker.pkg.dev/$SPARK_GCP_PROJECT/spark \
  --include-tags
```

Excluir repositório:

```bash
gcloud artifacts repositories delete spark \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"
```

Isso remove as imagens.

Só faça depois que não houver nenhuma revision que você precise manter para rollback.

## 30.7 GCS — preservar ou destruir

Bucket:

```text
gs://spark-private-assets-prod
```

Antes de remover:

```bash
gcloud storage ls --recursive gs://spark-private-assets-prod
```

Se quiser preservar:

```bash
mkdir -p ~/spark-gcs-export
gcloud storage cp --recursive \
  gs://spark-private-assets-prod \
  ~/spark-gcs-export/
```

### Soft delete

A T18.3 espera soft delete de pelo menos 7 dias.

Se a intenção for **destruição definitiva deliberada**, você pode primeiro desabilitar soft delete:

```bash
gcloud storage buckets update \
  --clear-soft-delete \
  gs://spark-private-assets-prod
```

Atenção:

- objetos que já estavam soft-deleted podem continuar retidos até a janela original terminar;
- desabilitar soft delete remove a proteção contra exclusão acidental futura.

Depois:

```bash
gcloud storage rm --recursive \
  gs://spark-private-assets-prod
```

Esse comando remove conteúdo e o bucket.

Se você pretende excluir o projeto inteiro, pode optar por não fazer uma limpeza manual destrutiva do bucket e encerrar o projeto após o backup/export final.

## 30.8 Secrets

Somente depois de não precisar mais restaurar:

```bash
for secret in \
  spark-database-url \
  spark-database-url-direct \
  spark-gemini-api-key \
  spark-account-deletion-hmac-key
do
  gcloud secrets delete "$secret" \
    --project "$SPARK_GCP_PROJECT"
done
```

### ATENÇÃO especial à HMAC

Não apague:

```text
spark-account-deletion-hmac-key
```

antes de ter certeza de que:

- nenhum restore será necessário;
- nenhum tombstone precisa ser reconciliado;
- nenhum dado Spark será mantido.

## 30.9 Service Accounts

Depois dos serviços/jobs:

```bash
for sa in \
  spark-backend-runtime \
  spark-backend-migrator \
  spark-maintenance-scheduler \
  spark-backend-backup
do
  gcloud iam service-accounts delete \
    "${sa}@${SPARK_GCP_PROJECT}.iam.gserviceaccount.com" \
    --project "$SPARK_GCP_PROJECT"
done
```

## 30.10 Excluir projeto GCP inteiro

Se esse projeto não tiver nada fora do Spark:

```bash
gcloud projects delete project-47b17b25-909d-4ae8-943
```

Segundo o Resource Manager, o projeto entra numa janela de recuperação de aproximadamente 30 dias.

A exclusão:

- interrompe uso dos recursos;
- desconecta billing;
- após a janela, torna-se permanente;
- pode excluir alguns tipos de dados antes do fim da janela.

Se restaurar o projeto, o billing não volta automaticamente.

### Billing

Não exclua a conta de billing apenas porque encerrou o Spark se ela for usada por outros projetos.

Se esse projeto for o único uso, confirme em Billing que não existem recursos/projetos restantes que possam gerar cobrança.

---

# 31. Limpeza local da máquina do desenvolvedor

## 31.1 Credenciais GCP

Ver contas:

```bash
gcloud auth list
```

Revogar uma conta específica, se desejar:

```bash
gcloud auth revoke <email>
```

Revogar ADC local:

```bash
gcloud auth application-default revoke
```

Só faça isso se nenhuma outra aplicação local usar ADC.

## 31.2 Firebase Android

Remover, se não precisar mais:

```text
app/google-services.json
```

Ele não está no Git.

## 31.3 Gradle local

Revisar:

```text
~/.gradle/gradle.properties
local.properties
```

Pode existir:

```text
sparkBackendBaseUrl
sparkBackendBaseUrlDebug
```

## 31.4 Keystores

Debug keystore pode ser recriado.

Upload/release keystore:

> não apague se o app continuar existindo na Play.

## 31.5 Docker

Listar imagens:

```bash
docker images | grep spark
```

Remover só se desejado.

---

# 32. Checklist de encerramento definitivo

```text
PLAY
[ ] app despublicado
[ ] decidir se app será excluído da Play
[ ] decidir se package precisa ser preservado
[ ] upload key guardada ou destruída conscientemente

FIREBASE
[ ] exportar/aceitar perda de usuários
[ ] FCM não é mais necessário
[ ] App Check não é mais necessário
[ ] projeto spark-36b11 pode ser desligado

POSTGRESQL
[ ] backup final concluído
[ ] RESTORE_DRILL_PASS
[ ] export adicional, se necessário
[ ] Neon pode ser apagado

GCS
[ ] fotos exportadas ou descartadas
[ ] backups pessoais exportados ou descartados
[ ] deletion tombstones não são mais necessários
[ ] DR exportado
[ ] soft delete tratado conscientemente

GCP
[ ] schedulers removidos
[ ] Cloud Run Services removidos
[ ] Cloud Run Jobs removidos
[ ] alerts/metrics removidos ou projeto será apagado
[ ] Artifact Registry removido
[ ] secrets removidos
[ ] Service Accounts removidas
[ ] projeto pode ser apagado

LOCAL
[ ] google-services.json tratado
[ ] ADC revogada se apropriado
[ ] keystore tratado
[ ] propriedades Gradle tratadas

DOCUMENTAÇÃO
[ ] este arquivo foi preservado fora do projeto que será apagado
[ ] commit/tag final conhecido
```

---

# 33. Arquivos do repositório que são fonte de verdade

Leia primeiro:

```text
PROJECT_RULES.md
ARCHITECTURE.md
```

Arquitetura online:

```text
docs/architecture/ADR-0001-spark-online-architecture.md
docs/architecture/sync-protocol.md
docs/architecture/identity-contract.md
docs/architecture/data-classification-matrix.md
```

Firebase:

```text
docs/FIREBASE_AUTH_SETUP.md
```

Operação:

```text
docs/operations/README.md
docs/operations/CLOUD_RUN_DEPLOYMENT.md
docs/operations/OPERATIONS_CHECKLIST.md
docs/operations/DISASTER_RECOVERY.md
docs/operations/OBSERVABILITY.md
docs/operations/SECURITY.md
docs/operations/RUNBOOK.md
docs/operations/BACKUP_AND_RESTORE.md
```

Scripts:

```text
ops/gcp/lib.gcp.sh
ops/gcp/bootstrap-cloud-run.sh
ops/gcp/deploy-cloud-run.sh
ops/gcp/smoke-cloud-run.sh
ops/gcp/rollback-cloud-run.sh

ops/gcp/dr-status.sh
ops/gcp/dr-backup-now.sh
ops/gcp/dr-restore-drill.sh
ops/gcp/dr-backup-drill.sh

ops/gcp/rollback-drill.sh

ops/gcp/config-drift-audit.sh
ops/gcp/iam-audit.sh
ops/gcp/cost-audit.sh

ops/gcp/artifact-registry-retention.sh
ops/gcp/monitoring-alerts.sh
```

Backend:

```text
backend/src/config/env.schema.ts
backend/src/config/app-config.ts
backend/src/config/dr-job-config.ts

backend/src/cli/migrate-database.ts
backend/src/cli/db-backup.ts
backend/src/cli/db-restore-drill.ts
backend/src/cli/storage-audit.ts
backend/src/cli/reconcile-account-deletions.ts

backend/src/dr/
backend/src/object-storage/
backend/src/maintenance/
```

Android:

```text
app/src/main/java/com/example/data/auth/FirebaseAuthGateway.kt
app/src/main/java/com/example/data/ai/SparkBackendAiCoachGateway.kt
app/src/debug/java/com/example/data/firebase/SparkAppCheck.kt
app/src/release/java/com/example/data/firebase/SparkAppCheck.kt
app/build.gradle.kts
```

---

# 34. Notas históricas e armadilhas já encontradas

## 34.1 Não use Firebase AI Logic novamente no Android

O provider antigo foi removido.

Ter dois providers:

```text
Firebase AI Logic
+
Spark Backend → Gemini
```

criaria:

- custo duplicado;
- respostas divergentes;
- observabilidade fragmentada;
- duas autoridades de prompt/modelo.

## 34.2 `--no-traffic` no primeiro serviço Cloud Run

Não funciona como estratégia de validação do primeiro deploy.

O primeiro deploy usa serviço temporário privado.

## 34.3 Fake `gcloud` precisa drenar stdin

Nos testes shell, `--data-file=-` precisa ler stdin.

Caso contrário:

```text
openssl | fake gcloud
```

pode causar SIGPIPE sob `pipefail`.

Isso já causou CI vermelho.

## 34.4 PostgreSQL tool major precisa acompanhar o servidor

O primeiro T18.3 real revelou:

```text
Neon PG 18
pg_dump 17
→ FAIL
```

A imagem atual usa PG 18.

## 34.5 Não confie em `docker inspect RepoDigests[0]`

Com image store/containerd pode aparecer um digest local antes do Artifact Registry.

O deploy atual filtra pelo prefixo real do Artifact Registry.

## 34.6 Secret `:latest` é ruim para rastreabilidade

Revision deve ser pinada em versão.

## 34.7 Restore nunca é "por cima"

Destino deve ser limpo.

Caso contrário objetos novos podem sobreviver a um snapshot antigo.

## 34.8 HMAC de deletion é histórica

Não é uma chave descartável.

Ela liga UID ↔ tombstone ao longo do tempo.

## 34.9 Backup só existe depois do manifesto

Dump sem manifesto é incompleto.

## 34.10 Auditor não apaga

`storage-audit` é diagnóstico.

Nunca transformar auditoria em garbage collector automático sem uma tarefa específica.

## 34.11 Android continua local-first

Mesmo depois de toda essa infraestrutura:

```text
Cloud fora
Firebase fora
Gemini fora
```

não pode impedir o treino local básico.

---

# Apêndice A — comandos rápidos de inventário

```bash
export SPARK_GCP_PROJECT=project-47b17b25-909d-4ae8-943
export SPARK_FIREBASE_PROJECT=spark-36b11
export SPARK_GCP_REGION=southamerica-east1

echo "=== SERVICES ==="
gcloud run services list \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"

echo "=== JOBS ==="
gcloud run jobs list \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION"

echo "=== SCHEDULER ==="
gcloud scheduler jobs list \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

echo "=== SECRETS ==="
gcloud secrets list \
  --project "$SPARK_GCP_PROJECT"

echo "=== SERVICE ACCOUNTS ==="
gcloud iam service-accounts list \
  --project "$SPARK_GCP_PROJECT"

echo "=== ARTIFACT REGISTRY ==="
gcloud artifacts repositories list \
  --project "$SPARK_GCP_PROJECT" \
  --location "$SPARK_GCP_REGION"

echo "=== BUCKETS ==="
gcloud storage buckets list \
  --project "$SPARK_GCP_PROJECT"

echo "=== DR ==="
ops/gcp/dr-status.sh

echo "=== DRIFT ==="
ops/gcp/config-drift-audit.sh

echo "=== IAM ==="
ops/gcp/iam-audit.sh

echo "=== COST ==="
ops/gcp/cost-audit.sh
```

---

# Apêndice B — URLs e IDs conhecidos

```text
Repo:
github.com/IgorRibeiro98/workout

Firebase:
spark-36b11

GCP infra:
project-47b17b25-909d-4ae8-943
project number: 965678405850

Region:
southamerica-east1

Backend URL validada na primeira implantação:
https://spark-backend-965678405850.southamerica-east1.run.app

Bucket:
spark-private-assets-prod

Artifact Registry:
spark

Android applicationId:
com.aistudio.workout.v2
```

Sempre prefira descobrir o estado real em vez de assumir que um valor histórico continua igual:

```bash
gcloud run services describe spark-backend \
  --project "$SPARK_GCP_PROJECT" \
  --region "$SPARK_GCP_REGION" \
  --format='yaml(status.url,status.latestReadyRevisionName,status.traffic)'
```

---

# Apêndice C — referências externas para descomissionamento

Regras externas consultadas para esta documentação, válidas no snapshot de 2026-09-11:

- Google Cloud Resource Manager — exclusão/restauração de projetos:
  - projeto entra em janela de recuperação de 30 dias;
  - billing é desconectado;
  - alguns recursos podem ser excluídos antes;
  - billing não volta automaticamente ao restaurar.
- Cloud Run:
  - excluir Service é permanente e remove revisions;
  - Artifact Registry não é apagado junto.
- Cloud Run Jobs:
  - excluir Job encerra execuções em andamento.
- Cloud Storage:
  - `gcloud storage buckets update --clear-soft-delete gs://BUCKET` desabilita soft delete;
  - objetos já soft-deleted permanecem até a retenção terminar;
  - `gcloud storage rm --recursive gs://BUCKET` remove conteúdo/bucket.
- Neon:
  - excluir projeto é permanente;
  - remove computes, branches, databases e roles;
  - em plano pago, apagar projetos não substitui necessariamente downgrade/cancelamento do plano.
- Google Play:
  - cancelar publicação impede novos downloads, mas não remove instalações existentes;
  - exclusão do app depende de elegibilidade e pode afetar a reutilização do package name.

Como essas políticas podem mudar, consulte a documentação oficial novamente antes de uma exclusão definitiva.

---

# Encerramento

A forma mais curta de lembrar a história é:

```text
T14:
Android falava diretamente com IA.

T16:
criamos a fronteira online e movemos IA para o backend.

T17:
o backend virou plataforma de identidade social, FCM, mídia e exclusão segura.

T18:
transformamos isso em infraestrutura de produção:
PostgreSQL + GCS + Cloud Run + DR + operação.
```

A regra que permaneceu a mesma do início ao fim:

> **o Spark deve continuar útil e seguro mesmo quando a nuvem estiver indisponível.**

E a regra operacional que fecha a arquitetura atual:

> **nenhum dado é considerado protegido só porque existe um backup; ele só está protegido quando o restore foi provado.**
