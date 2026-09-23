package com.example.data.repository

import com.example.data.local.TemplateExerciseWithDetails
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateWithSchedule
import com.example.data.social.WorkoutShareSnapshotLimits
import com.example.domain.social.SharedCustomExerciseSnapshot
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedProgramTemplateSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent

sealed interface SnapshotBuildResult {
    /** O conteúdo portável — um treino (T17.7) ou um programa inteiro (T19.3). */
    data class Success(val content: WorkoutShareContent) : SnapshotBuildResult
    data class Blocked(val reasons: List<String>) : SnapshotBuildResult
}

/**
 * Constrói o snapshot portável e imutável a partir do que está no Room (T17.7 / T19.3 / T19.H2).
 *
 * ## O que mudou na T19.H2
 *
 * ```text
 * antes                             agora
 * treino sem exercícios  → bloqueia  →  vira oferta (V2)
 * exercício CUSTOM       → bloqueia  →  viaja como cópia (V2)
 * ```
 *
 * Um treino vazio é um estado legítimo do Workout local — o esqueleto que a pessoa ainda vai
 * preencher — e recusá-lo era o Social decidindo o que é um treino válido, que não é assunto dele.
 * E um exercício criado pelo usuário passa a viajar como **snapshot**, nunca como referência viva:
 * quem recebe cria um exercício dele, com identidade dele. Ver
 * [com.example.domain.social.SharedCustomExerciseSnapshot].
 *
 * ## V1 quando ela basta, V2 quando a oferta precisa
 *
 * O construtor escolhe a versão pelo conteúdo: V2 só quando há CUSTOM ou algum treino sem
 * exercícios; V1 em todo o resto. Uma oferta V1 é aceita por qualquer Spark Backend já publicado,
 * então o caminho que já funcionava não passa a depender de um servidor novo — e a V1 continua
 * sendo exercitada de verdade, em vez de virar um ramo morto que ninguém mais escreve.
 *
 * ## Por que a validação inteira mora aqui
 *
 * Quem decide se a oferta é aceita é `WorkoutShareService`. Mas uma recusa **depois** da requisição
 * chega à tela como "o servidor recusou o conteúdo", uma frase que não diz o que corrigir. Cada
 * regra do servidor tem aqui a sua checagem, nomeando o treino, o exercício e o campo — e as faixas
 * vêm de [WorkoutShareSnapshotLimits], amarrado à fixture que os dois lados leem
 * (`contracts/social/v1/workout-share-snapshot.json`).
 *
 * A H1.2 tinha feito isso para séries/repetições/descanso. Faltavam o tamanho do nome, o tamanho da
 * sigla, a quantidade de exercícios, a faixa de `sortOrder` e a forma do id canônico — todas com
 * exatamente o mesmo desfecho ilegível.
 */
class WorkoutShareSnapshotBuilder {

    fun buildSnapshot(
        template: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseWithDetails>
    ): SnapshotBuildResult {
        val reasons = mutableListOf<String>()
        reasons += headerReasons(template.name, template.shortIdentifier, owner = "O treino")
        reasons += exerciseReasons(exercises, owner = "O treino")
        if (reasons.isNotEmpty()) return SnapshotBuildResult.Blocked(reasons)

        val customs = CustomExerciseIndex(exercises)
        val version = versionFor(hasCustom = customs.isNotEmpty(), hasEmptyTemplate = exercises.isEmpty())

        val snapshot = SharedWorkoutSnapshot(
            snapshotVersion = version,
            name = template.name,
            shortIdentifier = template.shortIdentifier,
            customExercises = customs.snapshots(),
            exercises = exerciseSnapshots(exercises, customs)
        )

        return SnapshotBuildResult.Success(WorkoutShareContent.Workout(snapshot))
    }

