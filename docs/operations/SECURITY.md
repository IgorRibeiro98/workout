# Spark — Segurança operacional

> **Estado:** `IMPLEMENTED` (código, configuração, gates) · `MANUAL SETUP REQUIRED` (segredos reais
> na VPS) · `NOT VERIFIED` em produção real.

## Modelo de ameaças

Curto de propósito (§Etapa 2). O que está listado é o que uma VPS com um backend, um PostgreSQL gerenciado e dados
pessoais de um grupo pequeno realmente enfrenta.

| Ameaça | Consequência | Mitigação |
| --- | --- | --- |
| **Vazamento de credencial** | Acesso ao Firebase, ao Gemini ou aos backups | Nada versionado; teste varre a árvore; `.gitignore`/`.dockerignore`; credencial por caminho montado somente-leitura; `600` nos segredos só do host e `640` no grupo compartilhado na service account |
| **Acesso entre contas** | Um usuário lê o dado de outro | `uid` vem **só** do token verificado; corpo e query não influenciam identidade; dado de outra conta é `404`, nunca `403` |
| **Perda do banco** | Backups, sync e change log de todo mundo | Snapshot consistente diário + off-site criptografado + ensaio de restauração |
| **Disco cheio** | Mídia e ledger param de ser escritos; backup falha na área de trabalho | Rotação de log (10 MB × 5 por container); `check-health.sh` alerta em 80 % e falha em 90 % |
| **Deploy ruim** | Servidor fora | Tag por commit + healthcheck obrigatório + rollback automático |
| **Migration ruim** | Schema inconsistente | Backup pré-deploy obrigatório; migration antes de escutar a porta; readiness exige schema aplicado |
| **Backend exposto** | API sem TLS na internet | `docker-compose.prod.yml` não publica a porta do backend; só o Caddy escuta 80/443 |
| **Abuso de IA** | Custo inesperado | Quota por conta e global (durável), 1 chamada ativa por conta, dedupe, sem retry; `AI_ENABLED=false` como interruptor |
| **Abuso de sync** | Carga contínua sobre a VPS | Limite por conta (60/min), tetos de corpo e de lote, `SYNC_WRITE_ENABLED=false` como interruptor |
| **Roubo do backup** | Dado pessoal de todos os usuários | Criptografia do restic antes do envio; a senha nunca sai do gerenciador de segredos |
| **Falha de backup despercebida** | Descobrir no dia do desastre | Estado gravado a cada execução; idade do último sucesso monitorada; falha sai com código ≠ 0 |

Fora do modelo, por decisão: atacante com root na VPS (aí a discussão é outra), ataque físico ao
provedor, e DDoS volumétrico (uma VPS pessoal não se defende disso; o Caddy limita o que dá).

## Matriz de segredos

**Nenhum valor aparece aqui, e nenhum pode aparecer** (§114).

| Segredo | Onde vive | Como chega ao runtime | Versionado? |
| --- | --- | --- | --- |
| Service account do Firebase Admin | `/opt/spark/secrets/firebase-admin.json` (`640`, `spark:spark-data`) | Bind mount **somente leitura** em `/run/secrets/firebase-admin.json`; `GOOGLE_APPLICATION_CREDENTIALS` aponta o caminho | **Não** |
| Chave do Gemini | `/opt/spark/secrets/backend.env` (`600`) | `env_file` do Compose → `GEMINI_API_KEY` | **Não** |
| `DATABASE_URL` do PostgreSQL (carrega a senha do banco) | `/opt/spark/repo/backend/.env` (`600`, fora do Git) | `${DATABASE_URL:?}` no Compose; `ops/lib.sh` a lê do mesmo arquivo para `pg_dump`, por variável de ambiente do container de ferramentas — nunca por argumento, nunca em log ou em `backup-status.json` | **Não** |
| Senha do repositório de backup | `/opt/spark/secrets/restic-password` (`600`) + cópia fora da VPS | `RESTIC_PASSWORD_FILE` lido por `ops/lib.sh` | **Não** |
| Credencial do storage off-site | `/opt/spark/secrets/backup.env` (`600`) | `EnvironmentFile` da unidade systemd | **Não** |
| Chave privada de TLS | Volume `caddy-data` (gerenciada pelo Caddy) | Nunca sai de lá; o Node não a vê | **Não** |
| Chave SSH do operador | Máquina do operador | `~/.ssh/authorized_keys` na VPS | **Não** |
| `google-services.json` do Android | Console do Firebase; máquina de quem constrói | Plugin do Gradle em build local; o CI gera um **sintético e inerte** | **Não** (§`.gitignore`) |

