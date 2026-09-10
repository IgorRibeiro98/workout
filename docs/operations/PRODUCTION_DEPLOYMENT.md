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
                            │
                            ├─▶ PostgreSQL / Neon (DATABASE_URL, persistência remota desde T18.0)
                            │
                            ▼ bind mounts
                 /opt/spark/data/           ledgers operacionais (deletion_tombstones.tsv)
                 /opt/spark/media/          fotos dos check-ins (T17.9), fora do banco
                            │
                            │ ops/backup.sh (diário + pré-deploy)
                            ▼
                 restic → storage off-site (criptografado)
```

Persistência relacional exclusivamente no PostgreSQL (migrado na T18.0/T18.0.1). O app Android
continua usando Room/SQLite localmente para funcionamento offline integral.

## Usuários, grupos e permissões

> **Estado:** `IMPLEMENTED` · verificado em CI por `ops/tests/permissions.test.sh`, que constrói o
> desencontro de uid de propósito.

Duas identidades precisam alcançar os mesmos arquivos, e elas **não** são a mesma:

```text
host                                    container
────                                    ─────────
usuário spark                           usuário node
uid: o que o adduser tiver livre        uid 1000 (USER node no Dockerfile)
     (1000 numa VPS nova,
      1001+ onde já exista um usuário)
        │                                    │
        └────────── grupo spark-data ────────┘
                    (gid: o que o sistema atribuir)
```

**O uid dos dois não coincide, e o desenho não pode depender de que coincida.** A T16.8
documentava `/opt/spark/data` como `1000:1000` e a service account como `spark:spark` `600`; isso
funciona enquanto o `adduser spark` receber, por acaso, o uid 1000. Numa VPS onde outro usuário já
ocupe o 1000, `spark` vira 1001 e uma das duas coisas quebra:

- o backend (uid 1000) não abre `/opt/spark/data` de `spark` (uid 1001) em modo `700`; ou
- o operador (uid 1001) não lê o `firebase-admin.json` `600` do uid 1000.

Nenhuma das duas aparece em revisão de código, e a segunda só aparece na primeira requisição
autenticada. **Uma configuração que funciona porque dois usuários receberam o mesmo uid não é uma
configuração de produção.**

A solução é não depender do uid: o acesso vem de um **grupo compartilhado**, e o container entra
nele por `group_add` no compose. Não há `chmod 777`, não há `chown` em runtime, não há `sudo` na
operação normal e o backend continua rodando sem privilégio.

### Diretórios na VPS

| Caminho | Dono | Permissão | Quem precisa, e para quê |
| --- | --- | --- | --- |
| `/opt/spark/repo` | `spark:spark` | `755` | Repositório (compose, Caddyfile, `ops/`) |
| `/opt/spark/data` | `spark:spark-data` | `2770` | **host** + **container** (ledgers operacionais e tombstones anti-ressurreição) |
| `/opt/spark/media` | `spark:spark-data` | `2770` | **host** (backup lê as fotos) + **container** (escreve e serve a mídia dos check-ins, T17.9) |
| `/opt/spark/secrets` | `spark:spark-data` | `2750` | host escreve; container lê a service account |
| `/opt/spark/secrets/firebase-admin.json` | `spark:spark-data` | `640` | container lê (montado `:ro`) |
| `/opt/spark/secrets/backend.env` | `spark:spark` | `600` | só o host (o Compose o lê antes de subir) |
| `/opt/spark/secrets/backup.env` | `spark:spark` | `600` | só o host |
| `/opt/spark/secrets/restic-password` | `spark:spark` | `600` | só o host |
| `/opt/spark/backups` | `spark:spark` | `700` | só o host (área de trabalho do backup) |
| `/opt/spark/state` | `spark:spark` | `700` | só o host (`backup-status.json`) |

O `2` de `2770` e `2750` é o **setgid**, e ele não é decoração: sem ele, um arquivo criado dentro
do diretório nasce com o grupo *primário* de quem o criou em vez do grupo compartilhado — e o outro
lado deixa de conseguir lê-lo. Foi exatamente esse o erro que `ops/tests/permissions.test.sh` pegou
em `/opt/spark/secrets` quando este modelo foi escrito: o diretório de dados estava certo, o de
segredos estava errado, e a diferença só aparecia como `EACCES` no startup do container.

Repare no que **não** está no grupo compartilhado: `backend.env`, `backup.env` e a senha do restic
continuam `600` do `spark`. O container não precisa deles — quem os lê é o Docker (antes de subir) e
os scripts do host —, e ampliar o alcance de um segredo sem necessidade é o oposto do objetivo.

`700`/`2770` e nunca `777`: o banco não é segredo de autenticação, mas é dado pessoal de todo mundo
que usa o servidor (§145/§146). Um job do CI (`Nenhum chmod aberto`) recusa `777`/`666` em `ops/` e
nos arquivos de compose, para que "resolver" um problema de permissão abrindo tudo deixe de ser
possível sem que alguém precise notar em revisão.

## Checklist de provisionamento — `MANUAL SETUP REQUIRED`

### 1. Sistema

```bash
sudo apt-get update && sudo apt-get upgrade -y
sudo apt-get install -y ca-certificates curl restic
sudo timedatectl set-timezone UTC          # o servidor trabalha em UTC (§124/§126)
timedatectl show -p NTPSynchronized        # o systemd-timesyncd do Ubuntu já basta (§125)
```

### 2. Usuário de deploy e grupo compartilhado

Não operar como `root` (§11).

```bash
sudo adduser --disabled-password --gecos "" spark
sudo usermod -aG docker spark              # necessário para docker compose sem sudo