    /**
     * O programa inteiro (T19.3): nome, descrição e os treinos em ordem.
     *
     * Os exercícios CUSTOM são do **programa**, e não de um treino: o mesmo exercício usado em
     * três treinos aparece uma vez em `customExercises` e é referenciado três vezes, e é isso que
     * faz o destinatário receber uma cópia só (T19.H2 §15).
     *
     * `isCurrent`, `externalId`, `id` e `syncId` do programa ficam de fora: são do dono, não do
     * programa.
     */
    fun buildProgramSnapshot(
        program: WorkoutProgramEntity,
        templates: List<Pair<WorkoutTemplateWithSchedule, List<TemplateExerciseWithDetails>>>
    ): SnapshotBuildResult {
        if (templates.size < WorkoutShareSnapshotLimits.MIN_TEMPLATES) {
            return SnapshotBuildResult.Blocked(
                listOf("O programa precisa ter pelo menos um treino para ser compartilhado.")
            )
        }

        val reasons = mutableListOf<String>()
        if (program.name.trim().isEmpty() || program.name.trim().length > WorkoutShareSnapshotLimits.NAME_MAX_LENGTH) {
            reasons += "O nome do programa precisa ter entre 1 e ${WorkoutShareSnapshotLimits.NAME_MAX_LENGTH} caracteres."
        }
        program.description?.takeIf { it.length > WorkoutShareSnapshotLimits.DESCRIPTION_MAX_LENGTH }?.let {
            reasons += "A descrição do programa passa de ${WorkoutShareSnapshotLimits.DESCRIPTION_MAX_LENGTH} caracteres."
        }
        if (templates.size > WorkoutShareSnapshotLimits.MAX_TEMPLATES) {
            reasons += "O programa tem ${templates.size} treinos e o limite para compartilhar é ${WorkoutShareSnapshotLimits.MAX_TEMPLATES}."
        }
        templates.forEach { (template, exercises) ->
            val owner = "O treino \"${template.template.name}\""
            reasons += headerReasons(template.template.name, template.template.shortIdentifier, owner)
            reasons += exerciseReasons(exercises, owner)
        }
        if (reasons.isNotEmpty()) return SnapshotBuildResult.Blocked(reasons)

        val ordered = templates.sortedBy { (template, _) -> template.template.orderInProgram }
        // Um índice para a oferta inteira: a mesma linha de `exercises` em dois treinos produz a
        // mesma `ref`, e portanto um exercício só no aparelho de quem recebe.
        val customs = CustomExerciseIndex(ordered.flatMap { (_, exercises) -> exercises })
        val version = versionFor(
            hasCustom = customs.isNotEmpty(),
            hasEmptyTemplate = ordered.any { (_, exercises) -> exercises.isEmpty() }
        )

        val snapshot = SharedProgramSnapshot(
            snapshotVersion = version,
            name = program.name,
            description = program.description?.takeIf { it.isNotBlank() },
            customExercises = customs.snapshots(),
            templates = ordered.mapIndexed { index, (template, exercises) ->
                SharedProgramTemplateSnapshot(
                    name = template.template.name,
                    shortIdentifier = template.template.shortIdentifier,
                    // A posição viaja normalizada (0..n-1): é a ordem que importa, não o valor que
                    // o Room do remetente guardava.
                    orderInProgram = index,
                    // Os dias da semana são estruturais (T19.8): a cópia nasce com a mesma agenda,
                    // em forma canônica.
                    scheduledDays = template.scheduledDays,
                    exercises = exerciseSnapshots(exercises, customs)
                )
            }
        )

        return SnapshotBuildResult.Success(WorkoutShareContent.Program(snapshot))
    }

    /**
     * A versão mínima que consegue representar esta oferta.
     *
     * V2 só quando a oferta precisa de uma das duas capacidades novas; V1 sempre que ela basta.
     */
    private fun versionFor(hasCustom: Boolean, hasEmptyTemplate: Boolean): Int =
        if (hasCustom || hasEmptyTemplate) {
            WorkoutShareSnapshotLimits.VERSION_V2
        } else {
            WorkoutShareSnapshotLimits.VERSION_V1
        }

