package com.example

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.domain.engine.ExerciseMediaResolver
import org.junit.Assert.*
import org.junit.Test

class UserOverrideTest {

    @Test
    fun `test user override precedence over system defaults`() {
        val exercise = ExerciseEntity(
            id = 10L,
            name = "Supino Reto com Barra",
            exerciseDbSearch = "barbell bench press",
            gifUrl = "https://example.com/system.gif"
        )

        val override = ExerciseUserOverrideEntity(
            exerciseId = 10L,
            displayName = "Meu Supino Monstro",
            notes = "Banco inclinado no pino 3",
            customPhotoUri = "content://media/external/images/my_bench.jpg",
            defaultRestSeconds = 120
        )

        val resolvedName = ExerciseMediaResolver.resolveDisplayName(exercise, override, "Fallback")
        val resolvedNotes = ExerciseMediaResolver.resolveNotes(exercise, override)
        val resolvedMedia = ExerciseMediaResolver.resolveMedia(exercise, override, showGifs = true)

        assertEquals("Meu Supino Monstro", resolvedName)
        assertEquals("Banco inclinado no pino 3", resolvedNotes)
        assertEquals("content://media/external/images/my_bench.jpg", resolvedMedia.mediaUri)
        assertTrue(resolvedMedia.isCustomPhoto)
    }

    @Test
    fun `test removal of override restores default values`() {
        val exercise = ExerciseEntity(
            id = 10L,
            name = "Supino Reto com Barra",
            gifUrl = "https://example.com/system.gif"
        )

        val resolvedName = ExerciseMediaResolver.resolveDisplayName(exercise, null, "Fallback")
        val resolvedMedia = ExerciseMediaResolver.resolveMedia(exercise, null, showGifs = true)

        assertEquals("Supino Reto com Barra", resolvedName)
        assertEquals("https://example.com/system.gif", resolvedMedia.mediaUri)
        assertFalse(resolvedMedia.isCustomPhoto)
        assertTrue(resolvedMedia.isGif)
    }
}
