with open('app/src/main/java/com/example/data/local/Entities.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'val mappingStatus: String? = null\n)',
    'val mappingStatus: String? = null,\n    val shortDescription: String? = null,\n    val category: String? = null,\n    val difficulty: String? = null,\n    val exerciseType: String? = null\n)'
)

with open('app/src/main/java/com/example/data/local/Entities.kt', 'w') as f:
    f.write(content)
