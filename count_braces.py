with open("app/src/main/java/com/example/presentation/settings/SettingsScreen.kt", "r") as f:
    text = f.read()

import re

lines = text.split('\n')
open_braces = 0
for i, line in enumerate(lines):
    line_no_strings = re.sub(r'".*?"', '', line)
    open_braces += line_no_strings.count('{') - line_no_strings.count('}')
    if i % 100 == 0:
        print(f"Line {i}: open braces = {open_braces}")
print(f"Total open braces: {open_braces}")
