with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re

# find @Composable}    }        }private fun SettingsToggleItem
text = re.sub(r'@Composable.*?private fun SettingsToggleItem', '@Composable\nprivate fun SettingsToggleItem', text, flags=re.DOTALL)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
