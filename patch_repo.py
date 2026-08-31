import re

with open("app/src/main/java/com/example/data/remote/ExerciseMediaRepository.kt", "r") as f:
    content = f.read()

replacement = """
            val searchQueries = mutableListOf<String>()
            exercise.exerciseDbSearch?.takeIf { it.isNotBlank() }?.let { searchQueries.add(it) }
            exercise.exerciseDbAliases?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.let { searchQueries.addAll(it) }
            exercise.nameEn?.takeIf { it.isNotBlank() }?.let { searchQueries.add(it) }
            val normalName = exercise.canonicalId?.replace("-", " ") ?: exercise.name
            if (normalName.isNotBlank()) searchQueries.add(normalName)

            if (searchQueries.isEmpty()) {
                notFoundCount++
                Log.d("ExerciseDB_SYNC", \"\"\"
                    [ExerciseDB_SYNC]
                    Exercise: ${exercise.name}
                    Search: (empty)
                    Result: NOT_FOUND
                    Reason: No search term configured
                \"\"\".trimIndent())
                continue
            }

            var matched = false
            var matchLog = StringBuilder()
            matchLog.appendLine("Tentativas:")

            for ((attemptIdx, query) in searchQueries.distinct().withIndex()) {
                matchLog.appendLine("${attemptIdx + 1} - $query")
                
                var searchResult = remoteDataSource.searchExercises(query)
                if (searchResult is NetworkResult.HttpError && (searchResult.code == 429 || searchResult.code in 500..504)) {
                    delay(2000)
                    searchResult = remoteDataSource.searchExercises(query)
                }

                if (searchResult is NetworkResult.Success) {
                    val candidates = searchResult.data
                    val evaluation = evaluateCandidates(exercise, candidates)
                    if (evaluation.status == ExerciseMatchStatus.MATCHED) {
                        val best = evaluation.candidate!!
                        val updatedEntity = exercise.copy(
                            externalExerciseId = best.realId,
                            gifUrl = best.gifUrl,
                            lastVerifiedAt = System.currentTimeMillis(),
                            mappingStatus = ExerciseMatchStatus.MATCHED.name
                        )
                        workoutDao.updateExercise(updatedEntity)
                        matchedCount++
                        
                        matchLog.appendLine("Resultado: MATCHED")
                        matchLog.appendLine("ID: ${best.realId}")
                        matchLog.appendLine("GIF: FOUND")
                        
                        Log.d("ExerciseDB_SYNC", \"\"\"
                            [ExerciseDB_SYNC]
                            Exercise: ${exercise.name}
                            $matchLog
                        \"\"\".trimIndent())
                        matched = true
                        break
                    }
                } else if (searchResult is NetworkResult.Offline) {
                    return@withContext MediaSyncResult(
                        isOffline = true,
                        errors = listOf("Conecte-se à internet para atualizar as demonstrações.")
                    )
                }
                delay(280) // rate limit between query attempts
            }

            if (!matched) {
                matchLog.appendLine("Resultado: NOT_FOUND")
                val updatedEntity = exercise.copy(
                    mappingStatus = ExerciseMatchStatus.NOT_FOUND.name
                )
                workoutDao.updateExercise(updatedEntity)
                notFoundCount++
                Log.d("ExerciseDB_SYNC", \"\"\"
                    [ExerciseDB_SYNC]
                    Exercise: ${exercise.name}
                    $matchLog
                \"\"\".trimIndent())
            }

            // Respect rate-limiting before moving to the next exercise
            delay(280)"""

content = re.sub(
    r"val searchQuery = exercise\.exerciseDbSearch \?: exercise\.nameEn \?: exercise\.canonicalId\?\.replace.*?// Respect rate-limiting\n\s*delay\(280\)",
    replacement.strip(),
    content,
    flags=re.DOTALL
)

with open("app/src/main/java/com/example/data/remote/ExerciseMediaRepository.kt", "w") as f:
    f.write(content)
