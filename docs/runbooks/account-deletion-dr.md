# Runbook — Reconciliação de Exclusão de Conta e Disaster Recovery

- **Tarefa:** T17.6 (origem) · T17.9 (mídia) · T17.11/T17.12 (Squads e audiências) · **T17.13.1** (durabilidade e comando operacional)
- **Componente:** `AccountDeletionService` · `account_deletion_tombstones` · `account_deletion_jobs` · `deletion_tombstones.tsv`
- **Ambiente:** Backend Node.js / NestJS / SQLite / Docker

> **Mudou na T17.13.1.** A reconciliação passou a ter um **comando executável**
> (`dist/cli/reconcile-account-deletions.js`), o `ops/restore.sh --install` o executa sozinho, e o
> ledger deixou de ser best-effort. Este documento descrevia uma rotina que não existia; as seções
> abaixo descrevem o que o código faz hoje.

---

## 1. Contexto e objetivo

Quando alguém exclui a conta via `DELETE /v1/account`:

```text
lê as chaves de mídia          (antes do purge: depois, as linhas não existem mais)
        │
  BEGIN │ tombstone + job(LEDGER_PENDING) + purge das tabelas account-scoped
 COMMIT │ ← a partir daqui a conta está inacessível, e isso é irreversível
        │
 apaga os arquivos de mídia    (fora da transação)
        │
 persiste o ledger de DR       (append + fsync — falha aqui ⇒ DELETION_PENDING)
        │
 apaga o usuário no Firebase   (falha aqui ⇒ DELETION_PENDING)
        │
 remove o job                  ⇒ DELETED
```

Os três primeiros passos do `BEGIN` são **uma** transação (T17.13.1 §5). Uma falha no meio do purge
faz `ROLLBACK` de tudo: não existe conta bloqueada sobre dados intactos, nem job órfão, nem purge
pela metade.

**Depois do `COMMIT`, os dados nunca voltam** — nem por falha de disco, nem por falha do Firebase.
O que pode acontecer é a exclusão não ser declarada *terminada*: enquanto o ledger obrigatório não
estiver no disco, a resposta é `DELETION_PENDING` e a conta segue bloqueada pelo tombstone do banco.

### Por que o ledger existe

Numa restauração de desastre o **arquivo do banco inteiro** é substituído por uma cópia anterior, e
com ela voltam as contas já excluídas — inclusive a própria tabela `account_deletion_tombstones`,
que na versão restaurada ainda não conhece aquela exclusão.

`deletion_tombstones.tsv` vive **ao lado** do banco (`DELETION_TOMBSTONES_FILE_PATH`, por padrão
`/data/deletion_tombstones.tsv` dentro do container = `/opt/spark/data/deletion_tombstones.tsv` no
host) e não é sobrescrito pelo restore. Ele é a única memória da exclusão que sobrevive à troca.

Formato, uma linha por exclusão (repetições são permitidas — o leitor consome um conjunto):

```tsv
<hmac-sha256 hex de 64 caracteres>\t<epoch em milissegundos>
```

Desde a T17.13.1 ele entra no snapshot do `ops/backup.sh` (o manifesto registra
`deletionLedgerRows`). Sem isso, uma perda total da VPS deixaria o operador sem nada contra o que
reconciliar — e, como a reconciliação falha fechada, toda recuperação viraria um bloqueio permanente.

---

## 2. Procedimento pós-restore

### O caminho normal: `ops/restore.sh --install` já faz tudo

```bash
ops/restore.sh --to /tmp/restauracao --install
```

O script, nesta ordem: restaura o banco e a mídia, valida `integrity_check`/`foreign_key_check`,
instala os dois, **une** o ledger do snapshot ao que houver no disco, roda a reconciliação e só
então declara a restauração completa. Uma falha na reconciliação falha o `--install` inteiro.

A união (e não substituição) do ledger é deliberada: numa restauração em que a máquina sobreviveu,
o arquivo local conhece exclusões que o snapshot não conhece. Concatenar é seguro porque o leitor
consome hashes como conjunto.

### Rodar a reconciliação à mão

Só é necessário quando o banco foi restaurado por fora do `ops/restore.sh`.