    /** Nome e sigla do treino, com as mesmas faixas que o servidor aplica. */
    private fun headerReasons(name: String, shortIdentifier: String?, owner: String): List<String> {
        val reasons = mutableListOf<String>()
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > WorkoutShareSnapshotLimits.NAME_MAX_LENGTH) {
            reasons += "$owner precisa de um nome com 1 a ${WorkoutShareSnapshotLimits.NAME_MAX_LENGTH} caracteres para ser compartilhado."
        }
        if ((shortIdentifier?.length ?: 0) > WorkoutShareSnapshotLimits.SHORT_IDENTIFIER_MAX_LENGTH) {
            reasons += "$owner tem uma sigla de ${shortIdentifier?.length} caracteres; para compartilhar, o limite é ${WorkoutShareSnapshotLimits.SHORT_IDENTIFIER_MAX_LENGTH}."
        }
        return reasons
    }

    /**
     * Tudo o que o servidor checa em cada exercício, checado aqui primeiro.
     *
     * Um treino **sem** exercícios não produz motivo nenhum: desde a T19.H2 ele é uma oferta V2
     * válida.
     */
    private fun exerciseReasons(
        exercises: List<TemplateExerciseWithDetails>,
        owner: String
    ): List<String> {
        val reasons = mutableListOf<String>()
        if (exercises.size > WorkoutShareSnapshotLimits.MAX_EXERCISES) {
            reasons += "$owner tem ${exercises.size} exercícios e o limite para compartilhar é ${WorkoutShareSnapshotLimits.MAX_EXERCISES}."
        }

        val outOfRange = exercises.filter { detail ->
            val ex = detail.templateExercise
            ex.targetSets !in WorkoutShareSnapshotLimits.MIN_TARGET_SETS..WorkoutShareSnapshotLimits.MAX_TARGET_SETS ||
                ex.minReps < WorkoutShareSnapshotLimits.MIN_REPS ||
                ex.maxReps > WorkoutShareSnapshotLimits.MAX_REPS ||
                ex.minReps > ex.maxReps ||
                ex.restDurationSeconds !in WorkoutShareSnapshotLimits.MIN_REST_SECONDS..WorkoutShareSnapshotLimits.MAX_REST_SECONDS
        }
        if (outOfRange.isNotEmpty()) {
            reasons += "$owner tem configuração de série/repetição/descanso fora do permitido para " +
                "compartilhar: ${outOfRange.joinToString(", ") { it.exercise.name }}. Séries " +
                "${WorkoutShareSnapshotLimits.MIN_TARGET_SETS}–${WorkoutShareSnapshotLimits.MAX_TARGET_SETS}, " +
                "repetições ${WorkoutShareSnapshotLimits.MIN_REPS}–${WorkoutShareSnapshotLimits.MAX_REPS} e descanso até " +
                "${WorkoutShareSnapshotLimits.MAX_REST_SECONDS / 60} minutos."
        }

        // Não há checagem de `sortOrder` aqui, e isso é a consequência de uma decisão, não um
        // esquecimento: ele viaja **normalizado** para 0..n-1 (ver [exerciseSnapshots]), então o
        // maior valor possível é `exercises.size - 1`, que a checagem de quantidade acima já
        // limita a menos do que os 30 que o servidor aceita.

        val badCanonical = exercises.filter { detail ->
            val canonical = detail.exercise.canonicalId
            !detail.exercise.isUserCreated &&
                (canonical.isNullOrBlank() || !WorkoutShareSnapshotLimits.CANONICAL_EXERCISE_ID_PATTERN.matches(canonical))
        }
        if (badCanonical.isNotEmpty()) {
            reasons += "$owner tem exercícios do catálogo sem identificação válida e não pode ser " +
                "compartilhado: ${badCanonical.joinToString(", ") { it.exercise.name }}."
        }

        val custom = exercises.filter { it.isCustom }
        if (custom.distinctBy { it.exercise.id }.size > WorkoutShareSnapshotLimits.MAX_CUSTOM_EXERCISES) {
            reasons += "$owner tem mais de ${WorkoutShareSnapshotLimits.MAX_CUSTOM_EXERCISES} exercícios personalizados."
        }
        val unnamedCustom = custom.filter {
            val name = it.exercise.name.trim()
            name.isEmpty() || name.length > WorkoutShareSnapshotLimits.CUSTOM_NAME_MAX_LENGTH
        }
        if (unnamedCustom.isNotEmpty()) {
            reasons += "$owner tem exercícios personalizados com nome fora de 1 a ${WorkoutShareSnapshotLimits.CUSTOM_NAME_MAX_LENGTH} caracteres."
        }
        return reasons
    }

    /**
     * Os exercícios, em ordem, com `sortOrder` **normalizado** para 0..n-1.
     *
     * O valor guardado no Room do remetente pode ter buracos (remover um exercício não renumera os
     * outros) e o servidor limita `sortOrder` a 0..30 — o que transformava um treino legítimo numa
     * recusa genérica. O que a oferta precisa transportar é a ordem, não os números.
     */
    private fun exerciseSnapshots(
        exercises: List<TemplateExerciseWithDetails>,
        customs: CustomExerciseIndex
    ): List<SharedExerciseSnapshot> =
        exercises
            .sortedWith(compareBy({ it.templateExercise.sortOrder }, { it.templateExercise.id }))
            .mapIndexed { index, detail ->
                SharedExerciseSnapshot(
                    canonicalExerciseId = if (detail.isCustom) null else detail.exercise.canonicalId,
                    customExerciseRef = if (detail.isCustom) customs.refOf(detail) else null,
                    sortOrder = index,
                    targetSets = detail.templateExercise.targetSets,
                    minReps = detail.templateExercise.minReps,
                    maxReps = detail.templateExercise.maxReps,
                    restDurationSeconds = detail.templateExercise.restDurationSeconds
                )
            }

    /**
     * As chaves CUSTOM desta oferta.
     *
     * Uma linha de `exercises` recebe **uma** chave (`custom-1`, `custom-2`, ...), na ordem em que
     * aparece. Duas posições que apontam para o mesmo `ExerciseEntity` — no mesmo treino ou em
     * treinos diferentes do mesmo programa — recebem a mesma chave, e é por isso que o destinatário
     * cria um exercício só. A chave morre com a oferta: ela não é derivada do `localId` nem do
     * `syncId` do remetente, e nenhum dos dois atravessa a rede.
     */
    private class CustomExerciseIndex(exercises: List<TemplateExerciseWithDetails>) {

        private val refsByLocalId: Map<Long, String>
        private val ordered: List<SharedCustomExerciseSnapshot>

        init {
            val distinct = exercises
                .filter { it.isCustom }
                .distinctBy { it.exercise.id }
            refsByLocalId = distinct.mapIndexed { index, detail ->
                detail.exercise.id to WorkoutShareSnapshotLimits.customRefAt(index)
            }.toMap()
            ordered = distinct.map { detail ->
                SharedCustomExerciseSnapshot(
                    ref = refsByLocalId.getValue(detail.exercise.id),
                    name = detail.exercise.name.trim(),
                    primaryMuscle = detail.exercise.primaryMuscle?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(WorkoutShareSnapshotLimits.CUSTOM_PRIMARY_MUSCLE_MAX_LENGTH),
                    equipment = detail.exercise.equipment?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(WorkoutShareSnapshotLimits.CUSTOM_EQUIPMENT_MAX_LENGTH),
                    description = detail.exercise.description?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(WorkoutShareSnapshotLimits.CUSTOM_DESCRIPTION_MAX_LENGTH)
                )
            }
        }

        fun isNotEmpty(): Boolean = ordered.isNotEmpty()

        fun snapshots(): List<SharedCustomExerciseSnapshot> = ordered

        fun refOf(detail: TemplateExerciseWithDetails): String? = refsByLocalId[detail.exercise.id]
    }
}

/**
 * Um exercício é CUSTOM quando **o usuário o criou** — e só nesse caso.
 *
 * Uma linha do catálogo sem `canonicalId` é outra coisa: é uma anomalia (importação antiga, dado
 * corrompido), e transformá-la em cópia CUSTOM silenciosamente reclassificaria o dado de alguém.
 * Ela continua bloqueando a oferta, com o nome do exercício, para que a pessoa saiba o que
 * aconteceu — a mesma regra que a T17.7 já aplicava.
 */
private val TemplateExerciseWithDetails.isCustom: Boolean
    get() = exercise.isUserCreated
