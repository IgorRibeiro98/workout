# Spark — Segurança operacional

> **Estado:** `IMPLEMENTED` (código, configuração, gates) · `MANUAL SETUP REQUIRED` (segredos reais
> na VPS) · `NOT VERIFIED` em produção real.

## Modelo de ameaças

Curto de propósito (§Etapa 2). O que está listado é o que uma VPS com um backend, um SQLite e dados
pessoais de um grupo pequeno realmente enfrenta.

| Ameaça | Consequência | Mitigação |
| --- | --- | --- |
| **Vazamento de credencial** | Acesso ao Firebase, ao Gemini ou aos backups | Nada versionado; teste varre a árvore; `.gitignore`/`.dockerignore`; credencial por caminho montado somente-leitura; `600` nos segredos só do host e `640` no grupo compartilhado na service account |
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
| Service account do Firebase Admin | `/opt/spark/secrets/firebase-admin.json` (`640`, `spark:spark-data`) | Bind mount **somente leitura** em `/run/secrets/firebase-admin.json`; `GOOGLE_APPLICATION_CREDENTIALS` aponta o caminho | **Não** |
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

**Onde fica.** No SQLite da VPS, como texto opaco por agregado — o servidor não desmonta treino em
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
