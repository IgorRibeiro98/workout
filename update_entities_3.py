import re

with open('app/src/main/java/com/example/data/local/Entities.kt', 'r') as f:
    content = f.read()

# Let's ensure bodyRegion is in the constructor of ExerciseEntity
if "val bodyRegion: String?" not in content:
    content = content.replace("val contentVersion: Int = 0", "val contentVersion: Int = 0,\n    val bodyRegion: String? = null,\n    val trainingGoals: String? = null")
    with open('app/src/main/java/com/example/data/local/Entities.kt', 'w') as f:
        f.write(content)

with open('app/src/main/java/com/example/data/local/PremiumEntities.kt', 'r') as f:
    content = f.read()

if "val repRangeMin: Int?" not in content:
    content = content.replace("val increaseRule: String? = null", "val increaseRule: String? = null,\n    val repRangeMin: Int? = null,\n    val repRangeMax: Int? = null,\n    val incrementUpper: Double? = null,\n    val incrementLower: Double? = null")
    content = content.replace("val imageUrls: String? = null // JSON list", "val imageUrls: String? = null, // JSON list\n    val gifSource: String? = null,\n    val videos: String? = null")
    with open('app/src/main/java/com/example/data/local/PremiumEntities.kt', 'w') as f:
        f.write(content)
