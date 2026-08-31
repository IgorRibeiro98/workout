import re

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    content = f.read()

# Fix isUserCreated
content = content.replace("!isUserCreated }", "!it.isUserCreated }")

# Let's fix the dialogMessage completely
start_marker = 'dialogMessage = if (result.isOffline) {'
end_marker = 'if (result.errors.isNotEmpty()) {\n                                        append("(Total de erros: ${result.errors.size})")\n                                    }\n                                }\n                            }'

new_block = '''dialogMessage = if (result.isOffline) {
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
                            }'''

# Since we don't know exactly what broken lines are in there, let's just do a regex replace
content = re.sub(
    r"dialogMessage = if \(result\.isOffline\).*?if \(result\.errors\.isNotEmpty\(\)\).*?\}",
    new_block,
    content,
    flags=re.DOTALL
)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(content)
