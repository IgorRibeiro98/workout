with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re
audit_button = """                    BottomSheetActionItem(
                        icon = Icons.Default.Analytics,
                        title = "Auditoria ExerciseDB",
                        subtitle = "Verificar cobertura do catálogo",
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch {
                                val diag = mediaEngine.getLibraryDiagnostic()
                                dialogTitle = "Auditoria ExerciseDB"
                                dialogMessage = "Total exercícios: ${diag.totalExercises}\\n\\n" +
                                        "Com exerciseDbSearch: ${diag.withExerciseDbSearch}\\n" +
                                        "Sem exerciseDbSearch: ${diag.withoutExerciseDbSearch}\\n\\n" +
                                        "Mapeados: ${diag.matchedCount}\\n" +
                                        "Ambíguos: ${diag.ambiguousCount}\\n" +
                                        "Não encontrados: ${diag.notFoundCount}"
                                showDialog = true
                            }
                        }
                    )
"""
text = text.replace('Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {', 'Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n' + audit_button)

# Also need to import Icons.Default.Analytics
text = text.replace('import androidx.compose.material.icons.filled.Refresh', 'import androidx.compose.material.icons.filled.Refresh\nimport androidx.compose.material.icons.filled.Analytics')

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
