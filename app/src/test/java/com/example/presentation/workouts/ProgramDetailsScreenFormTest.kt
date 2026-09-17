package com.example.presentation.workouts

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O formulário de treino na tela do programa (T19.8): o que é obrigatório está marcado e é só o
 * que o domínio exige; os dias são seleção múltipla sem um "Nenhum" concorrente; desmarcar tudo é
 * "sem dia fixo"; reabrir mostra o que foi salvo; e o erro aparece onde falta.
 */
// Sem `qualifiers = "w411dp-h891dp"`: com esse tamanho de janela, um `OutlinedTextField` dentro
// de um `AlertDialog` do Material 3 nunca fica ocioso no Robolectric (`waitForIdle` estoura), e o
// formulário não depende do tamanho da tela para o que este teste prova.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProgramDetailsScreenFormTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var settings: SettingsManager
    private val stores = mutableListOf<ViewModelStore>()
    private var programId = 0L
    private var existingId = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsManager(context)
        repository = WorkoutRepository(database.workoutDao(), settingsManager = settings)
        programId = database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        existingId = database.workoutDao().insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Peito", shortIdentifier = "A", orderInProgram = 0)
        )
        database.workoutDao().replaceSchedulesForTemplate(existingId, listOf("MONDAY", "THURSDAY"))
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
    }

    private fun waitUntilIdling(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condição não satisfeita em ${timeoutMs}ms" }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun viewModel(): ProgramDetailsViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProgramDetailsViewModel(repository, settings) as T
        }
        return ViewModelProvider(store, factory)[ProgramDetailsViewModel::class.java].also { it.loadProgram(programId) }
    }

    private fun daysOf(templateId: Long) = runBlocking { repository.getTemplateScheduledDays(templateId) }

    private fun dayTag(day: DayOfWeek) = "template_form_day_${day.name}"

    @Test
    fun `criar - obrigatorio marcado, dias em selecao multipla, sem Nenhum, salva o que foi escolhido`() {
        val viewModel = viewModel()
        composeRule.setContent { ProgramDetailsScreen(viewModel = viewModel, onNavigateBack = {}, onTemplateClick = {}) }
        waitUntilIdling { viewModel.templates.value.size == 1 }

        composeRule.onNodeWithTag("template_form_name").assertDoesNotExist()
        viewModel.openCreateTemplateForm()
        composeRule.waitForIdle()

        // Obrigatoriedade explícita e coerente com o domínio: só o nome.
        composeRule.onNodeWithText("Nome *").assertExists()
        composeRule.onNodeWithText("Sigla").assertExists()
        composeRule.onNodeWithText("Sigla *").assertDoesNotExist()
        composeRule.onNodeWithText("Dias da semana").assertExists()
        // Não existe item "Nenhum": nenhum chip ligado é "sem dia fixo", e a legenda diz isso.
        composeRule.onNodeWithText("Nenhum").assertDoesNotExist()
        composeRule.onNodeWithText("Nenhum dia selecionado", substring = true).assertExists()
        DayOfWeek.entries.forEach { composeRule.onNodeWithTag(dayTag(it)).assertIsOff() }

        // Seleção múltipla: Seg + Qui ligados ao mesmo tempo.
        composeRule.onNodeWithTag(dayTag(DayOfWeek.MONDAY)).performClick()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.THURSDAY)).performClick()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.MONDAY)).assertIsOn()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.THURSDAY)).assertIsOn()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.TUESDAY)).assertIsOff()
        composeRule.onNodeWithText("Nenhum dia selecionado", substring = true).assertDoesNotExist()

        composeRule.onNodeWithTag("template_form_name").performTextInput("Pernas")
        composeRule.onNodeWithTag("template_form_submit").performClick()
        waitUntilIdling { viewModel.templates.value.size == 2 }

        composeRule.onNodeWithTag("template_form_name").assertDoesNotExist()
        val created = viewModel.templates.value.single { it.template.name == "Pernas" }
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), created.scheduledDays)
        assertNull("sigla vazia é opcional e vira null", created.template.shortIdentifier)
        // A lista mostra os dois dias no mesmo treino — no novo e no que já existia.
        composeRule.onAllNodesWithText("Seg · Qui").assertCountEquals(2)
    }

    @Test
    fun `salvar sem nome mostra o erro no campo e nao fecha`() {
        val viewModel = viewModel()
        composeRule.setContent { ProgramDetailsScreen(viewModel = viewModel, onNavigateBack = {}, onTemplateClick = {}) }
        waitUntilIdling { viewModel.templates.value.size == 1 }
        viewModel.openCreateTemplateForm()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Informe o nome do treino.").assertDoesNotExist()
        composeRule.onNodeWithTag("template_form_submit").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Informe o nome do treino.").assertExists()
        composeRule.onNodeWithTag("template_form_name").assertExists()
        assertEquals(1, viewModel.templates.value.size)
    }

    @Test
    fun `editar - reabre com os dias salvos, desmarcar todos salva sem dia fixo`() {
        val viewModel = viewModel()
        composeRule.setContent { ProgramDetailsScreen(viewModel = viewModel, onNavigateBack = {}, onTemplateClick = {}) }
        waitUntilIdling { viewModel.templates.value.size == 1 }

        viewModel.openEditTemplateForm(viewModel.templates.value.single())
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Editar Treino").assertExists()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.MONDAY)).assertIsOn()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.THURSDAY)).assertIsOn()

        // Desliga os dois: nenhum selecionado é um estado válido, e a legenda muda.
        composeRule.onNodeWithTag(dayTag(DayOfWeek.MONDAY)).performClick()
        composeRule.onNodeWithTag(dayTag(DayOfWeek.THURSDAY)).performClick()
        composeRule.onNodeWithText("Nenhum dia selecionado", substring = true).assertExists()
        composeRule.onNodeWithTag("template_form_submit").performClick()
        // Espera pelo mesmo `Flow` que a tela observa — não pela leitura direta do banco
        // (`daysOf`), que é uma consulta independente e pode ver o `DELETE` antes da
        // recomposição, causando falha intermitente só no CI.
        waitUntilIdling { viewModel.templates.value.single { it.template.id == existingId }.scheduledDays.isEmpty() }

        assertEquals("o mesmo treino, sem dia", 1, viewModel.templates.value.size)
        assertEquals("persistiu sem dia", emptyList<DayOfWeek>(), daysOf(existingId))
        composeRule.onNodeWithText("Seg · Qui").assertDoesNotExist()

        // Reabrir mostra o que está persistido agora: nenhum dia ligado.
        viewModel.openEditTemplateForm(viewModel.templates.value.single())
        composeRule.waitForIdle()
        DayOfWeek.entries.forEach { composeRule.onNodeWithTag(dayTag(it)).assertIsOff() }
    }
}
