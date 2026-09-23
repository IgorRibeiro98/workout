package com.example.data.repository

import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutShareImportReceiptEntity
import com.example.data.local.WorkoutShareReceiptDao
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.SyncIds
import com.example.domain.social.SharedCustomExerciseSnapshot
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent
import com.example.domain.social.WorkoutShareError
import com.example.domain.social.WorkoutShareGateway
import com.example.domain.social.WorkoutShareKind
import com.example.domain.social.WorkoutShareOutcome

sealed interface WorkoutShareImportResult {
    /** A cópia foi criada. [localId] é o `localId` da raiz — o treino, ou o programa (T19.3). */
    data class Success(val kind: WorkoutShareKind, val localId: Long) : WorkoutShareImportResult
    data class AlreadyImported(val kind: WorkoutShareKind, val localId: Long) : WorkoutShareImportResult
    data class MissingExercises(val missingCanonicalIds: List<String>) : WorkoutShareImportResult
    /** O servidor não deixou aceitar: cancelada, expirada, bloqueio, sem rede... (T19.3). */
    data class Rejected(val error: WorkoutShareError) : WorkoutShareImportResult
    data class Error(val message: String) : WorkoutShareImportResult
}

/**
 * Importador de ofertas compartilhadas para o Room local (T17.7 / T19.3).
 *
 * Cria uma cópia totalmente nova e independente — do treino, ou do programa inteiro — pertencente
 * ao destinatário. Nunca cria vínculo vivo com o que está no aparelho do remetente.
 *
 * ## O fluxo de aceite (T19.3)
 *
 * ```text
 * recibo local?  ──sim──▶  AlreadyImported            (offline também)
 *      │ não
 *      ▼
 * POST :shareId/accept    ← o servidor revalida bloqueio, cancelamento, expiração e amizade,
 *      │                    e devolve o conteúdo IMUTÁVEL da oferta — não o que a tela tinha
 *      ▼
 * transação Room          ← programa/treino + exercícios + recibo: tudo ou nada
 *      │
 *      ▼
 * POST :shareId/complete-import   (best effort; um retry chega aqui de novo pelo recibo)
 * ```
 *
 * Idempotente de ponta a ponta: o aceite no servidor é repetível, e o recibo local impede uma
 * segunda cópia — toque duplo, retry depois de timeout e reabrir a oferta produzem **uma** cópia.
 */
