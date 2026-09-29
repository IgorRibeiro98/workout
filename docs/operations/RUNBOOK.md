# Spark — Runbook operacional

> **Estado:** `IMPLEMENTED` (procedimentos e ferramentas) · `NOT VERIFIED` em produção real.
>
> Cada seção começa pelo **sintoma**, não pela causa: quem chega aqui está vendo alguma coisa
> quebrada e não sabe ainda o que é.

## Diagnóstico em 30 segundos

```bash
cd /opt/spark/repo && ops/check-health.sh
```

Ele responde de uma vez: o backend está no ar? O banco está acessível? Quanto disco resta? Quando
foi o último backup bem-sucedido? Sai com código diferente de zero quando algo está errado.

```bash
docker compose -f /opt/spark/repo/backend/docker-compose.prod.yml ps
docker compose -f /opt/spark/repo/backend/docker-compose.prod.yml logs --tail 100 backend
curl -s https://api.<dominio>/health/ready          # a internet chega até ele?
```

> **O backend não publica porta no host.** `curl http://127.0.0.1:8080/health/ready` na VPS **não
> funciona, por construção**: quem escuta 80/443 é o Caddy, e `docker-compose.prod.yml` só declara
> `expose`. Se você precisar falar direto com o processo, é por dentro do container:
>
> ```bash
> cd /opt/spark/repo/backend
> docker compose -f docker-compose.prod.yml exec -T backend \
>   node -e "fetch('http://127.0.0.1:8080/health/ready').then(r=>r.text()).then(console.log)"
> ```
>
> É exatamente isso que `ops/check-health.sh` e `ops/deploy.sh` fazem. **Publicar a porta 8080 para
> "facilitar o diagnóstico" é reabrir `http://IP:8080`**, que a T16.8 fechou de propósito.

### O health interno passa e o público não

São perguntas diferentes, e a distinção economiza horas: o backend serve, mas a internet não chega
até ele. Olhe, nesta ordem, DNS (`dig +short api.<dominio>`), certificado
(`docker compose -f docker-compose.prod.yml logs caddy | tail -50`), firewall (`sudo ufw status`) e
por último o próprio Caddy. O banco, as migrations e a aplicação estão fora de suspeita — o health
interno já respondeu por eles.

## O que NUNCA está quebrado

Antes de qualquer urgência: **o Spark no aparelho continua funcionando**. Com a VPS fora, sem
internet, sem Firebase e sem Gemini, o usuário abre o app, vê os treinos, executa um treino,
registra séries, conclui e consulta o histórico local. O que para é a nuvem — backup, restore,
sync e Coach — e cada um deles falha como erro tipado e recuperável, não como travamento.

Isso significa que **nenhum incidente aqui é uma emergência de minutos**. Não improvise sob
pressão; siga o procedimento.

---

## O backend está fora

**Sintoma:** o app diz que o servidor está indisponível; `/health/live` não responde.

```bash
docker compose -f docker-compose.prod.yml ps          # o container existe? está reiniciando?
docker compose -f docker-compose.prod.yml logs --tail 100 backend
```

| O que você vê | Causa provável | O que fazer |
| --- | --- | --- |
| `Configuração inválida:` | Variável de ambiente errada | Corrija `.env`/`backend.env` e suba de novo |
| `Configuração incompleta:` | `REQUIRE_FIREBASE_ADMIN=true` sem `GOOGLE_APPLICATION_CREDENTIALS` | Defina o caminho no compose |
| `REQUIRE_FIREBASE_ADMIN=true, mas o arquivo não pôde ser lido (EACCES)` | A service account não é legível pelo container | `install -m 640 -o spark -g spark-data` — ver "permissão" abaixo |
| `... não pôde ser lido (ENOENT)` | O arquivo não existe no caminho montado | Confira `/opt/spark/secrets/firebase-admin.json` e a montagem |
| `... o conteúdo não é JSON válido` / `faltam campos obrigatórios` | Arquivo truncado ou não é a service account | Baixe de novo do console do Firebase |
| `Falha no bootstrap:` | Banco não abre, ou migration falhou | Ver "migration falhou" e "banco corrompido" |
| Container reiniciando em laço | Configuração inválida | **Não** aumente o restart: corrija a configuração. Um laço de restart mascarando config errada é pior que o serviço parado |
| Container ausente | Nunca subiu | `ops/deploy.sh` |
| Backend ok, Caddy fora | Proxy | `docker compose logs caddy`; certificado, porta 80/443, DNS |

**Reiniciar** (a coisa segura de sempre):

```bash
docker compose -f docker-compose.prod.yml restart backend
curl -s https://api.<dominio>/health/ready
```

O SIGTERM faz o processo parar de aceitar requisições, drenar as em andamento até
`SHUTDOWN_TIMEOUT_MS` e fechar o pool de conexões do PostgreSQL. Requisição interrompida no
meio não é perda: backup e sync são idempotentes por contrato, e o cliente reenvia.

---

## `/health/ready` falha, mas `/health/live` responde

O processo está vivo e o **banco** não está utilizável. O corpo diz qual verificação falhou:

```json
{"status":"unavailable","checks":{"config":true,"database":false,"migrations":true}}
```

