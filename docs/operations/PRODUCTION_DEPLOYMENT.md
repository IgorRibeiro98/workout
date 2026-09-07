# Spark — Implantação em produção

> **Estado:** `IMPLEMENTED` (repositório) · `MANUAL SETUP REQUIRED` (VPS) · `NOT VERIFIED` (produção real).
>
> Tudo o que está neste documento existe no repositório e foi exercitado localmente com Docker.
> **Nenhuma VPS foi provisionada** por esta tarefa: não há domínio, DNS, certificado, service
> account de produção nem storage off-site contratado. Cada passo abaixo marcado
> `MANUAL SETUP REQUIRED` é trabalho de um operador humano com acesso ao provedor.

## Topologia

```text
                        Internet
                            │
                   80/tcp   │   443/tcp + 443/udp
                            ▼
                 ┌──────────────────────┐
                 │  Caddy               │  TLS automático (Let's Encrypt)
                 │  spark-caddy         │  redirect 80 → 443
                 └──────────┬───────────┘
                            │ rede interna do Compose
                            │ (o backend NÃO publica porta)
                            ▼
                 ┌──────────────────────┐
                 │  Spark Backend       │  NestJS, usuário `node` (uid 1000)
                 │  spark-backend:<sha> │  :8080 apenas na rede interna
                 └──────────┬───────────┘
                            │ bind mount
                            ▼
                 /opt/spark/data/spark.db   SQLite (WAL, FULL, foreign_keys)
                            │
                            │ ops/backup.sh (diário + pré-deploy)
                            ▼
                 restic → storage off-site (criptografado)
```

Nada além disso. Sem Kubernetes, Redis, Kafka, PostgreSQL, service mesh ou monitoramento pago —
a decisão está no [ADR-0001](../architecture/ADR-0001-spark-online-architecture.md), "política de
custo", e a T16.8 não a reabre.

## Diretórios na VPS

| Caminho | Dono | Permissão | Conteúdo |
| --- | --- | --- | --- |
| `/opt/spark/repo` | `spark:spark` | `755` | O repositório (compose, Caddyfile, `ops/`) |
| `/opt/spark/data` | `1000:1000` | `700` | `spark.db` e os artefatos do WAL |
| `/opt/spark/secrets` | `spark:spark` | `700` | `firebase-admin.json`, `backend.env`, `backup.env`, senha do restic |
| `/opt/spark/backups` | `spark:spark` | `700` | Área de trabalho do backup (temporária) |
| `/opt/spark/state` | `spark:spark` | `700` | `backup-status.json` |

`/opt/spark/data` pertence ao **uid 1000** porque é como o container roda (`USER node` no
Dockerfile). O banco não é segredo de autenticação, mas é dado pessoal: `700` é o mínimo adequado
(§145/§146).

## Checklist de provisionamento — `MANUAL SETUP REQUIRED`

### 1. Sistema

```bash
sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y ca-certificates curl restic
sudo timedatectl set-timezone UTC          # o servidor trabalha em UTC (§124/§126)
timedatectl show -p NTPSynchronized        # o systemd-timesyncd do Ubuntu já basta (§125)
```

### 2. Usuário de deploy

Não operar como `root` (§11).

```bash
sudo adduser --disabled-password --gecos "" spark
sudo usermod -aG docker spark              # necessário para docker compose sem sudo
```

> O grupo `docker` equivale a root na máquina. É um custo aceito conscientemente: a alternativa
> (rootless Docker) traz complexidade de rede e de bind mount que não se paga em uma VPS pessoal.

### 3. SSH

```bash
ssh-copy-id spark@<vps>                    # PRIMEIRO: confirme que a chave funciona
ssh spark@<vps> 'echo ok'                  # SÓ ENTÃO endureça
```

Depois de confirmar o acesso por chave, em `/etc/ssh/sshd_config`:

```text
PasswordAuthentication no
PermitRootLogin no
KbdInteractiveAuthentication no
```

```bash
sudo sshd -t && sudo systemctl reload ssh
```

