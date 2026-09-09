-- T17.13.1 — A exclusão de conta ganha uma fase durável
--
-- Aditiva e não destrutiva: uma coluna nova em `account_deletion_jobs`, nenhuma tabela criada,
-- nenhuma tabela reconstruída, nenhuma linha apagada.
--
-- ## O problema que ela resolve (§8/§9/§10/§11)
--
-- O ledger de DR (`deletion_tombstones.tsv`) é o que impede uma conta excluída de voltar à vida
-- quando um backup **anterior à exclusão** é restaurado: ele é a única memória da exclusão que
-- sobrevive à troca do arquivo do banco. Até aqui, a falha ao escrevê-lo era engolida por um
-- `catch {}` vazio, e a exclusão respondia `DELETED` mesmo assim.
--
-- Isso é exatamente o contrário do propósito do arquivo. Um disco cheio, um volume montado
-- somente-leitura ou um diretório com permissão errada produziam uma conta apagada do banco, uma
-- pessoa informada de que sua conta foi excluída, e **nenhum** registro anti-ressurreição — a
-- combinação que faz o próximo restore trazer a conta de volta sem que nada acuse o problema.
--
-- ## Por que a fase mora aqui, e não numa fila nova
--
-- §11 pede que o estado pendente sobreviva a um restart, e §11 também pede para não criar uma
-- segunda fila concorrente se `account_deletion_jobs` puder representar as fases. Ela pode: a
-- tabela já é, desde a 0013, exatamente "o que ainda falta terminar nesta exclusão", já tem
-- `attempts`/`last_error`/`next_attempt_at` para backoff, já é varrida pelo reconciliador a cada
-- minuto e já é o que `deletion-status` consulta para responder `DELETION_PENDING`. Faltava só
-- dizer **qual** passo falta.
--
--   LEDGER_PENDING    o purge do banco foi committed; o ledger de DR ainda não foi persistido.
--                     A conta já está inacessível (o tombstone do banco existe), e a exclusão
--                     **não** pode ser declarada `DELETED`.
--
--   FIREBASE_PENDING  o ledger está no disco e sincronizado; falta apagar o usuário no Firebase
--                     Auth. É a fase que a T17.6 já tinha, agora nomeada.
--
-- Um job que termina é **removido** da tabela — não existe fase `DONE`. "Sem job" é o que
-- `getDeletionStatus` já lê como exclusão concluída, e inventar um estado terminal aqui criaria
-- duas respostas para a mesma pergunta.
--
-- ## O backfill é `LEDGER_PENDING`, e é de propósito
--
-- As linhas que já existem foram criadas pelo fluxo antigo, em que a escrita do ledger era
-- best-effort silenciosa: não há como saber, olhando a linha, se o arquivo recebeu aquele hash ou
-- se a escrita falhou sem deixar rastro. `FIREBASE_PENDING` assumiria o caso otimista e perderia
-- para sempre a chance de corrigir o pessimista.
--
-- `LEDGER_PENDING` assume o contrário, e o custo disso é uma linha repetida no TSV — que não
-- custa nada, porque o leitor consome os hashes como conjunto (§13) e reconciliar duas vezes o
-- mesmo hash é a mesma operação. Assumir o caso otimista custaria uma conta ressuscitada.

ALTER TABLE account_deletion_jobs
    ADD COLUMN phase TEXT NOT NULL DEFAULT 'LEDGER_PENDING'
    CHECK (phase IN ('LEDGER_PENDING', 'FIREBASE_PENDING'));

-- O reconciliador varre por `next_attempt_at`; a fase entra no índice para que a varredura
-- continue sendo uma leitura de índice quando as duas fases coexistirem na tabela.
CREATE INDEX idx_account_deletion_jobs_phase ON account_deletion_jobs (phase, next_attempt_at);