| Falha | Causa provável |
| --- | --- |
| `database: false` | PostgreSQL inacessível, timeout de conexão, credenciais inválidas em `DATABASE_URL` |
| `migrations: false` | Migration nova no código que não foi aplicada — quase sempre um deploy pela metade |

```bash
docker compose -f docker-compose.prod.yml logs --tail 50 backend | grep -i -E "database|postgres|pool"
```

O esperado é `/opt/spark/data` em `spark:spark-data 2770`. **Não confira se o dono é o uid 1000**:
o acesso do container vem do *grupo* compartilhado, e o uid do `spark` pode ser qualquer um (ver
[PRODUCTION_DEPLOYMENT.md](./PRODUCTION_DEPLOYMENT.md), "Usuários, grupos e permissões"). Duas
formas de quebrar, as duas silenciosas:

| O que você vê | O que aconteceu | Correção |
| --- | --- | --- |
| Modo sem o `2` inicial (`770`) | O setgid caiu; o que o container criar sai com outro grupo | `sudo chmod 2770 /opt/spark/data` |
| `SPARK_DATA_GID` errado no `.env` | O container entrou no grupo errado | `getent group spark-data \| cut -d: -f3` e corrija |

Nunca resolva isso com `chmod 777`: além de expor o dado pessoal de todo mundo à máquina inteira,
esconde o defeito em vez de corrigi-lo. `check-health.sh` acusa as duas situações acima.

O readiness **não** consulta Firebase nem Gemini, e nunca vai consultar: o Coach fora não pode
derrubar sync e backup (§61). Se `ready` está falhando, o problema é banco ou configuração.

---

## Disco cheio ou PostgreSQL sob pressão

```bash
df -h
du -sh /opt/spark/data /opt/spark/media /opt/spark/backups /var/lib/docker/containers
docker system df
```

**Nunca apague backups nem ledgers para liberar espaço** (§131). Nesta ordem, do mais seguro:

```bash
docker image prune -a                     # imagens antigas: a fonte mais provável de espaço
docker system prune                       # containers/redes parados
rm -rf /opt/spark/backups/work-*          # resíduo de backup interrompido
docker compose -f docker-compose.prod.yml restart   # drena pool e reinicia limpo
```

Se ainda faltar espaço, **aumente o disco**. Log já é rotacionado (10 MB × 5 por container), então
ele dificilmente é a causa — mas confirme com `docker system df`.

---

## Banco de Dados Inacessível ou Falha de Conexão

**Sintoma:** o log traz `ECONNREFUSED`, `connection timeout`, `out of shared memory`, ou `503` em `/health/ready`.

```bash
# 1. PARE AS ESCRITAS se necessário
docker compose -f docker-compose.prod.yml stop backend

# 2. Verifique conectividade com a URL do banco
docker compose -f docker-compose.prod.yml logs --tail 50 backend | grep -i -E "database|error|timeout"

# 3. O operador alcança o banco com as ferramentas do backup? (mesmo caminho do pg_dump)
ops/check-health.sh                                   # "banco: ... alcançável pelas ferramentas do operador"

# 4. Em caso de restore de desastre
ops/restore.sh --to /tmp/recuperacao                  # verifica sem tocar em produção
ops/restore.sh --to /tmp/recuperacao --install        # preserva pre-restore-*.dump, pg_restore em transação única
docker compose -f docker-compose.prod.yml up -d
curl -s https://api.<dominio>/health/ready
```

**Nunca** apague o banco nem o dump `pre-restore-*.dump` para "resolver" (§130). Um banco que o
provedor reporta como corrompido pode conter linhas que o último backup não tem; preservar é o que
transforma um incidente recuperável em recuperado, e apagar é o que o transforma em perda
definitiva. Se o provedor oferece PITR/branch, use-o **antes** de restaurar o dump — ele é mais
recente. No Cloud Run, o caminho é o de [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md): banco
novo restaurado do dump de DR, nunca por cima (T18.3).

Aparelhos com cursor à frente do change log restaurado recebem `CURSOR_EXPIRED` e pedem rebaseline
explícito — é o desenho da T16.7 funcionando, não um efeito colateral.

---

## Migration falhou

**Sintoma:** o deploy não termina; o log mostra erro em `runMigrations`; `ready` não fica verde.

O processo **não serve tráfego com schema pela metade** por construção: migrations rodam antes de a
porta abrir, e cada migration entra na mesma transação do seu registro em `schema_migrations`. Uma
migration que falha no meio não deixa schema parcialmente migrado.

```bash
docker compose -f docker-compose.prod.yml logs --tail 100 backend | grep -i migration
```

| Mensagem | Significado |
| --- | --- |
| `Migration N já aplicada como "X", mas o repositório agora traz "Y"` | Alguém reescreveu uma migration já aplicada. **Não** apague a linha de `schema_migrations`: restaure o nome do arquivo no repositório |
| `Sequência de migrations quebrada` | Falta um número na sequência `NNNN_` |
| Erro de SQL | A migration está errada |

**Recuperação:**

```bash
ops/deploy.sh --rollback <sha-anterior>
```

