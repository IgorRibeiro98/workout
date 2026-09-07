# Spark — Backup e restauração do servidor

> **Estado:** `IMPLEMENTED` (scripts, agendamento, ensaio) · `MANUAL SETUP REQUIRED` (destino
> off-site) · `NOT VERIFIED` em produção real — nenhum storage foi contratado por esta tarefa.
>
> O fluxo inteiro foi exercitado localmente com Docker e um repositório restic real: snapshot com
> o banco ativo recebendo escrita → `integrity_check` → envio criptografado → restauração →
> backend subindo sobre a cópia → `/health/ready`.

## Duas coisas diferentes com o mesmo nome

```text
T16.4  BACKUP DO USUÁRIO          protege contra perder o APARELHO
       Android ──▶ Spark Backend  o dado do usuário passa a existir no servidor

T16.8  BACKUP DO SERVIDOR         protege contra perder a VPS
       SQLite ──▶ storage off-site o banco do servidor passa a existir fora dela
```

Confundir os dois dá falsa sensação de segurança nos dois sentidos (§48):

- o backup da T16.4 **não** protege contra a perda da VPS. Antes da T16.8, um `docker compose
  down -v` destruía os backups de todo mundo;
- restaurar o SQLite do servidor **não** restaura o Room de ninguém (§49). Ele recupera os
  snapshots de backup remotos, o estado de sync, o change log, os tombstones e a metadata de uso
  da IA. O aparelho continua sendo a autoridade operacional do treino, e o usuário restaura o
  aparelho dele pelo fluxo da T16.5.

## Como o snapshot é feito

```text
spark.db (ativo, WAL) ──VACUUM INTO──▶ cópia ──integrity_check──▶ foreign_key_check ──▶ manifesto
```

**`cp spark.db backup.db` com o banco ativo é proibido** (§26). Em WAL, o arquivo principal não
contém as transações que ainda estão no `-wal`: a cópia abre, parece íntegra e está desatualizada —
ou rasgada no meio de uma transação, se a cópia levar tempo. É o pior tipo de backup, o que só
falha no dia em que é usado.

`VACUUM INTO` é o mecanismo oficial do SQLite para copiar um banco **em uso**: roda dentro de uma
transação de leitura, enxerga um ponto único no tempo e escreve um arquivo novo já compactado. Em
WAL ele **não bloqueia escritores** (§28) — o servidor continua atendendo durante o backup, e isso
é verificado no smoke local com escrita concorrente.

A alternativa considerada foi a Backup API (`db.backup()` do `better-sqlite3`), igualmente correta.
`VACUUM INTO` venceu por ser uma instrução única e síncrona e por entregar um arquivo menor — o que
importa quando ele sobe pela rede de uma VPS.

A conexão que gera o snapshot é aberta **somente leitura**: o caminho de backup não consegue
escrever no banco de produção, nem por um erro futuro.

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
| `spark.db` (snapshot consistente e verificado) | Service account do Firebase |
| `manifest.json`: versão do schema, imagem do backend, tamanho, horário, host | Chave do Gemini |
| | Senha do restic, chaves TLS, chave SSH |

Segredo nenhum entra no banco (§35) e, portanto, nenhum entra no backup. Eles vivem fora dele por
construção: a credencial do Admin é um **caminho** montado somente-leitura, e a do Gemini é
variável de runtime a partir de um arquivo `600`.

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

- escreve o estado com `outcome: FAILURE` e o motivo;
- imprime a saída real do restic em `stderr` — é ela que diz *por quê*;
- sai com código diferente de zero, que é o que o systemd registra e o `OnFailure=` enxerga.

`ops/check-health.sh` acusa quando o último sucesso passa de 36 h (§138) — backup diário com mais
de 36 h significa pelo menos uma execução perdida sem ninguém notar.

## Ensaio de restauração — obrigatório

```bash
ops/verify-backup.sh                       # do off-site
ops/verify-backup.sh --from-file /caminho/spark.db   # local, sem credencial de storage
```

```text
backup off-site ──▶ restauração ──▶ integrity_check + foreign_key_check
                ──▶ backend real sobe sobre a cópia ──▶ /health/ready
                ──▶ /v1/backups e /v1/sync/pull respondem 401 (nascem fechadas)
```

**"O job saiu com código 0" e "o arquivo apareceu no storage" não são validação** (§43/§112). O
ensaio prova quatro coisas de uma vez: o repositório abre com a senha que o operador tem, o banco
restaurado é íntegro, o schema está aplicado, e o processo de produção sobe sobre ele.

Ele não toca em produção em momento nenhum: sobe um container separado, em porta separada, sobre
uma cópia.

**Faça o ensaio depois do primeiro backup e sempre que a senha, o destino ou o schema mudarem.**

## Restaurar de verdade

```bash
ops/restore.sh --to /tmp/restauracao                    # extrai e verifica; não toca em produção
ops/restore.sh --to /tmp/restauracao --snapshot <id>    # um snapshot específico
docker compose -f docker-compose.prod.yml down          # o backend precisa estar parado
ops/restore.sh --to /tmp/restauracao --install          # troca o banco
docker compose -f docker-compose.prod.yml up -d
curl -s https://api.<dominio>/health/ready
```

O que `restore.sh` **nunca** faz (§130):

- não apaga o banco atual — move para `spark.db.pre-restore-<timestamp>`. Se a restauração for a
  errada, o estado anterior ainda está lá;
- não instala sobre um backend em execução — dois processos escrevendo no mesmo arquivo durante a
  troca é como se corrompe um SQLite de propósito;
- não instala sem `integrity_check` passar.

Listar o que existe no repositório:

```bash
restic snapshots --host spark --tag spark-db
```

## Snapshot do provedor não substitui isto

Um snapshot da VPS pelo painel do provedor é uma camada adicional útil e barata — e **não** é uma
estratégia de recuperação sozinho (§38): ele vive na conta do mesmo provedor, é um retrato do disco
inteiro (não um backup lógico verificado do banco), e não passa por `integrity_check`. Use os dois;
não troque um pelo outro.

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
