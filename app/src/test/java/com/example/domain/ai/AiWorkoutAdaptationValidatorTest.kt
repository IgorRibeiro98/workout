package com.example.domain.ai

import com.example.domain.ai.WorkoutAdaptationTestData.candidate
import com.example.domain.ai.WorkoutAdaptationTestData.context
import com.example.domain.ai.WorkoutAdaptationTestData.loadChange
import com.example.domain.ai.WorkoutAdaptationTestData.plannedExercise
import com.example.domain.ai.WorkoutAdaptationTestData.repsChange
import com.example.domain.ai.WorkoutAdaptationTestData.replacementChange
import com.example.domain.ai.WorkoutAdaptationTestData.response
import com.example.domain.ai.WorkoutAdaptationTestData.restChange
import com.example.domain.ai.WorkoutAdaptationTestData.setsChange
import com.example.domain.ai.model.AiCoachResponseDataQuality
import com.example.domain.ai.model.AiDataQualityLevel
import com.example.domain.ai.model.AiWorkoutAdaptationContext
import com.example.domain.ai.model.AiWorkoutAdaptationResponse
import com.example.domain.ai.model.WorkoutAdaptationType
import com.example.domain.ai.model.WorkoutAdaptationValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uma proposta de adaptação é entrada não confiável.
 *
 * O que estes testes protegem: o exercício alterado precisa estar no treino, o valor atual
 * declarado precisa bater com o que o treino tem hoje, o substituto precisa ter sido oferecido, e
 * toda mudança precisa de razão, evidência e confiança válidas.
 */
class AiWorkoutAdaptationValidatorTest {

    private fun validate(
        response: AiWorkoutAdaptationResponse,
        context: AiWorkoutAdaptationContext = context()
    ) = AiCoachResponseValidator.validateWorkoutAdaptation(
        requestId = "req-1",
        templateId = WorkoutAdaptationTestData.TEMPLATE_ID,
        sourceRevision = WorkoutAdaptationTestData.REVISION,
        context = context,
        response = response
    )

    private fun invalidReason(result: AiWorkoutAdaptationValidation): String {
        assertTrue("esperava rejeição, veio $result", result is AiWorkoutAdaptationValidation.Invalid)
        return (result as AiWorkoutAdaptationValidation.Invalid).reason
    }

    @Test
    fun `mudanca de carga valida vira rascunho com antes e depois`() {
        val result = validate(response())

        val draft = (result as AiWorkoutAdaptationValidation.Valid).draft
        assertEquals(WorkoutAdaptationTestData.TEMPLATE_ID, draft.templateId)
        assertEquals(WorkoutAdaptationTestData.REVISION, draft.sourceRevision)

        val change = draft.changes.single()
        assertEquals(WorkoutAdaptationType.ADJUST_LOAD, change.type)
        assertEquals("ADJUST_LOAD:supino-reto-barra", change.id)
        assertEquals("Supino reto com barra", change.exerciseName)
        assertEquals(WorkoutAdaptationValue.Load(60f), change.currentValue)
        assertEquals(WorkoutAdaptationValue.Load(62.5f), change.suggestedValue)
        assertEquals(0.84, change.confidence, 0.0001)
    }

    @Test
    fun `valor atual diferente do treino invalida a proposta`() {
        // O treino tem 60 kg; o modelo achou que tinha 55 e está raciocinando sobre outro estado.
        val result = validate(response(changes = listOf(loadChange(currentWeightKg = 55.0, suggestedWeightKg = 60.0))))

        assertTrue(invalidReason(result).contains("currentWeightKg não corresponde"))
    }

    @Test
    fun `series e repeticoes atuais tambem precisam bater`() {
        assertTrue(
            invalidReason(validate(response(changes = listOf(setsChange(currentSets = 3)))))
                .contains("currentSets não corresponde")
        )
        assertTrue(
            invalidReason(validate(response(changes = listOf(repsChange(currentMinReps = 6)))))
                .contains("repetições atuais não correspondem")
        )
        assertTrue(
            invalidReason(validate(response(changes = listOf(restChange(currentRestSeconds = 60)))))
                .contains("currentRestSeconds não corresponde")
        )
    }

    @Test
    fun `exerciseId inexistente e rejeitado`() {
        val result = validate(response(changes = listOf(loadChange(exerciseId = "UNKNOWN"))))

        assertTrue(invalidReason(result).contains("fora do treino"))
    }

