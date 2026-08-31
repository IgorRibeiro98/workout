with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re
lines = text.split('\n')
for i, line in enumerate(lines):
    if "private fun SettingsToggleItem" in line:
        # We need to insert closing braces before this line
        # Let's insert 4 closing braces
        lines.insert(i, "        }")
        lines.insert(i, "    }")
        lines.insert(i, "}")
        break

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write('\n'.join(lines))