> **Rollback de aplicação não desfaz migration** (§24). Se a versão que falhou aplicou uma
> migration que a anterior não entende, o schema fica à frente do código. Aí:
>
> ```bash
> docker compose -f docker-compose.prod.yml down
> ops/restore.sh --to /tmp/pre-deploy --snapshot <id-do-backup-pre-deploy> --install
> ops/deploy.sh --rollback <sha-anterior>
> ```
>
> `restic snapshots --host spark --tag spark-db --tag pre-deploy` lista os pontos de recuperação
> criados por deploy.
>
> A partir da T16.8, mudança incompatível segue *expand → deploy → contract em release posterior*
> (§25) — justamente para que este parágrafo raramente seja necessário.

---

## O backup falhou

**Sintoma:** `check-health.sh` acusa, ou `systemctl status spark-backup.service` mostra falha.

```bash
systemctl status spark-backup.service
journalctl -u spark-backup.service -n 50
cat /opt/spark/state/backup-status.json
```

| Causa provável | Como confirmar |
| --- | --- |
| Credencial do storage errada/expirada | `restic snapshots` falha na autenticação |
| Repositório inacessível | Rede, endpoint, bucket removido |
| Disco cheio no staging | `df -h /opt/spark/backups` |
| Outro backup em andamento | A mensagem do `flock` diz isso; não é erro se foi só uma corrida |
| `pg_dump` falhou ao conectar | `DATABASE_URL` inalcançável pelo operador — `ops/check-health.sh` acusa antes do backup |
| `pg_restore --list` falhou no snapshot | O arquivo saiu truncado/corrompido: disco do staging, ou `pg_dump` interrompido |
| `o dump não contém schema_migrations` | `DATABASE_URL` aponta para um banco que não é o do Spark |

**Rodar manualmente** (a saída real do restic aparece em `stderr`):

```bash
cd /opt/spark/repo
set -a; . /opt/spark/secrets/backup.env; set +a
ops/backup.sh --tag manual
```

Um backup que falhou **não** apaga o registro do último sucesso: `check-health.sh` continua medindo
a idade real, e não a data de uma falha.

**Todo** desfecho fica registrado. `backup-status.json` traz a etapa em que parou, e ela leva
direto à causa:

| `message` | Onde parou |
| --- | --- |
| `falha na etapa 'config'` | Credencial do storage ausente (`RESTIC_REPOSITORY`/senha) |
| `falha na etapa 'workdir'` | Não conseguiu escrever em `/opt/spark/backups` — quase sempre disco |
| `falha na etapa 'snapshot'` | `pg_dump` não conectou/não terminou, ou o arquivo não passou em `pg_restore --list` |
| `falha na etapa 'offsite-upload'` | O restic recusou o envio; o motivo dele está no journal |
| `falha na etapa 'offsite-retention'` | O snapshot subiu, mas o `forget --prune` falhou: o repositório está crescendo sem limite |

O arquivo carrega o rótulo da etapa e **nunca** a saída do comando: endereço de repositório, chave
de storage e senha do restic ficam fora dele de propósito, porque ele é lido durante o diagnóstico.
O detalhe técnico está em `journalctl -u spark-backup.service`.

---

## O Coach está caro, lento ou fora

**Sintoma:** `429` no app, custo inesperado, ou o provider de IA (Gemini ou Groq) indisponível.

**Primeiro, quem recusou (T19.H4)** — no Cloud Run:

```bash
gcloud logging read 'resource.type="cloud_run_revision" AND resource.labels.service_name="spark-backend"
  AND (jsonPayload.event="ai.request.finished" OR jsonPayload.event="ai.quota.exceeded")' \
  --project project-47b17b25-909d-4ae8-943 --freshness=1d \
  --format='table(timestamp,jsonPayload.event,jsonPayload.provider,jsonPayload.status,jsonPayload.failureKind,jsonPayload.providerStatus,jsonPayload.limitSource,jsonPayload.limit,jsonPayload.requestTooLarge,jsonPayload.finishReason)'
```

| O log diz | Causa | O que fazer |
| --- | --- | --- |
| `ai.quota.exceeded limitSource=SPARK` | quota do próprio Spark | esperar o dia UTC virar, ou revisar a quota em `ops/gcp/lib.gcp.sh` — sem passar da capacidade do provider |
| `failureKind=RATE_LIMITED limitSource=PROVIDER limit=RPD/TPD` | cota diária do provider | esperar o dia do provider virar (Gemini: 07:00 UTC); se recorrente, a quota do Spark está acima da capacidade |
| `limit=TPM`/`OTPM` com `requestTooLarge=true`, ou `providerStatus=413` | a chamada, sozinha, não cabe no minuto do plano (Groq free: 8 000 tokens, e o teto de saída é reservado na chegada) | esperar não resolve: reduzir `GROQ_MAX_OUTPUT_TOKENS`, plano pago, ou contexto menor decidido no app |
| `limit=TPM`/`RPM` sem `requestTooLarge` | o minuto do plano foi dividido com outras chamadas (Groq free: ~1–2 chamadas do Coach por minuto, todos os usuários somados) | transitório — o app recebe o mesmo 429 de sempre; se recorrente, o volume passou do free tier |
| `failureKind=EMPTY_RESPONSE finishReason=MAX_TOKENS/length` | o raciocínio consumiu o teto de saída | subir `SPARK_*_MAX_OUTPUT_TOKENS` (o Gemini com MEDIUM precisa de 8 192) |
| `failureKind=UNAVAILABLE providerStatus=503` | provider sobrecarregado (o free tier do Gemini é o primeiro a ser cortado) | trocar de provider — [AI_PROVIDERS.md](./AI_PROVIDERS.md) |
| `failureKind=NOT_CONFIGURED` | chave ausente no provider selecionado | `gcloud secrets versions list spark-<provider>-api-key` e deploy |

