package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import kotlinx.coroutines.flow.Flow
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkExerciseRemoteDataSource
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncIds
import com.example.data.sync.SyncMutationCoordinator

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

    /** Devolve o id gerado pelo Room, para quem precisa continuar montando o treino recém-criado. */
    suspend fun addTemplate(programId: Long, name: String, shortId: String, order: Int, dayOfWeek: String? = null): Long {
        val template = WorkoutTemplateEntity(
            programId = programId,
            name = name,
            shortIdentifier = shortId,
            orderInProgram = order,
            dayOfWeek = dayOfWeek
        )
        return syncMutations.mutate {
            val id = dao.insertTemplate(template)
            upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
            id
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
        andThen: suspend (templateId: Long) -> Unit = {}
    ): Long = syncMutations.mutate {
        val templateId = dao.insertTemplate(template)
        exercises.forEach { dao.insertTemplateExercise(it.copy(templateId = templateId)) }
        andThen(templateId)
        upsert(SyncEntityType.WORKOUT_TEMPLATE, template.syncId)
        templateId
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
        templates: List<Pair<WorkoutTemplateEntity, List<WorkoutTemplateExerciseEntity>>>,
        andThen: suspend (programId: Long) -> Unit = {}
    ): Long = syncMutations.mutate {
        val programId = dao.insertProgram(program.copy(isCurrent = false))
        val templateSyncIds = templates.map { (template, exercises) ->
            val templateId = dao.insertTemplate(template.copy(programId = programId))
            exercises.forEach { dao.insertTemplateExercise(it.copy(templateId = templateId)) }
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
object CustomExerciseFields {
    fun isValidName(name: String?): Boolean = !name.isNullOrBlank()

    fun requireName(name: String): String {
        require(isValidName(name)) { "Nome do exercício é obrigatório." }
        return name.trim()
    }

    fun optional(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
}
