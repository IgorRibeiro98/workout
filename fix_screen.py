import re

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

# I will rewrite the Premium UI section to correctly parse JSON without Try/Catch around composables.
# Also fix the 'premiumInfo' variable reference if needed.

# Wait, `val premium = premiumInfo!!` is inside `if (premiumInfo != null)`. Why is `premium.execution` unresolved?
# Ah, `PremiumExerciseInfo` was defined in `com.example.presentation.exercises`, so it should be visible. But maybe the state was named differently?
# Let's just create a completely clean PREMIUM CONTENT block.

new_premium_ui = """
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
                                premium.execution.setup?.let { Text("Preparação: $it", color = TextSecondary, fontSize = 14.sp); Spacer(Modifier.height(4.dp)) }
                                premium.execution.steps?.let { steps ->
                                    val stepsList = remember(steps) {
                                        try {
                                            val arr = JSONArray(steps)
                                            (0 until arr.length()).map { arr.getString(it) }
                                        } catch(e: Exception) { emptyList<String>() }
                                    }
                                    if (stepsList.isNotEmpty()) {
                                        Text("Passo a passo:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        stepsList.forEachIndexed { i, step ->
                                            Text("${i+1}. $step", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                premium.execution.breathing?.let { Text("Respiração: $it", color = TextSecondary, fontSize = 14.sp) }
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
                                premium.education.tips?.let { tips ->
                                    val tipsList = remember(tips) {
                                        try { val arr = JSONArray(tips); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                                    }
                                    if (tipsList.isNotEmpty()) {
                                        Text("Dicas:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        tipsList.forEach { tip ->
                                            Text("• $tip", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                premium.education.commonMistakes?.let { mistakes ->
                                    val mistakesList = remember(mistakes) {
                                        try { val arr = JSONArray(mistakes); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                                    }
                                    if (mistakesList.isNotEmpty()) {
                                        Text("Erros comuns:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        mistakesList.forEach { mistake ->
                                            Text("• $mistake", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    }
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
                                premium.progression.repRange?.let { Text("Faixa recomendada: $it", color = TextSecondary, fontSize = 14.sp); Spacer(Modifier.height(4.dp)) }
                                premium.progression.increaseRule?.let { Text("Regra de evolução: $it", color = TextSecondary, fontSize = 14.sp) }
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
                                premium.substitution.sameMovement?.let { sm ->
                                    val smList = remember(sm) {
                                        try { val arr = JSONArray(sm); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                                    }
                                    if (smList.isNotEmpty()) {
                                        Text("Mesmo movimento:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        smList.forEach { text ->
                                            Text("• $text", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                                premium.substitution.sameMuscle?.let { sm ->
                                    val smList = remember(sm) {
                                        try { val arr = JSONArray(sm); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                                    }
                                    if (smList.isNotEmpty()) {
                                        Text("Mesmo grupo muscular:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        smList.forEach { text ->
                                            Text("• $text", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
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
                                premium.safety.attentionPoints?.let { points ->
                                    val pointsList = remember(points) {
                                        try { val arr = JSONArray(points); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                                    }
                                    if (pointsList.isNotEmpty()) {
                                        Text("Pontos de atenção:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        pointsList.forEach { point ->
                                            Text("• $point", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
"""

start_marker = "// PREMIUM CONTENT"
end_marker = "// END PREMIUM CONTENT"

pattern = re.compile(f"{start_marker}.*?{end_marker}", re.DOTALL)
content = pattern.sub(new_premium_ui, content)

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
    f.write(content)