Para reproduzir fora do app (uma chamada sintética, gasta uma requisição da cota):
`AI_PROVIDER=… <CHAVE>=… npm run ai:provider-smoke` em `backend/`.

```bash
docker compose -f docker-compose.prod.yml logs backend | grep -E 'ai\.(quota|provider|request)'
```

**Desligar o Coach sem tocar em backup e sync:**

```bash
# em backend/.env
SPARK_AI_ENABLED=false
docker compose -f docker-compose.prod.yml up -d backend
```

`/v1/ai/coach` passa a responder `503 AI_PROVIDER_UNAVAILABLE` **antes** de qualquer chamada ao
provider e antes de consumir quota. O app já trata esse código como "Coach indisponível" desde a
T16.2: não é preciso publicar APK novo. Backup, restore e sync continuam intactos.

Para apenas **apertar** a quota em vez de desligar: `AI_MAX_REQUESTS_PER_USER_DAY` e
`AI_MAX_REQUESTS_GLOBAL_DAY`.

Se o provider está fora e a chave está certa, o que o servidor pode fazer é **trocar de provider**
(configuração + deploy, sem APK — [AI_PROVIDERS.md](./AI_PROVIDERS.md)); nunca há fallback
automático. E nada mais quebra: readiness não depende de provider de IA, por decisão (§61).

---

## Uma conta recebe `403 AI_CAPABILITY_DENIED` inesperadamente (T19.0)

**Sintoma:** o app mostra a capability como indisponível, ou a chamada real ao Coach recusa com
`AI_CAPABILITY_DENIED`, para uma conta que deveria ter acesso.

```bash
cd backend
npm run capabilities:ai -- list <uid>
```

Nenhuma linha impressa (só "nenhuma linha gravada") significa que a conta está no default —
**liberado** para as quatro capabilities conhecidas. Uma linha `REVOKED` é a causa: alguém negou
aquela capability para aquela conta deliberadamente.

```bash
npm run capabilities:ai -- grant <uid> <capability>
```

Contra a **produção** (Neon), o comando é o mesmo `dist/cli/manage-ai-capabilities.js` da imagem —
só precisa de `DATABASE_URL`, e ela vem do Secret Manager direto para a variável do processo, nunca
digitada nem gravada em arquivo (o mesmo cuidado de `reconcile-account-deletions` em
DISASTER_RECOVERY.md):

```bash
cd backend && npm ci && npm run build
DATABASE_URL="$(gcloud secrets versions access latest --secret spark-database-url --project "$SPARK_GCP_PROJECT")" \
  node dist/cli/manage-ai-capabilities.js list <uid>
```

O `uid` é o Firebase UID da conta (`uidPrefix` aparece no log `ai.entitlement.denied`; o UID
completo está no Firebase Console → Authentication). Não existe Job de Cloud Run para isto de
propósito: grant/revoke é ação humana, rara e auditável pelo histórico de quem tinha acesso ao
secret.

`grant`/`revoke` são idempotentes — repetir não duplica linha nem falha. Capabilities conhecidas:
`AI_ANALYZE_WORKOUT`, `AI_GENERATE_WORKOUT`, `AI_ADAPT_WORKOUT`, `AI_EXPLAIN`. Isto não substitui
`AI_ENABLED` nem a quota: os dois continuam funcionando exatamente como antes, e `AI_ENABLED=false`
continua vencendo mesmo com entitlement concedido.

---

## Bug grave no sync

**Sintoma:** dado convergindo errado, conflito aparecendo onde não deveria, mutação aplicada de
forma inesperada.

```bash
# em backend/.env
SPARK_SYNC_WRITE_ENABLED=false
docker compose -f docker-compose.prod.yml up -d backend
```

`POST /v1/sync/push` passa a responder `503 SYNC_WRITE_DISABLED`. O `GET /v1/sync/pull` **continua
funcionando**: leitura não corrompe nada, e cortá-la só deixaria os aparelhos mais desatualizados.

**Isto é uma pausa, não uma perda.** O aparelho trata 5xx como não confirmado: a Outbox permanece
pendente, nada é apagado, e tudo sobe quando o interruptor voltar. Nenhum aparelho perde alteração
local, e o núcleo do Spark continua completo.

Enquanto estiver pausado: **não edite dado no banco na mão**. `serverRevision`, change log e ledger
de idempotência são um conjunto consistente; alterar um deles por fora produz divergência que os
aparelhos não sabem interpretar.

---

## A credencial do Firebase expirou ou foi revogada

**Sintoma:** toda rota autenticada responde `503 AUTH_UNAVAILABLE`; o log traz `auth.unavailable`.

Se o processo estiver de pé com `REQUIRE_FIREBASE_ADMIN=true`, ele subiu com a credencial
**existente** — então o problema é a credencial ter sido revogada depois, ou o relógio da máquina.

```bash
docker compose -f docker-compose.prod.yml exec backend ls -l /run/secrets/firebase-admin.json
timedatectl                                   # relógio errado invalida a verificação de JWT
```

