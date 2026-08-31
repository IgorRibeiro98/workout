with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

# I will replace the newlines inside the strings with \\n
import re
# Wait, let's just do a manual string replacement for the broken parts:
text = text.replace('dialogMessage = result.errors.joinToString("\\n")', 'dialogMessage = result.errors.joinToString("\\n")')

# Actually, the file has literal newlines inside "..."
# Let's see how it looks
