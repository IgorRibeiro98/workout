with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

content = content.replace(
    'val showGifs by viewModel.showGifs.collectAsState()',
    'val showGifs by viewModel.showGifs.collectAsState()\n    val premiumInfo by viewModel.getPremiumInfo(exerciseId).collectAsState(initial = null)'
)

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
    f.write(content)
