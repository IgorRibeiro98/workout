with open('app/src/main/java/com/example/presentation/settings/SettingsScreen.kt', 'r') as f:
    content = f.read()

import_statement = "import com.example.domain.engine.PremiumManifestImporter\n"
if "PremiumManifestImporter" not in content:
    content = content.replace("import com.example.domain.engine.ProgramImporter", import_statement + "import com.example.domain.engine.ProgramImporter")

init_statement = "val premiumImporter = PremiumManifestImporter(db, context)\n"
if "val premiumImporter" not in content:
    content = content.replace("val programImporter = ProgramImporter(db, context)", "val programImporter = ProgramImporter(db, context)\n    " + init_statement)

button_code = """
        SettingsItem(
            title = "Importar Exercícios Premium",
            subtitle = "Sincronizar exercise-content-manifest.v1.json",
            onClick = {
                coroutineScope.launch {
                    val result = premiumImporter.importFromAssets("catalog/exercise-content-manifest.v1.json")
                    val message = buildString {
                        if (result.added > 0) append("${result.added} adicionados. ")
                        if (result.updated > 0) append("${result.updated} atualizados. ")
                        if (result.errors.isNotEmpty()) append("Erros: ${result.errors.joinToString()}.")
                        if (isEmpty()) append("Nenhuma alteração feita.")
                    }
                    dialogTitle = "Importação Premium"
                    dialogMessage = message
                    showDialog = true
                }
            }
        )
"""
content = content.replace(
    'Text("Dados e Importação", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)\n        Spacer(modifier = Modifier.height(16.dp))',
    'Text("Dados e Importação", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 14.sp)\n        Spacer(modifier = Modifier.height(16.dp))' + button_code
)

with open('app/src/main/java/com/example/presentation/settings/SettingsScreen.kt', 'w') as f:
    f.write(content)
