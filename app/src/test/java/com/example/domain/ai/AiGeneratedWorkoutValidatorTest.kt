package com.example.domain.ai

import com.example.domain.ai.WorkoutGenerationTestData.candidate
import com.example.domain.ai.WorkoutGenerationTestData.context
import com.example.domain.ai.WorkoutGenerationTestData.exerciseResponse
import com.example.domain.ai.WorkoutGenerationTestData.response
import com.example.domain.ai.model.AiExerciseLoadEvidenceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structured output válido não é treino válido.
 *
 * Política: uma violação invalida a proposta inteira. Nada é removido em silêncio e nenhum
 * `exerciseId` desconhecido é resolvido por aproximação.
 */
class AiGeneratedWorkoutValidatorTest {

    private fun validate(
        response: com.example.domain.ai.model.AiGeneratedWorkoutResponse,
        context: com.example.domain.ai.model.AiWorkoutGenerationContext = context()
    ) = AiCoachResponseValidator.validateGeneratedWorkout("req-1", context, response)

    private fun invalidReason(result: AiGeneratedWorkoutValidation): String {
        assertTrue("esperava rejeição, veio $result", result is AiGeneratedWorkoutValidation.Invalid)
        return (result as AiGeneratedWorkoutValidation.Invalid).reason
    }

    @Test
    fun `proposta valida vira rascunho`() {
        val result = validate(response())

        val draft = (result as AiGeneratedWorkoutValidation.Valid).draft
        assertEquals("req-1", draft.requestId)
        assertEquals("Peito e tríceps", draft.name)
        assertEquals(listOf(0, 1), draft.exercises.map { it.sortOrder })
        assertEquals(
            listOf("supino-reto-barra", "crucifixo-halteres"),
            draft.exercises.map { it.exerciseId }
        )
        // O nome exibido sai do catálogo do app, não do texto do modelo.
        assertEquals("Supino reto com barra", draft.exercises.first().name)
        assertEquals(4, draft.exercises.first().sets)
        assertEquals(8, draft.exercises.first().minReps)
        assertEquals(12, draft.exercises.first().maxReps)
        assertEquals(90, draft.exercises.first().restSeconds)
        assertNull("sem histórico, sem carga", draft.exercises.first().weightKg)
    }

    @Test
    fun `exerciseId inventado invalida a proposta inteira`() {
        val result = validate(
            response(exercises = listOf(exerciseResponse("UNKNOWN_ID", order = 1)))
        )

        assertTrue(invalidReason(result).contains("UNKNOWN_ID"))
    }

    @Test
    fun `exerciseId real fora dos candidatos e rejeitado`() {
        // "agachamento-livre" existe no catálogo do app, mas não foi oferecido nesta requisição.
        val result = validate(
            response(exercises = listOf(exerciseResponse("agachamento-livre", order = 1)))
        )

        assertTrue(invalidReason(result).contains("fora dos candidatos"))
    }

