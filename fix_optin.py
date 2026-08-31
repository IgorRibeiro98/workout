with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

text = text.replace("@Composable\nfun SettingsScreen() {", "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun SettingsScreen() {")

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