    @Test
    fun `exerciseId real mas fora do treino e rejeitado`() {
        // O agachamento existe no catálogo, mas não é um exercício deste treino.
        val result = validate(response(changes = listOf(loadChange(exerciseId = "agachamento-livre"))))

        assertTrue(invalidReason(result).contains("fora do treino"))
    }

    @Test
    fun `substituicao valida traz antes e depois de exercicio`() {
        val result = validate(response(changes = listOf(replacementChange())))

        val change = (result as AiWorkoutAdaptationValidation.Valid).draft.changes.single()
        assertEquals(WorkoutAdaptationType.REPLACE_EXERCISE, change.type)
        assertEquals(
            WorkoutAdaptationValue.Exercise("supino-reto-barra", "Supino reto com barra"),
            change.currentValue
        )
        assertEquals(
            WorkoutAdaptationValue.Exercise("supino-reto-halteres", "Supino reto com halteres"),
            change.suggestedValue
        )
    }

    @Test
    fun `substituto fora dos candidatos e rejeitado`() {
        val result = validate(
            response(changes = listOf(replacementChange(replacementExerciseId = "crucifixo-halteres")))
        )

        assertTrue(invalidReason(result).contains("fora dos candidatos"))
    }

    @Test
    fun `substituto inexistente e rejeitado`() {
        val result = validate(
            response(changes = listOf(replacementChange(replacementExerciseId = "UNKNOWN")))
        )

        assertTrue(invalidReason(result).contains("fora dos candidatos"))
    }

    @Test
    fun `substituto ausente e rejeitado`() {
        val result = validate(
            response(changes = listOf(replacementChange(replacementExerciseId = null)))
        )

        assertTrue(invalidReason(result).contains("replacementExerciseId ausente"))
    }

    @Test
    fun `substituto que ja esta no treino e rejeitado`() {
        val withBoth = context(
            exercises = listOf(
                plannedExercise(),
                plannedExercise(exerciseId = "supino-reto-halteres", name = "Supino reto com halteres")
            )
        )

        val result = validate(response(changes = listOf(replacementChange())), context = withBoth)

        assertTrue(invalidReason(result).contains("já está no treino"))
    }

    @Test
    fun `mudanca sem razao ou sem evidencia e rejeitada`() {
        assertTrue(
            invalidReason(validate(response(changes = listOf(loadChange().copy(reason = "  ")))))
                .contains("reason vazio")
        )
        assertTrue(
            invalidReason(validate(response(changes = listOf(loadChange().copy(evidence = "")))))
                .contains("evidence vazia")
        )
    }

    @Test
    fun `confidence invalida e rejeitada`() {
        listOf(-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            val result = validate(response(changes = listOf(loadChange(confidence = value))))
            assertTrue("confidence=$value deveria ser rejeitada", invalidReason(result).contains("confidence"))
        }
    }

    @Test
    fun `carga sugerida invalida e rejeitada`() {
        listOf(-20.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, AiCoachResponseValidator.MAX_WEIGHT_KG + 1)
            .forEach { value ->
                val result = validate(response(changes = listOf(loadChange(suggestedWeightKg = value))))
                assertTrue("carga=$value deveria ser rejeitada", invalidReason(result).contains("suggestedWeightKg"))
            }
    }

    @Test
    fun `series sugeridas fora da faixa sao rejeitadas`() {
        listOf(0, -1, AiCoachResponseValidator.MAX_SETS + 1).forEach { value ->
            val result = validate(response(changes = listOf(setsChange(suggestedSets = value))))
            assertTrue("sets=$value deveria ser rejeitado", invalidReason(result).contains("suggestedSets fora"))
        }
    }

    @Test
    fun `repeticoes sugeridas invalidas sao rejeitadas`() {
        assertTrue(
            invalidReason(validate(response(changes = listOf(repsChange(suggestedMinReps = 0)))))
                .contains("suggestedMinReps fora")
        )
        assertTrue(
            invalidReason(validate(response(changes = listOf(repsChange(suggestedMinReps = 12, suggestedMaxReps = 8)))))
                .contains("suggestedMaxReps inválido")
        )
        assertTrue(
            invalidReason(
                validate(
                    response(
                        changes = listOf(repsChange(suggestedMaxReps = AiCoachResponseValidator.MAX_REPS + 1))
                    )
                )
            ).contains("suggestedMaxReps inválido")
        )
    }

