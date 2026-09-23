package com.example.presentation.workouts

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.datastore.SettingsManager
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.repository.WorkoutRepository
import com.example.presentation.assertWithinViewportHeight
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O seletor de exercícios não perde o que o usuário escolheu (T19.H2 / H2.9).
 *
 * ## Os dois defeitos
 *
 * 1. **A folha abria "meio aberta"** e o CTA "ADICIONAR N EXERCÍCIOS" ficava abaixo da borda. Com o
 *    teclado aberto, nem arrastar resolvia.
 * 2. **O estado da seleção vivia dentro do `if` que desenhava a folha.** Qualquer coisa que
 *    fechasse a folha — inclusive o "voltar" que devia apenas esconder o teclado — levava a
 *    seleção junto. O usuário aprendeu a usar o "OK" do teclado para não perder o trabalho.
 *
 * O estado agora vive na tela, é zerado **explicitamente** ao fechar ou confirmar, e o "voltar" da
 * folha é tratado pelo app (`shouldDismissOnBackPress = false` + `BackHandler`): com o teclado
 * visível ele esconde o teclado; sem teclado, fecha a folha.
 *
 * O primeiro toque de "voltar" **com IME visível** não é exercitado aqui — Robolectric não tem
 * teclado de verdade, e um teste que finge ter não prova nada. O que este arquivo prova é o que
 * tornava a perda possível: onde o estado mora, e onde o botão fica.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w360dp-h740dp")
class ExercisePickerStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var settings: SettingsManager
    private val stores = mutableListOf<ViewModelStore>()

    private var templateId = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.workoutDao()
        settings = SettingsManager(context)
        repository = WorkoutRepository(dao, settingsManager = settings)

        dao.insertExercise(ExerciseEntity(name = "Supino reto", canonicalId = "supino-reto-barra", primaryMuscle = "Peitoral"))
        dao.insertExercise(ExerciseEntity(name = "Crucifixo", canonicalId = "crucifixo-halteres", primaryMuscle = "Peitoral"))
        dao.insertExercise(ExerciseEntity(name = "Tríceps corda", canonicalId = "triceps-corda", primaryMuscle = "Tríceps"))

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Peito", shortIdentifier = "A"))
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
    }

    private fun show(): TemplateDetailsViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TemplateDetailsViewModel(repository, settings) as T
        }
        val vm = ViewModelProvider(store, factory)[TemplateDetailsViewModel::class.java]
        vm.load(templateId)
        composeRule.setContent { TemplateDetailsScreen(viewModel = vm, onBack = {}) }
        composeRule.waitForIdle()
        return vm
    }

    private fun openPicker() {
        composeRule.onNodeWithContentDescription("Adicionar Exercícios").performClick()
        composeRule.waitForIdle()
    }

    private fun templateExerciseNames(): List<String> = runBlocking {
        database.workoutDao().getTemplateExercisesWithDetails(templateId).map { it.exercise.name }
    }

    @Test
    fun `o botao de confirmar esta dentro da tela assim que a folha abre`() {
        show()
        openPicker()

        composeRule.onNodeWithTag("exercise_picker_confirm")
            .assertIsDisplayed()
            .assertWithinViewportHeight(740, "CTA do seletor")
    }

    @Test
    fun `sem selecao o CTA fica desabilitado, e com selecao ele habilita e conta`() {
        show()
        openPicker()

        composeRule.onNodeWithTag("exercise_picker_confirm").assertIsNotEnabled()
        composeRule.onNodeWithText("Selecione exercícios").assertIsDisplayed()

        composeRule.onNodeWithText("Supino reto").performClick()
        composeRule.onNodeWithTag("exercise_picker_confirm").assertIsEnabled()
        composeRule.onNodeWithText("ADICIONAR 1 EXERCÍCIO").assertIsDisplayed()
    }

    @Test
    fun `a selecao sobrevive a buscar e a limpar a busca`() {
        show()
        openPicker()

        composeRule.onNodeWithText("Supino reto").performClick()
        composeRule.onNodeWithText("ADICIONAR 1 EXERCÍCIO").assertIsDisplayed()

        // Digitar filtra a lista — e o exercício escolhido some da tela. A escolha não some com ele.
        composeRule.onNodeWithTag("exercise_picker_search").performTextInput("tríceps")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ADICIONAR 1 EXERCÍCIO").assertIsDisplayed()

        // Escolher um segundo enquanto a busca está ativa.
        composeRule.onNodeWithText("Tríceps corda").performClick()
        composeRule.onNodeWithText("ADICIONAR 2 EXERCÍCIOS").assertIsDisplayed()

        // Limpar a busca: as duas escolhas continuam de pé.
        composeRule.onNodeWithTag("exercise_picker_search").performTextReplacement("")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("ADICIONAR 2 EXERCÍCIOS").assertIsDisplayed()
    }

    @Test
    fun `o filtro de musculo tambem sobrevive, e a selecao com ele`() {
        show()
        openPicker()

        // "Peitoral" é o chip de filtro **e** o músculo de duas linhas da lista; o chip é o
        // primeiro nó na ordem da tela.
        composeRule.onAllNodesWithText("Peitoral")[0].performClick()
        composeRule.onNodeWithText("Supino reto").performClick()
        composeRule.waitForIdle()

        // O filtro continua aplicado: "Tríceps corda" não está na lista filtrada.
        composeRule.onNodeWithText("Tríceps corda").assertDoesNotExist()
        composeRule.onNodeWithText("ADICIONAR 1 EXERCÍCIO").assertIsDisplayed()
    }

    @Test
    fun `confirmar adiciona exatamente os escolhidos, e so uma vez`() {
        show()
        openPicker()

        composeRule.onNodeWithText("Supino reto").performClick()
        composeRule.onNodeWithText("Tríceps corda").performClick()
        composeRule.onNodeWithTag("exercise_picker_confirm").performClick()
        composeRule.waitForIdle()

        composeRule.waitUntil(10_000) { templateExerciseNames().size == 2 }
        assertEquals(listOf("Supino reto", "Tríceps corda"), templateExerciseNames())

        // Reabrir o seletor começa do zero: a seleção anterior já virou treino.
        openPicker()
        composeRule.onNodeWithTag("exercise_picker_confirm").assertIsNotEnabled()
    }

    @Test
    fun `dois toques seguidos em confirmar adicionam uma vez so`() {
        show()
        openPicker()
        composeRule.onNodeWithText("Supino reto").performClick()

        // Dois toques antes de qualquer recomposição: é o caso real do toque duplo. A seleção é
        // limpa sincronamente pelo primeiro, e o segundo encontra a lista vazia.
        composeRule.onNodeWithTag("exercise_picker_confirm").performClick()
        composeRule.onNodeWithTag("exercise_picker_confirm").assertDoesNotExist()
        composeRule.waitForIdle()

        composeRule.waitUntil(10_000) { templateExerciseNames().isNotEmpty() }
        assertEquals(listOf("Supino reto"), templateExerciseNames())
    }

    @Test
    fun `fechar explicitamente descarta a selecao, que e o que fechar significa`() {
        show()
        openPicker()
        composeRule.onNodeWithText("Supino reto").performClick()
        composeRule.onNodeWithText("ADICIONAR 1 EXERCÍCIO").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Fechar").performClick()
        composeRule.waitForIdle()

        // Nada foi adicionado ao treino...
        assertEquals(emptyList<String>(), templateExerciseNames())
        // ...e reabrir não ressuscita a escolha descartada.
        openPicker()
        composeRule.onNodeWithTag("exercise_picker_confirm").assertIsNotEnabled()
    }
}
