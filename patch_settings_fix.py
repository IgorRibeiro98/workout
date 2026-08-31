import re

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    content = f.read()

# Fix dialogMessage
replacement = """dialogMessage = if (result.isOffline) {
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
                            }"""

content = re.sub(
    r"dialogMessage = if \(result\.isOffline\).*?                            }",
    replacement,
    content,
    flags=re.DOTALL
)

# Fix getAllExercisesList
content = content.replace("db.workoutDao().getAllExercises()", "db.workoutDao().getAllExercisesList()")
# Fix isCanonical
content = content.replace("isCanonical()", "canonicalId != null && !isUserCreated")

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(content)
