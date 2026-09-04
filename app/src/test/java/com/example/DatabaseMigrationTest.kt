package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    @Throws(IOException::class)
    fun migrate28To29() {
        var db = helper.createDatabase("migration-test", 28)
        
        // Ensure no exception when inserting into previous schema? No, table shouldn't exist.
        // We just run migration
        db.close()

        db = helper.runMigrationsAndValidate("migration-test", 29, true, AppDatabase.MIGRATION_28_29)
        
        // Assert table exists by inserting a test row
        db.execSQL("INSERT INTO weekly_goal_history (effectiveFromWeekStartEpochDay, goal, createdAt) VALUES (100, 3, 1000)")
        
        db.query("SELECT * FROM weekly_goal_history").use { cursor ->
            assert(cursor.moveToFirst())
            assert(cursor.getInt(cursor.getColumnIndexOrThrow("goal")) == 3)
        }
    }
}
