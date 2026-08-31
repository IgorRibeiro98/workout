import re

with open('app/src/main/java/com/example/data/local/Entities.kt', 'r') as f:
    content = f.read()

# I will add to ExerciseEntity
if "val bodyRegion: String?" not in content.split("@Entity(tableName = \"workout_programs\"")[0]:
    content = content.replace("val exerciseType: String? = null", "val exerciseType: String? = null,\n    val bodyRegion: String? = null,\n    val trainingGoals: String? = null")
    with open('app/src/main/java/com/example/data/local/Entities.kt', 'w') as f:
        f.write(content)
