import os

with open('app/src/main/java/com/example/data/local/Entities.kt', 'r') as f:
    content = f.read()

if "val bodyRegion: String? = null" not in content:
    content = content.replace("val contentVersion: Int = 0\n)", "val contentVersion: Int = 0,\n    val bodyRegion: String? = null,\n    val trainingGoals: String? = null\n)")
    with open('app/src/main/java/com/example/data/local/Entities.kt', 'w') as f:
        f.write(content)

with open('app/src/main/java/com/example/data/local/PremiumEntities.kt', 'r') as f:
    content = f.read()

if "val repRangeMin: Int? = null" not in content:
    content = content.replace("val increaseRule: String?\n)", "val increaseRule: String?,\n    val repRangeMin: Int? = null,\n    val repRangeMax: Int? = null,\n    val incrementUpper: Double? = null,\n    val incrementLower: Double? = null\n)")
    content = content.replace("val imageUrls: String?\n)", "val imageUrls: String?,\n    val gifSource: String? = null,\n    val videos: String? = null\n)")
    with open('app/src/main/java/com/example/data/local/PremiumEntities.kt', 'w') as f:
        f.write(content)
