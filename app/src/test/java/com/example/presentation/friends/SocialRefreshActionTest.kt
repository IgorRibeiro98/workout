package com.example.presentation.friends

import android.os.Build
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** O componente "↻" (T19.H3 §5): rótulo acessível, alvo de 48dp e estado de atualização. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SocialRefreshActionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `parado ele diz Atualizar, tem 48dp e chama o metodo uma vez por toque`() {
        var calls = 0
        composeRule.setContent { SocialRefreshAction(isRefreshing = false, onRefresh = { calls++ }) }

        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL)
            .assertIsEnabled()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(1, calls)
    }

    @Test
    fun `atualizando ele diz Atualizando e nao aceita toque`() {
        var calls = 0
        composeRule.setContent { SocialRefreshAction(isRefreshing = true, onRefresh = { calls++ }) }

        composeRule.onNodeWithContentDescription(REFRESHING_ACTION_LABEL).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).assertDoesNotExist()
        assertEquals(0, calls)
    }

    @Test
    fun `desabilitado sem conta ele existe, mas nao age`() {
        composeRule.setContent {
            SocialRefreshAction(isRefreshing = false, onRefresh = {}, enabled = false)
        }
        composeRule.onNodeWithContentDescription(REFRESH_ACTION_LABEL).assertIsNotEnabled()
    }
}
