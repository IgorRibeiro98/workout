# Spark — Backup e restauração do servidor

> **Estado:** `IMPLEMENTED` (scripts, agendamento, ensaio; PostgreSQL desde a T18.0.2) ·
> `MANUAL SETUP REQUIRED` (destino off-site) · `NOT VERIFIED` em produção real — nenhum storage foi
> contratado, e nenhum PostgreSQL gerenciado foi provisionado por esta tarefa.
>
> O fluxo é exercitado no CI (`backend.yml`, job `backup-drill`) contra um PostgreSQL real:
> `pg_dump` com o banco ativo recebendo escrita → verificação do arquivo → restauração num banco
> descartável → backend real subindo sobre a cópia → `/health/ready` → mídia legível pelo processo.
> O envio off-site (restic) continua sem credencial no CI e é validado manualmente.
>
> **O que este documento não é:** o DR do PostgreSQL gerenciado na topologia Cloud Run. Esse é
> `spark-db-backup` + `system/dr/postgres/` no bucket + o ensaio em destino limpo — desenhado na
> T18.3 e documentado em [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md). O que existe aqui é o
> backup lógico da topologia VPS (restic off-site) — que continua válido e continua necessário mesmo
> com PITR do provedor, porque vive **fora** da conta do provedor.

## Duas coisas diferentes com o mesmo nome

```text
T16.4  BACKUP DO USUÁRIO          protege contra perder o APARELHO
       Android ──▶ Spark Backend  o dado do usuário passa a existir no servidor
       (desde a T18.1: metadata e hashes no PostgreSQL; o documento canônico no Object Storage —
        GCS privado em produção, disco local no provider `local`)

T16.8  BACKUP DO SERVIDOR         protege contra perder a VPS (e a conta do provedor do banco)
       PostgreSQL ──▶ off-site     o banco do servidor passa a existir fora dos dois
       (pg_dump + ledger + o volume de mídia do provider `local`; um bucket GCS NÃO passa por aqui)
```

Confundir os dois dá falsa sensação de segurança nos dois sentidos (§48):

- o backup da T16.4 **não** protege contra a perda da VPS. Antes da T16.8, um `docker compose
  down -v` destruía os backups de todo mundo;
- restaurar o banco do servidor **não** restaura o Room de ninguém (§49). Ele recupera a metadata
  dos snapshots de backup remotos (e, com o provider `local`, os documentos deles junto com a
  mídia), o estado de sync, o change log, os tombstones e a metadata de uso da IA. O aparelho continua sendo a autoridade operacional do treino, e o usuário restaura o
  aparelho dele pelo fluxo da T16.5.

## Como o snapshot é feito

```text
PostgreSQL (ativo, DATABASE_URL) ──pg_dump --format=custom──▶ spark.dump ──pg_restore --list──▶ manifesto
```

`ops/snapshot.sh` roda `pg_dump` **por container** (`SPARK_PG_TOOLS_IMAGE`, `postgres:17-alpine`,
`--network host`), contra a `DATABASE_URL` que ele lê de `SPARK_DATABASE_URL`, do ambiente ou do
`.env` do compose — o mesmo arquivo de onde o serviço a lê. A connection string nunca é impressa e
nunca passa pela linha de comando do host.

**Por que `pg_dump`, e por que o formato custom.** `pg_dump` roda dentro de **uma** transação de
leitura (`REPEATABLE READ`): ele enxerga um ponto único no tempo, e o servidor continua aceitando
escrita enquanto ele copia — o mesmo invariante que o `VACUUM INTO` dava no SQLite (§28), agora
garantido pelo próprio banco. O CI prova isso com um escritor em laço durante o snapshot. O formato
custom é comprimido, carrega o índice do que contém e é o único que permite `--single-transaction`
na restauração. `--no-owner --no-privileges`: o dump restaura em um servidor cujo papel tenha outro
nome (Neon, local, CI) — dono e GRANT são configuração do destino, não conteúdo do backup.

**O que a verificação do arquivo prova, e o que não prova.** `pg_restore --list` lê o índice do
arquivo inteiro e falha em arquivo truncado ou corrompido, e o snapshot exige que a entrada
`schema_migrations` exista — é o que distingue um dump do Spark de qualquer outro arquivo. Ela
**não** prova que o conteúdo restaura nem que o backend sobe sobre ele: isso é o ensaio de
restauração, abaixo, que restaura de verdade num banco descartável.

