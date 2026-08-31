package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import android.os.Build

@RunWith(AndroidJUnit4::class)
@Config(application = MainApplication::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class AppStartupTest {
    @Test
    fun testAppStarts() {
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assert(activity != null)
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