    @Test
    fun `exercicio repetido e rejeitado`() {
        val result = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1),
                    exerciseResponse("supino-reto-barra", order = 2)
                )
            )
        )

        assertTrue(invalidReason(result).contains("repetido"))
    }

    @Test
    fun `ordem repetida e rejeitada`() {
        val result = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1),
                    exerciseResponse("crucifixo-halteres", order = 1)
                )
            )
        )

        assertTrue(invalidReason(result).contains("order repetida"))
    }

    @Test
    fun `ordem com buraco e rejeitada`() {
        val result = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1),
                    exerciseResponse("crucifixo-halteres", order = 5)
                )
            )
        )

        assertTrue(invalidReason(result).contains("order fora de"))
    }

    @Test
    fun `series fora da faixa sao rejeitadas`() {
        listOf(-1, 0, AiCoachResponseValidator.MAX_SETS + 1).forEach { sets ->
            val result = validate(
                response(exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, sets = sets)))
            )
            assertTrue("sets=$sets deveria ser rejeitado", invalidReason(result).contains("sets fora"))
        }
    }

    @Test
    fun `repeticoes invalidas sao rejeitadas`() {
        val zero = validate(
            response(exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, minReps = 0)))
        )
        assertTrue(invalidReason(zero).contains("minReps fora"))

        val inverted = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1, minReps = 12, maxReps = 8)
                )
            )
        )
        assertTrue(invalidReason(inverted).contains("maxReps inválido"))

        val above = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1, maxReps = AiCoachResponseValidator.MAX_REPS + 1)
                )
            )
        )
        assertTrue(invalidReason(above).contains("maxReps inválido"))
    }

    @Test
    fun `descanso impossivel e rejeitado`() {
        listOf(-30, AiCoachResponseValidator.MAX_REST_SECONDS + 1).forEach { rest ->
            val result = validate(
                response(
                    exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, restSeconds = rest))
                )
            )
            assertTrue("rest=$rest deveria ser rejeitado", invalidReason(result).contains("restSeconds fora"))
        }
    }

    @Test
    fun `carga nao numerica nao atravessa o validador`() {
        val withEvidence = context(
            loadEvidence = listOf(
                AiExerciseLoadEvidenceContext("supino-reto-barra", lastWeightKg = 60f, lastReps = 8, sessionsWithHistory = 3)
            )
        )

        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            val result = validate(
                response(
                    exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, weightKg = value))
                ),
                context = withEvidence
            )
            assertTrue("$value deveria ser rejeitado", invalidReason(result).contains("weightKg"))
        }
    }

    @Test
    fun `carga sem historico registrado e invencao e e rejeitada`() {
        val result = validate(
            response(
                exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, weightKg = 80.0))
            )
        )

        assertTrue(invalidReason(result).contains("sem carga registrada"))
    }

    @Test
    fun `carga com historico registrado e aceita`() {
        val withEvidence = context(
            loadEvidence = listOf(
                AiExerciseLoadEvidenceContext("supino-reto-barra", lastWeightKg = 60f, lastReps = 8, sessionsWithHistory = 3)
            )
        )

        val result = validate(
            response(
                exercises = listOf(exerciseResponse("supino-reto-barra", order = 1, weightKg = 60.0))
            ),
            context = withEvidence
        )

        val draft = (result as AiGeneratedWorkoutValidation.Valid).draft
        assertEquals(60f, draft.exercises.single().weightKg)
    }

    @Test
    fun `carga acima do teto do app e rejeitada`() {
        val withEvidence = context(
            loadEvidence = listOf(
                AiExerciseLoadEvidenceContext("supino-reto-barra", lastWeightKg = 60f, lastReps = 8, sessionsWithHistory = 3)
            )
        )

        val result = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1, weightKg = AiCoachResponseValidator.MAX_WEIGHT_KG + 1)
                )
            ),
            context = withEvidence
        )

        assertTrue(invalidReason(result).contains("weightKg fora"))
    }

    @Test
    fun `treino sem nome ou sem exercicios e rejeitado`() {
        assertTrue(invalidReason(validate(response(name = "   "))).contains("name vazio"))
        assertTrue(invalidReason(validate(response(exercises = emptyList()))).contains("sem exercícios"))
    }

    @Test
    fun `mais exercicios que o teto do app e rejeitado`() {
        val many = (1..AiCoachResponseValidator.MAX_GENERATED_EXERCISES + 1).map { index ->
            candidate("ex-$index", "Exercício $index")
        }
        val result = validate(
            response(
                exercises = many.mapIndexed { index, candidate ->
                    exerciseResponse(candidate.exerciseId, order = index + 1)
                }
            ),
            context = context(candidates = many)
        )

        assertTrue(invalidReason(result).contains("exercícios"))
    }

    @Test
    fun `modelo admitindo falta de candidatos vira estado proprio`() {
        val result = validate(
            response(name = "", exercises = emptyList(), insufficientCandidates = true)
        )

        assertEquals(AiGeneratedWorkoutValidation.InsufficientCandidates, result)
    }

    @Test
    fun `admitir falta de candidatos e ainda propor exercicios e contradicao rejeitada`() {
        val result = validate(response(insufficientCandidates = true))

        assertTrue(invalidReason(result).contains("insufficientCandidates"))
    }

    @Test
    fun `uma parte invalida invalida a proposta inteira`() {
        val result = validate(
            response(
                exercises = listOf(
                    exerciseResponse("supino-reto-barra", order = 1),
                    exerciseResponse("crucifixo-halteres", order = 2, sets = 0)
                )
            )
        )

        // Nada de aproveitar o primeiro exercício: ou a proposta inteira vale, ou nenhuma vale.
        assertTrue(invalidReason(result).contains("sets fora"))
    }
}
