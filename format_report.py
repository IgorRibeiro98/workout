with open('app/src/main/java/com/example/presentation/settings/SettingsScreen.kt', 'r') as f:
    content = f.read()

import_logic = """
                    val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v1.json", force = true)
                    val message = buildString {
                        if (result.errors.isEmpty()) {
                            appendLine("Status: Sucesso")
                        } else {
                            appendLine("Status: Falha")
                        }
                        appendLine("")
                        appendLine("Exercícios encontrados: ${result.added + result.updated + result.ignored + result.errors.size}")
                        appendLine("Importados: ${result.added + result.updated}")
                        appendLine("Falhas: ${result.errors.size}")
                        appendLine("Ignorados: ${result.ignored}")
                        
                        if (result.errors.isNotEmpty()) {
                            appendLine("")
                            appendLine("Lista de erros:")
                            result.errors.forEach { err ->
                                appendLine("- $err")
                            }
                        }
                    }
                    dialogTitle = "Exercise Premium Import"
                    dialogMessage = message
                    showDialog = true
"""

content = content.replace(
    """val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v1.json")
                    val message = buildString {
                        if (result.added > 0) append("${result.added} adicionados. ")
                        if (result.updated > 0) append("${result.updated} atualizados. ")
                        if (result.errors.isNotEmpty()) append("Erros: ${result.errors.joinToString()}.")
                        if (isEmpty()) append("Nenhuma alteração feita.")
                    }
                    dialogTitle = "Importação Premium"
                    dialogMessage = message
                    showDialog = true""",
    import_logic
)

with open('app/src/main/java/com/example/presentation/settings/SettingsScreen.kt', 'w') as f:
    f.write(content)
