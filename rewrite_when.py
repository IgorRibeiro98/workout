with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re

# We will just replace the entire 'when (activeSheet) { ... }' block
new_when_block = """    when (activeSheet) {
        is SettingsSheetType.RestBetweenSets -> {
            SelectionBottomSheet(
                title = "Descanso entre séries",
                options = listOf(
                    "Desativado" to 0,
                    "30 segundos" to 30,
                    "45 segundos" to 45,
                    "60 segundos (1 min)" to 60,
                    "90 segundos (1.5 min)" to 90,
                    "120 segundos (2 min)" to 120,
                    "180 segundos (3 min)" to 180
                ),
                selectedOption = listOf("Desativado" to 0, "30 segundos" to 30, "45 segundos" to 45, "60 segundos (1 min)" to 60, "90 segundos (1.5 min)" to 90, "120 segundos (2 min)" to 120, "180 segundos (3 min)" to 180).find { it.second == defaultRestSecs },
                optionTitle = { it.first },
                onOptionSelected = { 
                    coroutineScope.launch { settingsManager.setDefaultRestSeconds(it.second) }
                    activeSheet = null
                },
                onDismissRequest = { activeSheet = null }
            )
        }
        is SettingsSheetType.RestBetweenExercises -> {
             SelectionBottomSheet(
                title = "Descanso entre exercícios",
                options = listOf(
                    "Desativado" to 0,
                    "60 segundos (1 min)" to 60,
                    "90 segundos (1.5 min)" to 90,
                    "120 segundos (2 min)" to 120,
                    "150 segundos (2.5 min)" to 150,
                    "180 segundos (3 min)" to 180,
                    "240 segundos (4 min)" to 240
                ),
                selectedOption = listOf("Desativado" to 0, "60 segundos (1 min)" to 60, "90 segundos (1.5 min)" to 90, "120 segundos (2 min)" to 120, "150 segundos (2.5 min)" to 150, "180 segundos (3 min)" to 180, "240 segundos (4 min)" to 240).find { it.second == defaultExerciseRestSecs },
                optionTitle = { it.first },
                onOptionSelected = { 
                    coroutineScope.launch { settingsManager.setDefaultExerciseRestSeconds(it.second) }
                    activeSheet = null
                },
                onDismissRequest = { activeSheet = null }
            )
        }
        is SettingsSheetType.ManageData -> {
            AppModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                title = "Gerenciar Dados"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BottomSheetActionItem(
                        icon = Icons.Default.Refresh,
                        title = "Reimportar Catálogo Canônico",
                        subtitle = "Restaura os 144 exercícios oficiais",
                        onClick = {
                            activeSheet = SettingsSheetType.ConfirmReimportCatalog
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.UploadFile,
                        title = "Importar exercícios (JSON)",
                        subtitle = "Adicionar ou atualizar catálogo a partir de um arquivo",
                        onClick = {
                            importLauncher.launch("application/json")
                            activeSheet = null
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.UploadFile,
                        title = "Importar programa de treino",
                        subtitle = "Carregar rotina no formato GymLog",
                        onClick = {
                            programImportLauncher.launch("application/json")
                            activeSheet = null
                        }
                    )
                    BottomSheetActionItem(
                        icon = Icons.Default.FileDownload,
                        title = "Exportar todos os dados",
                        subtitle = "Salvar backup em formato legível",
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch { exportEngine.exportData() }
                        }
                    )
                }
            }
        }
        is SettingsSheetType.ConfirmReimportCatalog -> {
            AppModalBottomSheet(
                onDismissRequest = { activeSheet = null },
                title = "Reimportar Catálogo Canônico",
                subtitle = "Base canônica de 144 exercícios"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "A base oficial de exercícios será sincronizada. Seus treinos, notas e personalizações de exercícios existentes serão totalmente preservados.",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Button(
                        onClick = {
                            activeSheet = null
                            coroutineScope.launch {
                                try {
                                    val json = context.assets.open("catalog/catalogo_exercicios_base_ptbr.v1.json").bufferedReader().use { it.readText() }
                                    val result = manifestImporter.importFromJsonString(json, force = true)
                                    if (result.errors.isNotEmpty()) {
                                        dialogTitle = "Avisos/Erros no Catálogo"
                                        dialogMessage = result.errors.joinToString("\\n")
                                    } else {
                                        dialogTitle = "Catálogo Canônico"
                                        dialogMessage = "Catálogo canônico sincronizado com sucesso!\\n\\n144 exercícios processados de forma transacional.\\n\\nNovos adicionados: ${result.added}\\nAtualizados: ${result.updated}\\nInalterados: ${result.unchanged}\\nAlternativas vinculadas: ${result.alternativesAdded}"
                                    }
                                } catch (e: Exception) {
                                    dialogTitle = "Erro"
                                    dialogMessage = "Erro ao carregar catálogo: ${e.message}"
                                }
                                showDialog = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Lime400, contentColor = com.example.ui.theme.BackgroundDark),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("REIMPORTAR AGORA", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    TextButton(
                        onClick = { activeSheet = null },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("CANCELAR", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        else -> {}
    }"""

text = re.sub(r'when \(activeSheet\) \{.*?(?=\n\s*if \(showDialog\))', new_when_block + "\n    ", text, flags=re.DOTALL)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
