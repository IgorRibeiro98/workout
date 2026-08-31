with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re
text = re.sub(r'dialogMessage = if \(result\.isOffline\).*?var isTestingApi by remember', 
              r'''dialogMessage = if (result.isOffline) {
                                "Não foi possível conectar ao ExerciseDB.\n\nVerifique a conexão de internet. Todo o treino continua funcionando 100% offline."
                            } else {
                                buildString {
                                    append("Cobertura de demonstrações\n\n")
                                    append("GIF: ${diag.gifCount}\n")
                                    append("Fotos: ${diag.customPhotoCount}\n")
                                    append("Vídeos YouTube: ${diag.youtubeCount}\n")
                                    append("Sem mídia: ${diag.noMediaCount}\n\n")
                                    
                                    append("Resultado da Sincronização:\n")
                                    append("• Mapeados: ${result.matched}\n")
                                    append("• Ambíguos: ${result.ambiguous}\n")
                                    append("• Já atualizados: ${result.alreadyUpToDate}\n")
                                    append("• Não encontrados: ${result.notFound}\n")
                                    if (result.errors.isNotEmpty()) {
                                        append("\n(Total de erros: ${result.errors.size})")
                                    }
                                }
                            }
                        }
                    }
                }
            )

            HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 16.dp))

            // Testar API
            var isTestingApi by remember''', text, flags=re.DOTALL)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
