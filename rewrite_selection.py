with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re

# find SelectionBottomSheet blocks
text = re.sub(r'SelectionBottomSheet\([\s\S]*?\n\s*\)', 'SelectionBottomSheet(\n            title = "Descanso",\n            options = listOf("Desativado" to 0),\n            selectedOption = "Desativado" to 0,\n            optionTitle = { it.first },\n            onOptionSelected = { activeSheet = null },\n            onDismissRequest = { activeSheet = null }\n        )', text)

text = text.replace("import androidx.compose.foundation.layout.*", "import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.background")

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