> **A ordem não é burocracia.** Aplicar isso antes de validar a chave tranca o operador para fora
> de uma máquina que só ele administra (§10/§144). Mantenha a sessão atual aberta e teste uma
> **segunda** sessão antes de fechá-la.

### 4. Firewall

Somente o necessário (§9):

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp                      # restrinja à sua faixa quando possível:
                                           #   sudo ufw allow from <seu-ip> to any port 22 proto tcp
sudo ufw allow 80/tcp                      # ACME + redirect para HTTPS
sudo ufw allow 443/tcp
sudo ufw allow 443/udp                     # HTTP/3
sudo ufw enable
sudo ufw status verbose
```

A porta **8080 não é liberada, e não precisa ser**: `docker-compose.prod.yml` não a publica, e o
backend só existe na rede interna do Compose.

> Docker publica portas manipulando `iptables` diretamente e pode contornar o `ufw`. A proteção
> real aqui é o compose não publicar a porta; o firewall é a segunda camada, não a primeira.

### 5. Docker

```bash
curl -fsSL https://get.docker.com | sudo sh
docker --version && docker compose version
```

### 6. Diretórios e segredos

```bash
sudo mkdir -p /opt/spark/{data,secrets,backups,state}
sudo chown -R spark:spark /opt/spark
sudo chown 1000:1000 /opt/spark/data
sudo chmod 700 /opt/spark/{data,secrets,backups,state}

sudo -u spark git clone <url-do-repo> /opt/spark/repo
```

Coloque em `/opt/spark/secrets` (nada disso vem do Git — ver [SECURITY.md](./SECURITY.md)):

```bash
# service account do Firebase Admin (console → Configurações → Contas de serviço)
install -m 600 -o spark -g spark ~/firebase-admin.json /opt/spark/secrets/firebase-admin.json

# variáveis de runtime com segredo (hoje: a chave do Gemini)
cat > /opt/spark/secrets/backend.env <<'EOF'
GEMINI_API_KEY=...
EOF
chmod 600 /opt/spark/secrets/backend.env

# senha do repositório de backup — GUARDE UMA CÓPIA FORA DA VPS (§113)
head -c 32 /dev/urandom | base64 > /opt/spark/secrets/restic-password
chmod 600 /opt/spark/secrets/restic-password

# configuração do backup
cp /opt/spark/repo/ops/spark-backup.env.example /opt/spark/secrets/backup.env
chmod 600 /opt/spark/secrets/backup.env
$EDITOR /opt/spark/secrets/backup.env
```

### 7. DNS e TLS

1. Aponte `api.<seu-dominio>` (registro `A`, e `AAAA` se houver IPv6) para o IP da VPS.
2. Espere a propagação: `dig +short api.<seu-dominio>`.
3. Configure o domínio para o Compose:

```bash
cd /opt/spark/repo/backend
cp Caddyfile.prod Caddyfile
cat > .env <<'EOF'
SPARK_DOMAIN=api.seudominio.com
SPARK_ACME_EMAIL=voce@seudominio.com
EOF
chmod 600 .env
```

O certificado é emitido e **renovado sozinho** pelo Caddy. Não há cron de renovação para escrever,
e não há certificado para copiar — o domínio precisa resolver para esta VPS e a porta 80 precisa
estar aberta, e é só isso.

### 8. Primeiro deploy

```bash
cd /opt/spark/repo
ops/deploy.sh --skip-backup        # só no primeiro: ainda não há banco para salvar
```

Do segundo em diante, **sem `--skip-backup`**: o backup pré-deploy é o ponto de recuperação de uma
migration que der errado (§21).

### 9. Backup agendado

```bash
sudo install -m 644 /opt/spark/repo/ops/systemd/spark-*.service /etc/systemd/system/
sudo install -m 644 /opt/spark/repo/ops/systemd/spark-*.timer   /etc/systemd/system/
sudo systemctl daemon-reload