# O grupo que liga o operador do host ao processo do container. NÃO fixe um gid: deixe o sistema
# escolher um livre — fixar um número é a mesma suposição que este modelo existe para remover.
sudo groupadd spark-data
sudo usermod -aG spark-data spark
```

Anote o gid; ele entra no `.env` do Compose no passo 7:

```bash
getent group spark-data | cut -d: -f3
```

> **Não confira o uid de `spark`, e não tente forçá-lo para 1000.** Ele pode ser qualquer número, e
> é isso que o modelo garante. O container continua rodando como `node` (uid 1000) e alcança os
> arquivos pelo grupo — ver "Usuários, grupos e permissões" acima.

Reabra a sessão (`exit` e `ssh` de novo) para que a participação nos grupos `docker` e `spark-data`
passe a valer: participação de grupo entra na sessão no login, e não retroativamente.

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
sudo mkdir -p /opt/spark/{data,media,secrets,backups,state}
sudo chown -R spark:spark /opt/spark

# Compartilhados com o container, pelo grupo — e com setgid, para que o que o container criar
# herde o grupo em vez do grupo primário de quem criou.
#
# `/opt/spark/media` é o volume da T17.9: as fotos dos check-ins vivem nele, fora do banco. Ele é
# **separado** de `/opt/spark/data` de propósito — o snapshot do banco é uma cópia completa a cada
# backup, e a mídia é grande, imutável e deduplicada pelo restic. Sem este diretório montado, o
# backend **não sobe** em produção (`SOCIAL_MEDIA_ROOT`), que é o comportamento desejado: um deploy
# que montasse o banco e esquecesse a mídia perderia todas as fotos na primeira recriação de
# container, em silêncio.
sudo chown spark:spark-data /opt/spark/data /opt/spark/media /opt/spark/secrets
sudo chmod 2770 /opt/spark/data /opt/spark/media
sudo chmod 2750 /opt/spark/secrets

# Só do host: o container não os toca.
sudo chmod 700 /opt/spark/backups /opt/spark/state

sudo -u spark git clone <url-do-repo> /opt/spark/repo
```

Confira antes de seguir — é mais barato conferir agora do que descobrir no primeiro deploy:

```bash
stat -c '%n %U:%G %a' /opt/spark/data /opt/spark/media /opt/spark/secrets
# /opt/spark/data    spark:spark-data 2770
# /opt/spark/media   spark:spark-data 2770
# /opt/spark/secrets spark:spark-data 2750
```

Coloque em `/opt/spark/secrets` (nada disso vem do Git — ver [SECURITY.md](./SECURITY.md)):

```bash
# service account do Firebase Admin (console → Configurações → Contas de serviço)
#
# `640` e grupo `spark-data`, e NÃO `600`: o container (uid 1000) precisa lê-la, e ele não é o
# dono do arquivo. Continua fora do alcance de qualquer outro usuário da máquina.
install -m 640 -o spark -g spark-data ~/firebase-admin.json /opt/spark/secrets/firebase-admin.json

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
cat > .env <<EOF
SPARK_DOMAIN=api.seudominio.com
SPARK_ACME_EMAIL=voce@seudominio.com
# O gid do grupo compartilhado (passo 2). O compose o exige — ele não tem valor padrão de
# propósito: um default silencioso aqui seria a mesma suposição de uid que este modelo remove.
SPARK_DATA_GID=$(getent group spark-data | cut -d: -f3)
EOF
chmod 600 .env

# Confira que o Compose resolve tudo antes do primeiro deploy.
SPARK_IMAGE_TAG=verificacao docker compose -f docker-compose.prod.yml config > /dev/null \
  && echo "compose de produção resolve"
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

### 10. Health: três perguntas diferentes

O backend **não publica porta no host** — quem escuta 80/443 é o Caddy. Isso torna
`curl http://127.0.0.1:8080/health/ready` na VPS uma verificação que **não pode funcionar**, por
construção. As três perguntas úteis têm três respostas distintas, e confundi-las faz um problema de
DNS parecer um backend morto:

