package com.example

import com.example.data.datastore.MediaProviderSettings
import com.example.data.local.ExerciseEntity
import com.example.data.remote.ExternalExerciseDto
import com.example.data.remote.ExerciseRemoteDataSource
import com.example.data.remote.NetworkResult
import com.example.data.remote.NetworkTestResult
import com.example.domain.engine.ExerciseMediaEngine
import com.example.domain.provider.ExerciseDbProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ExerciseDbOssProviderTest {

    private class MockRemoteDataSource(
        var response: NetworkResult<List<ExternalExerciseDto>>
    ) : ExerciseRemoteDataSource {
        override suspend fun fetchExternalCatalog(limit: Int, offset: Int): NetworkResult<List<ExternalExerciseDto>> = response
        override suspend fun searchExercises(query: String): NetworkResult<List<ExternalExerciseDto>> = response
        override suspend fun getExerciseById(id: String): NetworkResult<ExternalExerciseDto> {
            return NetworkResult.HttpError(404, "Not found")
        }
        override suspend fun testConnection(query: String): NetworkTestResult {
            return when (val res = response) {
                is NetworkResult.Success -> NetworkTestResult.Success(query, "Bench Press", "0001", "http://example.com/gif.gif", res.data.size)
                else -> NetworkTestResult.Failure(errorMessage = "Network error")
            }
        }
    }

    @Test
    fun `Scenario 1 - Default state when ExerciseDB is disabled`() = runBlocking {
        val mockRemote = MockRemoteDataSource(NetworkResult.Success(emptyList()))
        val provider = ExerciseDbProvider(mockRemote, MediaProviderSettings(exerciseDbEnabled = false))
        
        assertFalse("Provider should be disabled by default", provider.isEnabled)
        
        val result = provider.searchMedia("1", "Supino Reto")
        assertFalse("Media search should not succeed when disabled", result.isSuccess)
        assertEquals("ExerciseDB API", result.providerName)
        assertTrue(result.errorMessage?.contains("desativada") == true)

        val repo = com.example.data.remote.ExerciseMediaRepository(mockRemote)
        val engine = ExerciseMediaEngine(repo)
        val exercise = ExerciseEntity(id = 1, name = "Supino Reto", category = "Peito")
        val mediaRes = engine.resolveExerciseMedia(exercise, externalProvider = provider)
        
        assertFalse("Fallback to no media", mediaRes.isSuccess)
        assertEquals("Fallback", mediaRes.providerName)
    }

    @Test
    fun `Scenario 2 - ExerciseDB enabled and returns GIF without API key`() = runBlocking {
        val dto = ExternalExerciseDto(
            id = "0001",
            name = "3/4 sit-up",
            gifUrl = "https://v2.exercisedb.io/image/0001.gif",
            bodyPart = "waist",
            equipment = "body weight",
            target = "abs"
        )
        val mockRemote = MockRemoteDataSource(NetworkResult.Success(listOf(dto)))
        val settings = MediaProviderSettings(exerciseDbEnabled = true)
        val provider = ExerciseDbProvider(mockRemote, settings)

        assertTrue("Provider should be enabled", provider.isEnabled)

        val result = provider.searchMedia("1", "sit-up")
        assertTrue("Media search should succeed without API Key", result.isSuccess)
        assertEquals("https://v2.exercisedb.io/image/0001.gif", result.mediaUri)
        assertTrue("Should be a GIF", result.isGif)

        val repo = com.example.data.remote.ExerciseMediaRepository(mockRemote)
        val engine = ExerciseMediaEngine(repo)
        val exercise = ExerciseEntity(id = 1, name = "sit-up", category = "Abdômen")
        val mediaRes = engine.resolveExerciseMedia(exercise, externalProvider = provider)

        assertTrue("Engine should resolve ExerciseDB media", mediaRes.isSuccess)
        assertEquals("https://v2.exercisedb.io/image/0001.gif", mediaRes.mediaUri)
    }

    @Test
    fun `Scenario 3 - ExerciseDB unavailable or offline handled gracefully`() = runBlocking {
        val mockRemote = MockRemoteDataSource(NetworkResult.Offline)
        val settings = MediaProviderSettings(exerciseDbEnabled = true)
        val provider = ExerciseDbProvider(mockRemote, settings)

        val result = provider.searchMedia("1", "Supino")
        assertFalse("Should fail gracefully", result.isSuccess)
        assertTrue("Message should indicate offline", result.errorMessage?.contains("offline") == true)

        val repo = com.example.data.remote.ExerciseMediaRepository(mockRemote)
        val engine = ExerciseMediaEngine(repo)
        val exercise = ExerciseEntity(id = 1, name = "Supino", category = "Peito")
        val mediaRes = engine.resolveExerciseMedia(exercise, externalProvider = provider)

        assertFalse("App continues without crashing using fallback", mediaRes.isSuccess)
        assertEquals("Fallback", mediaRes.providerName)
    }
}