```bash
docker run --rm \
  --group-add "$(stat -c %g /opt/spark/data)" \
  -v /opt/spark/data:/data \
  -v /opt/spark/media:/media \
  -e NODE_ENV=production \
  -e DATABASE_PATH=/data/spark.db \
  -e SOCIAL_MEDIA_ROOT=/media \
  -e DELETION_TOMBSTONES_FILE_PATH=/data/deletion_tombstones.tsv \
  --env-file /opt/spark/secrets/backend.env \
  --entrypoint node \
  spark-backend:latest dist/cli/reconcile-account-deletions.js
```

O `--env-file` é o que traz `ACCOUNT_DELETION_HMAC_KEY`. **Ela precisa ser a mesma chave do
servidor**: é ela que liga um hash do ledger a um uid do banco, e rodar com a chave errada encontra
zero correspondências e **reporta sucesso** — o pior desfecho possível.

Saída esperada:

```text
ledger de exclusões: 42 registro(s), 41 conta(s) distinta(s)
contas reconciliadas (purgadas de novo): 3
foreign_key_check e integrity_check ok
```

### Códigos de saída

| Código | Significado | O que fazer |
| --- | --- | --- |
| `0` | reconciliação concluída, integridade validada | seguir com o restore |
| `1` | configuração inválida ou erro inesperado | ler a mensagem; normalmente falta variável de ambiente |
| `2` | **ledger ausente, ilegível ou malformado** | ver abaixo — não prossiga |
| `3` | `foreign_key_check`/`integrity_check` falhou depois do purge | o snapshot restaurado não presta; escolher outro |

### Ledger ausente ou malformado: falha fechada

O comando **não** trata ausência como "nenhuma conta excluída", e recusa uma única linha fora do
formato. As duas leituras produzem o mesmo efeito visível — nada é apagado — e uma delas devolve ao
ar contas que já tinham sido excluídas.

Se o comando sair com `2`:

1. procure o ledger em outro backup (`restic` guarda o arquivo dentro do snapshot, ao lado de
   `spark.db` e `manifest.json`);
2. se ele estiver corrompido no meio, **não edite às cegas**: cada linha é um bloqueio de conta, e
   remover uma reativa aquela conta. Recupere a versão íntegra mais recente;
3. só depois rode a reconciliação de novo.

Não existe opção de bypass, e isso é intencional.

---

## 3. Auditoria e validação

Os caminhos abaixo são os de produção (`docker-compose.prod.yml`).

```bash
# Tombstones no banco × registros no ledger.
# O ledger pode ter MAIS linhas que a tabela: repetições de retry são permitidas (§13).
sqlite3 /opt/spark/data/spark.db "SELECT COUNT(*) FROM account_deletion_tombstones;"
sort -u /opt/spark/data/deletion_tombstones.tsv | wc -l

# Integridade referencial depois da reconciliação.
sqlite3 /opt/spark/data/spark.db "PRAGMA foreign_key_check;"   # esperado: nenhuma linha
sqlite3 /opt/spark/data/spark.db "PRAGMA integrity_check;"     # esperado: ok
```

### 3.1 Exclusões ainda pendentes

`account_deletion_jobs` **não tem coluna `status`** — o que ela tem é `phase`, e a existência da
linha já significa "não terminou". Terminar é sair da tabela.

```bash
sqlite3 -header -column /opt/spark/data/spark.db "
  SELECT id, phase, attempts, last_error, next_attempt_at, created_at
    FROM account_deletion_jobs
   ORDER BY created_at;"
```

| `phase` | O que já aconteceu | O que falta |
| --- | --- | --- |
| `LEDGER_PENDING` | purge committed, conta bloqueada | gravar o ledger de DR |
| `FIREBASE_PENDING` | ledger no disco | apagar o usuário no Firebase Auth |

O `AccountDeletionReconciler` varre a tabela a cada minuto e avança as fases com backoff
exponencial. Uma linha `LEDGER_PENDING` parada por muito tempo aponta problema de **disco**
(volume cheio, montado somente-leitura, permissão errada em `/opt/spark/data`); uma
`FIREBASE_PENDING` parada aponta problema de **credencial ou rede** com o Firebase Admin. `attempts`
e `last_error` dizem qual dos dois.

O estado sobrevive a restart do processo porque é uma linha do SQLite.

### 3.2 A mídia da conta excluída (T17.9)

Uma conta tem conteúdo **fora** do banco: as fotos vivem em `/opt/spark/media`, e o
`ON DELETE CASCADE` do SQLite não alcança o sistema de arquivos.

