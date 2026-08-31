with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re

def repl(m):
    return """            SettingsActionItem(
                title = "SINCRONIZAR DEMONSTRAÇÕES (EXERCISEDB)",
                icon = Icons.Default.CloudDownload,
                isLoading = isSyncingMedia,
                loadingText = "Sincronizando...",
                onClick = {
                    if (!isSyncingMedia) {
                        isSyncingMedia = true
                        coroutineScope.launch {
                            val result = mediaEngine.syncMissingExercises { current, total ->
                                // Progress could be shown if we have a state for it
                            }
                            val diag = mediaEngine.getLibraryDiagnostic()
                            isSyncingMedia = false
                            dialogTitle = "Sincronização de Demonstrações"
                            dialogMessage = if (result.isOffline) {
                                "Não foi possível conectar ao ExerciseDB.\\n\\nVerifique a conexão de internet. Todo o treino continua funcionando 100% offline."
                            } else {
                                buildString {
                                    append("Cobertura de demonstrações\\n\\n")
                                    append("GIF: ${diag.gifsCount}\\n")
                                    append("Fotos: ${diag.customPhotosCount}\\n")
                                    append("Vídeos YouTube: ${diag.curatedVideosCount}\\n")
                                    append("Sem mídia: ${diag.noMediaCount}\\n\\n")
                                    
                                    append("Resultado da Sincronização:\\n")
                                    append("• Mapeados: ${result.matched}\\n")
                                    append("• Ambíguos: ${result.ambiguous}\\n")
                                    append("• Já atualizados: ${result.alreadyUpToDate}\\n")
                                    append("• Não encontrados: ${result.notFound}\\n")
                                    if (result.errors.isNotEmpty()) {
                                        append("\\n(Total de erros: ${result.errors.size})")
                                    }
                                }
                            }
                            showDialog = true
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            SettingsActionItem(
                title = "TESTAR CONEXÃO EXERCISEDB",
"""

text = re.sub(r'            SettingsActionItem\(\s+title = "SINCRONIZAR DEMONSTRAÇÕES \(EXERCISEDB\)".*?            SettingsActionItem\(\s+title = "TESTAR CONEXÃO EXERCISEDB",', repl, text, flags=re.DOTALL)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