| Pergunta | Como perguntar |
| --- | --- |
| O processo implantado serve? | `ops/check-health.sh` (usa `docker compose exec` no serviço) |
| A internet chega até ele? | `curl -s https://api.<seu-dominio>/health/ready` |
| Ele está vivo agora mesmo? | `cd /opt/spark/repo/backend && docker compose -f docker-compose.prod.yml exec -T backend node -e "fetch('http://127.0.0.1:8080/health/live').then(r=>r.text()).then(console.log)"` |

O health interno **não depende** de DNS, de certificado, de proxy nem de porta publicada: ele
verifica exatamente o container implantado, e é o que `ops/deploy.sh` exige antes de declarar
sucesso. Se ele responde e o público não, o problema está entre a internet e o container — DNS,
certificado, firewall ou o próprio Caddy.

Para incluir o health público no diagnóstico periódico, declare o endereço:

```bash
# em /opt/spark/secrets/backup.env, depois que houver domínio e TLS
SPARK_PUBLIC_HEALTH_URL=https://api.seudominio.com
```

Vazio (o padrão) significa "ainda não há endereço público": `check-health.sh` verifica só o
backend, que é o correto antes de existir domínio.

### 11. Monitoramento externo

`ops/check-health.sh` roda **dentro** da VPS — e uma VPS morta não avisa que morreu. Aponte um
monitor externo para `https://api.<seu-dominio>/health/ready` (§82). Qualquer serviço gratuito de
uptime serve, ou um `curl` em outra máquina que você já tenha. **Ele não é requisito para o Spark
rodar**; é requisito para você ficar sabendo quando ele parar.

### 12. Endereço no APK de release

```bash
./gradlew :app:assembleRelease -PsparkBackendBaseUrl=https://api.seudominio.com
```

O build **falha** se o endereço não for HTTPS ou apontar para host de desenvolvimento, rede privada
ou endereço IPv6 não roteável (`::1`, `fc00::/7`, `fe80::/10`), e o app recusa os mesmos em runtime
(`SparkBackendEndpoint`). Os dois portões são conferidos caso a caso contra a mesma tabela
(`contracts/endpoint/release-endpoint-cases.tsv`) por `./gradlew :app:verifyReleaseEndpointGate` e
pelo `SparkReleaseEndpointTableTest` — nenhum dos dois pode divergir sem quebrar o CI.

Sem `-P`, o endereço nasce vazio: o APK sai sem nuvem, e o núcleo do Spark continua completo.

## Deploy e rollback

```bash
ops/deploy.sh                      # backup → build spark-backend:<sha> → up → health
ops/deploy.sh --rollback <sha>     # volta para uma imagem anterior
```

O que `deploy.sh` garante, e por isso ele existe (§22/§107):

1. recusa deploy com alterações não commitadas — a tag da imagem precisa descrever o que sobe;
2. faz backup **antes** de qualquer migration, e aborta sem tocar em produção se ele falhar;
3. etiqueta a imagem com o SHA do commit — nada de `latest` em produção (§23);
4. exige `/health/ready` **pelo health interno** antes de declarar sucesso (§109) — sem depender de
   DNS, de certificado, do Caddy ou de porta publicada;
5. volta para a imagem anterior automaticamente se a nova não ficar saudável.

Os cinco são exercitados de verdade no CI (`backend.yml`, job `production-topology`), contra a
composição de produção real.

## Credencial obrigatória: o servidor não sobe sem ela

`docker-compose.prod.yml` declara `REQUIRE_FIREBASE_ADMIN=true`, e desde a T16.8.1 isso é
verificado **no startup**, não na primeira requisição. O processo lê o arquivo, faz o parse, confere
a forma de service account e inicializa o Admin SDK antes de abrir o banco. Se qualquer etapa
falhar, ele sai com código 1 e a razão vai para o log do container:

```bash
docker compose -f docker-compose.prod.yml logs backend | tail -5
# REQUIRE_FIREBASE_ADMIN=true, mas o arquivo não pôde ser lido (EACCES).
```

`EACCES` aqui é quase sempre permissão: a service account precisa ser `640` no grupo
`spark-data` (ver "Usuários, grupos e permissões"), e não `600` do dono.

A verificação é **local e offline** — nenhuma chamada ao Google no startup. `/health/ready`
continua sem consultar Firebase (§13.7): a credencial obrigatória impede o readiness de um jeito
mais forte, que é o processo não chegar a escutar a porta. O Gemini segue por outro caminho:
`REQUIRE_GEMINI` continua `false`, e o Coach fora nunca derruba backup e sync.

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
