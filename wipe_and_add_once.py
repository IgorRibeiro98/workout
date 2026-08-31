import re

with open('app/src/test/java/com/example/FakeWorkoutDao.kt', 'r') as f:
    content = f.read()

# Let's find exactly the methods and wipe them from everywhere
methods_to_remove = [
    "insertExerciseEducation",
    "insertExerciseMedia",
    "insertExerciseProgression",
    "insertExerciseSafety",
    "insertExerciseSubstitutionPremium",
    "insertExerciseAiContext",
    "insertExerciseBiomechanics",
    "insertExerciseExecution",
    "getExerciseEducation",
    "getExerciseMedia",
    "getExerciseProgression",
    "getExerciseSafety",
    "getExerciseSubstitutionPremium",
    "getExerciseAiContext",
    "getExerciseBiomechanics",
    "getExerciseExecution"
]

lines = content.split('\n')
new_lines = []
skip = False
for line in lines:
    is_target_method = False
    for m in methods_to_remove:
        if m in line and "fun " in line:
            is_target_method = True
            break
            
    if is_target_method:
        continue
    
    if line.strip() == "}" or line.strip() == "} " or line.strip() == "}}":
        # we skip all closing braces that might belong to the end of the file
        pass
    else:
        new_lines.append(line)


final_content = "\n".join(new_lines) + """
}
"""

with open('app/src/test/java/com/example/FakeWorkoutDao.kt', 'w') as f:
    f.write(final_content)