Gerar e instalar uma credencial nova: ver "Rotação" em [SECURITY.md](./SECURITY.md).

O `503` aqui é deliberado e importa: `401` diria ao app "sua credencial não serve", e ele trataria
a sessão como perdida. Um problema do servidor não pode significar isso.

---

## Manutenção programada

```bash
# em backend/.env
SPARK_MAINTENANCE_MODE=true
docker compose -f docker-compose.prod.yml up -d backend
```

Todo `/v1` responde `503 SERVICE_UNAVAILABLE` no envelope de sempre. `/health/live` e
`/health/ready` continuam verdes — de propósito: um readiness falso faria o Docker reiniciar o
container no meio da manutenção.

Para sair, `SPARK_MAINTENANCE_MODE=false` e suba de novo.

---

## Perdi a VPS

[DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md).

---

## Cloud Run (T18.2) — os mesmos sintomas, outra topologia

Esta seção só se aplica a quem fez deploy pela topologia Cloud Run
([CLOUD_RUN_DEPLOYMENT.md](./CLOUD_RUN_DEPLOYMENT.md)). Para VPS/Docker Compose, use as seções
acima.

### O deploy abortou no smoke do candidate

O tráfego antigo **continua servindo** — `deploy-cloud-run.sh` só move tráfego depois do smoke
passar. Não há nada a "reverter": a revision candidate simplesmente não recebeu tráfego.

```bash
ops/gcp/smoke-cloud-run.sh <url-do-candidate>   # repita manualmente para ver a falha exata
gcloud run services logs read spark-backend --region southamerica-east1 --limit 50
```

Corrija a causa e rode `ops/gcp/deploy-cloud-run.sh` de novo — um novo commit produz um novo
digest, e o fluxo inteiro (migration → candidate → smoke → tráfego) roda de novo do zero.

### O Job `spark-db-migrate` falhou

O deploy já abortou antes de qualquer candidate nascer — é o comportamento correto (§10: nenhuma
API nova pode receber tráfego com migration pendente ou falha).

```bash
gcloud run jobs executions list --job spark-db-migrate --region southamerica-east1
gcloud run jobs executions logs read <execution-id> --region southamerica-east1
```

Causas comuns: `DATABASE_URL_DIRECT` desatualizada, migration com erro de SQL, ou um lock
consultivo preso por uma execução anterior travada (raro; o advisory lock é liberado quando a
conexão termina).

### Preciso voltar para a revision anterior

```bash
ops/gcp/rollback-cloud-run.sh --list
ops/gcp/rollback-cloud-run.sh <revision-anterior>
```

Nunca rebuilda imagem — só move tráfego de volta para uma revision que já existe. Lembre-se: uma
migration já aplicada **não** é desfeita pelo rollback (§56) — é por isso que migrations de
produção são sempre additive.

### Toda rota autenticada responde 503 (`auth.tombstone.unavailable`) — o PostgreSQL é Tier-0

**Sintoma:** sync, social, backup e Coach falham **ao mesmo tempo** no app; a API responde `503`
com `ACCOUNT_STATE_UNAVAILABLE` ("Account state is temporarily unavailable"); nos logs,
`auth.tombstone.unavailable` seguido de `http.error`; `/health/live` continua `200`.

**Por que tudo cai junto:** toda rota autenticada fora de `/v1/account` passa pelo `BearerAuthGuard`,
que consulta `account_deletion_tombstones` **antes** de qualquer outra coisa. Sem PostgreSQL, ele
não consegue provar que a conta não foi excluída e **falha fechado** — de propósito: "não consigo
provar que a conta está ativa" nunca vira "considero ativa". O banco é dependência **Tier-0**: sem
ele, nenhuma rota autenticada existe, o maintenance falha e o backup de DR falha. O app no aparelho
continua inteiro (treino, histórico, Outbox pendente) — ver "O que NUNCA está quebrado".

Diagnóstico, nesta ordem (tudo somente leitura):

```bash
API_URL="$(gcloud run services describe spark-backend --region southamerica-east1 --project "$SPARK_GCP_PROJECT" --format='value(status.url)')"
curl -sS "$API_URL/health/ready"
# {"status":"unavailable","checks":{"config":true,"database":false,...}}  → é o banco

gcloud logging read 'resource.labels.service_name="spark-maintenance" AND jsonPayload.event="maintenance_failed"' \
  --project "$SPARK_GCP_PROJECT" --freshness=2h --limit 3 --format='value(timestamp,jsonPayload.stage,jsonPayload.errorName,jsonPayload.errorCode)'
# stage=database + o SQLSTATE (ou o código de rede) com que o banco recusou — desde a T19.H6
```

1. `database=false` → o banco não responde. Abra o **console do Neon** (projeto do Spark): o compute
   está suspenso por **franquia de compute esgotada** ("exceeded the compute time quota")? É a
   seção seguinte. Neon saudável → confira o secret `spark-database-url` (versão pinada na
   revision), a política de TLS e a página de status do provedor.
2. `database=true` e ainda `503` em rota autenticada → não é o banco: leia o `errorName` de
   `auth.tombstone.unavailable` e `http.error`.

