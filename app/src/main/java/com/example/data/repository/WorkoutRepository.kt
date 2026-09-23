package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import com.example.domain.workout.template.WeekdaySchedule
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkExerciseRemoteDataSource
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncIds
import com.example.data.sync.SyncMutationCoordinator
import com.example.data.sync.SyncMutationScope

/**
 * @param syncMutations a fronteira transacional entre a escrita de domínio e a Outbox (T16.3).
 *
 * O padrão é [SyncMutationCoordinator.disabled]: a alteração acontece e nada é registrado para a
 * nuvem. É o comportamento do Spark hoje — criar e editar treino continua sendo operação local,
 * sem conta, sem backend e sem rede.
 */
class WorkoutRepository(
    val dao: WorkoutDao,
    private val remoteDataSource: ExerciseRemoteDataSource = NetworkExerciseRemoteDataSource(),
    val settingsManager: com.example.data.datastore.SettingsManager? = null,
    private val syncMutations: SyncMutationCoordinator = SyncMutationCoordinator.disabled()
) {
    val activeExercises: Flow<List<ExerciseEntity>> = dao.getActiveExercises()
    
    val activeResolvedExercises: Flow<List<com.example.domain.model.ResolvedExercise>> = 
        if (settingsManager != null) {
            kotlinx.coroutines.flow.combine(
                dao.getActiveExercises(), 
                dao.getAllOverridesFlow(),
                settingsManager.showGifsFlow
            ) { exercises, overrides, showGifs ->
                com.example.domain.engine.ExerciseResolver.resolveAll(exercises, overrides.associateBy { it.exerciseId }, showGifs)
            }
        } else {
            kotlinx.coroutines.flow.combine(dao.getActiveExercises(), dao.getAllOverridesFlow()) { exercises, overrides ->
                com.example.domain.engine.ExerciseResolver.resolveAll(exercises, overrides.associateBy { it.exerciseId })
            }
        }
    val allPrograms: Flow<List<WorkoutProgramEntity>> = dao.getAllPrograms()
    val currentProgram: Flow<WorkoutProgramEntity?> = dao.getCurrentProgram()

    /**
     * Exercício criado pelo usuário — dado pessoal, e por isso nasce com identidade global.
     *
     * O `syncId` é explícito aqui porque a coluna é anulável: o catálogo canônico continua sem
     * `syncId`, com `canonicalId` como identidade.
     *
     * O único campo obrigatório é o nome — a mesma regra que a entidade impõe (`name` não é
     * anulável; todo o resto é). Músculo, equipamento e descrição em branco viram `null`, e não
     * `""`: o resolver visual e os filtros do catálogo tratam os dois como "não informado", e o
     * agregado `CUSTOM_EXERCISE` já viaja com esses campos anuláveis (T16.3).
     */
    suspend fun addExercise(
        name: String,
        muscle: String? = null,
        equipment: String? = null,
        description: String? = null
    ): Long {
        val exercise = ExerciseEntity(
            name = CustomExerciseFields.requireName(name),
            primaryMuscle = CustomExerciseFields.optional(muscle),
            equipment = CustomExerciseFields.optional(equipment),
            description = CustomExerciseFields.optional(description),
            isUserCreated = true,
            syncId = SyncIds.random()
        )
        return syncMutations.mutate {
            val id = dao.insertExercise(exercise)
            exercise.syncId?.let { upsert(SyncEntityType.CUSTOM_EXERCISE, it) }
            id
        }
    }

    /**
     * Edita os campos base de um exercício **criado pelo usuário** (T19.7C).
     *
     * Um exercício canônico não passa por aqui: o catálogo é do manifesto versionado, e o que o
     * usuário pode mudar nele é o override (`exercise_user_overrides`), não a linha. Um `CUSTOM`
     * é dele — nome, músculo, equipamento e descrição são a própria linha, e a edição registra
     * uma mutação do agregado `CUSTOM_EXERCISE` como qualquer outra.
     *
     * Se existia um override sobre este `CUSTOM` (o caminho antigo de "personalizar" servia para
     * qualquer exercício), `displayName` e `notes` dele são limpos: o nome e a descrição agora
     * vivem na linha, e um override sobreposto faria a edição "não pegar". Foto e descanso
     * padrão do override continuam.
     *
     * @return `false` quando o exercício não é `CUSTOM`; nada é escrito e nada é registrado.
     */
    suspend fun updateCustomExercise(
        exerciseId: Long,
        name: String,
        muscle: String?,
        equipment: String?,
        description: String?
    ): Boolean {
        val validName = CustomExerciseFields.requireName(name)
        return syncMutations.mutate {
            val existing = dao.getExerciseById(exerciseId) ?: return@mutate false
            if (!existing.isUserCreated || existing.syncId == null) return@mutate false
            dao.updateExercise(
                existing.copy(
                    name = validName,
                    primaryMuscle = CustomExerciseFields.optional(muscle),
                    equipment = CustomExerciseFields.optional(equipment),
                    description = CustomExerciseFields.optional(description)
                )
            )
            dao.getOverrideForExercise(exerciseId)?.let { override ->
                if (override.displayName != null || override.notes != null) {
                    dao.insertOrUpdateOverride(
                        override.copy(displayName = null, notes = null, updatedAt = System.currentTimeMillis())
                    )
                }
            }
            upsert(SyncEntityType.CUSTOM_EXERCISE, existing.syncId)
            true
        }
    }

    /**
     * Exclui um exercício criado pelo usuário respeitando quem ainda aponta para ele (T19.7C).
     *
     * - Canônico: recusado ([CustomExerciseDeleteResult.NotCustom]). A regra de domínio vem antes
     *   da mutação, então uma tentativa recusada não registra intenção de sync nenhuma.
     * - Usado em algum treino: recusado ([CustomExerciseDeleteResult.UsedByTemplates]). É o mesmo
     *   guard que o sync já aplica a uma exclusão vinda da nuvem
     *   (`countTemplateReferencesToExercise`, T16.7); a chave estrangeira é `RESTRICT` e o banco
     *   recusaria de qualquer jeito — aqui a recusa tem motivo legível.
     * - Com histórico: **arquivado** ([CustomExerciseDeleteResult.Archived]) — `active = false`,
     *   que some do catálogo e do seletor, mas mantém a linha para o histórico, os recordes
     *   (`personal_records` cascateia num delete) e as sessões antigas. `active` já faz parte do
     *   agregado `CUSTOM_EXERCISE`, então o outro aparelho arquiva também.
     * - Sem referência nenhuma: apagado de verdade ([CustomExerciseDeleteResult.Deleted]), com
     *   tombstone para a nuvem.
     */
    suspend fun deleteExercise(exercise: ExerciseEntity): CustomExerciseDeleteResult {
        if (!exercise.isUserCreated) return CustomExerciseDeleteResult.NotCustom
        return syncMutations.mutate {
            val current = dao.getExerciseById(exercise.id)
                ?: return@mutate CustomExerciseDeleteResult.Deleted
            if (!current.isUserCreated) return@mutate CustomExerciseDeleteResult.NotCustom
            val templateRefs = dao.countTemplateReferencesToExercise(current.id)
            if (templateRefs > 0) {
                return@mutate CustomExerciseDeleteResult.UsedByTemplates(templateRefs)
            }
            if (dao.countSessionReferencesToExercise(current.id) > 0) {
                dao.updateExercise(current.copy(active = false))
                current.syncId?.let { upsert(SyncEntityType.CUSTOM_EXERCISE, it) }
                return@mutate CustomExerciseDeleteResult.Archived
            }
            dao.deleteExercise(current)
            current.syncId?.let { delete(SyncEntityType.CUSTOM_EXERCISE, it) }
            CustomExerciseDeleteResult.Deleted
        }
    }

    /**
     * Cria um programa e, se ele for o primeiro, o deixa como atual — numa transação só.
     *
     * A checagem era `dao.getCurrentProgram() == null`, que compara um `Flow` com `null`: sempre
     * falso, e por isso o primeiro programa de uma instalação nova nunca virava o atual. A leitura
     * agora é suspensa ([WorkoutDao.getCurrentProgramSync]) e acontece **dentro** do mesmo
     * `mutate`: lá fora, dois caminhos criando programa ao mesmo tempo poderiam marcar os dois.
     */
    suspend fun addProgram(name: String) {
        val program = WorkoutProgramEntity(name = name)
        syncMutations.mutate {
            val id = dao.insertProgram(program)
            if (dao.getCurrentProgramSync() == null) {
                dao.setCurrentProgram(id)
            }
            // Uma mutação só, registrada depois de o programa estar no estado final: o payload é
            // montado do Room na hora do push, e `isCurrent` viaja nele.
            upsert(SyncEntityType.WORKOUT_PROGRAM, program.syncId)
        }
    }

    /**
     * Troca o programa atual — e conta isso à nuvem.
     *
     * `isCurrent` viaja no payload do programa (`SyncAggregateSnapshotBuilder`), então trocar sem
     * registrar mutação fazia o outro aparelho continuar afirmando o programa antigo e reativá-lo
     * no ciclo seguinte. São **dois** agregados alterados: o que perdeu a marca e o que a ganhou.
     *
     * As duas escritas também passaram a ser uma transação: entre o `clearCurrentProgram` e o
     * `setCurrentProgram` havia um instante — durável, se o processo morresse ali — em que nenhum
     * programa era o atual, e a Home não tem o que mostrar nesse estado.
     */
    suspend fun setCurrentProgram(id: Long) {
        syncMutations.mutate {
            val previous = dao.getCurrentProgramSync()
            if (previous?.id == id) return@mutate

            dao.clearCurrentProgram()
            dao.setCurrentProgram(id)

            previous?.let { upsert(SyncEntityType.WORKOUT_PROGRAM, it.syncId) }
            upsert(SyncEntityType.WORKOUT_PROGRAM) { dao.getProgramById(id)?.syncId }
        }
    }

    fun getTemplatesForProgram(programId: Long): Flow<List<WorkoutTemplateEntity>> {
        return dao.getTemplatesForProgram(programId)
    }

    /** Os treinos do programa **com** os dias da semana de cada um (T19.8), na ordem do programa. */
    fun getTemplatesWithScheduleForProgram(programId: Long): Flow<List<WorkoutTemplateWithSchedule>> =
        dao.getTemplatesWithScheduleForProgram(programId)

    fun getTemplateWithSchedule(templateId: Long): Flow<WorkoutTemplateWithSchedule?> =
        dao.getTemplateWithScheduleFlow(templateId)

    /** Os dias em que o treino acontece, na ordem da semana; vazio é "sem dia fixo". */
    suspend fun getTemplateScheduledDays(templateId: Long): List<DayOfWeek> =
        WeekdaySchedule.normalize(
            dao.getSchedulesForTemplate(templateId).mapNotNull { row ->
                DayOfWeek.entries.firstOrNull { it.name == row.dayOfWeek }
            }
        )

    /**
     * Cria um treino com a agenda semanal dele, numa transação só (T19.8).
     *
     * A validação é a mesma que a tela mostra ([WorkoutTemplateFields]): só o nome é obrigatório;
     * a sigla em branco vira `null` (a entidade e todos os contratos já a tratam como opcional).
     * [scheduledDays] vazio é um treino sem dia fixo — estado válido, e não erro. Dia repetido é
     * impossível por construção: a agenda é normalizada aqui e a chave primária composta da tabela
     * recusaria de qualquer forma.
     *
     * Devolve o id gerado pelo Room, para quem precisa continuar montando o treino recém-criado.
     */
    suspend fun addTemplate(
        programId: Long,
        name: String,
        shortId: String?,
        order: Int,
        scheduledDays: Collection<DayOfWeek> = emptyList()
    ): Long {
        val template = WorkoutTemplateEntity(
            programId = programId,
            name = WorkoutTemplateFields.requireName(name),
            shortIdentifier = WorkoutTemplateFields.optionalShortIdentifier(shortId),
            orderInProgram = order
        )
        val days = WeekdaySchedule.names(scheduledDays)
        return syncMutations.mutate {
            val id = dao.insertTemplate(template)
            dao.replaceSchedulesForTemplate(id, days)
            upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
            id
        }
    }

    /**
     * Edita o cabeçalho do treino — nome, sigla e dias da semana — sem tocar em exercícios,
     * `orderInProgram` nem `syncId` (T19.8).
     *
     * É o **mesmo** template em todos os dias: mudar a agenda de `[MONDAY, THURSDAY]` para
     * `[TUESDAY, FRIDAY]` substitui as linhas de agenda dessa raiz, e o histórico de sessões que
     * a referenciam por `templateId` não é reinterpretado. Nada mudou → nada é escrito e nenhuma
     * mutação de sync é registrada. Uma mutação só quando muda: o agregado é o treino inteiro.
     */
    suspend fun updateTemplateHeader(
        templateId: Long,
        name: String,
        shortId: String?,
        scheduledDays: Collection<DayOfWeek>
    ) {
        val validName = WorkoutTemplateFields.requireName(name)
        val validShortId = WorkoutTemplateFields.optionalShortIdentifier(shortId)
        val days = WeekdaySchedule.names(scheduledDays)
        syncMutations.mutate {
            val existing = dao.getTemplateById(templateId) ?: return@mutate
            val currentDays = dao.getSchedulesForTemplate(templateId).map { it.dayOfWeek }.sorted()
            val headerChanged = existing.name != validName || existing.shortIdentifier != validShortId
            val scheduleChanged = currentDays != days.sorted()
            if (!headerChanged && !scheduleChanged) return@mutate

            if (headerChanged) {
                // `copy` sobre a linha lida: `syncId`, `programId` e `orderInProgram` ficam como estão.
                dao.updateTemplate(existing.copy(name = validName, shortIdentifier = validShortId))
            }
            if (scheduleChanged) {
                dao.replaceSchedulesForTemplate(templateId, days)
            }
            upsert(SyncEntityType.WORKOUT_TEMPLATE, existing.syncId)
        }
    }

    /**
     * Cria um treino **inteiro** — cabeçalho, exercícios e o registro de quem o pediu — numa
     * transação só.
     *
     * Existe para a importação de treino compartilhado (T17.7), que fazia `addTemplate`, N
     * `addTemplateExercise` e a gravação do recibo como operações independentes. Uma interrupção
     * no meio deixava um treino pela metade **sem** recibo — e, como o recibo é a idempotência da
     * importação, reabrir a oferta criava um segundo treino incompleto ao lado do primeiro.
     *
     * [andThen] roda dentro da mesma transação, com o `localId` já atribuído: é onde o recibo
     * entra. Lançar dali desfaz o treino junto, que é exatamente o ponto.
     *
     * Uma mutação de sync só, e do **treino**: os exercícios dele são filhos do agregado e não têm
     * identidade global própria (T16.3).
     */
    suspend fun addTemplateWithExercises(
        template: WorkoutTemplateEntity,
        exercises: List<WorkoutTemplateExerciseEntity>,
        scheduledDays: Collection<DayOfWeek> = emptyList(),
        /**
         * Exercícios CUSTOM que nascem **nesta mesma transação** (T19.H2), com as posições que os
         * usam. É o caminho da importação de uma oferta que trouxe exercícios criados pelo
         * remetente: ou o treino, os exercícios e o recibo entram juntos, ou nada entra — e um
         * retry depois de um crash não cria uma segunda cópia de nada.
         */
        customExercises: List<NewCustomExercise> = emptyList(),
        andThen: suspend (templateId: Long) -> Unit = {}
    ): Long = syncMutations.mutate {
        val templateId = dao.insertTemplate(template)
        exercises.forEach { dao.insertTemplateExercise(it.copy(templateId = templateId)) }
        insertCustomExercises(customExercises, templateId, mutableMapOf())
        dao.replaceSchedulesForTemplate(templateId, WeekdaySchedule.names(scheduledDays))
        andThen(templateId)
        upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
        templateId
    }

    /**
     * Cria os exercícios CUSTOM de uma importação e as posições de treino que apontam para eles.
     *
     * [createdRefs] é o que faz o **mesmo** CUSTOM usado em vários treinos do mesmo programa nascer
     * uma vez só: a chave escopada à oferta (`custom-1`) já criada é reaproveitada, e as posições
     * seguintes recebem o `localId` do exercício que já existe.
     *
     * A identidade do exercício criado é inteiramente deste aparelho: `localId` do Room e `syncId`
     * gerado aqui. O do remetente nunca chegou — o snapshot não o carrega.
     */
    private suspend fun SyncMutationScope.insertCustomExercises(
        customExercises: List<NewCustomExercise>,
        templateId: Long,
        createdRefs: MutableMap<String, Long>
    ) {
        customExercises.forEach { custom ->
            val exerciseId = createdRefs[custom.ref] ?: run {
                val id = dao.insertExercise(custom.exercise)
                createdRefs[custom.ref] = id
                custom.exercise.syncId?.let { upsert(SyncEntityType.CUSTOM_EXERCISE, it) }
                id
            }
            custom.positions.forEach { position ->
                dao.insertTemplateExercise(
                    position.copy(templateId = templateId, exerciseId = exerciseId)
                )
            }
        }
    }

    /**
     * Cria um programa **inteiro** — cabeçalho, treinos, exercícios e o registro de quem o pediu —
     * numa transação só (T19.3).
     *
     * É o caminho da importação de programa compartilhado: ou o programa completo entra, ou nada
     * entra. Uma interrupção no meio faz rollback de tudo, inclusive do recibo que [andThen]
     * grava — e é o recibo que impede a oferta de ser importada duas vezes.
     *
     * O programa nasce com `isCurrent = false`, sempre: receber um programa não troca o programa
     * atual de ninguém, nem quando não existe nenhum. Quem decide qual é o atual é o usuário, pela
     * mesma tela de sempre ([setCurrentProgram]).
     *
     * Identidade: o `syncId` do programa e o de cada treino são os que as entidades trazem —
     * gerados aqui, no aparelho de quem recebe, nunca os do remetente. As mutações de sync são
     * registradas na ordem em que o outro aparelho precisa aplicá-las: o programa antes dos
     * treinos que o referenciam.
     */
    suspend fun addProgramWithTemplates(
        program: WorkoutProgramEntity,
        templates: List<NewTemplate>,
        andThen: suspend (programId: Long) -> Unit = {}
    ): Long = syncMutations.mutate {
        val programId = dao.insertProgram(program.copy(isCurrent = false))
        // As chaves CUSTOM valem para a oferta **inteira**: o mesmo exercício personalizado usado
        // em três treinos do programa nasce uma vez e é referenciado três vezes (T19.H2 §15).
        val createdRefs = mutableMapOf<String, Long>()
        val templateSyncIds = templates.map { newTemplate ->
            val (template, exercises, scheduledDays) = newTemplate
            val templateId = dao.insertTemplate(template.copy(programId = programId))
            exercises.forEach { dao.insertTemplateExercise(it.copy(templateId = templateId)) }
            insertCustomExercises(newTemplate.customExercises, templateId, createdRefs)
            dao.replaceSchedulesForTemplate(templateId, WeekdaySchedule.names(scheduledDays))
            template.syncId
        }
        andThen(programId)
        upsert(SyncEntityType.WORKOUT_PROGRAM, program.syncId)
        templateSyncIds.forEach { upsert(SyncEntityType.WORKOUT_TEMPLATE, it) }
        programId
    }

    /** O programa pela linha do Room. */
    suspend fun getProgram(programId: Long): WorkoutProgramEntity? = dao.getProgramById(programId)

    /**
     * O programa que deve receber um treino novo: o atual, ou o primeiro existente.
     *
     * Mesma escolha que a tela de treinos faz ao criar um template manualmente; `null` significa
     * que ainda não existe programa nenhum.
     */
    suspend fun getProgramForNewTemplate(): WorkoutProgramEntity? {
        val programs = dao.getAllProgramsSync()
        return programs.firstOrNull { it.isCurrent } ?: programs.firstOrNull()
    }

    suspend fun deleteTemplate(template: WorkoutTemplateEntity) {
        syncMutations.mutate {
            dao.deleteTemplate(template)
            delete(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
        }
    }

    suspend fun deleteProgram(program: WorkoutProgramEntity) {
        syncMutations.mutate {
            dao.deleteProgram(program)
            delete(SyncEntityType.WORKOUT_PROGRAM, program.syncId)
        }
    }

    suspend fun getLastCompletedSession() = dao.getLastCompletedSession()

    fun getWeeklyCompletedSessionsCount(startOfWeek: Long) = dao.getWeeklyCompletedSessionsCount(startOfWeek)

    /** Total de treinos concluídos. Apenas sessões `COMPLETED` entram na contagem. */
    fun getCompletedSessionsCountFlow() = dao.getCompletedSessionsCountFlow()

    /** Total de recordes pessoais persistidos. */
    fun getPersonalRecordsCountFlow() = dao.getPersonalRecordsCountFlow()

    fun getTemplateExercises(templateId: Long) = dao.getTemplateExercisesWithDetailsFlow(templateId)
    val allOverridesFlow = dao.getAllOverridesFlow()

    suspend fun getTemplate(templateId: Long) = dao.getTemplateById(templateId)

    suspend fun addExerciseToTemplate(templateId: Long, exerciseId: Long, sortOrder: Int) {
        syncMutations.mutate {
            dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = exerciseId, sortOrder = sortOrder))
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateId) }
        }
    }

    /**
     * Insere um exercício de template já com séries, repetições, descanso e carga definidos.
     *
     * É o mesmo insert de [addExerciseToTemplate] seguido de [updateTemplateExerciseFull] que o
     * editor faz em dois passos, em uma escrita só — útil para quem já chega com os valores
     * prontos.
     */
    suspend fun addTemplateExercise(templateExercise: WorkoutTemplateExerciseEntity) {
        syncMutations.mutate {
            dao.insertTemplateExercise(templateExercise)
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateExercise.templateId) }
        }
    }

    /** Exercício do catálogo pelo id canônico. A identidade nunca é o nome. */
    suspend fun getExerciseByCanonicalId(canonicalId: String): ExerciseEntity? =
        dao.getExerciseByCanonicalId(canonicalId)

    /** Exercício do catálogo pela linha do Room. */
    suspend fun getExerciseByRowId(rowId: Long): ExerciseEntity? = dao.getExerciseById(rowId)

    suspend fun updateTemplateExerciseFull(templateExercise: WorkoutTemplateExerciseEntity) {
        syncMutations.mutate {
            dao.updateTemplateExerciseFull(templateExercise)
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateExercise.templateId) }
        }
    }

    /**
     * Lote de atualizações do treino: todas entram juntas ou nenhuma entra.
     *
     * É o caminho do reordenar (arrastar e soltar) e de qualquer edição em massa. Ordem é dado de
     * domínio persistido, então mudar a ordem **é** mudar o agregado — e dezenas de linhas
     * alteradas produzem **uma** mutação do treino, não uma por linha.
     */
    suspend fun updateTemplateExercises(items: List<WorkoutTemplateExerciseEntity>) {
        if (items.isEmpty()) return
        syncMutations.mutate {
            dao.updateTemplateExercisesTransactionally(items)
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(items.first().templateId) }
        }
    }

    /** Os exercícios do treino como estão persistidos agora, fora de qualquer Flow. */
    suspend fun getTemplateExercisesSync(templateId: Long) =
        dao.getTemplateExercisesWithDetails(templateId)

    suspend fun removeExerciseFromTemplate(templateExercise: WorkoutTemplateExerciseEntity) {
        syncMutations.mutate {
            dao.deleteTemplateExercise(templateExercise)
            // O exercício some do treino, mas quem mudou foi o treino. Um `DELETE` de agregado
            // aqui apagaria o template inteiro do outro lado.
            upsert(SyncEntityType.WORKOUT_TEMPLATE) { dao.getTemplateSyncId(templateExercise.templateId) }
        }
    }
}

