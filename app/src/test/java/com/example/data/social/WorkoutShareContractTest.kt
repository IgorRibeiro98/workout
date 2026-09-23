package com.example.data.social

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O contrato do snapshot de compartilhamento, lido da **mesma** fixture que o backend lê
 * (`contracts/social/v1/workout-share-snapshot.json`).
 *
 * ## Por que isto existe
 *
 * Duas vezes seguidas o mesmo defeito apareceu com cara diferente: o app não conhecia uma regra do
 * servidor, e o usuário só descobria pela recusa — na H1.2 eram as faixas de série/repetição/
 * descanso; na H2.6, a versão do snapshot, que nem saía no corpo. Nos dois casos, **nada** ficava
 * vermelho, porque cada lado testava o seu próprio mundo.
 *
 * A fixture é a amarra: `workout-share-contract.spec.ts` (backend) afirma que o servidor aplica
 * estes números; este arquivo afirma que o app conhece os mesmos. Mudar um lado só deixa o teste do
 * outro vermelho.
 *
 * `org.json` não serve aqui: no teste unitário do Android ele é um stub que lança "not mocked".
 * `kotlinx.serialization` já é dependência do app e roda em JVM pura.
 */
class WorkoutShareContractTest {

    private fun fixture(): JsonObject {
        val relative = "contracts/social/v1/workout-share-snapshot.json"
        val file = File(relative).takeIf { it.isFile }
            ?: File("../$relative").takeIf { it.isFile }
            ?: error("fixture compartilhada não encontrada: $relative")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun limits(): JsonObject = fixture().getValue("limits").jsonObject

    private fun limit(name: String): Int = limits().getValue(name).jsonPrimitive.content.toInt()

    @Test
    fun `as faixas que o app aplica sao as da fixture`() {
        assertEquals(limit("nameMaxLength"), WorkoutShareSnapshotLimits.NAME_MAX_LENGTH)
        assertEquals(limit("shortIdentifierMaxLength"), WorkoutShareSnapshotLimits.SHORT_IDENTIFIER_MAX_LENGTH)
        assertEquals(limit("descriptionMaxLength"), WorkoutShareSnapshotLimits.DESCRIPTION_MAX_LENGTH)
        assertEquals(limit("minExercisesV1"), WorkoutShareSnapshotLimits.MIN_EXERCISES_V1)
        assertEquals(limit("minExercisesV2"), WorkoutShareSnapshotLimits.MIN_EXERCISES_V2)
        assertEquals(limit("maxExercises"), WorkoutShareSnapshotLimits.MAX_EXERCISES)
        assertEquals(limit("minTemplates"), WorkoutShareSnapshotLimits.MIN_TEMPLATES)
        assertEquals(limit("maxTemplates"), WorkoutShareSnapshotLimits.MAX_TEMPLATES)
        assertEquals(limit("minSortOrder"), WorkoutShareSnapshotLimits.MIN_SORT_ORDER)
        assertEquals(limit("maxSortOrder"), WorkoutShareSnapshotLimits.MAX_SORT_ORDER)
        assertEquals(limit("minTargetSets"), WorkoutShareSnapshotLimits.MIN_TARGET_SETS)
        assertEquals(limit("maxTargetSets"), WorkoutShareSnapshotLimits.MAX_TARGET_SETS)
        assertEquals(limit("minReps"), WorkoutShareSnapshotLimits.MIN_REPS)
        assertEquals(limit("maxReps"), WorkoutShareSnapshotLimits.MAX_REPS)
        assertEquals(limit("minRestSeconds"), WorkoutShareSnapshotLimits.MIN_REST_SECONDS)
        assertEquals(limit("maxRestSeconds"), WorkoutShareSnapshotLimits.MAX_REST_SECONDS)
        assertEquals(limit("maxOrderInProgram"), WorkoutShareSnapshotLimits.MAX_ORDER_IN_PROGRAM)
        assertEquals(limit("maxCustomExercises"), WorkoutShareSnapshotLimits.MAX_CUSTOM_EXERCISES)
        assertEquals(limit("customNameMaxLength"), WorkoutShareSnapshotLimits.CUSTOM_NAME_MAX_LENGTH)
        assertEquals(limit("customPrimaryMuscleMaxLength"), WorkoutShareSnapshotLimits.CUSTOM_PRIMARY_MUSCLE_MAX_LENGTH)
        assertEquals(limit("customEquipmentMaxLength"), WorkoutShareSnapshotLimits.CUSTOM_EQUIPMENT_MAX_LENGTH)
        assertEquals(limit("customDescriptionMaxLength"), WorkoutShareSnapshotLimits.CUSTOM_DESCRIPTION_MAX_LENGTH)
    }

    @Test
    fun `as formas de identidade sao as da fixture`() {
        assertEquals(
            fixture().getValue("canonicalExerciseIdPattern").jsonPrimitive.content,
            WorkoutShareSnapshotLimits.CANONICAL_EXERCISE_ID_PATTERN.pattern
        )
        assertEquals(
            fixture().getValue("customExerciseRefPattern").jsonPrimitive.content,
            WorkoutShareSnapshotLimits.CUSTOM_EXERCISE_REF_PATTERN.pattern
        )
        // A chave gerada pelo app precisa casar com a forma que o servidor exige.
        repeat(5) { index ->
            val ref = WorkoutShareSnapshotLimits.customRefAt(index)
            assertTrue("ref fora do formato: $ref", WorkoutShareSnapshotLimits.CUSTOM_EXERCISE_REF_PATTERN.matches(ref))
        }
        assertEquals("custom-1", WorkoutShareSnapshotLimits.customRefAt(0))
    }

    @Test
    fun `as versoes sao as da fixture`() {
        val versions = fixture().getValue("versions").jsonObject
        assertEquals(
            WorkoutShareSnapshotLimits.VERSION_V1,
            versions.getValue("v1").jsonObject.getValue("snapshotVersion").jsonPrimitive.content.toInt()
        )
        assertEquals(
            WorkoutShareSnapshotLimits.VERSION_V2,
            versions.getValue("v2").jsonObject.getValue("snapshotVersion").jsonPrimitive.content.toInt()
        )
    }

    // ------------------------------------------------------------------ as fixtures, decodificadas

    private fun decodeTemplate(name: String) = WorkoutShareWireFormat.json.decodeFromJsonElement(
        SharedWorkoutSnapshotDto.serializer(),
        fixture().getValue("fixtures").jsonObject.getValue(name)
    )

    private fun decodeProgram(name: String) = WorkoutShareWireFormat.json.decodeFromJsonElement(
        SharedProgramSnapshotDto.serializer(),
        fixture().getValue("fixtures").jsonObject.getValue(name)
    )

    @Test
    fun `o app le a fixture V1 exatamente como ela e`() {
        val snapshot = decodeTemplate("v1TemplateCanonical").toDomain()
        assertEquals(1, snapshot.snapshotVersion)
        assertEquals("Upper A", snapshot.name)
        assertTrue(snapshot.customExercises.isEmpty())
        assertEquals("supino-reto-barra", snapshot.exercises.single().canonicalExerciseId)
        assertNull(snapshot.exercises.single().customExerciseRef)
    }

    @Test
    fun `o app le um treino vazio V2`() {
        val snapshot = decodeTemplate("v2TemplateEmpty").toDomain()
        assertEquals(2, snapshot.snapshotVersion)
        assertTrue(snapshot.exercises.isEmpty())
    }

    @Test
    fun `o app le um CUSTOM V2 e resolve a referencia dentro do snapshot`() {
        val snapshot = decodeTemplate("v2TemplateCustom").toDomain()
        assertEquals(2, snapshot.snapshotVersion)
        val custom = snapshot.customExercises.single()
        assertEquals("custom-1", custom.ref)
        assertEquals("Meu Supino", custom.name)
        val referencing = snapshot.exercises.single { it.customExerciseRef != null }
        assertEquals(custom.ref, referencing.customExerciseRef)
        assertNull(referencing.canonicalExerciseId)
        // Toda referência usada resolve — é o que o servidor também exige.
        val declared = snapshot.customExercises.map { it.ref }.toSet()
        snapshot.exercises.mapNotNull { it.customExerciseRef }.forEach {
            assertTrue("referência sem entrada: $it", it in declared)
        }
    }

    @Test
    fun `o app le um programa cujo CUSTOM e compartilhado entre treinos`() {
        val snapshot = decodeProgram("v2ProgramSharedCustom").toDomain()
        assertEquals(2, snapshot.snapshotVersion)
        // Uma entrada, duas referências: é o que faz o destinatário criar **um** exercício.
        assertEquals(1, snapshot.customExercises.size)
        val ref = snapshot.customExercises.single().ref
        assertEquals(listOf(ref, ref), snapshot.templates.map { it.exercises.single().customExerciseRef })
    }

    @Test
    fun `o app le um programa com treino vazio`() {
        val snapshot = decodeProgram("v2ProgramWithEmptyTemplate").toDomain()
        assertEquals(2, snapshot.templates.size)
        assertTrue(snapshot.templates.last().exercises.isEmpty())
    }

    @Test
    fun `nenhum campo proibido aparece nas fixtures aceitas`() {
        val forbidden = fixture().getValue("forbiddenFields").jsonArray.map { it.jsonPrimitive.content }
        fixture().getValue("fixtures").jsonObject.forEach { (name, element) ->
            val text = element.toString()
            forbidden.forEach { field ->
                assertTrue("fixture $name traz campo proibido $field", !text.contains("\"$field\""))
            }
        }
    }

    @Test
    fun `a fixture registra o defeito que a H2_6 corrigiu`() {
        val rejected = fixture().getValue("rejected").jsonArray
        assertNotNull(rejected)
        // A lista de recusas é do backend; aqui a afirmação é mais modesta e ainda assim útil: o
        // primeiro caso é justamente o defeito da H2.6, e o app agora sempre escreve a versão.
        assertEquals("snapshotVersion ausente", rejected.first().jsonObject.getValue("case").jsonPrimitive.content)
    }
}
