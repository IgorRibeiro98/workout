package com.example.presentation.workouts

import android.content.Context
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.repository.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * O editor de treino na tela (T19.6): a lista mostra a ordem persistida, segurar e arrastar
 * reordena e persiste, e a pré-visualização é leitura que fecha sem tocar em nada.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], qualifiers = "w411dp-h891dp")
class TemplateDetailsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var settings: SettingsManager
    private val stores = mutableListOf<ViewModelStore>()

    private var templateId = 0L
    private var customTemplateId = 0L
    private var a = 0L
    private var b = 0L
    private var c = 0L
    private var customRow = 0L

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.workoutDao()
        settings = SettingsManager(context)
        repository = WorkoutRepository(dao, settingsManager = settings)

        val supino = dao.insertExercise(
            ExerciseEntity(name = "Supino reto", canonicalId = "supino-reto-barra", primaryMuscle = "Peitoral", equipment = "Barra", description = "Deite no banco e empurre a barra.")
        )
        val crucifixo = dao.insertExercise(ExerciseEntity(name = "Crucifixo", canonicalId = "crucifixo-halteres", primaryMuscle = "Peitoral"))
        val triceps = dao.insertExercise(ExerciseEntity(name = "Tríceps corda", canonicalId = "triceps-corda", primaryMuscle = "Tríceps"))
        // Um CUSTOM só com nome: sem músculo, sem equipamento, sem descrição, sem mídia.
        val custom = dao.insertExercise(ExerciseEntity(name = "Meu exercício", isUserCreated = true, syncId = "custom-1"))

        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        templateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Peito", shortIdentifier = "A"))
        customTemplateId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Livre", shortIdentifier = "B"))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = supino, sortOrder = 0, targetSets = 4, minReps = 8, maxReps = 12, restDurationSeconds = 90, plannedWeight = 60f))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = crucifixo, sortOrder = 1))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = templateId, exerciseId = triceps, sortOrder = 2))
        dao.insertTemplateExercise(WorkoutTemplateExerciseEntity(templateId = customTemplateId, exerciseId = custom, sortOrder = 0))
        val rows = dao.getTemplateExercisesWithDetails(templateId).map { it.templateExercise }
        a = rows[0].id
        b = rows[1].id
        c = rows[2].id
        customRow = dao.getTemplateExercisesWithDetails(customTemplateId).single().templateExercise.id
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
    }

    private fun viewModel(): TemplateDetailsViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = TemplateDetailsViewModel(repository, settings) as T
        }
        return ViewModelProvider(store, factory)[TemplateDetailsViewModel::class.java]
    }

    private fun show(templateId: Long): TemplateDetailsViewModel {
        val vm = viewModel()
        vm.load(templateId)
        composeRule.setContent { TemplateDetailsScreen(viewModel = vm, onBack = {}) }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTagPrefix().isNotEmpty()
        }
        return vm
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagPrefix(): List<Long> =
        listOf(a, b, c, customRow).filter { id ->
            runCatching { onNodeWithTag("template_exercise_$id").fetchSemanticsNode() }.isSuccess
        }

    private fun rowTop(id: Long) = composeRule.onNodeWithTag("template_exercise_$id").getBoundsInRoot().top

    private fun persistedOrder(): List<Long> = runBlocking {
        database.workoutDao().getTemplateExercisesWithDetails(templateId).map { it.templateExercise.id }
    }

    @Test
    fun `a lista mostra a ordem persistida com o handle de arrastar`() {
        show(templateId)

        assertTrue(rowTop(a) < rowTop(b) && rowTop(b) < rowTop(c))
        composeRule.onNodeWithContentDescription("Segure para mover Supino reto").assertIsDisplayed()
    }

    @Test
    fun `segurar e arrastar C para o topo reordena e persiste C A B`() {
        show(templateId)
        val rowHeight = composeRule.onNodeWithTag("template_exercise_$a").getBoundsInRoot().let { it.bottom - it.top }
        val distancePx = with(composeRule.density) { (rowHeight * 2.4f + 24.dp).toPx() }

        composeRule.onNodeWithTag("template_exercise_$c").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 200)
            // Um deslize em passos, como um dedo: o slot candidato é recalculado a cada passo.
            val steps = 12
            repeat(steps) {
                moveBy(Offset(0f, -distancePx / steps))
                advanceEventTime(16)
            }
            up()
        }

        composeRule.waitUntil(10_000) { persistedOrder() == listOf(c, a, b) }
        composeRule.waitForIdle()
        assertTrue(rowTop(c) < rowTop(a) && rowTop(a) < rowTop(b))
        val rows = runBlocking { database.workoutDao().getTemplateExercisesWithDetails(templateId).map { it.templateExercise } }
        assertEquals(listOf(0, 1, 2), rows.map { it.sortOrder })
        // A configuração viajou com o exercício.
        val supino = rows.single { it.id == a }
        assertEquals(4, supino.targetSets)
        assertEquals(8, supino.minReps)
        assertEquals(12, supino.maxReps)
        assertEquals(90, supino.restDurationSeconds)
        assertEquals(60f, supino.plannedWeight)
    }

    @Test
    fun `um toque simples abre as opcoes, nao inicia arraste`() {
        show(templateId)

        composeRule.onNodeWithTag("template_exercise_$b").performClick()

        composeRule.onNodeWithText("Opções do Exercício").assertIsDisplayed()
        composeRule.onNodeWithText("Pré-visualizar exercício").assertIsDisplayed()
        assertEquals(listOf(a, b, c), persistedOrder())
    }

    @Test
    fun `a pre-visualizacao mostra o exercicio certo, e leitura e fecha no mesmo editor`() {
        show(templateId)

        composeRule.onNodeWithTag("template_exercise_preview_$a").performClick()

        composeRule.onNodeWithTag("exercise_preview_sheet").assertExists()
        composeRule.onNodeWithText("Deite no banco e empurre a barra.").assertExists()
        composeRule.onNodeWithText("Neste treino").assertExists()
        composeRule.onNodeWithText("4", substring = false).assertExists()
        composeRule.onNodeWithText("Este exercício não tem demonstração cadastrada").assertExists()

        composeRule.onNodeWithTag("exercise_preview_close").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("exercise_preview_sheet").assertDoesNotExist()
        composeRule.onNodeWithTag("template_exercise_$a").assertIsDisplayed()
        assertEquals(listOf(a, b, c), persistedOrder())
    }

    @Test
    fun `reordenar, abrir e fechar a pre-visualizacao mantem a ordem`() {
        val vm = show(templateId)

        vm.moveExercise(2, 0)
        composeRule.waitUntil(10_000) { persistedOrder() == listOf(c, a, b) }
        composeRule.onNodeWithTag("template_exercise_preview_$c").performClick()
        composeRule.onNodeWithTag("exercise_preview_sheet").assertExists()
        composeRule.onNodeWithTag("exercise_preview_close").performClick()
        composeRule.waitForIdle()

        assertTrue(rowTop(c) < rowTop(a) && rowTop(a) < rowTop(b))
        assertEquals(listOf(c, a, b), persistedOrder())
    }

    @Test
    fun `CUSTOM sem metadata abre a pre-visualizacao coerente, sem crash`() {
        show(customTemplateId)

        composeRule.onNodeWithTag("template_exercise_preview_$customRow").performClick()

        composeRule.onNodeWithTag("exercise_preview_sheet").assertExists()
        composeRule.onNodeWithTag("exercise_preview_custom_badge").assertExists()
        composeRule.onNodeWithText("Este exercício não tem demonstração cadastrada").assertExists()
        composeRule.onNodeWithText("Sobre").assertDoesNotExist()
        composeRule.onNodeWithText("Neste treino").assertExists()
    }
}
