-- T17.9 — Denúncia de conteúdo (§101–§107)
--
-- A T17.6 criou `social_reports` com um alvo só: uma **conta**. Agora existe conteúdo gerado por
-- usuário — legenda, foto e comentário —, e denunciar "a pessoa" quando o problema é um post
-- específico perde justamente a informação que a revisão precisa.
--
-- Aditiva e não destrutiva (§163/§164). Duas colunas novas, com default que descreve exatamente o
-- que as linhas existentes já são: toda denúncia da T17.6 é uma denúncia de conta. O `UPDATE` de
-- backfill preenche `target_id` com o `reported_uid` que já estava lá — ele não inventa dado nem
-- apaga nenhum.
--
-- **Não existe** `REPORT MEDIA` (§102): a foto pertence ao check-in, e denunciá-la é denunciar o
-- check-in. Um alvo a mais criaria duas linhas para o mesmo problema e duas filas de revisão.

ALTER TABLE social_reports
    ADD COLUMN target_type TEXT NOT NULL DEFAULT 'USER'
    CHECK (target_type IN ('USER', 'CHECKIN', 'COMMENT'));

-- Nulável só porque o SQLite não aceita `NOT NULL` sem default constante num `ALTER TABLE`. O
-- backfill logo abaixo o preenche para toda linha existente, e o serviço sempre o escreve.
ALTER TABLE social_reports
    ADD COLUMN target_id TEXT;

-- Backfill: uma denúncia de conta tem como alvo a própria conta. `reported_uid` continua sendo o
-- **autor real resolvido pelo servidor** (§103) em todos os tipos — para `CHECKIN` e `COMMENT`
-- ele passa a ser o autor do conteúdo, e nunca um `reportedUid` vindo do cliente.
UPDATE social_reports SET target_id = reported_uid WHERE target_id IS NULL;

-- A anti-duplicata da T17.6 era `(reporter, reported, reason)`. Com alvo, ela precisa ser por
-- **alvo**: denunciar dois comentários diferentes da mesma pessoa pelo mesmo motivo são duas
-- denúncias legítimas, e colapsá-las esconderia a segunda da revisão.
CREATE INDEX idx_social_reports_target ON social_reports (reporter_uid, target_type, target_id, created_at);
