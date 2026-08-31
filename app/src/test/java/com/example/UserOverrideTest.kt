package com.example

import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseUserOverrideEntity
import com.example.domain.engine.ExerciseMediaResolver
import com.example.domain.engine.ExerciseResolver
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
        assertFalse(resolvedMedia.isGif)

        val resolved = ExerciseResolver.resolve(exercise, override, showGifs = true)
        assertEquals("Meu Supino Monstro", resolved.displayName)
        assertEquals("Banco inclinado no pino 3", resolved.notes)
        assertEquals("content://media/external/images/my_bench.jpg", resolved.resolvedMedia.mediaUri)
        assertTrue(resolved.isCustomPhoto)
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

        val resolved = ExerciseResolver.resolve(exercise, null, showGifs = true)
        assertEquals("Supino Reto com Barra", resolved.displayName)
        assertFalse(resolved.isCustomPhoto)
        assertEquals("https://example.com/system.gif", resolved.resolvedMedia.mediaUri)
    }

    @Test
    fun `test showGifs false disables gif loading but preserves custom photo`() {
        val exerciseWithGif = ExerciseEntity(
            id = 1L,
            name = "Puxada Alta",
            gifUrl = "https://example.com/lat_pulldown.gif"
        )

        // When showGifs is false, gif URL is not returned
        val resolvedNoGif = ExerciseResolver.resolve(exerciseWithGif, null, showGifs = false)
        assertNull(resolvedNoGif.resolvedMedia.mediaUri)
        assertFalse(resolvedNoGif.resolvedMedia.isGif)

        // But custom photo is still returned when showGifs is false
        val overrideWithPhoto = ExerciseUserOverrideEntity(
            exerciseId = 1L,
            customPhotoUri = "file:///data/user/0/photo.jpg"
        )
        val resolvedWithPhoto = ExerciseResolver.resolve(exerciseWithGif, overrideWithPhoto, showGifs = false)
        assertEquals("file:///data/user/0/photo.jpg", resolvedWithPhoto.resolvedMedia.mediaUri)
        assertTrue(resolvedWithPhoto.isCustomPhoto)
        assertFalse(resolvedWithPhoto.resolvedMedia.isGif)
    }
}