**Nunca** "conserte" isto afrouxando o guard (tratar a falha da consulta como "conta ativa") — é
exatamente a ressurreição de conta excluída que o tombstone existe para impedir.

### Franquia de compute do Neon esgotada (incidente de 2026-09-28, T19.H6)

O Neon Free inclui **100 CU-h por projeto por mês** e suspende o compute depois de **5 minutos sem
consulta** (fixo no plano). Com a franquia esgotada, o compute fica suspenso **até o próximo período
de cobrança ou um upgrade** — e o Spark inteiro autenticado fica em `503` (seção anterior). É
incidente de **disponibilidade/capacidade**, não de integridade: os dados estão intactos no Neon.

**Não faça:** criar outro projeto Neon, `DROP`, restore de backup de DR, ou trocar `DATABASE_URL`.
Nenhum desses resolve quota, e todos trocam um incidente de horas por um risco de divergência de
dados. O restore do [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md) é para perda ou corrupção **com
evidência**.

Procedimento (muda):

1. **Pare o polling que consome o compute:**
   `gcloud scheduler jobs pause spark-maintenance-cycle --location southamerica-east1 --project "$SPARK_GCP_PROJECT"`.
   Não se perde nada: notificação pendente, exclusão de conta pendente e varreduras vivem no
   PostgreSQL e convergem quando o ciclo voltar. **Não** pause `spark-db-backup-daily`: o backup
   falha enquanto o banco estiver fora (alerta esperado) e volta sozinho.
2. **Devolva o compute ao Neon** — decisão do dono da conta: esperar o reset da franquia no início
   do próximo período de cobrança (o console mostra a data), ou mudar de plano. Anote qual foi.
3. **Readiness:** `curl -sS "$API_URL/health/ready"` → `200`, `database: true`, `migrations: true`.
4. **Rotas autenticadas:** `SPARK_SMOKE_FIREBASE_ID_TOKEN=… ops/gcp/smoke-cloud-run.sh "$API_URL"`
   (conta de teste; ver [OPERATIONS_CHECKLIST.md](./OPERATIONS_CHECKLIST.md)) e o app num aparelho:
   sync, social, backup, Coach. O incidente só fecha aqui.
5. **Só então** o maintenance volta, na cadência nova: confirme que a produção já tem o deploy da
   T19.H6 (`gcloud scheduler jobs describe spark-maintenance-cycle … --format='value(schedule)'` →
   `*/15 * * * *`; o deploy atualiza o cron mas **nunca** muda o estado do job), depois
   `gcloud scheduler jobs resume spark-maintenance-cycle …` e
   `gcloud scheduler jobs run spark-maintenance-cycle …` para um ciclo imediato
   (`maintenance_completed` no log).
6. **Nas 24–72 h seguintes:** o banco voltou a dormir? — seção seguinte.

Registro de 2026-09-28 (horários UTC):

| Quando | O quê |
| --- | --- |
| 2026-09-11 ~15:04 | `spark-maintenance-cycle` passa a rodar `* * * * *`: 1 440 ciclos/dia, cada um com conexão, `BEGIN`, lock e heartbeat no PostgreSQL — com zero trabalho real (nas 24 h antes do incidente: 1 441 ciclos, 0 exclusões, 0 notificações; push desligado) |
| 11/09 → 28/09 | uma consulta por minuto nunca deixa passar os 5 min de suspensão: o compute fica de pé 24/7, ~6 CU-h/dia a 0,25 CU. A API teve de 0 a 283 requisições/dia no período |
| 2026-09-28 19:15:02 | último ciclo bem-sucedido |
| 2026-09-28 19:16:29 | primeiro `auth.tombstone.unavailable`; `/health/ready` com `database: false` |
| 19:16 → 22:29 | 194 ciclos falham (`INTERNAL` no Scheduler) **sem nenhum log de aplicação** — a falha de conexão acontecia antes do `try` que registra `maintenance_failed` (corrigido na T19.H6: `stage=database`) |
| 19:16 → | `spark-run-5xx`, `spark-scheduler-failed` e `spark-maintenance-stale` dispararam e chegaram por e-mail (confirmado pelo operador) — nenhum aviso **antes** da franquia acabar, que é o que `spark-db-compute-long-uptime` passa a dar |
| 2026-09-28 22:29:25 | Scheduler pausado pelo operador (passo 1) |
| 2026-09-28 | decisão do dono da conta (passo 2): esperar o reset mensal da franquia — as rotas autenticadas ficam em `503` até lá; o deploy da T19.H6 e a retomada do Scheduler vêm depois de `/health/ready` voltar a `200` |

A causa e a correção estão em `PROJECT_RULES.md` §13.31: Scheduler a cada 15 minutos, stale de
35 min, alerta de ausência de 45 min, e o ciclo passou a medir se o banco dorme.

### O banco não dorme (alerta `spark-db-compute-long-uptime`)

**Sintoma:** `database_compute_long_uptime` no log do maintenance (o compute está de pé há mais de
`DATABASE_COMPUTE_UPTIME_WARN_MS`, 6 h, sem suspender), ou o console do Neon mostra o consumo de
CU-h subindo como se o banco nunca parasse.

Todo `maintenance_completed` carrega o compute que atendeu o ciclo (`databaseStartedAt`, o
`pg_postmaster_start_time` — o Neon reinicia o Postgres a cada retomada) e há quanto tempo ele estava
de pé (`databaseUptimeMs`):

