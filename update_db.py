import re

with open('app/src/main/java/com/example/data/local/AppDatabase.kt', 'r') as f:
    content = f.read()

# Update version from 19 to 20
content = content.replace("version = 19", "version = 20")

migration_20 = """
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN bodyRegion TEXT")
                db.execSQL("ALTER TABLE exercises ADD COLUMN trainingGoals TEXT")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN repRangeMin INTEGER")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN repRangeMax INTEGER")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN incrementUpper REAL")
                db.execSQL("ALTER TABLE exercise_progression ADD COLUMN incrementLower REAL")
                db.execSQL("ALTER TABLE exercise_media ADD COLUMN gifSource TEXT")
                db.execSQL("ALTER TABLE exercise_media ADD COLUMN videos TEXT")
            }
        }
"""

# Find MIGRATION_18_19 to insert after
idx = content.find("val MIGRATION_18_19 = object : Migration(18, 19) {")
if idx != -1:
    content = content[:idx] + migration_20 + "\n" + content[idx:]

# Find where migrations are added
idx_add = content.find("MIGRATION_17_18, MIGRATION_18_19")
if idx_add != -1:
    content = content.replace("MIGRATION_17_18, MIGRATION_18_19", "MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20")

with open('app/src/main/java/com/example/data/local/AppDatabase.kt', 'w') as f:
    f.write(content)
