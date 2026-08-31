import os

exercise_file = 'app/src/main/java/com/example/data/local/ExerciseEntity.kt'
with open(exercise_file, 'r') as f:
    content = f.read()

if "val bodyRegion: String? = null" not in content:
    content = content.replace("val contentVersion: Int = 0", "val contentVersion: Int = 0,\n    val bodyRegion: String? = null,\n    val trainingGoals: String? = null")
    with open(exercise_file, 'w') as f:
        f.write(content)

progression_file = 'app/src/main/java/com/example/data/local/ExerciseProgressionEntity.kt'
with open(progression_file, 'r') as f:
    content = f.read()

if "val repRangeMin: Int? = null" not in content:
    content = content.replace("val increaseRule: String?", "val increaseRule: String?,\n    val repRangeMin: Int? = null,\n    val repRangeMax: Int? = null,\n    val incrementUpper: Double? = null,\n    val incrementLower: Double? = null")
    with open(progression_file, 'w') as f:
        f.write(content)

media_file = 'app/src/main/java/com/example/data/local/ExerciseMediaEntity.kt'
with open(media_file, 'r') as f:
    content = f.read()

if "val gifSource: String? = null" not in content:
    content = content.replace("val imageUrls: String?", "val imageUrls: String?,\n    val gifSource: String? = null,\n    val videos: String? = null")
    with open(media_file, 'w') as f:
        f.write(content)