    @Test
    fun `descanso sugerido impossivel e rejeitado`() {
        listOf(-30, AiCoachResponseValidator.MAX_REST_SECONDS + 1).forEach { value ->
            val result = validate(response(changes = listOf(restChange(suggestedRestSeconds = value))))
            assertTrue("rest=$value deveria ser rejeitado", invalidReason(result).contains("suggestedRestSeconds fora"))
        }
    }

    @Test
    fun `sugerir o mesmo valor que ja existe e rejeitado`() {
        assertTrue(
            invalidReason(validate(response(changes = listOf(loadChange(suggestedWeightKg = 60.0)))))
                .contains("igual ao atual")
        )
        assertTrue(
            invalidReason(validate(response(changes = listOf(setsChange(suggestedSets = 4)))))
                .contains("igual ao atual")
        )
        assertTrue(
            invalidReason(validate(response(changes = listOf(restChange(suggestedRestSeconds = 90)))))
                .contains("igual ao atual")
        )
    }

    @Test
    fun `campo de outro tipo na mesma mudanca e rejeitado`() {
        val ambiguous = loadChange().copy(suggestedSets = 5)

        val result = validate(response(changes = listOf(ambiguous)))

        assertTrue(invalidReason(result).contains("não pertence a ADJUST_LOAD"))
    }

    @Test
    fun `mesma mudanca repetida para o mesmo exercicio e rejeitada`() {
        val result = validate(response(changes = listOf(loadChange(), loadChange(suggestedWeightKg = 65.0))))

        assertTrue(invalidReason(result).contains("mudança repetida"))
    }

    @Test
    fun `dois tipos diferentes no mesmo exercicio sao aceitos`() {
        val result = validate(response(changes = listOf(loadChange(), restChange())))

        val draft = (result as AiWorkoutAdaptationValidation.Valid).draft
        assertEquals(2, draft.changes.size)
        assertEquals(
            listOf(WorkoutAdaptationType.ADJUST_LOAD, WorkoutAdaptationType.ADJUST_REST),
            draft.changes.map { it.type }
        )
    }

    @Test
    fun `tipo nao autorizado nesta requisicao e rejeitado`() {
        // Sem evidência de desempenho, o app não oferece progressão de carga.
        val conservative = context(
            allowedChangeTypes = WorkoutAdaptationType.entries.filterNot { it.requiresPerformanceEvidence },
            maxDataQuality = AiDataQualityLevel.INSUFFICIENT,
            sessionsAnalyzed = 0
        )

        val result = validate(
            response(
                changes = listOf(loadChange()),
                dataQuality = AiCoachResponseDataQuality(AiDataQualityLevel.INSUFFICIENT.name, "Sem histórico.")
            ),
            context = conservative
        )

        assertTrue(invalidReason(result).contains("tipo não autorizado"))
    }

    @Test
    fun `nivel de evidencia acima do que o app enviou e rejeitado`() {
        val limited = context(maxDataQuality = AiDataQualityLevel.LIMITED, sessionsAnalyzed = 2)

        val result = validate(response(), context = limited)

        assertTrue(invalidReason(result).contains("acima da evidência enviada"))
    }

    @Test
    fun `resposta sem mudancas e valida`() {
        val result = validate(response(summary = "Nada a ajustar por enquanto.", changes = emptyList()))

        val draft = (result as AiWorkoutAdaptationValidation.Valid).draft
        assertTrue(draft.changes.isEmpty())
        assertEquals("Nada a ajustar por enquanto.", draft.summary)
    }

    @Test
    fun `mais mudancas que o teto e rejeitado`() {
        val exercises = (1..AiCoachResponseValidator.MAX_ADAPTATION_CHANGES + 1).map { index ->
            plannedExercise(exerciseId = "ex-$index", name = "Exercício $index")
        }
        val changes = exercises.map { loadChange(exerciseId = it.exerciseId) }

        val result = validate(
            response(changes = changes),
            context = context(exercises = exercises, replacementCandidates = listOf(candidate()))
        )

        assertTrue(invalidReason(result).contains("mais de"))
    }

    @Test
    fun `resumo vazio e rejeitado`() {
        assertTrue(invalidReason(validate(response(summary = "   "))).contains("summary vazio"))
    }

    @Test
    fun `tipo desconhecido e rejeitado`() {
        val result = validate(response(changes = listOf(loadChange().copy(type = "DELETE_WORKOUT"))))

        assertTrue(invalidReason(result).contains("tipo desconhecido"))
    }
}
