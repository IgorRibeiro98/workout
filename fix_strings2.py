with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    lines = f.readlines()

out = []
for line in lines:
    if line.strip().startswith('append("') and not line.strip().endswith('\\n")'):
        line = line.replace('")', '\\n")')
    
    if "Não foi possível conectar ao ExerciseDB." in line:
        line = line.replace("ExerciseDB.Verifique", "ExerciseDB.\\n\\nVerifique")
        
    out.append(line)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.writelines(out)
