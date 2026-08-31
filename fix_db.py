with open('app/src/main/java/com/example/data/local/AppDatabase.kt', 'r') as f:
    content = f.read()

content = content.replace('val MIGRATION_17_18, MIGRATION_18_19 = object : Migration(17, 18)', 'val MIGRATION_17_18 = object : Migration(17, 18)')

with open('app/src/main/java/com/example/data/local/AppDatabase.kt', 'w') as f:
    f.write(content)