**Versão das ferramentas.** A major do `pg_dump` precisa ser **maior ou igual** à do servidor; é
por isso que a ferramenta roda por imagem fixada, e não pelo pacote do host. Ao subir a major do
PostgreSQL, suba `SPARK_PG_TOOLS_IMAGE` junto.

## Off-site e criptografia

Uma cópia em `/opt/spark/backups` na mesma VPS **não é backup** (§30): o disco que morre leva as
duas. O destino é `RESTIC_REPOSITORY`, e a escolha do provedor é operacional (§31/§32) — S3,
Backblaze B2, Cloudflare R2, outro servidor por SFTP. O backend não sabe, e não precisa saber, para
onde o backup vai; trocar de provedor não muda uma linha de código.

O restic **criptografa antes de enviar** (§33/§147): o provedor de armazenamento nunca vê dado
pessoal em claro. Isso é verificado no smoke — o repositório é varrido em busca de conteúdo
conhecido, e ele não aparece.

A senha do repositório:

- **não entra no Git**, na imagem Docker nem no APK;
- vive em `/opt/spark/secrets/restic-password` (`600`), lida por `RESTIC_PASSWORD_FILE` — arquivo,
  e não variável, porque `ps` e `docker inspect` não mostram o conteúdo de um arquivo;
- **precisa existir em pelo menos um lugar fora da VPS** (§113). Um backup criptografado cuja
  senha só existe na máquina perdida é um backup perdido. Ver
  [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md).

## O que vai no backup

| Vai | Não vai |
| --- | --- |
| `spark.dump` (`pg_dump` consistente, formato custom, verificado) | Service account do Firebase |
| `$SPARK_MEDIA_DIR` — as fotos dos check-ins (T17.9) | Chave do Gemini |
| `deletion_tombstones.tsv` — o ledger anti-ressurreição (T17.13.1) | `ACCOUNT_DELETION_HMAC_KEY` |
| `manifest.json`: versão do schema, engine e formato do dump, imagem do backend, tamanho, horário, host, contagem e bytes de mídia, linhas do ledger | Senha do restic, chaves TLS, chave SSH, `DATABASE_URL` |

Segredo nenhum entra no banco (§35) e, portanto, nenhum entra no backup. Eles vivem fora dele por
construção: a credencial do Admin é um **caminho** montado somente-leitura, e a do Gemini é
variável de runtime a partir de um arquivo `600`.

### A mídia entra desde a T17.9

Desde a T17.9 o Feed tem fotos, e elas **não** estão no banco: o PostgreSQL guarda metadata
(`social_checkin_media`) e os bytes vivem em `/opt/spark/media`. Um backup que levasse só o dump
restauraria um Feed que aponta para arquivos que não existem — íntegro na restauração, e quebrado
na tela de quem abrisse.

O diretório entra como **segundo caminho do mesmo `restic backup`**:

```text
PostgreSQL ──pg_dump──▶ spark.dump ──pg_restore --list──▶ manifesto ──┐
deletion_tombstones.tsv ────────────────────────────────────────────────┼──▶ restic ──▶ off-site
/opt/spark/media ──────────────────────────────────────────────────────┘
```

Não há cópia extra em disco. O restic lê o diretório de origem direto e deduplica entre snapshots —
as fotos são imutáveis depois de escritas, então o backup de amanhã não reenvia nenhuma das de hoje
— e criptografa antes de sair da VPS, como já fazia com o banco.

Um servidor que ainda não recebeu foto nenhuma não tem o diretório, e isso **não é erro**: o backup
registra um aviso e segue com o banco.

### Com o provider `gcs`, mídia e backup pessoal não vivem mais no sistema de arquivos (T18.1)

Desde a T18.1 o backend escolhe onde os **bytes** moram por `OBJECT_STORAGE_PROVIDER`:

