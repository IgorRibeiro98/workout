package com.example.presentation.exercises

import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.example.data.local.ExerciseEntity
import com.example.data.repository.CustomExerciseDeleteResult
import com.example.data.repository.WorkoutRepository
import com.example.domain.engine.EquipmentFamily
import com.example.domain.engine.WorkoutEngine
import com.example.presentation.exercises.components.CustomExerciseFormSheet
import com.example.presentation.exercises.components.EXERCISE_ORIGIN_CATALOG
import com.example.presentation.exercises.components.EXERCISE_ORIGIN_CUSTOM
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
 * O CRUD de exercício na tela (T19.7C): o formulário de `CUSTOM` só pede o que a entidade exige,
 * mostra a legenda visual ao vivo, e os detalhes distinguem catálogo de "criado por você".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w411dp-h891dp")
class ExerciseCrudScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var settings: SettingsManager
    private lateinit var engine: WorkoutEngine
    private val stores = mutableListOf<ViewModelStore>()

    private var canonicalId = 0L
    private var customId = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.workoutDao()
        settings = SettingsManager(context)
        repository = WorkoutRepository(dao, settingsManager = settings)
        engine = WorkoutEngine(dao, settings)
        canonicalId = dao.insertExercise(
            ExerciseEntity(name = "Supino reto com barra", canonicalId = "supino-reto-barra", primaryMuscle = "Peitoral", equipment = "Barra")
        )
        customId = dao.insertExercise(
            ExerciseEntity(name = "Meu exercício", primaryMuscle = "Costas", equipment = "Cabo", isUserCreated = true, syncId = "custom-1")
        )
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
    }

    /**
     * `composeRule.waitUntil` sozinho não esvazia o looper principal do Robolectric, e é nele que
     * uma consulta suspensa do Room devolve o resultado para o `viewModelScope`. Esvaziar o looper
     * a cada volta é o que faz a corrotina do ViewModel avançar.
     */
    private fun waitUntilIdling(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condição não satisfeita em ${timeoutMs}ms" }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun detailsViewModel(): ExerciseDetailsViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ExerciseDetailsViewModel(engine, repository, settings) as T
        }
        return ViewModelProvider(store, factory)[ExerciseDetailsViewModel::class.java]
    }

    @Test
    fun `formulario so habilita salvar com nome e mostra a legenda visual ao vivo`() {
        var saved: List<String?>? = null
        composeRule.setContent {
            CustomExerciseFormSheet(
                title = "Novo exercício",
                subtitle = null,
                saveLabel = "SALVAR",
                onDismiss = {},
                onSave = { name, muscle, equipment, description -> saved = listOf(name, muscle, equipment, description) }
            )
        }

        composeRule.onNodeWithTag("custom_exercise_save").assertIsNotEnabled()
        // Sem nada digitado, a legenda é o fallback neutro.
        composeRule.onNodeWithText(EquipmentFamily.UNKNOWN.displayName).assertExists()
        // O que o exercício NÃO é: a nota aponta séries/reps/descanso para o editor de treino.
        composeRule.onNodeWithText("Séries, repetições, descanso e carga", substring = true).assertExists()

        composeRule.onNodeWithTag("custom_exercise_name").performTextInput("Remada unilateral")
        composeRule.onNodeWithTag("custom_exercise_save").assertIsEnabled()
        // "Smith" é máquina para o resolver — e não colide com o texto digitado no campo.
        composeRule.onNodeWithTag("custom_exercise_equipment").performTextInput("Smith")
        composeRule.onNodeWithText(EquipmentFamily.MACHINE.displayName).assertExists()
        composeRule.onNodeWithTag("custom_exercise_muscle").performTextInput("Costas")

        composeRule.onNodeWithTag("custom_exercise_save").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf("Remada unilateral", "Costas", "Smith", null), saved)
    }

    @Test
    fun `salvar duas vezes seguidas entrega uma unica alteracao`() {
        var saves = 0
        composeRule.setContent {
            CustomExerciseFormSheet(
                title = "Novo exercício",
                subtitle = null,
                saveLabel = "SALVAR",
                onDismiss = {},
                onSave = { _, _, _, _ -> saves++ }
            )
        }
        composeRule.onNodeWithTag("custom_exercise_name").performTextInput("Meu supino")
        composeRule.onNodeWithTag("custom_exercise_save").performClick()
        composeRule.onNodeWithTag("custom_exercise_save").performClick()
        composeRule.waitForIdle()

        assertEquals(1, saves)
    }

    @Test
    fun `detalhes de exercicio canonico mostram a origem, a legenda e so a personalizacao`() {
        composeRule.setContent {
            ExerciseDetailsScreen(exerciseId = canonicalId, exerciseName = "Supino reto com barra", viewModel = detailsViewModel(), onNavigateBack = {})
        }
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithTag("exercise_semantics_row").fetchSemanticsNode() }.isSuccess
        }

        composeRule.onNodeWithText(EXERCISE_ORIGIN_CATALOG).assertIsDisplayed()
        composeRule.onNodeWithText(EquipmentFamily.FREE_WEIGHT.displayName).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Opções do Exercício").performClick()
        composeRule.onNodeWithText("Personalizar").assertIsDisplayed()
        composeRule.onNodeWithText("Editar exercício").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir exercício").assertDoesNotExist()
    }

    @Test
    fun `detalhes de CUSTOM oferecem editar e excluir, e excluir sem referencia apaga`() {
        val vm = detailsViewModel()
        var backCalls = 0
        composeRule.setContent {
            ExerciseDetailsScreen(exerciseId = customId, exerciseName = "Meu exercício", viewModel = vm, onNavigateBack = { backCalls++ })
        }
        composeRule.waitUntil(10_000) {
            runCatching { composeRule.onNodeWithTag("exercise_semantics_row").fetchSemanticsNode() }.isSuccess
        }

        composeRule.onNodeWithText(EXERCISE_ORIGIN_CUSTOM).assertIsDisplayed()
        composeRule.onNodeWithText(EquipmentFamily.CABLE.displayName).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Opções do Exercício").performClick()
        composeRule.onNodeWithText("Editar exercício").assertIsDisplayed()
        composeRule.onNodeWithText("Personalizar").assertDoesNotExist()
        composeRule.onNodeWithText("Excluir exercício").performClick()
        composeRule.onNodeWithTag("custom_exercise_delete_confirm").performClick()

        waitUntilIdling { backCalls == 1 }
        assertNull(runBlocking { database.workoutDao().getExerciseById(customId) })
        assertNull(vm.deleteResult.value)
    }

    @Test
    fun `viewmodel expoe o resultado da exclusao e o consome`() {
        val vm = detailsViewModel()
        vm.deleteCustomExercise(canonicalId)
        waitUntilIdling { vm.deleteResult.value != null }
        assertEquals(CustomExerciseDeleteResult.NotCustom, vm.deleteResult.value)
        vm.consumeDeleteResult()
        assertNull(vm.deleteResult.value)
    }
}