```bash
gcloud logging read 'resource.labels.service_name="spark-maintenance" AND jsonPayload.event="maintenance_completed"' \
  --project "$SPARK_GCP_PROJECT" --freshness=1d --limit 200 --format=json \
  | jq '{ciclos: length, computesDistintos: ([.[].jsonPayload.databaseStartedAt] | unique | length),
         uptimeMaxMin: (([.[].jsonPayload.databaseUptimeMs] | max) / 60000 | floor)}'
```

Sem tráfego, cada ciclo encontra um compute **novo** (o banco dormiu entre dois ciclos) com uptime
de segundos: `computesDistintos ≈ ciclos`. `computesDistintos = 1` com uptime de horas é o banco que
nunca dorme — o padrão do incidente. Causas, da mais provável à menos:

1. a cadência voltou (`gcloud scheduler jobs describe spark-maintenance-cycle … --format='value(schedule)'`
   diferente de `*/15 * * * *`; o `config-drift-audit.sh` aponta);
2. um poller novo do banco — um uptime check sobre `/health/ready`, um cron, um worker com
   `setInterval` — que ninguém fez a conta de CU-h (ver `PROJECT_RULES.md` §13.31);
3. tráfego real contínuo (requisições por hora na API, métrica `run.googleapis.com/request_count`):
   aí o plano do banco precisa ser revisto — é crescimento, não defeito.

### `spark-maintenance` parece não estar rodando (alerta `spark-maintenance-stale`)

```bash
ops/gcp/dr-status.sh                                # lê GET /internal/maintenance/status: stale, ageMs, lastErrorName
gcloud scheduler jobs describe spark-maintenance-cycle --location southamerica-east1 --format='value(state,schedule,status.code,lastAttemptTime)'
gcloud scheduler jobs run spark-maintenance-cycle --location southamerica-east1   # dispara um ciclo agora
gcloud run services logs read spark-maintenance --region southamerica-east1 --limit 50
```

Desde a T19.H6 o Scheduler chama a cada **15 minutos**: o heartbeat só é `stale` depois de **35 min**
sem sucesso (um ciclo perdido nunca é), e `spark-maintenance-stale` (CRITICAL) só dispara depois de
**45 min** sem nenhuma resposta `2xx` — vários ciclos seguidos perdidos. Um `spark-scheduler-failed`
isolado (WARNING) seguido de um ciclo bem-sucedido não é incidente. `state: PAUSED` é decisão de
operador (o deploy nunca muda o estado do job) — confira se ainda faz sentido.