open class WorkoutShareImporter(
    private val workoutRepository: WorkoutRepository? = null,
    private val receiptDao: WorkoutShareReceiptDao? = null,
    private val gateway: WorkoutShareGateway? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /**
     * Aceita a oferta no servidor e importa o que ela transporta. É o único caminho pelo qual uma
     * oferta vira treino ou programa no aparelho.
     */
    open suspend fun acceptAndImport(shareId: String): WorkoutShareImportResult {
        val repo = requireNotNull(workoutRepository) { "WorkoutRepository is required" }
        val receipts = requireNotNull(receiptDao) { "WorkoutShareReceiptDao is required" }
        val shareGateway = requireNotNull(gateway) { "WorkoutShareGateway is required" }

        // 1. Já importado? A resposta vem do recibo local, sem rede — e vale mesmo que, no servidor,
        // a oferta tenha sido cancelada por um bloqueio posterior: a cópia é do destinatário.
        alreadyImported(repo, receipts, shareId)?.let { existing ->
            shareGateway.completeImport(shareId)
            return existing
        }

        // 2. Aceitar no servidor. É ele quem sabe se a oferta ainda existe para este destinatário.
        val detail = when (val outcome = shareGateway.acceptShare(shareId)) {
            is WorkoutShareOutcome.Success -> outcome.data
            is WorkoutShareOutcome.Failure -> return WorkoutShareImportResult.Rejected(outcome.error)
        }

        // 3. A cópia é construída sobre o conteúdo que o servidor devolveu, e só sobre ele.
        return when (val content = detail.content) {
            is WorkoutShareContent.Workout -> importShare(shareId, content.snapshot)
            is WorkoutShareContent.Program -> importProgramShare(shareId, content.snapshot)
            null -> WorkoutShareImportResult.Error(
                "Esta oferta não pode ser adicionada nesta versão do Spark. Atualize o app e tente novamente."
            )
        }
    }

    /**
     * Importa um **treino** avulso (T17.7) para o programa atual — ou para [targetProgramId].
     *
     * Não fala com o servidor antes de escrever: quem revalida a oferta é [acceptAndImport]. Existe
     * separado porque é a transação local, testável sem rede.
     */
    open suspend fun importShare(
        shareId: String,
        snapshot: SharedWorkoutSnapshot,
        targetProgramId: Long? = null
    ): WorkoutShareImportResult {
        val repo = requireNotNull(workoutRepository) { "WorkoutRepository is required" }
        val receipts = requireNotNull(receiptDao) { "WorkoutShareReceiptDao is required" }
        val shareGateway = requireNotNull(gateway) { "WorkoutShareGateway is required" }

        // 1. Verificar idempotência local
        alreadyImported(repo, receipts, shareId)?.let { return it }

        // 2. Resolver o programa destino
        val program = if (targetProgramId != null) {
            repo.dao.getProgramById(targetProgramId)
        } else {
            repo.getProgramForNewTemplate()
        } ?: return WorkoutShareImportResult.Error("Nenhum programa de treino encontrado.")

        // 3. Validar se todos os exercícios canônicos existem no catálogo local
        val resolved = resolveExercises(repo, snapshot.exercises)
            ?: return WorkoutShareImportResult.MissingExercises(missingCanonicalIds(repo, snapshot.exercises))

        // 3b. Os exercícios CUSTOM da oferta (V2): cada um vira um exercício **novo** deste
        // aparelho, com identidade daqui, criado na mesma transação do treino.
        val customs = newCustomExercises(snapshot.customExercises, snapshot.exercises)

        // 4. Determinar a ordem dentro do programa
        val existingTemplates = repo.dao.getTemplatesForProgramSync(program.id)
        val nextOrder = (existingTemplates.maxOfOrNull { it.orderInProgram } ?: -1) + 1

        // 5-7. Treino, exercícios e recibo — **uma** transação.
        //
        // Eram três passos independentes, e uma interrupção entre eles deixava um treino pela
        // metade sem recibo: como o recibo é a idempotência desta importação, reabrir a oferta
        // criava um segundo treino ao lado do primeiro. Ou tudo entra, ou nada entra. Desde a
        // T19.H2 os exercícios CUSTOM criados entram na mesma transação, pelo mesmo motivo: um
        // retry depois de um crash não pode deixar duas cópias do mesmo exercício.
        val templateId = repo.addTemplateWithExercises(
            template = WorkoutTemplateEntity(
                programId = program.id,
                name = snapshot.name,
                shortIdentifier = snapshot.shortIdentifier ?: "T",
                orderInProgram = nextOrder
            ),
            exercises = resolved,
            customExercises = customs
        ) { localTemplateId ->
            receipts.insertReceipt(
                WorkoutShareImportReceiptEntity(
                    shareId = shareId,
                    importedTemplateLocalId = localTemplateId,
                    createdAt = clock()
                )
            )
        }

        // 8. Notificar backend que importação concluiu (best effort)
        shareGateway.completeImport(shareId)

        return WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_TEMPLATE, templateId)
    }

    /**
     * Importa um **programa inteiro** (T19.3): programa novo + todos os treinos + todos os
     * exercícios + recibo, numa transação só. Nada de parcial: um exercício que não existe no
     * catálogo local recusa o programa inteiro antes de qualquer escrita.
     *
     * O programa nasce com `isCurrent = false` — receber não troca o programa atual — e com
     * `localId`/`syncId` novos, gerados aqui, para ele e para cada treino.
     */
    open suspend fun importProgramShare(
        shareId: String,
        snapshot: SharedProgramSnapshot
    ): WorkoutShareImportResult {
        val repo = requireNotNull(workoutRepository) { "WorkoutRepository is required" }
        val receipts = requireNotNull(receiptDao) { "WorkoutShareReceiptDao is required" }
        val shareGateway = requireNotNull(gateway) { "WorkoutShareGateway is required" }

        // 1. Verificar idempotência local
        alreadyImported(repo, receipts, shareId)?.let { return it }

        if (snapshot.templates.isEmpty()) {
            return WorkoutShareImportResult.Error("O programa compartilhado não tem treinos.")
        }

        // 2. Resolver TODOS os exercícios de TODOS os treinos antes de escrever qualquer linha.
        val allExercises = snapshot.templates.flatMap { it.exercises }
        val missing = missingCanonicalIds(repo, allExercises)
        if (missing.isNotEmpty()) {
            return WorkoutShareImportResult.MissingExercises(missing)
        }
        // Os CUSTOM são da oferta inteira: um exercício por `ref`, mesmo quando vários treinos o
        // usam. As entidades são **as mesmas instâncias** em todos os treinos — e a transação,
        // pela chave, cria uma linha só (T19.H2 §15).
        val customsByRef = snapshot.customExercises.associate { custom ->
            custom.ref to ExerciseEntity(
                name = custom.name,
                primaryMuscle = custom.primaryMuscle,
                equipment = custom.equipment,
                description = custom.description,
                isUserCreated = true,
                syncId = SyncIds.random()
            )
        }
        val templates = mutableListOf<NewTemplate>()
        snapshot.templates.sortedBy { it.orderInProgram }.forEachIndexed { index, template ->
            val exercises = resolveExercises(repo, template.exercises)
                ?: return WorkoutShareImportResult.MissingExercises(missingCanonicalIds(repo, template.exercises))
            templates += NewTemplate(
                template = WorkoutTemplateEntity(
                    programId = 0,
                    name = template.name,
                    shortIdentifier = template.shortIdentifier ?: "T",
                    // A posição é a ordem em que os treinos chegaram, normalizada: preserva a
                    // ordem do remetente sem herdar os valores do Room dele.
                    orderInProgram = index
                ),
                exercises = exercises,
                // A agenda da cópia é a da oferta (T19.8): os mesmos dias, no mesmo treino — e
                // retry da importação não a duplica porque a transação inteira é idempotente pelo
                // recibo.
                scheduledDays = template.scheduledDays,
                customExercises = customPositions(customsByRef, template.exercises)
            )
        }

        // 3. Programa, treinos, exercícios e recibo — **uma** transação. Lançar dentro de
        // `andThen` desfaz tudo, inclusive o programa.
        val programId = repo.addProgramWithTemplates(
            program = WorkoutProgramEntity(
                name = snapshot.name,
                description = snapshot.description,
                isCurrent = false
            ),
            templates = templates
        ) { localProgramId ->
            receipts.insertReceipt(
                WorkoutShareImportReceiptEntity(
                    shareId = shareId,
                    importedProgramLocalId = localProgramId,
                    createdAt = clock()
                )
            )
        }

        // 4. Notificar backend que importação concluiu (best effort)
        shareGateway.completeImport(shareId)

        return WorkoutShareImportResult.Success(WorkoutShareKind.WORKOUT_PROGRAM, programId)
    }

    /**
     * O recibo desta oferta, se a cópia que ele aponta ainda existir. Uma cópia apagada pelo
     * usuário não conta: reabrir a oferta cria outra, que é o que ele pediu.
     */
    private suspend fun alreadyImported(
        repo: WorkoutRepository,
        receipts: WorkoutShareReceiptDao,
        shareId: String
    ): WorkoutShareImportResult.AlreadyImported? {
        val receipt = receipts.findReceipt(shareId) ?: return null
        receipt.importedProgramLocalId?.let { programId ->
            if (repo.getProgram(programId) != null) {
                return WorkoutShareImportResult.AlreadyImported(WorkoutShareKind.WORKOUT_PROGRAM, programId)
            }
        }
        receipt.importedTemplateLocalId?.let { templateId ->
            if (repo.getTemplate(templateId) != null) {
                return WorkoutShareImportResult.AlreadyImported(WorkoutShareKind.WORKOUT_TEMPLATE, templateId)
            }
        }
        return null
    }

    private suspend fun missingCanonicalIds(
        repo: WorkoutRepository,
        exercises: List<SharedExerciseSnapshot>
    ): List<String> = exercises
        .mapNotNull { it.canonicalExerciseId }
        .distinct()
        .filter { repo.getExerciseByCanonicalId(it) == null }

    /**
     * Os exercícios CUSTOM de uma oferta de **treino avulso**, prontos para a transação.
     *
     * Cada `ref` do snapshot vira uma `ExerciseEntity` nova, com `syncId` gerado **aqui** e
     * `canonicalId` nulo — a mesma forma de um exercício que o usuário cria à mão ([WorkoutRepository.addExercise]).
     * Nada do remetente chega junto: o snapshot não carrega `localId`, `syncId`, foto nem mídia.
     */
    private fun newCustomExercises(
        customExercises: List<SharedCustomExerciseSnapshot>,
        exercises: List<SharedExerciseSnapshot>
    ): List<NewCustomExercise> {
        if (customExercises.isEmpty()) return emptyList()
        val byRef = customExercises.associate { custom ->
            custom.ref to ExerciseEntity(
                name = custom.name,
                primaryMuscle = custom.primaryMuscle,
                equipment = custom.equipment,
                description = custom.description,
                isUserCreated = true,
                syncId = SyncIds.random()
            )
        }
        return customPositions(byRef, exercises)
    }

    /**
     * Liga cada `ref` às posições do treino que a usam.
     *
     * Uma `ref` que nenhum exercício referencia não produz exercício nenhum: o servidor já recusa
     * esse caso, e uma oferta antiga que passasse por ele não deve deixar lixo no catálogo de quem
     * recebe.
     */
    private fun customPositions(
        byRef: Map<String, ExerciseEntity>,
        exercises: List<SharedExerciseSnapshot>
    ): List<NewCustomExercise> = exercises
        .asSequence()
        .sortedBy { it.sortOrder }
        .mapNotNull { exercise -> exercise.customExerciseRef?.let { it to exercise } }
        .groupBy({ (ref, _) -> ref }, { (_, exercise) -> exercise })
        .mapNotNull { (ref, positions) ->
            val entity = byRef[ref] ?: return@mapNotNull null
            NewCustomExercise(
                ref = ref,
                exercise = entity,
                positions = positions.map { exercise ->
                    WorkoutTemplateExerciseEntity(
                        templateId = 0,
                        exerciseId = 0,
                        sortOrder = exercise.sortOrder,
                        targetSets = exercise.targetSets,
                        minReps = exercise.minReps,
                        maxReps = exercise.maxReps,
                        restDurationSeconds = exercise.restDurationSeconds,
                        plannedWeight = null,
                        machineLabel = null,
                        notes = null
                    )
                }
            )
        }

    /**
     * Os exercícios de um treino resolvidos para o catálogo **local**, pelo id canônico — nunca
     * pelo nome. `null` quando algum não existe aqui.
     *
     * Cargas, notas e máquinas ficam nulas: são do treino de quem compartilhou, não de quem recebe.
     */
    private suspend fun resolveExercises(
        repo: WorkoutRepository,
        exercises: List<SharedExerciseSnapshot>
    ): List<WorkoutTemplateExerciseEntity>? {
        val resolved = mutableListOf<WorkoutTemplateExerciseEntity>()
        for (ex in exercises.sortedBy { it.sortOrder }) {
            // Uma posição CUSTOM não se resolve no catálogo: ela é criada pela transação, a partir
            // do snapshot que a oferta trouxe.
            val canonical = ex.canonicalExerciseId ?: continue
            val exerciseEntity = repo.getExerciseByCanonicalId(canonical) ?: return null
            resolved.add(
                WorkoutTemplateExerciseEntity(
                    templateId = 0,
                    exerciseId = exerciseEntity.id,
                    sortOrder = ex.sortOrder,
                    targetSets = ex.targetSets,
                    minReps = ex.minReps,
                    maxReps = ex.maxReps,
                    restDurationSeconds = ex.restDurationSeconds,
                    plannedWeight = null,
                    machineLabel = null,
                    notes = null
                )
            )
        }
        return resolved
    }
}
