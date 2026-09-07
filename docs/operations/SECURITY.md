# Spark — Segurança operacional

> **Estado:** `IMPLEMENTED` (código, configuração, gates) · `MANUAL SETUP REQUIRED` (segredos reais
> na VPS) · `NOT VERIFIED` em produção real.

## Modelo de ameaças

Curto de propósito (§Etapa 2). O que está listado é o que uma VPS com um backend, um SQLite e dados
pessoais de um grupo pequeno realmente enfrenta.

| Ameaça | Consequência | Mitigação |
| --- | --- | --- |
| **Vazamento de credencial** | Acesso ao Firebase, ao Gemini ou aos backups | Nada versionado; teste varre a árvore; `.gitignore`/`.dockerignore`; credencial por caminho montado somente-leitura; `600` nos arquivos |
| **Acesso entre contas** | Um usuário lê o dado de outro | `uid` vem **só** do token verificado; corpo e query não influenciam identidade; dado de outra conta é `404`, nunca `403` |
| **Perda do banco** | Backups, sync e change log de todo mundo | Snapshot consistente diário + off-site criptografado + ensaio de restauração |
| **Disco cheio** | SQLite para de escrever; falha silenciosa | Rotação de log (10 MB × 5 por container); `check-health.sh` alerta em 80 % e falha em 90 % |
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
| Service account do Firebase Admin | `/opt/spark/secrets/firebase-admin.json` (`600`, dono `spark`) | Bind mount **somente leitura** em `/run/secrets/firebase-admin.json`; `GOOGLE_APPLICATION_CREDENTIALS` aponta o caminho | **Não** |
| Chave do Gemini | `/opt/spark/secrets/backend.env` (`600`) | `env_file` do Compose → `GEMINI_API_KEY` | **Não** |
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

Use uma credencial com o **menor escopo necessário** para verificar Firebase ID Token (§116). O
backend só chama `verifyIdToken`: ele não lê usuários, não escreve, não usa Firestore, Storage,
Messaging ou Functions — e há teste estrutural que falha se algum desses SDKs for importado
(`test/dependency-security.spec.ts`). Não conceda papel amplo por conveniência.

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
  `backup-logging.spec.ts`, `sync-persistence.spec.ts`).

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
install -m 600 -o spark -g spark nova.json /opt/spark/secrets/firebase-admin.json
docker compose -f docker-compose.prod.yml up -d backend
curl -s -o /dev/null -w '%{http_code}\n' https://api.<dominio>/v1/auth/me    # precisa ser 401
# 2. valide login no app; 3. só então apague a chave antiga no console
```

Impacto: durante o restart, rota autenticada indisponível por segundos. Se a nova credencial
estiver errada, o processo **não sobe** (`REQUIRE_FIREBASE_ADMIN=true`) — o que é o comportamento
desejado: falha visível em vez de 503 silencioso.

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
mutações de sync dos mesmos agregados. Contexto do Coach quando o usuário pede uma análise. **Não
saem:** mídia local, preferências de aparelho, catálogo, dado derivado (XP, conquistas, PRs,
streak) e credenciais.

**Onde fica.** No SQLite da VPS, como texto opaco por agregado — o servidor não desmonta treino em
colunas consultáveis. E, criptografado, no storage de backup off-site.

**Como é protegido.** HTTPS em trânsito (Caddy/Let's Encrypt); permissão `700` no diretório e `600`
no arquivo; ownership derivado do token verificado, com dado de outra conta indistinguível de
inexistente; criptografia no backup off-site.

**O que nunca é registrado em log.** `Authorization`, token, corpo de requisição, payload de backup,
payload de sync, prompt, resposta do modelo, nome de treino, nota, medida. Só metadata técnica —
`requestId`, prefixo de uid, contagens, duração, status. Verificado por testes que enviam marcas
reconhecíveis e varrem a saída real do logger.

**Como é removido.** Hoje: a retenção da T16.4 remove backups antigos por conta
(`BACKUP_RETENTION_COUNT`), e a retenção do restic remove snapshots antigos do servidor.

### Exclusão de conta — pendência registrada

**Não existe hoje** um caminho para o usuário apagar a conta e todo o dado dela do servidor.

```text
uso fechado (pessoal/família)      pendência controlada
distribuição pública com dado
pessoal armazenado online          PRE-RELEASE BLOCKER
```

Isso está registrado explicitamente (§149) e **não** faz parte do escopo da T16.8. Não é um
esquecimento: é uma decisão de escopo, e ela precisa ser resolvida antes de o app ser distribuído
publicamente com armazenamento online de dado pessoal.
