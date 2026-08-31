import re

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    content = f.read()

# First, strip out the messed up part
match = re.search(r'dialogMessage = if \(result\.isOffline\).*?isTestingApi by remember', content, re.DOTALL)
if match:
    bad_text = match.group(0)
    
    clean_text = """dialogMessage = if (result.isOffline) {
                                "Não foi possível conectar ao ExerciseDB.\\n\\nVerifique a conexão de internet. Todo o treino continua funcionando 100% offline."
                            } else {
                                buildString {
                                    append("Cobertura de demonstrações\\n\\n")
                                    append("GIF: ${diag.gifCount}\\n")
                                    append("Fotos: ${diag.customPhotoCount}\\n")
                                    append("Vídeos YouTube: ${diag.youtubeCount}\\n")
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
                        }
                    }
                }
            )

            HorizontalDivider(color = BorderLight, modifier = Modifier.padding(horizontal = 16.dp))

            // Testar API
            var isTestingApi by remember"""

    content = content.replace(bad_text, clean_text)
    
with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(content)
