package com.example.domain.engine

import android.content.Context
import org.json.JSONObject

data class CuratedExerciseVideo(
    val videoId: String,
    val title: String,
    val channel: String? = null,
    val startSeconds: Int? = null,
    val endSeconds: Int? = null
) {
    fun getEmbedUrl(): String {
        val params = mutableListOf<String>()
        startSeconds?.let { if (it > 0) params.add("start=$it") }
        endSeconds?.let { if (it > 0) params.add("end=$it") }
        params.add("autoplay=1")
        params.add("rel=0")
        val queryString = if (params.isNotEmpty()) "?${params.joinToString("&")}" else ""
        return "https://www.youtube-nocookie.com/embed/$videoId$queryString"
    }
}

data class VideoValidationResult(
    val isValid: Boolean,
    val validCount: Int,
    val warnings: List<String>
)

object ExerciseVideoRegistry {
    private val dynamicVideos = mutableMapOf<String, CuratedExerciseVideo>()
    private var isInitialized = false

    fun reset() {
        dynamicVideos.clear()
        isInitialized = false
    }

    fun validateVideoManifest(jsonString: String): VideoValidationResult {
        val warnings = mutableListOf<String>()
        var validCount = 0

        try {
            val root = JSONObject(jsonString)
            val videosArr = root.optJSONArray("videos")
            if (videosArr == null || videosArr.length() == 0) {
                warnings.add("Manifesto de vídeos não contém a lista 'videos'.")
            } else {
                for (i in 0 until videosArr.length()) {
                    val vObj = videosArr.getJSONObject(i)
                    val exId = vObj.optString("exerciseId")
                    val videoId = vObj.optString("youtubeVideoId")
                    val start = vObj.optInt("startSeconds", 0)
                    val hasEnd = vObj.has("endSeconds")
                    val end = if (hasEnd) vObj.optInt("endSeconds", 0) else null

                    if (exId.isBlank()) {
                        warnings.add("Item na posição $i não possui 'exerciseId'.")
                        continue
                    }
                    if (videoId.isBlank()) {
                        warnings.add("Exercício '$exId' não possui 'youtubeVideoId'.")
                        continue
                    }
                    if (start < 0) {
                        warnings.add("Exercício '$exId' possui 'startSeconds' negativo ($start).")
                    }
                    if (end != null && end <= start) {
                        warnings.add("Exercício '$exId' possui 'endSeconds' ($end) menor ou igual a 'startSeconds' ($start).")
                    }
                    validCount++
                }
            }
        } catch (e: Exception) {
            warnings.add("Erro ao validar JSON de vídeos: ${e.message}")
        }

        return VideoValidationResult(
            isValid = warnings.isEmpty(),
            validCount = validCount,
            warnings = warnings
        )
    }

    fun initialize(context: Context, assetPath: String = "catalog/youtube-exercise-videos.v1.json") {
        if (isInitialized) return
        try {
            val jsonString = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            loadFromJsonString(jsonString)
            isInitialized = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadFromJsonString(jsonString: String) {
        try {
            val root = JSONObject(jsonString)
            val videosArr = root.optJSONArray("videos")
            if (videosArr != null) {
                for (i in 0 until videosArr.length()) {
                    val vObj = videosArr.getJSONObject(i)
                    val exId = vObj.optString("exerciseId")
                    val slug = vObj.optString("canonicalSlug")
                    val videoId = vObj.optString("youtubeVideoId")
                    val title = vObj.optString("title", "Execução do Exercício")
                    val channel = vObj.optString("channel").takeIf { it.isNotEmpty() }
                    val start = vObj.optInt("startSeconds", 0)
                    val hasEnd = vObj.has("endSeconds")
                    val end = if (hasEnd) vObj.optInt("endSeconds", 0) else null

                    if (videoId.isNotEmpty() && exId.isNotEmpty()) {
                        val curated = CuratedExerciseVideo(
                            videoId = videoId,
                            title = title,
                            channel = channel,
                            startSeconds = if (start > 0) start else null,
                            endSeconds = if (end != null && end > start) end else null
                        )
                        dynamicVideos[exId.lowercase().replace("_", "-")] = curated
                        if (slug.isNotEmpty()) dynamicVideos[slug.lowercase().replace("_", "-")] = curated
                    }
                }
            }
            isInitialized = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getVideoForExercise(context: Context? = null, canonicalId: String?, slug: String?, name: String?): CuratedExerciseVideo? {
        if (!isInitialized && context != null) {
            initialize(context)
        }

        val normCanon = canonicalId?.lowercase()?.replace("_", "-")
        val normSlug = slug?.lowercase()?.replace("_", "-")

        if (!normCanon.isNullOrBlank()) {
            dynamicVideos[normCanon]?.let { return it }
        }
        if (!normSlug.isNullOrBlank()) {
            dynamicVideos[normSlug]?.let { return it }
        }

        return null
    }
}
