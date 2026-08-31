import re

with open("app/src/main/java/com/example/data/local/AppDatabase.kt", "r") as f:
    content = f.read()

content = content.replace("version = 17,", "version = 18,")
content = content.replace(
    "val MIGRATION_16_17 = object : Migration(16, 17) {",
    """val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN exerciseDbAliases TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {"""
)
content = content.replace(
    "MIGRATION_16_17\n            )",
    "MIGRATION_16_17,\n                MIGRATION_17_18\n            )"
)

with open("app/src/main/java/com/example/data/local/AppDatabase.kt", "w") as f:
    f.write(content)