### Por que a service account entra por caminho, e não por valor

Um JSON colado numa variável de ambiente aparece em `docker inspect`, em `ps` e em qualquer dump de
ambiente. Um arquivo montado somente-leitura não aparece em nenhum dos três, e o container não
consegue reescrevê-lo. A chave do Gemini é a exceção conhecida — ela é variável de ambiente porque o
SDK a espera assim, e o tradeoff está registrado: ela é visível a quem já tem acesso ao daemon
Docker da máquina, que é acesso equivalente a root (§54).

### Escopo da service account

Use uma credencial com o **menor escopo necessário** para verificar Firebase ID Token, apagar
usuário (`deleteUser`, T17.6) e enviar FCM (`SOCIAL_PUSH_ENABLED=true`, T17.5). O backend não lê
Firestore, Storage ou Functions do Firebase — e há teste estrutural que falha se algum desses SDKs
for importado (`test/dependency-security.spec.ts`). Não conceda papel amplo por conveniência, e
nunca `Owner`/`Editor` do projeto GCP.

### Cloud Run (T18.2) — a mesma matriz, sem arquivo

No Cloud Run nenhum dos segredos acima entra por bind mount — não há filesystem persistente para
montar nada. A matriz muda de forma, não de princípio:

| Segredo | Onde vive | Como chega ao runtime | IAM |
| --- | --- | --- | --- |
| Credencial do Firebase Admin | identidade da Service Account anexada à revision (`applicationDefault()`) | ADC — nenhum arquivo, nenhuma variável de credencial | Service Account `spark-backend-runtime`; `roles/firebaseauth.admin` + `roles/firebasecloudmessaging.admin`, nunca `Owner`/`Editor` |
| `DATABASE_URL` (pooled) | Secret Manager (`spark-database-url`) | `--set-secrets` na revision | `spark-backend-runtime` — `secretAccessor` só deste secret |
| `DATABASE_URL_DIRECT` | Secret Manager (`spark-database-url-direct`) | `--set-secrets` **só no Job de migration** | `spark-backend-migrator` — nunca `spark-backend-runtime` |
| `GEMINI_API_KEY` | Secret Manager (`spark-gemini-api-key`) | `--set-secrets` na revision | `spark-backend-runtime` |
| `ACCOUNT_DELETION_HMAC_KEY` | Secret Manager (`spark-account-deletion-hmac-key`) | `--set-secrets` na revision | `spark-backend-runtime` |
| Credencial do bucket (GCS) | identidade da Service Account anexada (ADC) | nenhum arquivo, nenhuma `GCS_PRIVATE_KEY`/`GCS_CLIENT_EMAIL` | mesma runtime SA, `roles/storage.objectAdmin` só sobre `spark-private-assets-prod` |

| `DATABASE_URL_DIRECT` (backup de DR, T18.3) | o mesmo secret `spark-database-url-direct` | `--set-secrets` **só no Job `spark-db-backup`** | `spark-backend-backup` — o `pg_dump` recebe a senha por variável da libpq, nunca por argv |

`roles/secretmanager.secretAccessor` é concedido **por secret**, nunca
`roles/secretmanager.admin` sobre o projeto — nenhum script de `ops/gcp/` imprime valor de secret.
Ver [`docs/operations/CLOUD_RUN_DEPLOYMENT.md`](./CLOUD_RUN_DEPLOYMENT.md) para o bootstrap e o
deploy completos.

**Versões pinadas (T18.3 §19).** Nenhuma revision referencia `secret:latest`: o deploy resolve a
versão habilitada mais recente por metadata e a grava na revision. Rotacionar um secret é
`gcloud secrets versions add` **+ um deploy** — sem o deploy, produção continua na versão anterior,
por desenho. `ops/gcp/config-drift-audit.sh` aponta `DRIFT` quando uma revision pina uma versão que
já não é a habilitada mais recente. `ops/gcp/iam-audit.sh` confirma que cada secret tem exatamente
os accessors esperados e que nenhuma das quatro Service Accounts tem chave JSON gerenciada por
usuário.

