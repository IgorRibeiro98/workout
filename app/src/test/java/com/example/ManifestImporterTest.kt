package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.domain.engine.ManifestImporter
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.os.Build

@RunWith(AndroidJUnit4::class)
@Config(application = MainApplication::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class ManifestImporterTest {
    @Test
    fun testManifestImporter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MainApplication>()
        val database = AppDatabase.getDatabase(context)
        try {
            ManifestImporter(database, context).importFromAssets()
        } catch(e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