```text
OBJECT_STORAGE_PROVIDER=local   fotos em $SPARK_MEDIA_DIR/checkins/…  e  documentos de backup do
                                usuário em $SPARK_MEDIA_DIR/backups/…  → os dois entram no restic
                                exatamente como acima (mesmo diretório, mesmo snapshot)

OBJECT_STORAGE_PROVIDER=gcs     fotos em  <bucket>/social/checkins/…  e  documentos de backup em
                                <bucket>/backups/…  → NÃO estão em disco nenhum da VPS, NÃO entram
                                no restic, e `ops/backup.sh` registra "mídia: 0 arquivos" com razão
```

Com `gcs`, o `pg_dump` continua levando **toda a metadata** (`backup_snapshots` com `storage_key`,
`payload_hash` e `size_bytes`; `social_checkin_media` com `storage_key` e `content_hash`), e a
durabilidade dos bytes passa a ser a do bucket — objetos imutáveis, nunca sobrescritos, apagados só
depois do commit que os desreferencia. Restaurar o banco recupera as referências; os objetos nunca
saíram do lugar. Um objeto que o banco restaurado referencie e o bucket não tenha (o bucket foi
tocado por fora) responde `410 BACKUP_CONTENT_UNAVAILABLE` no restore do usuário e `404` na foto —
nunca conteúdo corrompido, porque o hash é conferido antes de qualquer byte sair.

O que este documento **não** redesenha: a proteção do bucket (soft delete de 7 dias, `public
access prevention`, UBLA — auditadas por `ops/gcp/config-drift-audit.sh`) e o DR do PostgreSQL
gerenciado (`spark-db-backup`, T18.3). Os dois estão em [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md).

### O ledger de exclusões entra desde a T17.13.1

`deletion_tombstones.tsv` é o que impede uma conta excluída de voltar à vida quando um backup
anterior à exclusão é restaurado — ele é a única memória da exclusão que sobrevive à substituição do
conteúdo do banco. Ele vive **fora** do banco, em `$SPARK_DATA_DIR` (desde a T18.0, é o único
arquivo lá), e por isso não estava sendo capturado até a T17.13.1: o snapshot levava só o banco.

Uma perda total da VPS deixava o operador com o banco e a mídia de volta e **nenhum** registro de
quem já tinha sido excluído. E como a reconciliação pós-restore falha fechada quando o ledger não
existe, a ausência dele no backup transformaria toda recuperação de desastre num bloqueio
permanente. O `backup.sh` copia o arquivo para dentro da área de trabalho antes do `restic backup`,
e o manifesto registra `deletionLedgerRows`.

A chave HMAC **não** entra no backup: ela é segredo de runtime, vive em
`$SPARK_SECRETS_DIR/backend.env` e precisa ser guardada com o mesmo cuidado — um backup restaurado
sem ela é um ledger que não casa com nada.

## Agendamento e retenção

| | |
| --- | --- |
| **Quando** | Diário, 03:15 UTC (`spark-backup.timer`, com `Persistent=true`) + **antes de cada deploy** (`ops/deploy.sh`) |
| **Retenção** | 7 diários, 4 semanais, 12 mensais (§37) — configurável em `backup.env` |
| **Exclusão mútua** | `flock`: dois jobs simultâneos não rodam (§135) |
| **Idempotência** | Rodar duas vezes cria dois snapshots restic; o repositório deduplica e nada corrompe (§134) |
| **Ordem** | `--prune` só **depois** do backup novo confirmado — uma falha deixa backup a mais, nunca a menos (§133) |

Diário, e não a cada minuto (§36): o volume de escrita do Spark é pequeno e cada execução custa
I/O e armazenamento. O backup pré-deploy cobre o outro momento de risco real.

## Falha é visível

Cada execução grava `/opt/spark/state/backup-status.json` (§39/§137):

```json
{ "startedAt": "...", "finishedAt": "...", "durationSeconds": 3, "outcome": "SUCCESS",
  "tag": "scheduled", "snapshotId": "...", "sizeBytes": 94208,
  "lastSuccessfulBackupAt": "...", "lastSuccessfulBackupEpoch": 1788806805 }
```

Metadata apenas — nunca conteúdo, nunca uid, nunca nada do banco.

`lastSuccessful*` **não é apagado por uma falha**: `ops/check-health.sh` mede a idade do último
sucesso, e zerá-la faria uma falha parecer "nunca houve backup". Uma execução com `outcome:
FAILURE` é reportada como problema mesmo que o último sucesso ainda seja recente.