/** O que aconteceu com um pedido de exclusão de exercício `CUSTOM` — ver [WorkoutRepository.deleteExercise]. */
sealed class CustomExerciseDeleteResult {
    /** Não era `CUSTOM`; nada mudou. */
    data object NotCustom : CustomExerciseDeleteResult()

    /** Ainda aparece em [count] linhas de treino; nada mudou. */
    data class UsedByTemplates(val count: Int) : CustomExerciseDeleteResult()

    /** Tem histórico: saiu do catálogo (`active = false`), a linha ficou. */
    data object Archived : CustomExerciseDeleteResult()

    /** Apagado. */
    data object Deleted : CustomExerciseDeleteResult()
}

/**
 * A validação de campos de um exercício `CUSTOM` — a mesma para criar e editar, e a mesma que a
 * UI usa para habilitar o botão: obrigatório é só o nome.
 */
/**
 * Um treino a ser criado junto com um programa ([WorkoutRepository.addProgramWithTemplates]):
 * cabeçalho, exercícios e os dias da semana (T19.8). `programId` da entidade é ignorado — o do
 * programa recém-criado é que vale.
 */
data class NewTemplate(
    val template: WorkoutTemplateEntity,
    val exercises: List<WorkoutTemplateExerciseEntity>,
    val scheduledDays: List<DayOfWeek> = emptyList(),
    /** Exercícios CUSTOM da oferta usados por **este** treino (T19.H2). */
    val customExercises: List<NewCustomExercise> = emptyList()
)