### TLS do PostgreSQL: `verify-full`, explícito (T18.3 §20)

O `pg` 8 trata `sslmode=require` (a URL que o Neon entrega) como `verify-full` — e avisa nos logs de
produção (`SECURITY WARNING`) que a próxima major (`pg` 9) adotará a semântica libpq, em que
`require` **não** verifica certificado nem hostname. A política do Spark é `verify-full`: o
certificado do Neon é emitido por CA pública presente na cadeia do Node, então a verificação
completa funciona sem `sslrootcert`. `backend/src/database/postgres-url.ts#normalizeSslMode`
reescreve `require|prefer|verify-ca` para `verify-full` na string efetiva (o que já valia, agora
declarado — e imune à mudança de major); `AppConfig.missingRequirements()` **recusa subir em
produção** com `sslmode=disable|no-verify` ou `uselibpqcompat=true` sem `verify-full`;
`database.ready` loga `sslMode` e `sslNormalized`. O `pg_dump`/`pg_restore` dos Jobs de DR recebem
`PGSSLMODE=verify-full` + `PGSSLROOTCERT=system` pela libpq. `backend/test/postgres-url.spec.ts`
prova a normalização, que o aviso não dispara para a string normalizada, e falha deliberadamente
quando a major do `pg` mudar — para a atualização não passar despercebida.

### Deploy do GitHub Actions: nenhuma chave, nenhum segredo do GCP (T18.3.2)

A identidade que publica produção pelo GitHub Actions (`spark-github-deployer`) nunca tem chave
JSON. `.github/workflows/deploy-backend.yml` troca o token OIDC do próprio job
(`permissions.id-token: write`) por uma credencial federada de curta duração via **Workload
Identity Federation** (`google-github-actions/auth`, ação oficial do Google) — o mesmo princípio de
"nenhum arquivo, nenhuma variável de credencial" que já vale para o Firebase Admin e o bucket na
tabela acima, agora aplicado à identidade que faz o deploy em si.

O `Environment: production` do GitHub não guarda nenhum `secrets.*` do GCP — só `vars.*`
(`SPARK_GCP_PROJECT`, `SPARK_FIREBASE_PROJECT`, `SPARK_GCP_REGION`,
`GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`), porque nenhum desses valores é
sensível — todos aparecem em texto claro nesta documentação. `GCP_CREDENTIALS` e um
`credentials_json` de Service Account são explicitamente **proibidos** neste workflow (verificado
estaticamente por `ops/tests/deploy-backend-workflow.test.sh`).

