with open("app/src/main/java/com/example/domain/engine/ExerciseMediaEngine.kt", "r") as f:
    text = f.read()

import re

# Update MediaLibraryDiagnostic
text = re.sub(
    r'data class MediaLibraryDiagnostic\([\s\S]*?val noMediaCount: Int = 0\n\)',
    'data class MediaLibraryDiagnostic(\n    val totalExercises: Int = 0,\n    val withExerciseDbSearch: Int = 0,\n    val withoutExerciseDbSearch: Int = 0,\n    val matchedCount: Int = 0,\n    val ambiguousCount: Int = 0,\n    val notFoundCount: Int = 0,\n    val gifsCount: Int = 0,\n    val customPhotosCount: Int = 0,\n    val curatedVideosCount: Int = 0,\n    val noMediaCount: Int = 0\n)',
    text
)

# Update getLibraryDiagnostic
text = text.replace(
    'val exercises = dao?.getAllExercisesSync() ?: emptyList()',
    'val exercises = dao?.getAllExercisesSync() ?: emptyList()\n        var withSearch = 0\n        var withoutSearch = 0'
)
text = text.replace(
    'if (!hasCustomPhoto && !hasGif && !hasVideo) {\n                noMedia++\n            }',
    'if (!hasCustomPhoto && !hasGif && !hasVideo) {\n                noMedia++\n            }\n            if (ex.exerciseDbSearch.isNullOrBlank()) withoutSearch++ else withSearch++'
)
text = text.replace(
    'return MediaLibraryDiagnostic(\n            totalExercises = exercises.size,\n            matchedCount = matched,\n            ambiguousCount = ambiguous,\n            notFoundCount = notFound,\n            gifsCount = gifs,\n            customPhotosCount = photos,\n            curatedVideosCount = videos,\n            noMediaCount = noMedia\n        )',
    'return MediaLibraryDiagnostic(\n            totalExercises = exercises.size,\n            withExerciseDbSearch = withSearch,\n            withoutExerciseDbSearch = withoutSearch,\n            matchedCount = matched,\n            ambiguousCount = ambiguous,\n            notFoundCount = notFound,\n            gifsCount = gifs,\n            customPhotosCount = photos,\n            curatedVideosCount = videos,\n            noMediaCount = noMedia\n        )'
)

with open("app/src/main/java/com/example/domain/engine/ExerciseMediaEngine.kt", "w") as f:
    f.write(text)
