import re

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

text = text.replace("settingsManager.preAlertEnabled.", "settingsManager.preAlertEnabledFlow.")
text = text.replace("settingsManager.rirRpeEnabled.", "settingsManager.rirRpeEnabledFlow.")
text = text.replace("settingsManager.showGifs.", "settingsManager.showGifsFlow.")
text = text.replace("settingsManager.defaultRestSeconds.", "settingsManager.defaultRestSecondsFlow.")
text = text.replace("settingsManager.defaultExerciseRestSeconds.", "settingsManager.defaultExerciseRestSecondsFlow.")

text = text.replace("programImporter.importFromJsonString(", "programImporter.importProgramFromJson(")
text = text.replace(".background(BackgroundDark)", ".background(com.example.ui.theme.BackgroundDark)")
text = text.replace("diag.gifCount", "diag.gifsCount")
text = text.replace("diag.customPhotoCount", "diag.customPhotosCount")
text = text.replace("diag.youtubeCount", "diag.curatedVideosCount")

# SelectionBottomSheet parameters
text = text.replace("selectedValue =", "selectedOption =")
text = text.replace("onSelect =", "onOptionSelected =")
text = text.replace("optionTitle = { it.first }", "optionTitle = { it }") # wait, the options are Pair<String, Int>

# Let's fix SelectionBottomSheet properly
# The options are Pair<String, Int>, but SelectionBottomSheet takes T.
# we just need to pass optionTitle = { it.first }
text = re.sub(
    r'onSelect = { \n\s*coroutineScope\.launch { settingsManager\.setDefaultRestSeconds\(it\) }\n\s*activeSheet = null\n\s*},',
    'onOptionSelected = { \n                    coroutineScope.launch { settingsManager.setDefaultRestSeconds(it.second) }\n                    activeSheet = null\n                },\n                optionTitle = { it.first },',
    text
)
text = re.sub(
    r'onSelect = { \n\s*coroutineScope\.launch { settingsManager\.setDefaultExerciseRestSeconds\(it\) }\n\s*activeSheet = null\n\s*},',
    'onOptionSelected = { \n                    coroutineScope.launch { settingsManager.setDefaultExerciseRestSeconds(it.second) }\n                    activeSheet = null\n                },\n                optionTitle = { it.first },',
    text
)
# remove onCustomOptionSelect
text = re.sub(r',\n\s*onCustomOptionSelect = \{[^\}]+\}', '', text)

# fix the selectedOption search because defaultRestSecs is Int and options are Pair<String, Int>
# wait, selectedOption = defaultRestSecs will fail because T is Pair<String, Int>.
# We need to find the pair where second == defaultRestSecs
text = re.sub(
    r'selectedOption = defaultRestSecs,',
    r'selectedOption = listOf("Desativado" to 0, "30 segundos" to 30, "45 segundos" to 45, "60 segundos (1 min)" to 60, "90 segundos (1.5 min)" to 90, "120 segundos (2 min)" to 120, "180 segundos (3 min)" to 180).find { it.second == defaultRestSecs },',
    text
)
text = re.sub(
    r'selectedOption = defaultExerciseRestSecs,',
    r'selectedOption = listOf("Desativado" to 0, "60 segundos (1 min)" to 60, "90 segundos (1.5 min)" to 90, "120 segundos (2 min)" to 120, "150 segundos (2.5 min)" to 150, "180 segundos (3 min)" to 180, "240 segundos (4 min)" to 240).find { it.second == defaultExerciseRestSecs },',
    text
)

with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "w") as f:
    f.write(text)