sudo -u spark restic init                  # com backup.env carregado no ambiente
sudo systemctl enable --now spark-backup.timer spark-check-health.timer
sudo systemctl start spark-backup.service  # primeira execução, agora
sudo -u spark /opt/spark/repo/ops/verify-backup.sh   # ENSAIO OBRIGATÓRIO (§43)
```

Um backup que nunca foi restaurado não é um backup validado. Ver
[BACKUP_AND_RESTORE.md](./BACKUP_AND_RESTORE.md).

### 10. Monitoramento externo

`ops/check-health.sh` roda **dentro** da VPS — e uma VPS morta não avisa que morreu. Aponte um
monitor externo para `https://api.<seu-dominio>/health/ready` (§82). Qualquer serviço gratuito de
uptime serve, ou um `curl` em outra máquina que você já tenha. **Ele não é requisito para o Spark
rodar**; é requisito para você ficar sabendo quando ele parar.

### 11. Endereço no APK de release

```bash
./gradlew :app:assembleRelease -PsparkBackendBaseUrl=https://api.seudominio.com
```

O build **falha** se o endereço não for HTTPS ou apontar para host de desenvolvimento/rede privada
(§6/§7), e o app recusa o mesmo em runtime (`SparkBackendEndpoint`). Sem `-P`, o endereço nasce
vazio: o APK sai sem nuvem, e o núcleo do Spark continua completo.

## Deploy e rollback

```bash
ops/deploy.sh                      # backup → build spark-backend:<sha> → up → health
ops/deploy.sh --rollback <sha>     # volta para uma imagem anterior
```

O que `deploy.sh` garante, e por isso ele existe (§22/§107):

1. recusa deploy com alterações não commitadas — a tag da imagem precisa descrever o que sobe;
2. faz backup **antes** de qualquer migration;
3. etiqueta a imagem com o SHA do commit — nada de `latest` em produção (§23);
4. exige `/health/ready` antes de declarar sucesso (§109);
5. volta para a imagem anterior automaticamente se a nova não ficar saudável.

> **Rollback de aplicação não é rollback de migration** (§24). Se a versão que está saindo aplicou
> uma migration que a anterior não entende, voltar a imagem deixa um schema à frente do código. Aí
> o caminho é restaurar o backup pré-deploy — ver [RUNBOOK.md](./RUNBOOK.md), "migration falhou".
>
> Política a partir da T16.8 (§25): quando uma mudança incompatível for necessária, prefira
> *expand → deploy → contract em release posterior*. Adicione a coluna nova, publique o código que
> escreve nas duas, e só remova a antiga num release seguinte.

## Manutenção programada

```bash
# em backend/.env
SPARK_MAINTENANCE_MODE=true
docker compose -f docker-compose.prod.yml up -d backend
```

`/v1` responde `503` no envelope de erro de sempre; `/health/*` continua verde para que o
healthcheck do container não reinicie nada no meio da manutenção. O app trata 503 como
indisponibilidade recuperável em todos os caminhos online, e o núcleo continua funcionando
offline.

## Interruptores de operação

| Variável | Efeito | Quando usar |
| --- | --- | --- |
| `AI_ENABLED=false` | `/v1/ai/coach` → `503 AI_PROVIDER_UNAVAILABLE` | Custo do provider disparou, ou incidente na conta do Gemini |
| `SYNC_WRITE_ENABLED=false` | `POST /v1/sync/push` → `503`; pull continua | Defeito grave no sync: pausa a escrita remota sem quebrar o local |
| `MAINTENANCE_MODE=true` | Todo `/v1` → `503` | Manutenção, migration demorada |

Nenhum deles afeta o núcleo do Spark no aparelho. Detalhes de cada um no
[RUNBOOK.md](./RUNBOOK.md).

## O que esta implantação NÃO faz

- Não há deploy automático a partir do CI. Deploy é manual e documentado (§104) — para um app
  pessoal, isso é adequado, e evita guardar chave SSH de produção no GitHub (§106).
- Não há blue/green, réplica, load balancer ou failover. Um backend, uma VPS (§22).
- Não há registry: a imagem é construída na própria VPS a partir de um commit conferido (§108).
