# Agenda semanal do treino (T19.8 — Workout Scheduling V2)

- **Tarefa:** T19.8. **Estado:** implementada em 2026-09-16.
- **Escopo:** os dias da semana de um `WorkoutTemplate`; o formulário de criar/editar treino; o
  que o Hoje faz com a agenda; como ela atravessa sync, backup/restore e compartilhamento de
  programa.
- **Fora de escopo:** calendário, data específica, recorrência, horário, lembrete, "perdeu
  segunda → terça", criação automática de sessão.

## 1. O modelo

```text
WorkoutTemplate  (workout_templates)          "qual é este treino"
  └── 0..N  WorkoutTemplateSchedule           "quando ele costuma acontecer"
            (workout_template_schedules: templateId, dayOfWeek)
WorkoutSession   (workout_sessions)           "o que realmente aconteceu"
```

| Linhas de agenda | Significado |
| --- | --- |
| `[]` | treino **sem dia fixo** — estado válido, não erro, não "domingo", não "desconhecido" |
| `[MONDAY]` | um dia: o comportamento anterior à T19.8 |
| `[MONDAY, THURSDAY]` | o **mesmo** treino, duas vezes na semana |

- **Um treino, uma entidade.** "Treino A" com segunda e quinta continua sendo um
  `WorkoutTemplateEntity` (um `id`, um `syncId`). Não existe "Treino A segunda" e "Treino A quinta".
- **Valor canônico.** A coluna guarda o nome de `java.time.DayOfWeek` (`MONDAY`..`SUNDAY`). Rótulo
  de tela (`Seg`, `Qui`) é apresentação (`WeekdaySchedule.shortLabel`) e nunca identidade. Nenhuma
  string concatenada, nenhum JSON, nenhum bitmask.
- **Dia repetido é impossível.** `PRIMARY KEY(templateId, dayOfWeek)`; o DAO insere com `IGNORE`
  e o repositório normaliza antes (`WeekdaySchedule.normalize`: sem repetição, ordem da semana).
- **Cascade.** `FOREIGN KEY(templateId) … ON DELETE CASCADE`: apagar o treino apaga a agenda.
- **A agenda não é ordem.** `orderInProgram` continua sendo a ordem do programa; a agenda nunca o
  altera nem o substitui.
- **A agenda não é histórico.** `workout_sessions.templateId` continua apontando para o mesmo
  treino; mudar os dias não reescreve nem reinterpreta uma sessão concluída.

Componentes: `WorkoutTemplateScheduleEntity`, `WorkoutTemplateWithSchedule` (`@Relation`, com
`scheduledDays` derivado), `WorkoutDao.replaceSchedulesForTemplate` (apaga + insere, em
transação — o estado final, nunca um delta), `WeekdaySchedule` (domínio puro:
`normalize`/`names`/`parseCanonical`/`fromLegacyLabel`/`formatShort`),
`WorkoutRepository.addTemplate` / `updateTemplateHeader` / `getTemplatesWithScheduleForProgram`.

## 2. Migração Room 40 → 41

`workout_templates.dayOfWeek` (um dia, e guardando o **rótulo** do chip: `"Seg"`) deixa de
existir. `MIGRATION_40_41`:

1. cria `workout_template_schedules`;
2. converte cada `dayOfWeek` reconhecível em **uma** linha canônica (`WeekdaySchedule.fromLegacyLabel`
   entende `Seg`..`Dom`, `Segunda`/`segunda-feira`, `MONDAY`, `Mon`, com ou sem acento);
   `NULL`, vazio e texto que não descreve um dia viram **zero** linhas;
3. recria `workout_templates` sem a coluna (SQLite do Android não garante `DROP COLUMN`),
   copiando as linhas com o **mesmo `id`** — `workout_template_exercises`, `workout_sessions` e a
   agenda recém-criada continuam apontando para o mesmo treino. O Room roda migrações com
   `foreign_keys` desligado (só liga em `onOpen`), então o `DROP` não cascateia; o teste
   `AppDatabaseMigration40To41Test` prova que exercícios, sessões, `syncId`, `orderInProgram` e o
   `AUTOINCREMENT` sobrevivem.

## 3. Formulário (criar e editar)

`ProgramDetailsScreen` → `TemplateFormDialog`; estado em `ProgramDetailsViewModel.templateForm`
(`TemplateFormState`, temporário — vira dado só no salvar).

```text
Nome *            obrigatório   (workout_templates.name NOT NULL)
Sigla             opcional      (em branco → null; nunca "")
Dias da semana    opcional      [Seg][Ter][Qua][Qui][Sex][Sáb][Dom]  seleção múltipla
```

- A regra é uma só, `WorkoutTemplateFields` (repositório) — a tela marca com `*` exatamente o que
  o repositório recusaria, e o erro aparece **depois** de tentar salvar sem nome.
- **Não existe "Nenhum".** Nenhum chip ligado é "sem dia fixo", e a legenda diz isso. Um item
  fictício concorreria com os dias reais e criaria um valor artificial.
