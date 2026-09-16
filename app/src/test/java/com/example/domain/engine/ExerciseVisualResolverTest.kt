package com.example.domain.engine

import com.example.data.local.ExerciseEntity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A taxonomia visual da T19.7A: ícone = família de equipamento, cor = grupo muscular, e tudo
 * derivado de campos que já existem — determinístico, com fallback neutro.
 */
class ExerciseVisualResolverTest {

    @Test
    fun `cada valor de equipamento do catalogo canonico resolve para uma familia conhecida`() {
        val catalog = File("src/main/assets/catalog/catalogo_exercicios_base_ptbr.v1.json").readText()
        val equipments = Regex("\"equipment\"\\s*:\\s*\"([^\"]+)\"").findAll(catalog).map { it.groupValues[1] }.toSet()
        assertTrue("o catálogo deveria ter mais de uma dezena de equipamentos", equipments.size >= 20)

        val unresolved = equipments.filter { ExerciseVisualResolver.resolveEquipmentFamily(it) == EquipmentFamily.UNKNOWN }
        assertEquals("equipamentos do catálogo sem família: $unresolved", emptyList<String>(), unresolved)
    }

    @Test
    fun `familias do catalogo`() {
        val expected = mapOf(
            "Barra" to EquipmentFamily.FREE_WEIGHT,
            "Halteres" to EquipmentFamily.FREE_WEIGHT,
            "Kettlebell" to EquipmentFamily.FREE_WEIGHT,
            "Barra EZ" to EquipmentFamily.FREE_WEIGHT,
            "Trap bar" to EquipmentFamily.FREE_WEIGHT,
            "Anilhas" to EquipmentFamily.FREE_WEIGHT,
            "Máquina" to EquipmentFamily.MACHINE,
            "Smith" to EquipmentFamily.MACHINE,
            "Cabo" to EquipmentFamily.CABLE,
            "Elástico" to EquipmentFamily.CABLE,
            "Cabo/Elástico" to EquipmentFamily.CABLE,
            "Peso corporal" to EquipmentFamily.BODYWEIGHT,
            "Peso corporal/Peso" to EquipmentFamily.BODYWEIGHT,
            "Barra fixa" to EquipmentFamily.BODYWEIGHT,
            "Livre" to EquipmentFamily.BODYWEIGHT,
            "Bola suíça" to EquipmentFamily.BODYWEIGHT,
            "Roda abdominal" to EquipmentFamily.BODYWEIGHT,
            "Cadeira romana" to EquipmentFamily.BODYWEIGHT,
            "Banco" to EquipmentFamily.BODYWEIGHT,
            "Banco 45°" to EquipmentFamily.BODYWEIGHT
        )
        expected.forEach { (raw, family) ->
            assertEquals(raw, family, ExerciseVisualResolver.resolveEquipmentFamily(raw))
        }
    }

    @Test
    fun `em valores combinados a carga decide e o apoio so desempata`() {
        assertEquals(EquipmentFamily.FREE_WEIGHT, ExerciseVisualResolver.resolveEquipmentFamily("Banco/Halteres"))
        assertEquals(EquipmentFamily.MACHINE, ExerciseVisualResolver.resolveEquipmentFamily("Máquina/Banco"))
        assertEquals(EquipmentFamily.FREE_WEIGHT, ExerciseVisualResolver.resolveEquipmentFamily("Barra/Máquina"))
        assertEquals(EquipmentFamily.FREE_WEIGHT, ExerciseVisualResolver.resolveEquipmentFamily("Barra/Halter/Máquina"))
        // Dentro de um mesmo termo, a carga vence o apoio.
        assertEquals(EquipmentFamily.MACHINE, ExerciseVisualResolver.resolveEquipmentFamily("Supino máquina no banco"))
    }

