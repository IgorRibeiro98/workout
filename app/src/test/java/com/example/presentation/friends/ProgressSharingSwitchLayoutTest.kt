package com.example.presentation.friends

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.domain.social.ProgressSharingAvailability
import com.example.domain.social.ProgressSharingSettings
import com.example.domain.social.SocialFieldAvailability
import com.example.presentation.account.ProgressSharingPhase
import com.example.presentation.account.SocialProfileUiState
import com.example.presentation.assertWithinViewportWidth
import com.example.presentation.setContentWithFontScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Os interruptores de "Compartilhar progresso" aparecem **inteiros** (T19.H2 / H2.8).
 *
 * O defeito: a coluna de textos do `SharingToggle` não tinha `weight(1f)`. Numa `Row`, os filhos
 * sem peso são medidos **primeiro**, com a linha inteira à disposição — e a legenda de
 * disponibilidade ("Em breve", mais a explicação) consumia o espaço que sobraria para o `Switch`,
 * que aparecia metade fora da tela em 320/360dp.
 *
 * O estado `UNSUPPORTED` da T19.H0 continua exatamente como estava: desabilitado e **visível**. Um
 * interruptor cortado era, além de feio, a perda do único sinal que diz "isto não tem efeito".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w360dp-h740dp")
class ProgressSharingSwitchLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val switches = listOf(
        "progress_sharing_switch_Nível",
        "progress_sharing_switch_Consistência semanal",
        "progress_sharing_switch_Treinos da semana",
        "progress_sharing_switch_Conquistas em destaque"
    )

    /** O pior caso de texto: legenda + explicação + a nota de "Nível". */
    private fun render(fontScale: Float) {
        composeRule.setContentWithFontScale(fontScale) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                ProgressSharingBody(
                    uiState = SocialProfileUiState(
                        sharingPhase = ProgressSharingPhase.Ready,
                        settings = ProgressSharingSettings(
                            shareLevel = true,
                            shareConsistencyStreak = true,
                            shareWeeklyWorkoutCount = true,
                            shareHighlightedAchievements = true
                        ),
                        availability = ProgressSharingAvailability(
                            level = SocialFieldAvailability.UNSUPPORTED,
                            consistencyStreak = SocialFieldAvailability.UNAVAILABLE,
                            weeklyWorkoutCount = SocialFieldAvailability.AVAILABLE,
                            highlightedAchievements = SocialFieldAvailability.UNSUPPORTED
                        )
                    )
                )
            }
        }
    }

    private fun verify(screenWidthDp: Int, fontScale: Float) {
        render(fontScale)
        switches.forEach { tag ->
            composeRule.onNodeWithTag(tag)
                .assertWithinViewportWidth(screenWidthDp, "$tag em ${screenWidthDp}dp, fontScale $fontScale")
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_0`() = verify(320, 1.0f)

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `320dp fontScale 1_5`() = verify(320, 1.5f)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_0`() = verify(360, 1.0f)

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun `360dp fontScale 1_3`() = verify(360, 1.3f)

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `411dp fontScale 1_3`() = verify(411, 1.3f)

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun `UNSUPPORTED continua desabilitado e visivel, mesmo espremido em 320dp`() {
        render(1.5f)
        // T19.H0: preferência antiga continua ligada, o controle continua sem efeito — e agora
        // inteiro na tela, que é o que permite ao usuário ver os dois fatos.
        composeRule.onNodeWithTag("progress_sharing_switch_Nível")
            .assertIsOn()
            .assertIsNotEnabled()
            .assertWithinViewportWidth(320, "Nível UNSUPPORTED")
        composeRule.onNodeWithTag("progress_sharing_switch_Treinos da semana")
            .assertIsEnabled()
            .assertWithinViewportWidth(320, "Treinos da semana AVAILABLE")
    }
}
