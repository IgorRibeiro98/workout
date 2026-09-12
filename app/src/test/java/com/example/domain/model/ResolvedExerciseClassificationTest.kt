package com.example.domain.model

import com.example.data.local.ExerciseEntity
import com.example.domain.engine.ResolvedMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As duas classificações derivadas de [ResolvedExercise] — modo de execução e peso corporal.
 *
 * `isBodyweight` chegou aqui na auditoria de 2026-09-12: a regra vivia dentro de `ExecutionScreen`,
 * num `val` de nove linhas na composição, e a folha "Todas as séries" da **mesma tela** usava uma
 * segunda heurística, diferente. Estes testes existem para que exista uma resposta só.
 */
class ResolvedExerciseClassificationTest {

    private fun resolved(
        name: String,
        equipment: String? = null,
        isBodyweightFlag: Boolean = false,
        category: String? = null
    ): ResolvedExercise {
        val raw = ExerciseEntity(
            id = 1L,
            name = name,
            equipment = equipment,
            isBodyweight = isBodyweightFlag,
            category = category
        )
        return ResolvedExercise(
            id = 1L,
            canonicalId = null,
            slug = null,
            displayName = name,
            nameEn = null,
            primaryMuscle = null,
            secondaryMuscles = emptyList(),
            equipment = equipment,
            movementPattern = null,
            substitutionGroup = null,
            notes = null,
            resolvedMedia = ResolvedMedia(mediaUri = null, isCustomPhoto = false, isGif = false),
            defaultRestSeconds = null,
            isUserCreated = false,
            isCustomPhoto = false,
            rawExercise = raw
        )
    }

    @Test
    fun `campo do catalogo manda quando existe`() {
        assertTrue(resolved("Agachamento búlgaro", isBodyweightFlag = true).isBodyweight)
    }

    @Test
    fun `equipamento corporal conta como peso corporal`() {
        assertTrue(resolved("Mergulho nas paralelas", equipment = "Body weight").isBodyweight)
        assertTrue(resolved("Elevação de pernas", equipment = "Peso corporal").isBodyweight)
    }

    @Test
    fun `nome ainda decide enquanto o catalogo nao declara o campo`() {
        // O catálogo em `assets` não traz `isBodyweight` em nenhuma das suas entradas; sem a
        // heurística de nome, 21 exercícios voltariam a pedir carga.
        assertTrue(resolved("Flexão de braços").isBodyweight)
        assertTrue(resolved("Barra fixa pronada").isBodyweight)
        assertFalse(resolved("Supino reto com barra").isBodyweight)
    }

    @Test
    fun `a regra por nome e a mesma usada quando o exercicio nao resolve`() {
        assertTrue(ResolvedExercise.isBodyweightName("Prancha frontal"))
        assertFalse(ResolvedExercise.isBodyweightName("Rosca direta"))
    }

    @Test
    fun `modo de execucao por tempo continua valendo`() {
        assertEquals(ExerciseExecutionMode.DURATION, resolved("Prancha isométrica").executionMode)
        assertEquals(ExerciseExecutionMode.DURATION, resolved("Esteira").executionMode)
        assertEquals(ExerciseExecutionMode.REPS, resolved("Rosca direta").executionMode)
    }
}