`stale: true` com `lastErrorName` preenchido é um ciclo que **falha** (veja `maintenance_failed` no
log); `maintenance_failed` com `stage=database` é o **banco** que não respondeu (seção "Toda rota
autenticada responde 503"); `stale: true` com `lastStartedAt` parado é o Scheduler que **não chama**
(estado, IAM `run.invoker`, `status.code` do último attempt). Procure por `maintenance.cycle.skipped_locked` —
se aparecer em toda chamada, outra execução está segurando o lock do ciclo. O lock é
`pg_try_advisory_xact_lock` numa transação que dura o ciclo: some quando a transação termina, ou
quando o processo morre (o pooler aborta a transação). Se mesmo assim persistir, o suspeito é uma
conexão presa no pooler do Neon; reiniciar a revision (`gcloud run services update spark-maintenance
--region southamerica-east1 --update-labels restart=$(date +%s)`) fecha as conexões do processo.
Foi exatamente assim que a T18.2 quebrou no primeiro dia de heartbeat (2026-09-11, 17:56Z–18:02Z:
7 ciclos `skipped_locked`, `maintenance_stale ageMs=479368` na volta) — com lock de **sessão**
(`pg_try_advisory_lock`) sobre o endpoint pooled, o `unlock` caía noutra conexão e o lock ficava
preso numa conexão do pooler até ele a reciclar (~7 min naquela ocorrência; sem garantia); a T18.3
trocou para lock de transação, chave nova e `503` enquanto preso. Quando o ciclo volta, ele registra
`maintenance_stale` com `ageMs` (quanto tempo ficou parado).

### O backup de DR falhou ou está velho (alertas `spark-job-backup-failed`, `spark-db-backup-stale`)

```bash
ops/gcp/dr-status.sh --backups-only
gcloud run jobs executions list --job spark-db-backup --region southamerica-east1 --limit 5
gcloud logging read 'resource.type="cloud_run_job" AND jsonPayload.event="db_backup_failed"' --limit 3 \
  --format='value(timestamp,jsonPayload.step,jsonPayload.errorName,jsonPayload.errorMessage)'
gcloud scheduler jobs describe spark-db-backup-daily --location southamerica-east1 --format='value(state,status.code,lastAttemptTime)'
ops/gcp/dr-backup-now.sh                            # um backup agora, verificado no bucket
```

`step` diz onde parou: `pg_dump` (banco inalcançável, credencial, major do `pg_dump` < a do servidor),
`arquivo` (dump vazio/acima de `SPARK_DR_MAX_DUMP_BYTES` — suba `SPARK_RUN_BACKUP_MEMORY` junto),
`upload do dump` (IAM do bucket para `spark-backend-backup`: a condição de prefixo), `releitura`
(integridade — não ignore), `retenção` (o backup novo está íntegro; só a limpeza falhou).

### O auditor de storage encontrou achados (`storage_audit_completed` com `status=ISSUES`)

```bash
gcloud run jobs executions list --job spark-storage-audit --region southamerica-east1 --limit 1
gcloud run jobs executions logs read <execution> --region southamerica-east1 | grep -A200 '"findings"'
```

| Classe | Significa | Ação |
| --- | --- | --- |
| `MISSING_OBJECT` | linha aponta para objeto que não existe | se veio de um restore: exclusão/coleta posterior ao backup — registre; senão, investigue a ordem "objeto antes, metadata depois" |
| `ORPHAN_OBJECT` | objeto sem linha, velho | o coletor do maintenance recolhe; se persistir, o coletor não está rodando |
| `RECENT_UNREFERENCED` | objeto sem linha, novo | nada — upload em voo |
| `INVALID_METADATA` / `HASH_MISMATCH` | metadata e bytes não batem | nunca aconteceu por desenho (create-only + CRC32C); trate como incidente |
| `INCOMPLETE_BACKUP` | dump de DR sem manifesto | um Job de backup morreu no meio; o próximo backup segue; remova a pasta à mão se quiser |
| `TOMBSTONE_INCONSISTENT` | ledger e tabela discordam | rode `reconcile-account-deletions` (ver DISASTER_RECOVERY.md) |

O auditor **nunca apaga nada** — a ação é sempre humana.

### Uma revision aponta para uma versão antiga de secret (`config-drift-audit` → DRIFT)

Alguém adicionou uma versão de secret sem deploy. É o comportamento esperado até o deploy:
`ops/gcp/deploy-cloud-run.sh` cria revisions novas pinadas na versão habilitada mais recente. Só
depois desabilite a versão antiga.

### Notificação social não está sendo entregue em Cloud Run

Confirme, nesta ordem: `SOCIAL_PUSH_ENABLED=true` na revision de `spark-maintenance` (não só na
API — é a manutenção que despacha); `spark-maintenance` está recebendo chamadas do Scheduler
(seção acima); e o IAM de FCM da Service Account (`roles/firebasecloudmessaging.admin`).

---

## Referência rápida

### VPS / Docker Compose

| Preciso de | Comando |
| --- | --- |
| Diagnóstico geral | `ops/check-health.sh` |
| Health do backend (sem porta publicada) | `docker compose -f docker-compose.prod.yml exec -T backend node -e "fetch('http://127.0.0.1:8080/health/ready').then(r=>r.text()).then(console.log)"` |
| Health público (quando há domínio) | `curl -s https://api.<dominio>/health/ready` |
| Deploy | `ops/deploy.sh` |
| Rollback | `ops/deploy.sh --rollback <sha>` |
| Backup agora | `ops/backup.sh --tag manual` |
| Ensaio de restauração | `ops/verify-backup.sh` |
| Restaurar (verificando) | `ops/restore.sh --to /tmp/x` |
| Restaurar (instalando) | `ops/restore.sh --to /tmp/x --install` |
| Listar backups | `restic snapshots --host spark --tag spark-db` |

### Cloud Run

| Preciso de | Comando |
| --- | --- |
| Bootstrap (uma vez) | `ops/gcp/bootstrap-cloud-run.sh` |
| Deploy | `ops/gcp/deploy-cloud-run.sh` |
| Smoke manual | `ops/gcp/smoke-cloud-run.sh <url>` |
| Rollback | `ops/gcp/rollback-cloud-run.sh <revision>` |
| Ensaio de rollback (A→B→A com smoke) | `ops/gcp/rollback-drill.sh --to <revision>` |
| Migration manual (fora do deploy) | `gcloud run jobs execute spark-db-migrate --region southamerica-east1 --wait` |
| Disparar um ciclo de manutenção agora | `gcloud scheduler jobs run spark-maintenance-cycle --location southamerica-east1` |
| Existe backup recente? maintenance vivo? | `ops/gcp/dr-status.sh` |
| Backup de DR agora | `ops/gcp/dr-backup-now.sh` |
| Ensaio de restauração (bucket real → PostgreSQL local descartável) | `ops/gcp/dr-restore-drill.sh --record` |
| Auditar drift / IAM / custo | `ops/gcp/config-drift-audit.sh` · `ops/gcp/iam-audit.sh` · `ops/gcp/cost-audit.sh` |
| Auditar PostgreSQL ↔ GCS | `gcloud run jobs execute spark-storage-audit --region southamerica-east1 --wait` |
| Alertas (criar/atualizar; listar) | `SPARK_ALERT_EMAIL=… ops/gcp/monitoring-alerts.sh` · `--list` |
| Retenção do Artifact Registry | `ops/gcp/artifact-registry-retention.sh [--apply]` |
| Logs da API | `gcloud run services logs read spark-backend --region southamerica-east1` |
| Logs da manutenção | `gcloud run services logs read spark-maintenance --region southamerica-east1` |
| Log do backend | `docker compose -f docker-compose.prod.yml logs -f backend` |
| Versão no ar | `docker compose -f docker-compose.prod.yml ps --format '{{.Image}}'` |
