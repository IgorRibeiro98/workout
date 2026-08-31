import re

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

# I will replace the old 'item' blocks of Premium Content and Overview Card
# Let's write a python script that will use a regex to replace everything from `// Exercise Overview Card` to the end of the `Scaffold`.

# We will just rewrite the `LazyColumn` and below.

match = re.search(r'// Exercise Overview Card.*', content, re.DOTALL)
if match:
    pass