O Provider de identidade (`workout`, no pool `github-actions`) só aceita tokens OIDC que casem as
três condições ao mesmo tempo: `repository == 'IgorRibeiro98/workout'`,
`ref == 'refs/heads/main'` e `environment == 'production'` — nunca o pool inteiro, nunca só o
repositório. `spark-github-deployer` nunca tem `Owner`, `Editor`, `secretmanager.secretAccessor`
(só `secretmanager.viewer` — lista/lê metadata de versão, nunca o valor) ou
`iam.serviceAccountKeyAdmin`. A matriz completa de IAM está em
[`CLOUD_RUN_DEPLOYMENT.md` §20.2](./CLOUD_RUN_DEPLOYMENT.md#202-matriz-de-iam-do-deployer-spark-github-deployer),
e `ops/gcp/bootstrap-github-deploy.sh --verify` confere isso contra o IAM real, sem corrigir nada.

## O que já é garantido por teste

Não por disciplina — por suíte que quebra:

- nenhum arquivo versionado do backend contém chave privada, service account ou API key
  (`auth-config.spec.ts`);
- `.gitignore` e `.dockerignore` barram `service-account*.json`, `firebase-adminsdk*.json`, `*.pem`,
  `*.key`;
- o `Dockerfile` não copia credencial para a imagem;
- não existe chave de ambiente capaz de desligar a autenticação (`AUTH_DISABLED` não existe, e há
  teste que verifica que ele não entrou no schema);
- o aplicativo Android não contém chave de provider de IA, nem token de App Check embutido, nem
  provedor de depuração no source set de release (`AiCoachSecurityConfigTest`);
- release não permite texto claro, e o endereço de release precisa ser HTTPS em host público
  (`SparkBackendEndpointTest`, `SparkProductionNetworkConfigTest`);
- `Authorization`, cookie e corpo nunca aparecem em log (`http.spec.ts`, `ai-logging.spec.ts`,
  `backup-logging.spec.ts`, `sync-persistence.spec.ts`);
- `deploy-backend.yml` nunca tem `credentials_json`, `GCP_CREDENTIALS`, `secretAccessor`,
  `private_key` nem `GOOGLE_APPLICATION_CREDENTIALS` explícito, só `workflow_dispatch` (nunca
  `push`/`pull_request`), `permissions` mínimas e `environment: production`
  (`ops/tests/deploy-backend-workflow.test.sh`);
- `ops/gcp/bootstrap-github-deploy.sh` nunca cria chave de Service Account, nunca concede
  Owner/Editor/`secretmanager.secretAccessor` ao deployer, e um Provider existente com
  issuer/condição diferente do esperado aborta em vez de ser corrigido em silêncio
  (`ops/tests/bootstrap-github-deploy.test.sh`).

## Histórico de credenciais no Git

Uma credencial de depuração foi removida em fase anterior (o `AiCoachSecurityConfigTest` existe em
parte por causa disso). A varredura desta tarefa não encontrou **nenhuma** credencial viva na árvore
atual — nem chave privada, nem service account, nem API key.

O que continua valendo, e é uma decisão consciente (§52):

- **a história do Git não foi reescrita.** Reescrever história é destrutivo, quebra todo clone
  existente e não remove nada de quem já clonou. Para um segredo que já vazou, a única mitigação
  que funciona é **rotacionar**, e não esconder;
- **qualquer credencial que já tenha estado em um commit deve ser considerada comprometida** e
  rotacionada, independentemente de o commit ainda ser alcançável.

## Rotação

Nenhuma rotação é urgente hoje. O runbook existe para quando for (§115):

### Chave do Gemini

```bash
# 1. gere a chave nova no Google AI Studio (não revogue a antiga ainda)
$EDITOR /opt/spark/secrets/backend.env          # GEMINI_API_KEY=<nova>
cd /opt/spark/repo/backend
docker compose -f docker-compose.prod.yml up -d backend
# 2. valide o Coach no app com uma conta de teste
# 3. só então revogue a antiga no console
```

Impacto: nenhum, se a ordem for essa. O núcleo do Spark não depende do Coach.

### Service account do Firebase Admin

```bash
# 1. gere uma chave nova no console (Configurações → Contas de serviço)
#
# `640` no grupo `spark-data`, e NÃO `600`: o container roda como `node` (uid 1000) e não é o dono
# do arquivo — com `600` ele não a lê, e desde a T16.8.1 isso **derruba o startup** em vez de virar
# 503 silencioso. Ver PRODUCTION_DEPLOYMENT.md, "Usuários, grupos e permissões".
install -m 640 -o spark -g spark-data nova.json /opt/spark/secrets/firebase-admin.json
docker compose -f docker-compose.prod.yml up -d backend
curl -s -o /dev/null -w '%{http_code}\n' https://api.<dominio>/v1/auth/me    # precisa ser 401
# 2. valide login no app; 3. só então apague a chave antiga no console
```

Impacto: durante o restart, rota autenticada indisponível por segundos. Se a nova credencial
estiver errada, o processo **não sobe** (`REQUIRE_FIREBASE_ADMIN=true`) — o que é o comportamento
desejado: falha visível em vez de 503 silencioso.

Desde a T16.8.1 "errada" quer dizer o que a palavra deveria ter significado desde o começo. O
startup lê o arquivo, faz o parse, confere a forma de service account e inicializa o Admin SDK,
nesta ordem — arquivo ausente, sem permissão de leitura, JSON truncado, campo faltando ou chave
privada inválida derrubam o processo. Antes, a checagem era se `GOOGLE_APPLICATION_CREDENTIALS`
era uma string não vazia, o que é verdade em todos esses casos: o servidor subia, respondia
`/health/ready` 200 e devolvia `503` em toda requisição autenticada.

A verificação é **local e offline**: nada de chamada ao Google no startup, porque isso tornaria a
subida do Spark dependente da disponibilidade de um terceiro. O que ela não pode cobrir — o projeto
apagado no console, a chave revogada — continua sendo `503` em runtime, como sempre foi.

### Credencial do storage de backup

Troque em `backup.env`, rode `ops/backup.sh --tag rotacao` e confira o estado. Só revogue a antiga
depois de um backup bem-sucedido com a nova.

### Senha do repositório restic

`restic key add` cria uma senha nova **sem invalidar** a antiga; `restic key remove` remove a
anterior depois de você confirmar que a nova funciona. Nunca troque a senha sem antes provar que a
nova abre o repositório — e atualize a cópia fora da VPS no mesmo momento.

## Dependências

Estado desta tarefa, após remediação deliberada:

```text
critical  0
high      0     (7 antes: @nestjs/core, @nestjs/platform-express, path-to-regexp, multer, glob, picomatch, webpack)
moderate  6     todas na mesma cadeia transitiva de firebase-admin, NÃO alcançável
low       0
```

**`npm audit fix --force` não foi usado** (§95). As correções foram atualizações explícitas dentro
da mesma major: `@nestjs/common|core|platform-express|testing` para `11.2.3`, `@nestjs/cli` para
`11.0.24`, `@nestjs/schematics` para `11.1.0`. A suíte inteira passou antes e depois.

As 6 moderadas remanescentes são `firebase-admin` → `@google-cloud/storage` →
`retry-request`/`teeny-request`/`gaxios` → `uuid`. Elas ficam porque:

1. `firebase-admin` já está na **versão mais recente** (14.3.0). A "correção" que o `npm audit`
   sugere é regredir para a 10.3.0 — uma major para trás, o que trocaria seis moderadas por um
   downgrade de segurança maior;
2. **elas não são alcançáveis.** O backend importa apenas `firebase-admin/app` e
   `firebase-admin/auth`; `@google-cloud/storage` nunca entra no grafo de módulos. Isso não é
   suposição: `test/dependency-security.spec.ts` carrega exatamente esses dois módulos e verifica
   que os pacotes vulneráveis não são carregados — e falha se alguém importar Storage, Firestore,
   Database, Messaging ou Functions no futuro.

O gate de CI (`npm audit --omit=dev --audit-level=high`) barra qualquer vulnerabilidade **alta ou
crítica em dependência de runtime**. Moderadas não bloqueiam — e o teste acima é o que impede a
avaliação acima de ser herdada sem ser refeita.

## Privacidade — escopo técnico

Sem construir arcabouço jurídico (§148). O que é tecnicamente verdade:

**Que dado sai do aparelho.** Só com conta e só por ação explícita: o snapshot de backup (treinos,
sessões concluídas, séries, medidas corporais, check-ins, programas, exercícios pessoais) e as
mutações de sync dos mesmos agregados. Contexto do Coach quando o usuário pede uma análise. Desde a
**T17.0**, e só se o usuário ativar os recursos sociais, o **nome social** que ele mesmo escolheu.
**Não saem:** mídia local, preferências de aparelho, catálogo, dado derivado (XP, conquistas, PRs,
streak), e-mail e credenciais.

**O que o social publica.** A T17.0 criou identidade (`socialId`, `friendCode`, nome social) e
privacidade; a **T17.1** acrescentou o grafo — amizade bilateral, pedidos e descoberta por código.
O que uma pessoa consegue ver de outra continua sendo **o mínimo**: `socialId` e `displayName`, e
nada mais. Não existe atividade, feed, ranking, nível, sequência, último treino nem medida — e ser
amigo **não** concede acesso a treino, backup, sync, histórico, e-mail ou Firebase UID.

O estado permanece: social opt-in (desligado até o usuário ativar), `activitySharingEnabled =
false`, sem busca pública por nome, sem busca por e-mail, sem listagem global, sem sugestão de
pessoas e sem e-mail no perfil social. A **única** descoberta é o lookup por `friendCode` exato,
autenticado, com teto próprio de requisições (20/min por conta) e cuja resposta para código
malformado, inexistente e de perfil desativado é a mesma — o que impede a rota de virar oráculo de
existência. O envio de pedidos tem teto próprio (15/min) para que um bug em laço não vire centenas
de convites.

Nenhum dado de treino entra em `social_profiles`, `friend_requests` ou `friendships`, e o único
caminho futuro para progresso social é uma projeção explícita (`SocialProjection`) — ver
[`docs/architecture/social-domain.md`](../architecture/social-domain.md) e
[`docs/architecture/friendship-contract.md`](../architecture/friendship-contract.md).

**QR Code (T17.1).** O convite carrega `spark://friend/v1/<friendCode>` e mais nada: sem Firebase
UID, sem e-mail, sem token, sem `socialId`, sem `deviceId` e sem endereço de servidor. Ele é gerado
no aparelho, e o leitor **não executa** o que a câmera capturou — nada de `Intent`, navegação ou
`WebView`. Ler QR também **não custa permissão de câmera**: o Google Code Scanner abre a câmera na
UI do Play Services, e `android.permission.CAMERA` não existe no manifesto do Spark (há teste).

**Onde fica.** No PostgreSQL de `DATABASE_URL`, como texto opaco por agregado — o servidor não desmonta treino em
colunas consultáveis. E, criptografado, no storage de backup off-site.

**Como é protegido.** HTTPS em trânsito (Caddy/Let's Encrypt); `2770` no diretório de dados e
`spark:spark-data` como dono — alcançável pelo operador e pelo container, e por mais ninguém na
máquina; ownership derivado do token verificado, com dado de outra conta indistinguível de
inexistente; criptografia no backup off-site.

`777` e `666` são proibidos em caminho operacional, e a proibição é verificada: um passo do CI
(`Nenhum chmod aberto`) recusa o commit, e `ops/check-health.sh` acusa um diretório de dados que
tenha perdido o setgid ou ganhado permissão para "outros". A tentação é real — quase todo problema
de permissão "some" com `chmod 777` —, e o custo é o dado pessoal de todo mundo que usa o servidor
ficar legível por qualquer processo da máquina.

**O que nunca é registrado em log.** `Authorization`, token, corpo de requisição, payload de backup,
payload de sync, prompt, resposta do modelo, nome de treino, nota, medida e — desde a T17.0 —
nome social, `friendCode` e `socialId`. Só metadata técnica —
`requestId`, prefixo de uid, contagens, duração, status. Verificado por testes que enviam marcas
reconhecíveis e varrem a saída real do logger.

**Como é removido.** Hoje: a retenção da T16.4 remove backups antigos por conta
(`BACKUP_RETENTION_COUNT`), e a retenção do restic remove snapshots antigos do servidor.

### Exclusão de conta — pendência registrada

**Não existe hoje** um caminho para o usuário apagar a conta e todo o dado dela do servidor.

Desativar os recursos sociais (T17.0) **não** é isso, e a distinção precisa ficar registrada:

```text
social disabled  ≠  conta Firebase apagada
social disabled  ≠  Conta Spark apagada
```

Desativar preserva o perfil (`status = DISABLED`) justamente para que reativar devolva o mesmo
`socialId` e o mesmo `friendCode` — e, desde a T17.1, para que as **relações** voltem inteiras:
amizades e pedidos pendentes ficam suspensos, não apagados.

A T17.0 e a T17.1 **aumentaram** o escopo do que uma exclusão completa precisará cobrir: além de
Firebase, backups, snapshots de sync e tombstones, ela terá de remover `social_profiles`,
`social_privacy_settings`, `friend_requests` e `friendships`.

E a T17.1 **muda a natureza da pendência**: até aqui, todo dado do servidor pertencia a uma conta
só, e apagá-lo era uma decisão de uma pessoa. Uma amizade é um fato sobre **duas** — apagar a conta
de A altera o que B vê. A exclusão terá de decidir explicitamente o que acontece com o outro lado
(a amizade some da lista de B, e é isso que as `FK ... ON DELETE CASCADE` já preparam), em vez de
descobrir isso no dia da implementação. Isso reforça, e não enfraquece, o status abaixo.

```text
uso fechado (pessoal/família)      pendência controlada
distribuição pública com dado
pessoal armazenado online          PRE-RELEASE BLOCKER
```

Isso está registrado explicitamente (§149) e **não** faz parte do escopo da T16.8. Não é um
esquecimento: é uma decisão de escopo, e ela precisa ser resolvida antes de o app ser distribuído
publicamente com armazenamento online de dado pessoal.
