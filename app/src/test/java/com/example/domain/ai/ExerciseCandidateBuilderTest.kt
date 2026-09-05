package com.example.domain.ai

import com.example.domain.ai.WorkoutGenerationTestData.catalogExercise
import com.example.domain.ai.WorkoutGenerationTestData.preferences
import com.example.domain.ai.model.EquipmentAvailability
import com.example.domain.engine.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O catálogo que chega ao modelo é um recorte, não o banco inteiro.
 *
 * O que estes testes protegem: só entra exercício do foco pedido, com equipamento disponível e
 * não excluído; a identidade é sempre o id canônico; e a lista é determinística e limitada.
 */
class ExerciseCandidateBuilderTest {

    private val catalog = listOf(
        catalogExercise(1, "Supino reto com barra", "supino-reto-barra", "Peitoral"),
        catalogExercise(2, "Crucifixo com halteres", "crucifixo-halteres", "Peitoral", equipment = "Halteres"),
        catalogExercise(3, "Flexão de braço", "flexao", "Peitoral", equipment = "Peso corporal", isBodyweight = true),
        catalogExercise(4, "Remada curvada", "remada-curvada", "Dorsal"),
        catalogExercise(5, "Agachamento livre", "agachamento-livre", "Quadríceps"),
        catalogExercise(6, "Tríceps testa", "triceps-testa", "Tríceps", equipment = "Barra EZ"),
        catalogExercise(
            7,
            "Supino fechado",
            "supino-fechado",
            "Peitoral",
            secondaryMuscles = "Tríceps,Deltoide anterior"
        )
    )

    @Test
    fun `so entram exercicios do foco pedido`() {
        val candidates = ExerciseCandidateBuilder.build(catalog, preferences(focus = listOf(MuscleGroup.CHEST)))

        val ids = candidates.map { it.exerciseId }
        assertTrue("supino-reto-barra" in ids)
        assertTrue("crucifixo-halteres" in ids)
        assertTrue("flexao" in ids)
        // Costas e pernas não têm nada a ver com o pedido e não podem custar tokens.
        assertFalse("remada-curvada" in ids)
        assertFalse("agachamento-livre" in ids)
    }

    @Test
    fun `todo candidato leva o id canonico do catalogo`() {
        val candidates = ExerciseCandidateBuilder.build(catalog, preferences(focus = listOf(MuscleGroup.CHEST)))

        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            val source = catalog.single { it.name == candidate.name }
            assertEquals(source.canonicalId, candidate.exerciseId)
            assertTrue(candidate.exerciseId.isNotBlank())
        }
    }

    @Test
    fun `exercicio sem canonicalId usa a identidade local do app`() {
        val custom = catalogExercise(99, "Supino do usuário", canonicalId = null, primaryMuscle = "Peitoral")

        val candidates = ExerciseCandidateBuilder.build(
            catalog + custom,
            preferences(focus = listOf(MuscleGroup.CHEST))
        )

        assertTrue("local:99" in candidates.map { it.exerciseId })
    }

    @Test
    fun `exercicio excluido nao entra nos candidatos`() {
        val candidates = ExerciseCandidateBuilder.build(
            catalog,
            preferences(focus = listOf(MuscleGroup.CHEST), excluded = setOf("crucifixo-halteres"))
        )

        assertFalse("crucifixo-halteres" in candidates.map { it.exerciseId })
        assertTrue("supino-reto-barra" in candidates.map { it.exerciseId })
    }

    @Test
    fun `equipamento indisponivel nao entra nos candidatos`() {
        val candidates = ExerciseCandidateBuilder.build(
            catalog,
            preferences(
                focus = listOf(MuscleGroup.CHEST),
                equipment = setOf(EquipmentAvailability.DUMBBELL)
            )
        )

        assertEquals(listOf("crucifixo-halteres"), candidates.map { it.exerciseId })
    }

    @Test
    fun `peso corporal alcanca o exercicio marcado como peso corporal`() {
        val candidates = ExerciseCandidateBuilder.build(
            catalog,
            preferences(
                focus = listOf(MuscleGroup.CHEST),
                equipment = setOf(EquipmentAvailability.BODYWEIGHT)
            )
        )

        assertEquals(listOf("flexao"), candidates.map { it.exerciseId })
    }

    @Test
    fun `foco sem nenhum exercicio compativel devolve lista vazia`() {
        val candidates = ExerciseCandidateBuilder.build(
            catalog,
            preferences(
                focus = listOf(MuscleGroup.CALVES),
                equipment = setOf(EquipmentAvailability.KETTLEBELL)
            )
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `musculo secundario entra quando o principal nao e do foco`() {
        val candidates = ExerciseCandidateBuilder.build(catalog, preferences(focus = listOf(MuscleGroup.TRICEPS)))

        val ids = candidates.map { it.exerciseId }
        assertEquals("o principal do foco vem antes do secundário", "triceps-testa", ids.first())
        assertTrue("supino-fechado" in ids)
    }

    @Test
    fun `exercicio inativo nunca vira candidato`() {
        val inactive = catalogExercise(50, "Supino desativado", "supino-desativado", "Peitoral", active = false)

        val candidates = ExerciseCandidateBuilder.build(
            catalog + inactive,
            preferences(focus = listOf(MuscleGroup.CHEST))
        )

        assertFalse("supino-desativado" in candidates.map { it.exerciseId })
    }

    @Test
    fun `a lista respeita a cota por grupo e o teto total`() {
        val big = (1..60).map { index ->
            catalogExercise(1000L + index, "Peito $index", "peito-$index", "Peitoral")
        } + (1..60).map { index ->
            catalogExercise(2000L + index, "Costas $index", "costas-$index", "Dorsal")
        }

        val candidates = ExerciseCandidateBuilder.build(
            big,
            preferences(focus = listOf(MuscleGroup.CHEST, MuscleGroup.BACK))
        )

        assertTrue(candidates.size <= AiModelConfig.MAX_CANDIDATE_EXERCISES)
        val chest = candidates.count { it.muscleGroup == MuscleGroup.CHEST.displayName }
        val back = candidates.count { it.muscleGroup == MuscleGroup.BACK.displayName }
        assertEquals(AiModelConfig.MAX_CANDIDATES_PER_MUSCLE_GROUP, chest)
        assertEquals(AiModelConfig.MAX_CANDIDATES_PER_MUSCLE_GROUP, back)
    }

    @Test
    fun `a mesma configuracao produz sempre a mesma lista`() {
        val prefs = preferences(focus = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS))

        val first = ExerciseCandidateBuilder.build(catalog, prefs)
        val second = ExerciseCandidateBuilder.build(catalog.reversed(), prefs)

        assertEquals(first, second)
    }

    @Test
    fun `apenas metadata util para montar treino vai para o modelo`() {
        val candidates = ExerciseCandidateBuilder.build(catalog, preferences(focus = listOf(MuscleGroup.CHEST)))

        val supino = candidates.single { it.exerciseId == "supino-reto-barra" }
        assertEquals("Supino reto com barra", supino.name)
        assertEquals(MuscleGroup.CHEST.displayName, supino.muscleGroup)
        assertEquals("Barra", supino.equipment)
    }
}
