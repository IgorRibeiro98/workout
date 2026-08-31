with open("app/src/main/java/com/example/domain/engine/ManifestImporter.kt", "r") as f:
    content = f.read()

replacement = """                    val aliasesStr = if (aliasesList.isNotEmpty()) aliasesList.joinToString(",") else null

                    val exDbAliasesArr = exObj.optJSONArray("exerciseDbAliases")
                    val exDbAliasesList = mutableListOf<String>()
                    if (exDbAliasesArr != null) {
                        for (j in 0 until exDbAliasesArr.length()) {
                            exDbAliasesList.add(exDbAliasesArr.getString(j))
                        }
                    }
                    val exDbAliasesStr = if (exDbAliasesList.isNotEmpty()) exDbAliasesList.joinToString(",") else null"""

content = content.replace(
    "val aliasesStr = if (aliasesList.isNotEmpty()) aliasesList.joinToString(\",\") else null",
    replacement
)

content = content.replace(
    "exerciseDbSearch = exObj.optString(\"exerciseDbSearch\", null),",
    "exerciseDbSearch = exObj.optString(\"exerciseDbSearch\", null),\n                        exerciseDbAliases = exDbAliasesStr,"
)

with open("app/src/main/java/com/example/domain/engine/ManifestImporter.kt", "w") as f:
    f.write(content)
