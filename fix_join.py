with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re
text = re.sub(
    r'dialogMessage = result\.errors\.joinToString\("\n"\)',
    'dialogMessage = result.errors.joinToString("\\\\n")',
    text
)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