Uma falha (§40):

- escreve o estado com `outcome: FAILURE` e a **etapa** em que parou;
- imprime a saída real do restic em `stderr` — é ela que diz *por quê*;
- sai com código diferente de zero, que é o que o systemd registra e o `OnFailure=` enxerga.

### Todo desfecho chega ao estado (T16.8.1 §9)

Isso não pode depender de qual caminho de erro foi tomado, e por um tempo dependeu: o script usava
`trap ... ERR`, e `fail()` faz `exit` — que **não** dispara o trap de ERR. Credencial ausente,
restic recusado e `snapshot_id` vazio saíam com código diferente de zero deixando o arquivo com o
desfecho **anterior**. Um backup quebrado registrado como sucesso é o pior resultado possível deste
script: ele não só falha, como afirma que não falhou.

Hoje há um `trap finalize EXIT`, que cobre `set -e`, `exit` explícito e término normal de uma vez.
`ops/tests/backup-status.test.sh` exercita os sete desfechos no CI — sucesso, credencial ausente,
falha do snapshot, falha do envio, restic sem `snapshot_id`, falha da retenção e área de trabalho
sem escrita — e exige, em todos, `outcome: FAILURE`, código diferente de zero e o último sucesso
**preservado**.

A `message` carrega a etapa, de um vocabulário fechado:

| Etapa | O que estava acontecendo |
| --- | --- |
| `config` | Credencial do storage (`RESTIC_REPOSITORY`, senha) |
| `workdir` | Criando a área de trabalho em `/opt/spark/backups` |
| `snapshot` | `pg_dump` + `pg_restore --list` (ou `DATABASE_URL` inalcançável) |
| `manifest` | Lendo a versão do schema e a imagem no ar |
| `offsite-upload` | `restic backup` |
| `offsite-retention` | `restic forget --prune` |

É um rótulo, e **não** a saída do comando que falhou: a saída do restic pode conter o endereço do
repositório e a credencial de storage, e este arquivo é aberto durante o diagnóstico. O detalhe
técnico fica no journal do systemd, onde ele pertence.

`ops/check-health.sh` acusa quando o último sucesso passa de 36 h (§138) — backup diário com mais
de 36 h significa pelo menos uma execução perdida sem ninguém notar.

## Ensaio de restauração — obrigatório

```bash
export SPARK_DRILL_DATABASE_URL=postgresql://.../spark_drill   # um banco DESCARTÁVEL, nunca produção
ops/verify-backup.sh                                            # do off-site
ops/verify-backup.sh --from-file /caminho/spark.dump            # local, sem credencial de storage
```

```text
backup off-site ──▶ restauração ──▶ pg_restore --list
                ──▶ pg_restore --single-transaction num banco DESCARTÁVEL
                ──▶ backend real sobe sobre ele ──▶ /health/ready
                ──▶ /v1/backups e /v1/sync/pull respondem 401 (nascem fechadas)
                ──▶ a mídia restaurada é lida byte a byte pelo processo
```

**"O job saiu com código 0" e "o arquivo apareceu no storage" não são validação** (§43/§112). O
ensaio prova quatro coisas de uma vez: o repositório abre com a senha que o operador tem, o dump
restaura, o schema está aplicado, e o processo de produção sobe sobre ele.

Ele não toca em produção em momento nenhum: o dump é restaurado em `SPARK_DRILL_DATABASE_URL` — um
banco separado que o operador cria para isto (`CREATE DATABASE spark_drill`, ou um branch no Neon)
— e o script **recusa rodar** se essa URL for a de produção. O backend do ensaio sobe em porta
separada, sobre a mídia restaurada com o **mesmo modelo de permissão da produção** (diretório
`2770`, arquivo `660`, container no grupo por `--group-add`). Até a T16.8.1 o ensaio usava
`chmod 777`, o que além de ser o que a T16.8 proíbe tornava o ensaio incapaz de detectar o problema
que ele deveria detectar: com `777`, qualquer combinação de uid passa.

**Faça o ensaio depois do primeiro backup e sempre que a senha, o destino ou o schema mudarem.**

