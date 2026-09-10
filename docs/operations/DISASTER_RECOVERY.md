# Spark — Recuperação de desastre

> **Estado:** `IMPLEMENTED` (procedimento e scripts; PostgreSQL desde a T18.0.2) · `NOT VERIFIED`
> em VPS real — não há VPS nem PostgreSQL gerenciado provisionados. O ensaio equivalente
> (`ops/verify-backup.sh`) roda no CI contra um PostgreSQL real: restauração → `pg_restore` num
> banco descartável → backend real subindo sobre a cópia → `/health/ready`.
>
> Desde a T18.0 o banco **não vive na VPS**: ele é o PostgreSQL de `DATABASE_URL` (Neon ou outro
> gerenciado). Perder a VPS não perde o banco — perde a mídia, o ledger de exclusões e os segredos.
> Perder o **provedor do banco** (conta suspensa, credencial vazada, erro do provedor) é o cenário
> que o `pg_dump` off-site cobre, e é por isso que ele continua existindo mesmo com PITR do
> provedor. A política definitiva (PITR/branches) é a T18.3.

## O princípio

Uma VPS não é backup. Um volume Docker não é backup. Um snapshot do provedor não é, sozinho, uma
estratégia de recuperação.

Este documento existe para responder a uma pergunta com um procedimento, e não com uma intenção:
**a VPS morreu; o que eu faço agora?**

## Contra o que estamos protegidos

| Cenário | O que recupera | Onde está |
| --- | --- | --- |
| Container removido | O bind mount (mídia, ledger) sobrevive; o banco está fora da VPS | `PRODUCTION_DEPLOYMENT.md` |
| Deploy ruim | Rollback de imagem por tag | `ops/deploy.sh --rollback <sha>` |
| Migration ruim | Backup pré-deploy (`pg_dump`) | [RUNBOOK.md](./RUNBOOK.md), "migration falhou" |
| Disco da VPS corrompido | Backup off-site (mídia + ledger); o banco está fora da VPS | Este documento |
| **VPS perdida** | **Backup off-site + o banco gerenciado continua no ar** | **Este documento** |
| **Banco (provedor) perdido ou corrompido** | **`pg_dump` off-site, restaurado num PostgreSQL novo** | **Este documento** |
| Erro operacional / exclusão acidental | Backup off-site, snapshot anterior | `ops/restore.sh --snapshot <id>` |

## RPO — quanto se pode perder

```text
Backup do servidor      diário (03:15 UTC) + pré-deploy
RPO off-site            até 24 h
```

**O que isso significa de verdade, e é menos assustador do que parece.** O Spark é local-first: o
Room de cada aparelho é a autoridade operacional, e ele não é afetado por nada aqui. Perder até 24 h
do servidor significa perder até 24 h de *estado remoto* — o que já subiu de sync e de backup nesse
intervalo. Os aparelhos ainda têm o dado, e a Outbox de cada um só é liberada com confirmação do
servidor: o que não foi confirmado continua pendente e sobe de novo.

A consequência prática de restaurar o servidor para um ponto anterior é que aparelhos com cursor à
frente do change log restaurado recebem `CURSOR_EXPIRED` e pedem rebaseline explícito — que é
exatamente o desenho da T16.7, e não um efeito colateral (§46).

**Não prometemos RPO menor porque não existe RPO menor.** Um backup mais frequente é possível
(`spark-backup.timer`), mas enquanto ele for diário, o número é 24 h.

## RTO — quanto tempo leva

```text
provisionar VPS + instalar Docker      15–30 min
clonar repositório e restaurar segredos 10–20 min   ← depende de você ter os segredos
baixar e restaurar o backup             5–15 min    ← depende do tamanho e da rede
subir e validar                          5–10 min
DNS propagar                             minutos a horas   ← fora do seu controle
──────────────────────────────────────────────────
RTO realista                            1–2 h de trabalho, mais a propagação de DNS
```

Sem SLA e sem promessa de automação: é uma recuperação manual, feita por uma pessoa, em um app
pessoal (§47). O maior risco de atraso não é técnico — é não encontrar os segredos.

## Procedimento — a VPS foi perdida

### 0. Antes de tudo: você tem estes três?

```text
[ ] a senha do repositório restic
[ ] a credencial de acesso ao storage off-site
[ ] a service account do Firebase Admin (ou acesso ao console para gerar outra)
[ ] a DATABASE_URL do PostgreSQL (ou acesso ao provedor para criar um banco novo)
```

**Sem a senha do restic, o backup é irrecuperável.** Nenhum procedimento neste documento contorna
isso — é o ponto inteiro da criptografia. Ver "Onde os segredos precisam estar", abaixo.

### 1. Provisionar a máquina

Ubuntu LTS. Siga [PRODUCTION_DEPLOYMENT.md](./PRODUCTION_DEPLOYMENT.md), passos 1 a 6: sistema,
usuário `spark`, SSH por chave, firewall, Docker, diretórios.

