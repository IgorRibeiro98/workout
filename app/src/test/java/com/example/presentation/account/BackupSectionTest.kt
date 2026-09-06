package com.example.presentation.account

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.backup.BackupSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A seção de backup, renderizada de verdade (T16.4).
 *
 * Três requisitos que só uma tela real prova:
 *
 * 1. abrir a tela e recompor **não** disparam backup;
 * 2. a confirmação da adoção é uma decisão separada de "ativar";
 * 3. o texto não promete o que não existe — nem restore, nem sincronização, nem proteção total.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BackupSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val summary = BackupSummary(
        programs = 1,
        templates = 4,
        completedSessions = 87,
        customExercises = 2,
        bodyMeasurements = 9,
        checkIns = 30,
        exerciseOverrides = 3,
        weeklyGoals = 5
    )

    @Test
    fun `abrir a secao e recompor nao disparam backup`() {
        var activateCount = 0
        var backupNowCount = 0
        var state by mutableStateOf(BackupUiState(phase = BackupPhase.Unbound))

        composeRule.setContent {
            BackupSection(
                uiState = state,
                onActivate = { activateCount++ },
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = { backupNowCount++ }
            )
        }

        // Recomposições forçadas: nada além de renderizar pode acontecer.
        repeat(3) { state = state.copy(accountEmail = "atleta$it@example.com") }
        composeRule.waitForIdle()

        assertEquals("abrir a tela não pode ativar backup", 0, activateCount)
        assertEquals("abrir a tela não pode enviar backup", 0, backupNowCount)
    }

    @Test
    fun `sem conta a secao convida a entrar e nao oferece ativar`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(phase = BackupPhase.NotAuthenticated),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Proteja seus dados").assertExists()
        composeRule.onNodeWithText("Ativar backup").assertDoesNotExist()
        composeRule.onNodeWithText("Fazer backup agora").assertDoesNotExist()
    }

    @Test
    fun `ativar backup nao envia nada — abre a confirmacao`() {
        var activateCount = 0
        var confirmCount = 0

        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(phase = BackupPhase.Unbound),
                onActivate = { activateCount++ },
                onConfirmAdoption = { confirmCount++ },
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Ativar backup").performClick()

        // Um toque pede a confirmação; ele **não** confirma. São duas decisões, nesta ordem.
        assertEquals(1, activateCount)
        assertEquals(0, confirmCount)
    }

    @Test
    fun `a confirmacao mostra o que sera associado e o que fica local`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(
                    phase = BackupPhase.Unbound,
                    isConfirmingAdoption = true,
                    summary = summary,
                    accountEmail = "atleta@example.com"
                ),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Associar dados à Conta Spark?").assertExists()
        composeRule.onNodeWithText("4 treinos").assertExists()
        composeRule.onNodeWithText("87 treinos concluídos").assertExists()
        composeRule.onNodeWithText("9 medidas corporais").assertExists()
        // O que **não** sobe também é dito, antes de o usuário confiar no backup.
        composeRule.onNodeWithText(
            "Fotos personalizadas de exercício continuam somente neste aparelho."
        ).assertExists()
        composeRule.onNodeWithText("Cancelar").assertExists()
        composeRule.onNodeWithText("Associar e criar backup").assertExists()
    }

    @Test
    fun `cancelar a confirmacao nao confirma`() {
        var confirmCount = 0
        var cancelCount = 0

        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(
                    phase = BackupPhase.Unbound,
                    isConfirmingAdoption = true,
                    summary = summary
                ),
                onActivate = {},
                onConfirmAdoption = { confirmCount++ },
                onCancelAdoption = { cancelCount++ },
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Cancelar").performClick()

        assertEquals(1, cancelCount)
        assertEquals(0, confirmCount)
    }

    @Test
    fun `enviando nao mostra porcentagem inventada`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(phase = BackupPhase.Uploading),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Enviando backup...").assertExists()
        // Não há métrica real de bytes; mostrar "72%" seria inventar informação.
        composeRule.onNodeWithText("72%").assertDoesNotExist()
        composeRule.onNodeWithText("Fazer backup agora").assertDoesNotExist()
    }

    @Test
    fun `o ultimo backup usa a hora do servidor e nao promete sincronizacao`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(
                    phase = BackupPhase.Ready(
                        ownerUid = "uid-A",
                        lastBackupAt = 1_757_183_520_000L,
                        lastBackupItemCount = 132
                    )
                ),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Último backup salvo no Spark").assertExists()
        composeRule.onNodeWithText("132 itens enviados").assertExists()
        composeRule.onNodeWithText("Fazer backup agora").assertExists()

        // A T16.4 protege contra a perda do aparelho. Ela não sincroniza e não restaura.
        composeRule.onNodeWithText("Todos os seus dispositivos estão sincronizados").assertDoesNotExist()
        composeRule.onNodeWithText("Seus dados estão totalmente protegidos").assertDoesNotExist()
        composeRule.onNodeWithText("Restaurar backup").assertDoesNotExist()
        composeRule.onNodeWithText("Sincronizado").assertDoesNotExist()
    }

    @Test
    fun `descompasso de conta bloqueia a nuvem sem expor a outra conta`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(phase = BackupPhase.AccountMismatch),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Dados de outra Conta Spark").assertExists()
        // Nem ativar, nem enviar: só operações de nuvem ficam indisponíveis.
        composeRule.onNodeWithText("Ativar backup").assertDoesNotExist()
        composeRule.onNodeWithText("Fazer backup agora").assertDoesNotExist()
    }

    @Test
    fun `falha e recuperavel e nao vira logout`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(phase = BackupPhase.Failed(BackupFailure.UNAVAILABLE)),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Backup não concluído").assertExists()
        composeRule.onNodeWithText("Tentar novamente").assertExists()
        composeRule.onNodeWithText("Sair da conta").assertDoesNotExist()
    }

    @Test
    fun `sem backend configurado a secao nao aparece`() {
        composeRule.setContent {
            BackupSection(
                uiState = BackupUiState(phase = BackupPhase.NotConfigured),
                onActivate = {},
                onConfirmAdoption = {},
                onCancelAdoption = {},
                onBackupNow = {}
            )
        }

        composeRule.onNodeWithText("Backup na nuvem").assertDoesNotExist()
    }
}
