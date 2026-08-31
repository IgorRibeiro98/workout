import re

with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'r') as f:
    content = f.read()

# Add imports for components
import_statement = "import com.example.presentation.exercises.components.premium.*\n"
if import_statement not in content:
    content = content.replace("import com.example.ui.theme.*", "import com.example.ui.theme.*\n" + import_statement)

# We will find where `// PREMIUM CONTENT & OVERVIEW` starts and rewrite the section.
idx_start = content.find("            // PREMIUM CONTENT & OVERVIEW")
idx_end = content.find("            // Video Guide Card - ONLY shown if curated video mapping exists")

if idx_start != -1 and idx_end != -1:
    before = content[:idx_start]
    after = content[idx_end:]

    # Now I will replace the Banner, which is under "// Photo / GIF / Media Banner"
    # Actually, ExerciseHeroCard will replace the banner AND the about card.
    
    # Wait, let's see where the old banner is.
    # We can just clear from "// Photo / GIF / Media Banner" to `// Video Guide Card - ONLY shown if curated video mapping exists`
    
    banner_idx = content.find("            // Photo / GIF / Media Banner")
    if banner_idx != -1:
        before = content[:banner_idx]
    
    new_section = """
            // HERO SECTION
            item {
                ExerciseHeroCard(
                    title = title,
                    primaryMuscle = primaryMuscle,
                    equipment = equipment,
                    difficulty = exerciseInfo?.difficulty,
                    mediaUrl = resolvedMedia?.url
                )
            }
            
            // ABOUT
            item {
                ExerciseAboutCard(
                    description = resolvedNotes ?: exerciseInfo?.shortDescription,
                    primaryMuscles = primaryMuscle,
                    secondaryMuscles = secondaryMuscles.joinToString(", "),
                    equipment = equipment,
                    difficulty = exerciseInfo?.difficulty
                )
            }
            
            // PREMIUM CONTENT
            if (premiumInfo != null) {
                val premium = premiumInfo!!
                
                if (premium.execution != null) {
                    item {
                        ExerciseExecutionCard(
                            setupJson = premium.execution.setup,
                            stepsJson = premium.execution.steps,
                            breathingJson = premium.execution.breathing
                        )
                    }
                }
                
                if (premium.education != null) {
                    if (!premium.education.tips.isNullOrEmpty()) {
                        item { ExerciseTipsCard(premium.education.tips) }
                    }
                    if (!premium.education.commonMistakes.isNullOrEmpty()) {
                        item { ExerciseMistakesCard(premium.education.commonMistakes) }
                    }
                }
                
                if (premium.progression != null) {
                    item {
                        ExerciseProgressionCard(
                            method = premium.progression.progressionMethod,
                            repRange = premium.progression.repRange,
                            rule = premium.progression.increaseRule,
                            sets = premium.progression.standardSets,
                            incUpper = premium.progression.incrementUpper,
                            incLower = premium.progression.incrementLower
                        )
                    }
                }
                
                if (premium.substitution != null) {
                    item {
                        ExerciseSubstitutionCard(
                            sameMovement = premium.substitution.sameMovement,
                            sameMuscle = premium.substitution.sameMuscle,
                            notRecommended = premium.substitution.notRecommended
                        )
                    }
                }
                
                if (premium.safety != null) {
                    item {
                        ExerciseSafetyCard(
                            riskLevel = premium.safety.riskLevel,
                            attentionPointsJson = premium.safety.attentionPoints,
                            discomfortsJson = premium.safety.commonDiscomforts
                        )
                    }
                }
            }
            
"""
    with open('app/src/main/java/com/example/presentation/exercises/ExerciseDetailsScreen.kt', 'w') as f:
        f.write(before + new_section + after)