### 2. Restaurar os segredos

Em `/opt/spark/secrets`, com permissão `600` (ver [SECURITY.md](./SECURITY.md)):

```text
firebase-admin.json     do gerenciador de segredos, ou gerada de novo no console do Firebase
backend.env             GEMINI_API_KEY + ACCOUNT_DELETION_HMAC_KEY
restic-password         do gerenciador de segredos / cópia física
backup.env              RESTIC_REPOSITORY + credencial do storage
backend/.env            DATABASE_URL (a do banco que sobreviveu, ou a do banco novo do passo 4)
```

**`ACCOUNT_DELETION_HMAC_KEY` é tão insubstituível quanto a senha do restic.** Ela é o que liga cada
linha do ledger de exclusões a um uid do banco. Sem ela o servidor não sobe em produção (é
exigência de startup), e a reconciliação pós-restore roda **sem encontrar nada e reportando
sucesso** — devolvendo ao ar todas as contas que haviam sido excluídas. Ela não está no backup, por
construção: é segredo de runtime.

### 3. Recuperar o repositório e a imagem

```bash
sudo -u spark git clone <url-do-repo> /opt/spark/repo
cd /opt/spark/repo/backend && cp Caddyfile.prod Caddyfile
```

### 4. Restaurar o banco, a mídia e o ledger

Decida primeiro **qual banco** é o destino:

- **a VPS morreu, o banco gerenciado está no ar** — o cenário mais provável. O banco não precisa
  ser restaurado: aponte `DATABASE_URL` para ele. O que falta na VPS nova é a mídia e o ledger, e
  eles vêm do snapshot (ver "só a mídia e o ledger", abaixo). Só restaure o dump por cima se a
  intenção for **voltar o banco** ao ponto do snapshot;
- **o banco foi perdido ou corrompido** — crie um PostgreSQL novo (um projeto/branch no Neon, ou
  qualquer PostgreSQL 17), coloque a `DATABASE_URL` dele em `backend/.env` e restaure o dump.

```bash
cd /opt/spark/repo
set -a; . /opt/spark/secrets/backup.env; set +a

restic snapshots --host spark --tag spark-db     # veja o que existe e escolha
ops/restore.sh --to /tmp/dr                      # extrai e VERIFICA, sem tocar em nada
ops/restore.sh --to /tmp/dr --install            # pg_restore em DATABASE_URL + mídia + ledger
```

`--install` só age depois de `pg_restore --list` passar e de `schema_migrations` existir no dump.
Antes de tocar no banco ele tira um dump do estado atual (`/opt/spark/backups/pre-restore-*.dump`)
e restaura em transação única — ou tudo entra, ou nada muda. Ele recusa rodar com o backend de pé.

Para restaurar **só a mídia e o ledger** (banco gerenciado intacto e mais novo que o snapshot):
extraia com `ops/restore.sh --to /tmp/dr`, copie a árvore `media` restaurada para
`/opt/spark/media` e una o ledger a `/opt/spark/data/deletion_tombstones.tsv`, com o mesmo modelo
de permissão (`2770`/`640` no grupo `spark-data`, ver PRODUCTION_DEPLOYMENT.md) — e rode a
reconciliação (`node dist/cli/reconcile-account-deletions.js`) antes de subir.

Ele também **instala o ledger de exclusões e roda a reconciliação anti-ressurreição** antes de
declarar a restauração completa (T17.13.1). Numa recuperação a partir do zero o ledger vem do
próprio snapshot; se ele não estiver nem lá nem no disco, o `--install` **para** — sem ele não há
como saber quais contas já foram excluídas, e prosseguir as devolveria ao ar. Ver
[../runbooks/account-deletion-dr.md](../runbooks/account-deletion-dr.md).

### 5. Subir

```bash
cd /opt/spark/repo
docker build -t spark-backend:recuperacao backend
cd backend
cat > .env <<EOF
SPARK_DOMAIN=api.seudominio.com
SPARK_ACME_EMAIL=voce@seudominio.com
# O gid do grupo compartilhado NESTA máquina — ele quase certamente não é o mesmo da VPS antiga,
# e não precisa ser: o modelo é por grupo, nunca por número fixo.
SPARK_DATA_GID=$(getent group spark-data | cut -d: -f3)
DATABASE_URL=postgresql://...            # o banco do passo 4
EOF
SPARK_IMAGE_TAG=recuperacao docker compose -f docker-compose.prod.yml up -d
```

Antes do DNS, confirme que o backend serve — o health interno não depende de domínio nem de
certificado, e separá-lo do público evita perseguir um problema de DNS achando que é de banco:

```bash
cd /opt/spark/repo && ops/check-health.sh
```

### 6. DNS

