package com.example

import com.example.domain.engine.ExerciseVideoRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class YouTubeTest {

    @Before
    fun setup() {
        ExerciseVideoRegistry.reset()
    }

    @Test
    fun `test video registry returns null when no mapping exists`() {
        val jsonFixture = """
            {
                "videos": [
                    {
                        "exerciseId": "supino-reto-barra",
                        "youtubeVideoId": "rT7DgCr-3pg",
                        "title": "Execução Supino Reto",
                        "startSeconds": 10
                    }
                ]
            }
        """.trimIndent()

        ExerciseVideoRegistry.loadFromJsonString(jsonFixture)

        val unmappedVideo = ExerciseVideoRegistry.getVideoForExercise(
            canonicalId = "exercicio-desconhecido-raro",
            slug = "exercicio-desconhecido-raro",
            name = "Exercício Raro"
        )

        assertNull("Unmapped exercise MUST return null (no fallback search button)", unmappedVideo)
    }

    @Test
    fun `test video manifest validation bounds check`() {
        val invalidJson = """
            {
                "videos": [
                    {
                        "exerciseId": "ex-invalid",
                        "youtubeVideoId": "abc12345",
                        "startSeconds": 100,
                        "endSeconds": 50
                    }
                ]
            }
        """.trimIndent()

        val validation = ExerciseVideoRegistry.validateVideoManifest(invalidJson)
        assertFalse(validation.isValid)
        assertTrue(validation.warnings.isNotEmpty())
    }
}
