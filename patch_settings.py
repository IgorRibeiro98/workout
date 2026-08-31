import re

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    content = f.read()

# Add state variables
state_vars = """
    var activeSheet by remember { mutableStateOf<SettingsSheetType?>(null) }
    var auditReport by remember { mutableStateOf<String?>(null) }
"""
content = re.sub(r"var activeSheet by remember \{ mutableStateOf<SettingsSheetType\?>\(null\) \}", state_vars.strip(), content)

# Add Audit action item
action_item = """
                        BottomSheetActionItem(
                            title = "Auditoria ExerciseDB",
                            selected = false,
                            onClick = {
                                activeSheet = null
                                coroutineScope.launch {
                                    val exercises = viewModel.getAllExercises()
                                    val canonicals = exercises.filter { it.isCanonical() }
                                    val total = canonicals.size
                                    val withSearch = canonicals.count { !it.exerciseDbSearch.isNullOrBlank() }
                                    val withoutSearch = total - withSearch
                                    val matched = canonicals.count { it.mappingStatus == com.example.domain.model.ExerciseMatchStatus.MATCHED.name }
                                    val ambiguous = canonicals.count { it.mappingStatus == com.example.domain.model.ExerciseMatchStatus.AMBIGUOUS.name }
                                    val notFound = canonicals.count { it.mappingStatus == com.example.domain.model.ExerciseMatchStatus.NOT_FOUND.name }
                                    
                                    val withoutSearchList = canonicals.filter { it.exerciseDbSearch.isNullOrBlank() }.take(10).joinToString("\\n") { "• ${it.name}" }
                                    
                                    val report = \"\"\"
                                        Total exercícios (canônicos): $total
                                        
                                        Com exerciseDbSearch: $withSearch
                                        Sem exerciseDbSearch: $withoutSearch
                                        
                                        Mapeados (MATCHED): $matched
                                        Ambíguos: $ambiguous
                                        Não encontrados: $notFound
                                        
                                        Sem termo de busca (Exemplos):
                                        $withoutSearchList
                                    \"\"\".trimIndent()
                                    auditReport = report
                                }
                            }
                        )
                        
                        BottomSheetActionItem(
                            title = "Reimportar Catálogo Canônico (144 exercícios)",
"""
content = content.replace("BottomSheetActionItem(\n                            title = \"Reimportar Catálogo Canônico (144 exercícios)\",", action_item.strip())

# Add Audit Dialog
dialog = """
    if (auditReport != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { auditReport = null },
            title = { Text("Auditoria ExerciseDB") },
            text = { Text(auditReport ?: "") },
            confirmButton = {
                TextButton(onClick = { auditReport = null }) {
                    Text("Fechar")
                }
            }
        )
    }

    if (activeSheet != null) {
"""
content = content.replace("if (activeSheet != null) {", dialog.strip())

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(content)
