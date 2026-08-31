import re

with open("app/src/main/java/com/example/data/local/Entities.kt", "r") as f:
    content = f.read()

content = content.replace(
    "val exerciseDbSearch: String? = null,",
    "val exerciseDbSearch: String? = null,\n    val exerciseDbAliases: String? = null,"
)

with open("app/src/main/java/com/example/data/local/Entities.kt", "w") as f:
    f.write(content)

with open("app/src/main/java/com/example/domain/model/ResolvedExercise.kt", "r") as f:
    content = f.read()

content = content.replace(
    "val exerciseDbSearch: String? = null,",
    "val exerciseDbSearch: String? = null,\n    val exerciseDbAliases: String? = null,"
)

with open("app/src/main/java/com/example/domain/model/ResolvedExercise.kt", "w") as f:
    f.write(content)
