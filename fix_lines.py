with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    lines = f.readlines()

out = lines[:289] + lines[345:]

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.writelines(out)
