with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re

# We want to replace from "ATUALIZAR DEMONSTRAÇÕES (EXERCISEDB)" up to "TESTAR CONEXÃO EXERCISEDB"
def repl(m):
    return """            SettingsActionItem(
                title = "ATUALIZAR DEMONSTRAÇÕES (EXERCISEDB)",
                icon = Icons.Default.CloudDownload,
                isLoading = isSyncingMedia,
                loadingText = syncProgress,
                onClick = {
                    if (!isSyncingMedia) {
                        isSyncingMedia = true
                        syncProgress = "Consultando catálogo remoto..."
                        coroutineScope.launch {
                            val result = mediaEngine.syncExerciseGifs { cur, tot ->
                                syncProgress = "Verificando exercício $cur de $tot..."
                            }
                            if (!result.isOffline && result.errors.isEmpty()) {
                                settingsManager.setLastMediaSyncAt(System.currentTimeMillis())
                                settingsManager.setMediaSyncContentVersion(1)
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

            HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(8.dp))

            SettingsActionItem(
                title = "TESTAR CONEXÃO EXERCISEDB","""

# Let's match from ATUALIZAR to TESTAR
text = re.sub(r'            SettingsActionItem\(\s+title = "ATUALIZAR DEMONSTRAÇÕES \(EXERCISEDB\)".*?            SettingsActionItem\(\s+title = "TESTAR CONEXÃO EXERCISEDB",', repl, text, flags=re.DOTALL)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
