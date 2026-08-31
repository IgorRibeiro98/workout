with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

import_statement = "import com.example.presentation.exercises.PremiumExerciseInfo\nimport org.json.JSONArray\n"
if "import com.example.presentation.exercises.PremiumExerciseInfo" not in content:
    content = content.replace("import com.example.presentation.exercises.ExerciseHistoryItem", import_statement + "import com.example.presentation.exercises.ExerciseHistoryItem")

if "import org.json.JSONArray" not in content:
    content = content.replace("package com.example.presentation.exercises", "package com.example.presentation.exercises\n" + import_statement)


state_declaration = """
    val premiumInfo by viewModel.getPremiumInfo(exerciseId).collectAsState(initial = null)
"""

if "val premiumInfo" not in content:
    content = content.replace(
        'val showGifsEnabled by viewModel.showGifs.collectAsState()',
        'val showGifsEnabled by viewModel.showGifs.collectAsState()\n    val premiumInfo by viewModel.getPremiumInfo(exerciseId).collectAsState(initial = null)'
    )

premium_ui = """
            // PREMIUM CONTENT
            if (premiumInfo != null) {
                val premium = premiumInfo!!
                item {
                    Text("Conteúdo Premium", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                }
                
                // Sobre o exercício
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Sobre o exercício", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            exerciseInfo?.shortDescription?.let { desc ->
                                Text(desc, color = TextSecondary, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                            }
                            Text("Músculos: ${exerciseInfo?.primaryMuscle ?: ""}", color = TextSecondary, fontSize = 14.sp)
                            Text("Equipamento: ${exerciseInfo?.equipment ?: ""}", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                }

                // Como executar
                if (premium.execution != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Como executar", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                premium.execution?.setup?.let { Text("Preparação: $it", color = TextSecondary, fontSize = 14.sp); Spacer(Modifier.height(4.dp)) }
                                premium.execution?.steps?.let { steps ->
                                    try {
                                        val arr = JSONArray(steps)
                                        Text("Passo a passo:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("${i+1}. ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    } catch (e: Exception) {}
                                    Spacer(Modifier.height(4.dp))
                                }
                                premium.execution?.breathing?.let { Text("Respiração: $it", color = TextSecondary, fontSize = 14.sp) }
                            }
                        }
                    }
                }

                // Dicas do treinador
                if (premium.education != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Dicas do treinador", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                premium.education?.tips?.let { tips ->
                                    try {
                                        val arr = JSONArray(tips)
                                        Text("Dicas:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    } catch (e: Exception) {}
                                    Spacer(Modifier.height(4.dp))
                                }
                                premium.education?.commonMistakes?.let { mistakes ->
                                    try {
                                        val arr = JSONArray(mistakes)
                                        Text("Erros comuns:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }

                // Progressão
                if (premium.progression != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Progressão", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                premium.progression?.repRange?.let { Text("Faixa recomendada: $it", color = TextSecondary, fontSize = 14.sp); Spacer(Modifier.height(4.dp)) }
                                premium.progression?.increaseRule?.let { Text("Regra de evolução: $it", color = TextSecondary, fontSize = 14.sp) }
                            }
                        }
                    }
                }

                // Substituições
                if (premium.substitution != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Substituições", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                premium.substitution?.sameMovement?.let { sm ->
                                    try {
                                        val arr = JSONArray(sm)
                                        Text("Mesmo movimento:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    } catch (e: Exception) {}
                                }
                                premium.substitution?.sameMuscle?.let { sm ->
                                    try {
                                        val arr = JSONArray(sm)
                                        Text("Mesmo grupo muscular:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }

                // Segurança
                if (premium.safety != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Segurança", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                premium.safety?.attentionPoints?.let { points ->
                                    try {
                                        val arr = JSONArray(points)
                                        Text("Pontos de atenção:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }
            }
            // END PREMIUM CONTENT
"""

if "PREMIUM CONTENT" not in content:
    content = content.replace(
        '            // Video Guide Card',
        premium_ui + '\n            // Video Guide Card'
    )

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
    f.write(content)