## Restaurar de verdade

```bash
ops/restore.sh --to /tmp/restauracao                    # extrai e verifica; não toca em produção
ops/restore.sh --to /tmp/restauracao --snapshot <id>    # um snapshot específico
docker compose -f docker-compose.prod.yml down          # o backend precisa estar parado
ops/restore.sh --to /tmp/restauracao --install          # troca o banco E a mídia
docker compose -f docker-compose.prod.yml up -d
ops/check-health.sh                                     # health interno: não depende de DNS/TLS
curl -s https://api.<dominio>/health/ready              # e o público, quando houver domínio
```

`--install` restaura o dump **no PostgreSQL de `DATABASE_URL`** com `pg_restore --clean --if-exists
--single-transaction --exit-on-error`: ou o dump inteiro entra, ou nada muda. Antes de tocar no
banco ele tira um dump do estado atual (`$SPARK_STAGING_DIR/pre-restore-<timestamp>.dump`) — é o
equivalente do `spark.db.pre-restore-*` de antes, e removê-lo é decisão humana depois da validação.
Objetos que existam no banco e não no dump (uma migration mais nova que a do snapshot) permanecem;
o runner de migrations, no próximo startup, parte da `schema_migrations` restaurada e reaplica o
que faltar.

**Risco conhecido, não resolvido nesta tarefa (T18.0.3 §8): restaurar um snapshot mais antigo que a
migration mais recente de produção.** `pg_restore --clean --if-exists` só recria os objetos que
estão no dump — ele não sabe quais tabelas/colunas o banco de destino ganhou **depois** daquele
snapshot, e por isso não as apaga. O resultado depois do `--install`:

- `schema_migrations` volta a mostrar só as versões do snapshot — a bookkeeping "esquece" que a(s)
  migration(ões) mais recente(s) já rodou(aram);
- mas os objetos que aquela migration criou **continuam no banco**, porque `--clean --if-exists` não
  os tocou.

No próximo boot, o runner de migrations vê `schema_migrations` incompleta e tenta reaplicar a
migration que "falta". Para a maioria das migrations — `CREATE TABLE`, `ALTER TABLE ... ADD COLUMN`
sem `IF NOT EXISTS`, `CREATE INDEX` sem `IF NOT EXISTS` — isso **falha alto**: o objeto já existe, o
`CREATE`/`ALTER` dá erro, a migration não commita, e o processo não sobe. É desconfortável, mas é o
desfecho seguro: nada fica silenciosamente incoerente, e o operador percebe no primeiro `/health/ready`
que falhou.

