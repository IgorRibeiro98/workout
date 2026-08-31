with open('app/src/main/java/com/example/presentation/settings/SettingsScreen.kt', 'r') as f:
    content = f.read()

premium_btn = """
                    BottomSheetActionItem(
                        icon = Icons.Default.Refresh,
                        title = "Sincronizar Manifesto Premium",
                        subtitle = "Carregar informações avançadas do catálogo",
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch {
                                try {
                                    val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v1.json", force = true)
                                    val message = buildString {
                                        if (result.errors.isEmpty()) {
                                            appendLine("Status: Sucesso")
                                        } else {
                                            appendLine("Status: Falha")
                                        }
                                        appendLine("")
                                        val total = result.added + result.updated + result.ignored + result.errors.size
                                        appendLine("Exercícios encontrados: $total")
                                        appendLine("Importados: ${result.added + result.updated}")
                                        appendLine("Falhas: ${result.errors.size}")
                                        appendLine("Ignorados: ${result.ignored}")
                                        appendLine("Versão: 1.0")
                                        
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
                                } catch (e: Exception) {
                                    dialogTitle = "Erro"
                                    dialogMessage = "Erro ao carregar manifesto premium: ${e.message}"
                                }
                                showDialog = true
                            }
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.Refresh,
                        title = "Reimportar Catálogo Canônico","""

content = content.replace(
    'BottomSheetActionItem(\n                        icon = Icons.Default.Refresh,\n                        title = "Reimportar Catálogo Canônico",',
    premium_btn
)

with open('app/src/main/java/com/example/presentation/settings/SettingsScreen.kt', 'w') as f:
    f.write(content)