    @Test
    fun `texto livre de CUSTOM e reconhecido sem acento e sem caixa`() {
        assertEquals(EquipmentFamily.MACHINE, ExerciseVisualResolver.resolveEquipmentFamily("MAQUINA"))
        assertEquals(EquipmentFamily.FREE_WEIGHT, ExerciseVisualResolver.resolveEquipmentFamily("halter"))
        assertEquals(EquipmentFamily.CABLE, ExerciseVisualResolver.resolveEquipmentFamily("polia alta"))
        assertEquals(EquipmentFamily.BODYWEIGHT, ExerciseVisualResolver.resolveEquipmentFamily("paralelas"))
        assertEquals(EquipmentFamily.FREE_WEIGHT, ExerciseVisualResolver.resolveEquipmentFamily("peso livre"))
        assertEquals(EquipmentFamily.MACHINE, ExerciseVisualResolver.resolveEquipmentFamily("cadeira extensora"))
    }

    @Test
    fun `sem equipamento ou texto desconhecido cai no fallback neutro`() {
        assertEquals(EquipmentFamily.UNKNOWN, ExerciseVisualResolver.resolveEquipmentFamily(null))
        assertEquals(EquipmentFamily.UNKNOWN, ExerciseVisualResolver.resolveEquipmentFamily(""))
        assertEquals(EquipmentFamily.UNKNOWN, ExerciseVisualResolver.resolveEquipmentFamily("   "))
        assertEquals(EquipmentFamily.UNKNOWN, ExerciseVisualResolver.resolveEquipmentFamily("xyz"))

        val visual = ExerciseVisualResolver.resolve(primaryMuscle = null, equipment = null)
        assertEquals(EquipmentFamily.UNKNOWN, visual.equipmentFamily)
        assertEquals(MuscleGroup.FULL_BODY, visual.muscleGroup)
        assertEquals(EquipmentFamily.UNKNOWN.icon, visual.icon)
        assertEquals(MuscleGroup.FULL_BODY.color, visual.color)
    }

    @Test
    fun `flag isBodyweight so desempata quando o equipamento nao diz nada`() {
        val semEquipamento = ExerciseEntity(name = "Flexão", isBodyweight = true, isUserCreated = true)
        assertEquals(EquipmentFamily.BODYWEIGHT, ExerciseVisualResolver.resolve(semEquipamento).equipmentFamily)

        val comEquipamento = ExerciseEntity(name = "Supino", equipment = "Barra", isBodyweight = true)
        assertEquals(EquipmentFamily.FREE_WEIGHT, ExerciseVisualResolver.resolve(comEquipamento).equipmentFamily)
    }

    @Test
    fun `icone e cor sao dimensoes independentes`() {
        val supinoBarra = ExerciseVisualResolver.resolve(primaryMuscle = "Peitoral", equipment = "Barra")
        val supinoMaquina = ExerciseVisualResolver.resolve(primaryMuscle = "Peitoral", equipment = "Máquina")
        val remadaBarra = ExerciseVisualResolver.resolve(primaryMuscle = "Costas", equipment = "Barra")

        // Mesmo músculo, equipamento diferente: mesma cor, ícone diferente.
        assertEquals(supinoBarra.color, supinoMaquina.color)
        assertNotEquals(supinoBarra.icon, supinoMaquina.icon)
        // Mesmo equipamento, músculo diferente: mesmo ícone, cor diferente.
        assertEquals(supinoBarra.icon, remadaBarra.icon)
        assertNotEquals(supinoBarra.color, remadaBarra.color)
    }

    @Test
    fun `mesma entrada, mesma saida`() {
        val a = ExerciseVisualResolver.resolve(primaryMuscle = "Quadríceps", equipment = "Smith")
        val b = ExerciseVisualResolver.resolve(primaryMuscle = "Quadríceps", equipment = "Smith")
        assertEquals(a, b)
        assertEquals(EquipmentFamily.MACHINE, a.equipmentFamily)
        assertEquals(MuscleGroup.QUADS, a.muscleGroup)
    }
}
