with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re
text = re.sub(
    r'dialogMessage = "Catálogo canônico sincronizado com sucesso![\s\S]*?Alternativas vinculadas: \$\{result.alternativesAdded\}"',
    r'dialogMessage = "Catálogo canônico sincronizado com sucesso!\\n\\n144 exercícios processados de forma transacional.\\n\\nNovos adicionados: ${result.added}\\nAtualizados: ${result.updated}\\nInalterados: ${result.unchanged}\\nAlternativas vinculadas: ${result.alternativesAdded}"',
    text
)
with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