O desfecho perigoso é uma migration futura escrita de forma idempotente (`CREATE TABLE IF NOT
EXISTS`, `ADD COLUMN IF NOT EXISTS`): ela reaplicaria **sem erro** sobre o objeto que já estava lá
desde antes da restauração — sem recriar dados, sem revalidar constraints que só o `CREATE`
original teria checado — e o boot pareceria limpo. Esse é o cenário de "corrupção silenciosa" que
a T18.0.3 não resolvia. **Desde a T18.3 ele é recusado**: `ops/restore.sh --install` compara as
tabelas do destino com o índice do dump (`pg_dump_extra_tables`, em `ops/lib.sh`) **antes** do
`pg_restore --clean`, e para com uma mensagem clara quando o destino tem tabelas que o snapshot não
contém — `ops/tests/restore-old-snapshot-risk.test.sh` prova as duas coisas com `pg_dump`/`pg_restore`
reais. O caminho para restaurar um snapshot mais antigo que o schema atual é o destino **limpo**:
um banco novo, restaurado do zero e depois apontado por `DATABASE_URL` — o mesmo desenho do DR do
Cloud Run ([`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md)).

**O Feed só está recuperado quando os dois voltaram.** `--install` instala o banco **e** a mídia:
restaurar só o banco deixa `/health/ready` respondendo e cada card com foto sem imagem. A mídia
anterior é preservada em `/opt/spark/media.pre-restore-<timestamp>` pelo mesmo motivo do banco — se
a restauração for a errada, o que existia ainda está lá.

E a reconciliação anti-ressurreição **já roda dentro do `--install`** desde a T17.13.1: o script
instala o ledger de exclusões (unido ao que houver no disco), executa
`node dist/cli/reconcile-account-deletions.js` sobre o banco recém-instalado e só então declara a
restauração completa. Uma falha ali falha o `--install` inteiro.

Isso importa porque restaurar um snapshot **anterior** a uma exclusão de conta traz a conta de
volta — linhas e arquivos. A reconciliação purga os dois de novo. Antes, o script apenas imprimia
"rode a reconciliação depois", e o comando que ele mandava rodar não existia.

Duas consequências operacionais:

- o `--install` exige `ACCOUNT_DELETION_HMAC_KEY` (do ambiente ou de
  `$SPARK_SECRETS_DIR/backend.env`). É ela que liga um hash do ledger a um uid do banco; com a
  chave errada a reconciliação não encontra nada e **reporta sucesso**;
- se o ledger não existir nem no snapshot nem no disco, o `--install` **para**. Isso é
  deliberado: sem ele não há como saber quais contas já foram excluídas, e prosseguir as devolveria
  ao ar.

Ver [../runbooks/account-deletion-dr.md](../runbooks/account-deletion-dr.md).

A mídia e o ledger são instalados com modo `660`/`640` no **grupo compartilhado** do diretório de
dados, e não com `chown 1000:1000` — que era o que o script fazia até a T16.8.1 e que estava errado
duas vezes: `chown` exige root e o número 1000 presumia que o uid do operador e o do container
coincidissem. Ver [PRODUCTION_DEPLOYMENT.md](./PRODUCTION_DEPLOYMENT.md), "Usuários, grupos e
permissões".

O que `restore.sh` **nunca** faz (§130):

- não descarta o estado atual do banco sem preservá-lo — tira `pre-restore-<timestamp>.dump`
  antes. Se a restauração for a errada, o estado anterior ainda está lá;
- não apaga a mídia atual — move para `media.pre-restore-<timestamp>`, pelo mesmo motivo;
- não instala sobre um backend em execução — o processo tem conexões abertas e migrations que
  rodam no startup, e o `pg_restore` precisa ser o único escritor;
- não deixa restauração pela metade — `--single-transaction --exit-on-error`;
- não instala sem `pg_restore --list` passar e sem `schema_migrations` no dump.

Listar o que existe no repositório:

```bash
restic snapshots --host spark --tag spark-db
```

## Snapshot do provedor não substitui isto

Um snapshot da VPS pelo painel do provedor — ou o PITR/branch do provedor do PostgreSQL (Neon) — é
uma camada adicional útil e barata, e **não** é uma estratégia de recuperação sozinho (§38): vive
na conta do mesmo provedor, e uma conta suspensa, uma credencial vazada ou um erro do provedor leva
o banco e a "cópia" juntos. O `pg_dump` off-site é o que existe **fora** dessa conta. Use os dois;
não troque um pelo outro. No Cloud Run, o `pg_dump` fora da conta do provedor é o Job
`spark-db-backup` (T18.3, [`DISASTER_RECOVERY.md`](./DISASTER_RECOVERY.md)).

## RPO e RTO

Ver [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md).

## O que a T16.8 deliberadamente não faz

- **Não apaga tombstone** (§152). `SYNC_TOMBSTONE_RETENTION_DAYS` declara a retenção pretendida e
  nada a executa: um aparelho offline por meses precisa receber a exclusão quando voltar, e apagar
  cedo demais causa ressurreição. Uma limpeza segura exigiria conhecer o menor cursor entre os
  aparelhos ativos da conta — informação que o servidor não guarda.
- **Não compacta o change log** (§151). Pela mesma razão: `CURSOR_EXPIRED` existe justamente para o
  caso em que o servidor não pode mais entregar o histórico de um aparelho, e criá-lo por decisão
  própria seria forçar rebaseline sem necessidade.
- **Não limpa `sync_mutations`** (§153). O ledger é a janela de idempotência; encurtá-la faria um
  reenvio legítimo virar mutação nova. Na escala do Spark, não há razão para otimizar isso.
- **Não apaga backups de usuário além da retenção da T16.4** (§150). `BACKUP_RETENTION_COUNT`
  continua sendo a única política, e a T16.8 não a toca.
