package com.example

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DatabaseSafetyTest {
    @Test
    fun `ensure no destructive migration fallback is used anywhere in production source`() {
        val searchDirs = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("src/main"),
            File("app/src/main")
        )
        val mainDir = searchDirs.firstOrNull { it.exists() && it.isDirectory }
        assertTrue("Main source directory must exist", mainDir != null)

        val kotlinFiles = mainDir!!.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
        assertTrue("Kotlin files must be found in production source", kotlinFiles.isNotEmpty())

        for (file in kotlinFiles) {
            val content = file.readText()
            assertFalse(
                "Production file '${file.name}' MUST NOT use fallbackToDestructiveMigration()",
                content.contains("fallbackToDestructiveMigration")
            )
            assertFalse(
                "Production file '${file.name}' MUST NOT use deleteDatabase()",
                content.contains("deleteDatabase")
            )
        }
    }
}