Aponte `api.<dominio>` para o IP **novo**. O Caddy emite o certificado sozinho assim que o nome
resolver e a porta 80 estiver acessível.

```bash
dig +short api.seudominio.com          # precisa devolver o IP novo
```

### 7. Validar, nesta ordem

```bash
curl -s https://api.seudominio.com/health/live      # {"status":"ok"}
curl -s https://api.seudominio.com/health/ready     # {"status":"ok", checks: todos true}

# Firebase Auth ponta a ponta: sem token, 401 (e não 404 nem 503)
curl -s -o /dev/null -w '%{http_code}\n' https://api.seudominio.com/v1/auth/me    # 401
# Com um ID Token real de uma conta de teste:
curl -s -H "Authorization: Bearer <id-token>" https://api.seudominio.com/v1/auth/me   # {"uid": ...}
```

`401` sem token prova que a rota existe e nasce fechada. `503` ali significaria service account
ausente ou inválida — volte ao passo 2.

No app, com uma **conta de teste** (§111):

```text
[ ] entrar na Conta Spark
[ ] Coach responde (ou responde indisponível, se AI_ENABLED=false)
[ ] listar backups: os snapshots anteriores aparecem
[ ] sync: push e pull convergem
[ ] um conflito existente ainda pode ser lido
```

E o portão anti-ressurreição, no servidor (T17.13.1):

```bash
# A reconciliação já rodou dentro do --install. Confira que ela encontrou o ledger:
sort -u /opt/spark/data/deletion_tombstones.tsv | wc -l
psql "$DATABASE_URL" -c "SELECT COUNT(*) FROM account_deletion_tombstones;"
```

A tabela precisa cobrir todas as contas do ledger. Se o número de tombstones for **zero** com um
ledger não vazio, a reconciliação rodou com a chave HMAC errada — pare e volte ao passo 2 antes de
apontar o DNS: o servidor está servindo contas que deveriam estar excluídas.

Nada destrutivo nessa validação: não apague backup, não force restore, não resolva conflito real.

### 8. Retomar o backup

```bash
sudo install -m 644 /opt/spark/repo/ops/systemd/spark-*.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now spark-backup.timer spark-check-health.timer
sudo systemctl start spark-backup.service
ops/verify-backup.sh                    # ensaio na máquina nova
```

**A recuperação não está concluída até o backup voltar a rodar.** Uma VPS restaurada sem backup é
a mesma situação de antes do desastre, com a diferença de que agora você sabe que ela acontece.

## O que a restauração do servidor NÃO faz

Ela **não** restaura o Room de nenhum aparelho (§49). O que volta é:

```text
backup_snapshots     os snapshots que os usuários enviaram (T16.4)
sync_entities        o estado remoto por agregado, com revision e tombstones
sync_changes         o change log
sync_mutations       o ledger de idempotência
ai_usage_daily       contagem de uso do Coach, sem conteúdo
```

E, fora do banco, na VPS, o ledger de exclusões (`deletion_tombstones.tsv`) — que não é dado de
usuário, e sim a lista de quem **não** pode voltar — e a mídia dos check-ins.

O treino de cada pessoa continua no aparelho dela. Quem perdeu o **aparelho** usa o restore da
T16.5, dentro do app.

## Onde os segredos precisam estar

Este documento **não contém segredo algum** (§114). O que ele registra é *onde eles precisam estar
no momento da recuperação*:

| Segredo | Precisa estar disponível fora da VPS | Sem ele |
| --- | --- | --- |
| Senha do repositório restic | **Sim, obrigatoriamente** | O backup é irrecuperável |
| Credencial do storage off-site | Sim | Não dá para baixar o backup |
| Service account do Firebase Admin | Não — pode ser gerada de novo no console | Rota autenticada responde 503 |
| Chave do Gemini | Não — pode ser gerada de novo | Coach indisponível; o resto funciona |
| `DATABASE_URL` / acesso ao provedor do banco | Sim — ou a capacidade de criar um banco novo e restaurar o dump | Sem banco não há servidor |
| `ACCOUNT_DELETION_HMAC_KEY` | **Sim, obrigatoriamente** | Contas excluídas voltam ao ar numa restauração |

Um gerenciador de senhas resolve todos. A senha do restic e a chave HMAC merecem também uma cópia
física em lugar seguro: são as duas cujo esquecimento é definitivo.

## Ensaio periódico

`ops/verify-backup.sh` **é** o ensaio de recuperação executável, e roda contra o backup real sem
tocar em produção. Rode-o depois do primeiro backup e sempre que a senha, o destino ou o schema
mudarem — e, idealmente, uma vez por trimestre por hábito.

A parte que ele não ensaia é a que depende de acesso humano: provisionar máquina, restaurar
segredos, mexer no DNS. Essa é a parte que costuma custar o tempo real de um desastre — vale ler
este documento antes de precisar dele.