- Editar (`Opções do treino → Editar treino`) abre o mesmo diálogo com o que está persistido; salvar
  passa por `updateTemplateHeader`, que não toca em exercícios, `orderInProgram` nem `syncId`, e
  não registra mutação de sync quando nada mudou.

## 4. Hoje

`TodayTemplateSelector` (puro):

1. treino **agendado para hoje** vence a sequência — o primeiro na ordem do programa, se houver
   mais de um;
2. sem treino agendado para hoje, vale a rotação de sempre: o próximo depois do último concluído,
   ou o primeiro do programa;
3. a escolha manual ("Trocar") vence as duas — muda o treino efetivo, não a sugestão.

A tela diz por quê ("TREINO DE HOJE" / "PRÓXIMO DA SEQUÊNCIA" / "TREINO SELECIONADO") e mostra
todos os dias do treino (`Seg · Qui`). `todayFlow` reavalia a data na virada da meia-noite (mesmo
padrão do `weekStartFlow`). Nada além disso: sem "perdeu segunda, empurra para terça".

## 5. Fronteiras

### Sync e backup — `WORKOUT_TEMPLATE` v2

```text
v1  { ..., "dayOfWeek": "Seg" | null }            um dia, rótulo
v2  { ..., "scheduledDays": ["MONDAY","THURSDAY"] } 0..N dias, canônicos, sem repetição, ordem da semana
```

- O app **escreve** só v2 (`WorkoutTemplateSyncDto.SCHEMA_VERSION = 2`,
  `BackupEntityType.WORKOUT_TEMPLATE.schemaVersion = 2`).
- O app **lê** v1 e v2 (`WorkoutTemplateSyncDto.READABLE_SCHEMA_VERSIONS`,
  `SyncProtocol.readableEntitySchemaVersions`, `BackupEntityType.readableSchemaVersions`), por uma
  fronteira só: `WorkoutTemplatePayloadCompat` — v1 é reconhecida pela chave `dayOfWeek`,
  convertida e decodificada pelo mesmo serializer estrito da v2. Um backup guardado antes da T19.8
  continua restaurável; um aparelho ainda na v1 continua sendo lido.
- O servidor aceita `[1, 2]` (`backup-entity.registry.ts`: `legacySchemas`/`schemaFor`); cada
  versão tem uma forma só — `dayOfWeek` na v2, `scheduledDays` na v1, rótulo, repetição ou nome
  fora do enum são `INVALID_PAYLOAD`.
- Apply remoto e restore **substituem** a agenda inteira (`replaceSchedulesForTemplate`), como os
  exercícios: o mesmo payload aplicado duas vezes produz as mesmas linhas.
- Um cliente anterior à T19.8 que receba uma mudança v2 **pausa** o sync naquele ponto e pede
  atualização — o comportamento de protocolo que sempre existiu para versão desconhecida.
- Fixtures do contrato: `backup-v1-complete.json` (treino v2) e `backup-v1-legacy-template.json`
  (treino v1 com `"Seg"`), com hash fixado nos dois lados.

### Compartilhamento de programa (T19.3)

`SharedProgramTemplateSnapshot.scheduledDays: List<DayOfWeek>` no domínio. No fio
(`SharedProgramTemplateSnapshotDto`): o app envia `scheduledDays` (lista, possivelmente vazia) e
nunca `dayOfWeek`; lê os dois (`dayOfWeek` → `fromLegacyLabel`, para ofertas criadas antes ou por
um app anterior). O servidor aceita **uma** das duas formas por treino, valida `scheduledDays`
(enum, ≤ 7, sem repetição) e guarda o snapshot verbatim. A cópia importada nasce com a mesma agenda
no mesmo treino; o retry não a duplica porque a importação inteira é idempotente pelo recibo.

O treino avulso (T17.7, `SharedWorkoutSnapshot`) **não** carrega dia — nunca carregou, e a T19.8
não o acrescenta.

### Importação manual de JSON (`ProgramImporter`)

Lê `scheduledDays` (nomes canônicos) ou, num manifesto antigo, `dayOfWeek`; o que não descreve um
dia vira "sem dia".

## 6. Testes

`WeekdayScheduleTest`, `WorkoutTemplatePayloadCompatTest`, `AppDatabaseMigration40To41Test`,
`WorkoutRepositoryScheduleTest`, `SyncScheduleTest`, `RestoreRoundTripTest`/`RestoreValidationTest`
(agenda e fixture legada), `WorkoutShareSnapshotBuilderTest`/`WorkoutShareImporterTest`/
`WorkoutShareScheduleDtoTest`, `ProgramDetailsViewModelFormTest`, `ProgramDetailsScreenFormTest`,
`TodayTemplateSelectorTest`; no backend, `sync-push.spec.ts` (v1/v2), `backup-contract.spec.ts`
(fixture legada), `program-share.spec.ts`, `social-privacy-sweep.spec.ts`.

Armadilha de teste: com `qualifiers = "w411dp-h891dp"`, um `OutlinedTextField` dentro de um
`AlertDialog` do Material 3 nunca fica ocioso no Robolectric (`waitForIdle` estoura em 60 s). O
teste da tela do formulário roda sem esse qualifier.