A reconciliação cobre isso: para cada conta ressuscitada pelo restore cujo HMAC bate com um
tombstone, ela lê as chaves de armazenamento **antes** do purge e apaga os arquivos depois do
commit. Depois do purge as linhas não existem mais, e não haveria como saber quais arquivos remover.

```bash
# Nenhuma linha de mídia da conta excluída
sqlite3 /opt/spark/data/spark.db "SELECT COUNT(*) FROM social_checkin_media;"

# E nenhum arquivo órfão crescendo
find /opt/spark/media -type f | wc -l
```

O que sobrar de arquivo — por falha de I/O no momento da purga — é recolhido pela varredura de
órfãos do `SocialMediaCleaner`, porque a metadata correspondente já não existe.

### 3.3 Squads e interações por audiência (T17.11 / T17.12)

Uma conta pode ser **dona de um Squad**, e as reações e comentários pertencem a uma audiência
(`FRIEND` ou `GROUP(x)`). A reconciliação cobre os dois porque reusa exatamente o mesmo
`purgeAccountData` da exclusão normal — não existe um segundo caminho de limpeza.

| Recurso | Efeito |
| --- | --- |
| Squads de que a conta era dona | removidos, com participações, convites e arestas de compartilhamento |
| Interações `GROUP` de **terceiros** dentro desses Squads | removidas por `ON DELETE CASCADE` de `group_id` |
| Participações da conta em Squads de terceiros | removidas — **o Squad permanece** |
| Compartilhamentos (`social_group_checkin_shares`) da conta | removidos |
| Reações e comentários da conta, nas duas audiências | removidos |
| Desafios criados pela conta | removidos, com participações e convites daquele desafio |

Interação em Squad **não** é read-only desde a T17.12: um membro sem amizade nenhuma reage e
comenta dentro do Squad, e só dentro dele. Ver
[`social-interaction-audience.md`](../architecture/social-interaction-audience.md).

```bash
# Nenhum Squad órfão de um dono já excluído
sqlite3 /opt/spark/data/spark.db "
  SELECT COUNT(*) FROM social_groups g
   WHERE NOT EXISTS (SELECT 1 FROM social_profiles p WHERE p.owner_uid = g.owner_uid);"
# esperado: 0
```

### 3.4 Restore parcial

A enumeração de contas do reconciliador não olha só o perfil: ela varre **toda** coluna de uid
declarada em `src/modules/account-deletion/account-uid-inventory.ts` — 35 colunas, de
`sync_entities` a `social_group_checkin_shares`.

Isso existe porque um restore não é garantidamente consistente: um snapshot copiado com o processo
escrevendo, um `.dump` parcial ou uma cópia sem checkpoint do WAL podem trazer de volta **um** único
rastro de uma conta (um comentário, uma reação, um convite de Squad, um dispositivo de push) sem o
`social_profiles` correspondente. Um inventário incompleto deixaria esse rastro de pé.

Uma tabela nova com coluna de uid não passa despercebida: um teste confronta o inventário com o
schema real do SQLite e reprova até alguém declarar a política daquela coluna.

---

## 4. Rotação do segredo HMAC (`ACCOUNT_DELETION_HMAC_KEY`)

A chave é crítica e **não pode ser rotacionada em silêncio**: os tombstones guardam `HMAC(uid)`, e
trocar a chave faz todos os existentes deixarem de casar — contas excluídas voltam a passar pelo
guard, e a reconciliação de DR deixa de reconhecê-las.

O servidor recusa subir em produção com a chave de desenvolvimento
(`AppConfig.missingRequirements()`), o que impede o pior caso: subir com o default e trocá-lo depois.

Uma rotação real exige re-hashear os `uid_hash` a partir dos uids originais **antes** de descartar a
chave anterior — e os uids originais não existem no servidor por construção. Na prática: escolha a
chave uma vez, guarde-a com o mesmo cuidado do backup, e não a troque.

O CI usa uma chave sintética própria (`ci-only-account-deletion-hmac-not-for-production`), versionada
justamente por não servir para nada: o job `docker` roda um container **sem** chave e exige que o
processo morra, para que a exigência de produção não possa ser enfraquecida sem o workflow acusar.

---

## 5. Garantia local-first do cliente Android

Nenhum procedimento de Disaster Recovery no backend toca o Room do aparelho. O cliente continua
operando offline normalmente, e a exclusão de conta no servidor não apaga o banco local.
