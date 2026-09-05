package com.example.domain.ai

import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A revisão é o que separa "proposta ainda válida" de "proposta obsoleta".
 *
 * Ela precisa ser estável para o mesmo treino e mudar para qualquer edição que a adaptação possa
 * tocar — se não mudasse, um draft antigo passaria por cima da edição nova do usuário.
 */
class WorkoutTemplateRevisionTest {

    private val template = WorkoutTemplateEntity(id = 7L, programId = 1L, name = "Peito + Tríceps")

    private val exercises = listOf(
        WorkoutTemplateExerciseEntity(
            id = 1L,
            templateId = 7L,
            exerciseId = 10L,
            sortOrder = 0,
            targetSets = 4,
            minReps = 8,
            maxReps = 12,
            restDurationSeconds = 90,
            plannedWeight = 60f
        ),
        WorkoutTemplateExerciseEntity(
            id = 2L,
            templateId = 7L,
            exerciseId = 11L,
            sortOrder = 1,
            targetSets = 3,
            minReps = 10,
            maxReps = 12,
            restDurationSeconds = 75,
            plannedWeight = null
        )
    )

    private fun revision(
        template: WorkoutTemplateEntity = this.template,
        exercises: List<WorkoutTemplateExerciseEntity> = this.exercises
    ) = WorkoutTemplateRevision.of(template, exercises)

    @Test
    fun `o mesmo treino produz sempre a mesma revisao`() {
        assertEquals(revision(), revision())
    }

    @Test
    fun `a ordem em que o banco devolveu as linhas nao muda a revisao`() {
        assertEquals(revision(), revision(exercises = exercises.reversed()))
    }

    @Test
    fun `qualquer campo que a adaptacao altera muda a revisao`() {
        val original = revision()

        assertNotEquals(original, revision(exercises = exercises.map { it.copy(plannedWeight = 62.5f) }))
        assertNotEquals(original, revision(exercises = exercises.map { it.copy(targetSets = 5) }))
        assertNotEquals(original, revision(exercises = exercises.map { it.copy(minReps = 10) }))
        assertNotEquals(original, revision(exercises = exercises.map { it.copy(maxReps = 15) }))
        assertNotEquals(original, revision(exercises = exercises.map { it.copy(restDurationSeconds = 120) }))
        assertNotEquals(original, revision(exercises = exercises.map { it.copy(exerciseId = 99L) }))
    }

    @Test
    fun `reordenar ou mexer na composicao do treino muda a revisao`() {
        val original = revision()

        assertNotEquals(original, revision(exercises = exercises.map { it.copy(sortOrder = it.sortOrder + 1) }))
        assertNotEquals(original, revision(exercises = exercises.drop(1)))
        assertNotEquals(
            original,
            revision(
                exercises = exercises + WorkoutTemplateExerciseEntity(
                    id = 3L,
                    templateId = 7L,
                    exerciseId = 12L
                )
            )
        )
        assertNotEquals(original, revision(template = template.copy(name = "Outro nome")))
    }

    @Test
    fun `carga ausente e carga zero sao revisoes diferentes`() {
        val withoutWeight = revision(exercises = exercises.map { it.copy(plannedWeight = null) })
        val withZero = revision(exercises = exercises.map { it.copy(plannedWeight = 0f) })

        assertNotEquals(withoutWeight, withZero)
    }
}
