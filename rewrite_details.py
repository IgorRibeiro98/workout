import re

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

# I will find the index of "// Exercise Overview Card" and "// Video Guide Card - ONLY shown if curated video mapping exists"

idx_start = content.find("            // Exercise Overview Card")
idx_end = content.find("            // Video Guide Card - ONLY shown if curated video mapping exists")

if idx_start != -1 and idx_end != -1:
    before = content[:idx_start]
    after = content[idx_end:]

    # Now I will write the new section based on the user's layout.
    new_section = """
            // PREMIUM CONTENT & OVERVIEW
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val premium = premiumInfo

                    // Section: Sobre
                    Text("Sobre", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
                    HorizontalDivider(color = SurfaceHighlight)
                    if (!resolvedNotes.isNullOrBlank() || exerciseInfo?.shortDescription != null) {
                        Text(
                            text = resolvedNotes ?: exerciseInfo?.shortDescription ?: "",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                    if (!primaryMuscle.isNullOrEmpty()) {
                        Text("Músculos: ${primaryMuscle}" + if (secondaryMuscles.isNotEmpty()) ", ${secondaryMuscles.joinToString(", ")}" else "", color = TextSecondary, fontSize = 14.sp)
                    }
                    if (!equipment.isNullOrEmpty()) {
                        Text("Equipamento: $equipment", color = TextSecondary, fontSize = 14.sp)
                    }
                    exerciseInfo?.difficulty?.let { Text("Dificuldade: $it", color = TextSecondary, fontSize = 14.sp) }

                    // Section: Como executar
                    if (premium?.execution != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Como executar", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        
                        premium.execution.setup?.let { Text("Preparação: $it", color = TextSecondary, fontSize = 14.sp) }
                        
                        premium.execution.steps?.let { steps ->
                            val stepsList = try {
                                val arr = org.json.JSONArray(steps)
                                (0 until arr.length()).map { arr.getString(it) }
                            } catch(e: Exception) { emptyList<String>() }
                            
                            if (stepsList.isNotEmpty()) {
                                stepsList.forEachIndexed { i, step ->
                                    Text("${i+1}. $step", color = TextSecondary, fontSize = 14.sp)
                                }
                            }
                        }
                        
                        premium.execution.breathing?.let { Text("Respiração: $it", color = TextSecondary, fontSize = 14.sp) }
                    }

                    // Section: Dicas
                    if (premium?.education?.tips != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Dicas", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        val tipsList = try { val arr = org.json.JSONArray(premium.education.tips!!); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                        tipsList.forEach { tip ->
                            Text("• $tip", color = TextSecondary, fontSize = 14.sp)
                        }
                    }

                    // Section: Erros comuns
                    if (premium?.education?.commonMistakes != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Erros comuns", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        val mistakesList = try { val arr = org.json.JSONArray(premium.education.commonMistakes!!); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                        mistakesList.forEach { mistake ->
                            Text("• $mistake", color = TextSecondary, fontSize = 14.sp)
                        }
                    }

                    // Section: Progressão
                    if (premium?.progression != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Progressão", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        premium.progression.repRange?.let { Text("Faixa recomendada: $it", color = TextSecondary, fontSize = 14.sp) }
                        premium.progression.increaseRule?.let { Text("Regra de evolução: $it", color = TextSecondary, fontSize = 14.sp) }
                    }
                    
                    // Section: Alternativas
                    if (premium?.substitution != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Alternativas", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        premium.substitution.sameMovement?.let { sm ->
                            val smList = try { val arr = org.json.JSONArray(sm); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            if (smList.isNotEmpty()) {
                                Text("Mesmo movimento: ${smList.joinToString(", ")}", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        premium.substitution.sameMuscle?.let { sm ->
                            val smList = try { val arr = org.json.JSONArray(sm); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            if (smList.isNotEmpty()) {
                                Text("Mesmo grupo muscular: ${smList.joinToString(", ")}", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }

                    // Section: Cuidados
                    if (premium?.safety != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Cuidados", color = Lime400, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        HorizontalDivider(color = SurfaceHighlight)
                        premium.safety.riskLevel?.let { Text("Nível de risco: $it", color = TextSecondary, fontSize = 14.sp) }
                        premium.safety.attentionPoints?.let { points ->
                            val pointsList = try { val arr = org.json.JSONArray(points); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            pointsList.forEach { p ->
                                Text("• $p", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        premium.safety.commonDiscomforts?.let { points ->
                            val pointsList = try { val arr = org.json.JSONArray(points); (0 until arr.length()).map { arr.getString(it) } } catch(e: Exception) { emptyList<String>() }
                            if (pointsList.isNotEmpty()) {
                                Text("Desconfortos comuns: ${pointsList.joinToString(", ")}", color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }

"""
    with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
        f.write(before + new_section + after)
