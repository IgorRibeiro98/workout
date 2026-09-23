package com.example.data.social

import com.example.domain.social.SharedCustomExerciseSnapshot
import com.example.domain.social.SharedExerciseSnapshot
import com.example.domain.social.SharedProgramSnapshot
import com.example.domain.social.SharedProgramTemplateSnapshot
import com.example.domain.social.SharedWorkoutSnapshot
import com.example.domain.social.WorkoutShareContent
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O **texto** que sai do aparelho ao compartilhar (T19.H2 / H2.6).
 *
 * ## Por que este teste existe
 *
 * Todo compartilhamento de treino e de programa era recusado com `INVALID_SNAPSHOT`, e nenhum teste
 * ficava vermelho. A causa não estava em nenhum DTO: `kotlinx.serialization` não escreve um campo
 * cujo valor é igual ao default declarado, `snapshotVersion` valia 1 e o default era 1 — o corpo
 * saía **sem** `snapshotVersion`, e a primeira coisa que o servidor valida é a versão.
 *
 * Os testes do Android afirmavam sobre o **objeto**, e os do backend montavam o corpo à mão em
 * TypeScript. Ninguém olhava o texto. Este arquivo olha.
 */
class WorkoutShareWireFormatTest {

    private fun body(content: WorkoutShareContent): String =
        WorkoutShareWireFormat.encodeCreateRequest(
            CreateWorkoutShareRequestDto.of(
                recipientSocialId = "spk-abc",
                clientRequestId = "req-1",
                content = content
            )
        )

    private val catalogExercise = SharedExerciseSnapshot(
        canonicalExerciseId = "supino-reto-barra",
        sortOrder = 0,
        targetSets = 4,
        minReps = 8,
        maxReps = 12,
        restDurationSeconds = 90
    )

    // ------------------------------------------------------------------ a regressão da H2.6

    @Test
    fun `o corpo de um treino carrega snapshotVersion`() {
        val text = body(
            WorkoutShareContent.Workout(
                SharedWorkoutSnapshot(
                    snapshotVersion = 1,
                    name = "Peito e Tríceps",
                    shortIdentifier = "A",
                    exercises = listOf(catalogExercise)
                )
            )
        )

        assertTrue("snapshotVersion ausente no corpo: $text", text.contains("\"snapshotVersion\":1"))
        assertEquals(
            """{"recipientSocialId":"spk-abc","clientRequestId":"req-1","snapshot":""" +
                """{"snapshotVersion":1,"name":"Peito e Tríceps","shortIdentifier":"A",""" +
                """"exercises":[{"canonicalExerciseId":"supino-reto-barra","sortOrder":0,""" +
                """"targetSets":4,"minReps":8,"maxReps":12,"restDurationSeconds":90}]}}""",
            text
        )
    }

    @Test
    fun `o corpo de um programa carrega snapshotVersion`() {
        val text = body(
            WorkoutShareContent.Program(
                SharedProgramSnapshot(
                    snapshotVersion = 1,
                    name = "PPL",
                    templates = listOf(
                        SharedProgramTemplateSnapshot(
                            name = "Push",
                            shortIdentifier = "A",
                            orderInProgram = 0,
                            scheduledDays = listOf(DayOfWeek.MONDAY),
                            exercises = listOf(catalogExercise)
                        )
                    )
                )
            )
        )

        assertTrue("snapshotVersion ausente no corpo: $text", text.contains("\"snapshotVersion\":1"))
        assertTrue(text.contains("\"programSnapshot\""))
        // O par exclusivo: o campo do outro tipo **não** viaja como `null`, porque o servidor lê
        // "presente" por nome.
        assertFalse(text.contains("\"snapshot\":null"))
        assertFalse(text.contains("\"snapshot\":"))
    }

    // ------------------------------------------------------------------ V2

    @Test
    fun `um treino vazio viaja como V2, com a lista de exercicios explicita`() {
        val text = body(
            WorkoutShareContent.Workout(
                SharedWorkoutSnapshot(
                    snapshotVersion = WorkoutShareSnapshotLimits.VERSION_V2,
                    name = "Treino Vazio",
                    shortIdentifier = "V"
                )
            )
        )

        assertTrue(text.contains("\"snapshotVersion\":2"))
        // Vazio é um estado, e um estado precisa ser dito: `exercises` omitido seria o servidor
        // adivinhando.
        assertTrue(text.contains("\"exercises\":[]"))
        assertFalse("V1 não tem customExercises, e V2 sem CUSTOM também não: $text", text.contains("customExercises"))
    }

    @Test
    fun `um CUSTOM viaja como copia, e a chave e escopada ao snapshot`() {
        val text = body(
            WorkoutShareContent.Workout(
                SharedWorkoutSnapshot(
                    snapshotVersion = WorkoutShareSnapshotLimits.VERSION_V2,
                    name = "Peito",
                    shortIdentifier = "P",
                    customExercises = listOf(
                        SharedCustomExerciseSnapshot(
                            ref = "custom-1",
                            name = "Meu Supino",
                            primaryMuscle = "Peitoral",
                            equipment = "Barra"
                        )
                    ),
                    exercises = listOf(
                        SharedExerciseSnapshot(
                            customExerciseRef = "custom-1",
                            sortOrder = 0,
                            targetSets = 3,
                            minReps = 8,
                            maxReps = 12,
                            restDurationSeconds = 90
                        )
                    )
                )
            )
        )

        assertTrue(text.contains("\"customExercises\":[{\"ref\":\"custom-1\",\"name\":\"Meu Supino\""))
        assertTrue(text.contains("\"customExerciseRef\":\"custom-1\""))
        // Exatamente uma identidade por exercício: o campo do catálogo não viaja como `null`.
        assertFalse(text.contains("\"canonicalExerciseId\""))
        // E nada do aparelho de quem compartilha.
        for (forbidden in listOf("localId", "syncId", "canonicalId", "customPhotoUri", "isUserCreated", "mediaUrl", "gifUrl")) {
            assertFalse("campo privado no corpo ($forbidden): $text", text.contains(forbidden))
        }
    }

    @Test
    fun `um exercicio do catalogo nunca leva customExerciseRef junto`() {
        val text = body(
            WorkoutShareContent.Workout(
                SharedWorkoutSnapshot(name = "A", exercises = listOf(catalogExercise))
            )
        )
        assertFalse(text.contains("customExerciseRef"))
    }
}