/**
 * Um exercício CUSTOM que nasce junto de uma cópia importada, e as posições que o usam (T19.H2).
 *
 * [ref] é a chave escopada à oferta (`custom-1`, `custom-2`). Ela serve para ligar posições ao
 * mesmo exercício dentro desta transação — inclusive posições de treinos diferentes do mesmo
 * programa — e morre aqui: a identidade do exercício criado é o `localId` do Room mais o `syncId`
 * gerado neste aparelho.
 *
 * [positions] chegam com `templateId` e `exerciseId` irrelevantes: os dois são preenchidos na
 * inserção.
 */
data class NewCustomExercise(
    val ref: String,
    val exercise: ExerciseEntity,
    val positions: List<WorkoutTemplateExerciseEntity>
)

/**
 * As regras dos campos do cabeçalho de um treino (T19.8) — as **mesmas** para a tela e para o
 * repositório, para que o asterisco do formulário corresponda ao que o domínio de fato exige.
 *
 * Obrigatório: só o nome (`workout_templates.name NOT NULL`). Sigla é opcional (`shortIdentifier`
 * anulável na entidade, no sync, no backup e no compartilhamento) e em branco vira `null`, não
 * `""`. Dias da semana são opcionais: nenhum dia é "sem dia fixo".
 */
object WorkoutTemplateFields {
    fun isValidName(name: String?): Boolean = !name.isNullOrBlank()

    fun requireName(name: String): String {
        require(isValidName(name)) { "Nome do treino é obrigatório." }
        return name.trim()
    }

    fun optionalShortIdentifier(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}

object CustomExerciseFields {
    fun isValidName(name: String?): Boolean = !name.isNullOrBlank()

    fun requireName(name: String): String {
        require(isValidName(name)) { "Nome do exercício é obrigatório." }
        return name.trim()
    }

    fun optional(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
