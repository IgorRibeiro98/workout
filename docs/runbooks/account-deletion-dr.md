# Runbook — Reconciliação de Exclusão de Conta e Disaster Recovery

- **Tarefa:** T17.6 — Hardening Social, Abuso e Exclusão de Conta
- **Componente:** `AccountDeletionService` / `account_deletion_tombstones` / `deletion_tombstones.tsv`
- **Ambiente:** Backend Node.js / NestJS / SQLite / Docker

---

## 1. Contexto e Objetivo

Quando um usuário exclui sua conta no Spark via `DELETE /v1/account`:
1. Todos os dados da conta são expurgados do SQLite em transação atômica.
2. Um tombstone criptográfico derivado de HMAC-SHA256 (`account_deletion_tombstones`) é gerado no banco para bloquear qualquer acesso futuro (`403 ACCOUNT_DELETED`).
3. Um registro em arquivo append-only (`deletion_tombstones.tsv`) é persistido no filesystem persistente montado no container (`DELETION_TOMBSTONES_FILE_PATH`).
4. Um job de exclusão assíncrona (`account_deletion_jobs`) é enfileirado para deletar o usuário no Firebase Auth.

Se o banco de dados for restaurado a partir de um backup diário anterior (Disaster Recovery), pode ocorrer uma situação em que uma conta excluída *depois* do backup volte a constar como ativa no banco restaurado (ressurreição acidental).

O log append-only `deletion_tombstones.tsv` sobrevive fora do snapshot do banco e é usado para **reconciliar** e reinserir todos os tombstones, garantindo que contas excluídas continuem permanentemente bloqueadas mesmo após restauração de backup antigo.

---

## 2. Procedimento Pós-Restore de Disaster Recovery

Sempre que o banco SQLite for restaurado via `ops/restore.sh` ou cópia de snapshot:

### Passo 1: Verificar a presença do arquivo de tombstones
```bash
ls -la /var/lib/spark/data/deletion_tombstones.tsv
```
O arquivo contém linhas tab-separated:
```tsv
<tombstoneHash>\t<deletedAtEpochMs>
```

### Passo 2: Executar a reconciliação de tombstones
O módulo de exclusão de conta possui a rotina de reconciliação embutida em `AccountDeletionService.reconcileTombstones(filePath)`.

No container ou ambiente local com ambiente configurado:
```bash
cd /var/www/html/workout/backend
node -e '
  const { AccountDeletionService } = require("./dist/modules/account-deletion/account-deletion.service");
'
```
Se o serviço estiver configurado para auto-reconciliação no startup ou chamado via script operacional, o serviço lê o TSV e insere os registros ausentes com `INSERT OR IGNORE INTO account_deletion_tombstones`.

### Passo 3: Auditoria e Validação
Validar que a tabela de tombstones contém todos os registros do TSV:
```bash
sqlite3 /var/lib/spark/data/workout.db "SELECT count(*) FROM account_deletion_tombstones;"
wc -l /var/lib/spark/data/deletion_tombstones.tsv
```

### Passo 4: Fila de Jobs Firebase Auth
Verificar se há jobs pendentes de exclusão no Firebase Auth:
```bash
sqlite3 /var/lib/spark/data/workout.db "SELECT id, status, attempts, last_error FROM account_deletion_jobs WHERE status = 'PENDING';"
```
Se houver jobs pendentes, o worker agendado processará as retentativas automaticamente.

---

## 3. Rotação de Segredo HMAC (`ACCOUNT_DELETION_HMAC_KEY`)

O segredo `ACCOUNT_DELETION_HMAC_KEY` é crítico. Se for alterado sem migração:
- Novos hashes não baterão com os hashes antigos;
- Em caso de necessidade de rotação de segredo, um script de rotação deve re-hashear os `tombstoneHash` a partir do mapeamento seguro ou os UIDs originais antes de descartar a chave anterior.

---

## 4. Garantia Local-First do Cliente Android

Nenhum procedimento de Disaster Recovery no backend afeta o banco de dados SQLite/Room local dos dispositivos. O cliente continua operando offline normalmente.
