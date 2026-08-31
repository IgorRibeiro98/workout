with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'Text("Equipamento: ${exerciseInfo?.equipment ?: ""}", color = TextSecondary, fontSize = 14.sp)',
    'Text("Equipamento: ${exerciseInfo?.equipment ?: ""}", color = TextSecondary, fontSize = 14.sp)\n                            exerciseInfo?.difficulty?.let { Text("Dificuldade: $it", color = TextSecondary, fontSize = 14.sp) }'
)

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
    f.write(content)
