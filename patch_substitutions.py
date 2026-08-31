with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

substitutions_ui = """
                // Substituições
                if (premium.substitution != null) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Substituições", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.height(8.dp))
                                premium.substitution.sameMovement?.let { sm ->
                                    try {
                                        val arr = JSONArray(sm)
                                        Text("Mesmo movimento:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    } catch (e: Exception) {}
                                }
                                premium.substitution.sameMuscle?.let { sm ->
                                    try {
                                        val arr = JSONArray(sm)
                                        Text("Mesmo grupo muscular:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    } catch (e: Exception) {}
                                }
                                premium.substitution.notRecommended?.let { nr ->
                                    try {
                                        val arr = JSONArray(nr)
                                        Text("Não recomendados:", color = TextSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                        for (i in 0 until arr.length()) {
                                            Text("• ${arr.getString(i)}", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                }
"""

content = content.replace(
    '// Segurança',
    substitutions_ui + '\n                // Segurança'
)

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
    f.write(content)
