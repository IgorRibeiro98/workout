package com.example.presentation.workouts

import android.content.Context
import android.os.Build
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
import com.example.data.local.WorkoutTemplateWithSchedule
import com.example.data.repository.WorkoutRepository
import com.example.data.sync.CloudSyncScope
import com.example.data.sync.RoomTransactionRunner
import com.example.data.sync.SyncEntityType
import com.example.data.sync.SyncMutationCoordinator
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * O formulário de treino na ViewModel do programa (T19.8): carregar zero/um/vários dias, ligar e
 * desligar dias, o conjunto vazio como estado válido, a obrigatoriedade que é a do domínio e o
 * salvar que passa pelo repositório — criar e editar pelo mesmo caminho.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProgramDetailsViewModelFormTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val ownerUid = "uid-da-conta"

    private lateinit var database: AppDatabase
    private lateinit var repository: WorkoutRepository
    private lateinit var settings: SettingsManager
    private val stores = mutableListOf<ViewModelStore>()

    private var programId = 0L
    private var noDaysId = 0L
    private var oneDayId = 0L
    private var manyDaysId = 0L

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(dispatcher)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dao = database.workoutDao()
        settings = SettingsManager(context)
        repository = WorkoutRepository(
            dao,
            settingsManager = settings,
            syncMutations = SyncMutationCoordinator(
                transactions = RoomTransactionRunner(database),
                outboxDao = database.syncOutboxDao(),
                scopeProvider = { CloudSyncScope.Enabled(ownerUid) },
                clock = { 1_700_000_000_000L }
            )
        )
        programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa", isCurrent = true))
        noDaysId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Sem dia", shortIdentifier = "S", orderInProgram = 0))
        oneDayId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Um dia", shortIdentifier = null, orderInProgram = 1))
        dao.replaceSchedulesForTemplate(oneDayId, listOf("MONDAY"))
        manyDaysId = dao.insertTemplate(WorkoutTemplateEntity(programId = programId, name = "Dois dias", shortIdentifier = "D", orderInProgram = 2))
        dao.replaceSchedulesForTemplate(manyDaysId, listOf("THURSDAY", "MONDAY"))
    }

    @After
    fun tearDown() {
        stores.forEach { it.clear() }
        database.close()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ProgramDetailsViewModel {
        val store = ViewModelStore().also { stores += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProgramDetailsViewModel(repository, settings) as T
        }
        return ViewModelProvider(store, factory)[ProgramDetailsViewModel::class.java].also { it.loadProgram(programId) }
    }

    /** Espera com teto de tempo real: o Room responde pelos executores dele, não pelo dispatcher de teste. */
    private suspend fun awaitTemplates(
        viewModel: ProgramDetailsViewModel,
        predicate: (List<WorkoutTemplateWithSchedule>) -> Boolean
    ): List<WorkoutTemplateWithSchedule> =
        withContext(Dispatchers.Default) { withTimeout(10_000) { viewModel.templates.first(predicate) } }

    private suspend fun daysOf(templateId: Long) = repository.getTemplateScheduledDays(templateId)

    // ------------------------------------------------------------------------------- carregar

    @Test
    fun `a lista carrega cada treino com os dias dele`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        val templates = awaitTemplates(viewModel) { it.size == 3 }

        assertEquals(listOf("Sem dia", "Um dia", "Dois dias"), templates.map { it.template.name })
        assertEquals(
            listOf(emptyList(), listOf(DayOfWeek.MONDAY), listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)),
            templates.map { it.scheduledDays }
        )
    }

    @Test
    fun `abrir para editar carrega nome, sigla e dias do que esta persistido`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val templates = awaitTemplates(viewModel) { it.size == 3 }

        viewModel.openEditTemplateForm(templates[0])
        assertEquals(TemplateFormState(templateId = noDaysId, name = "Sem dia", shortId = "S", scheduledDays = emptySet()), viewModel.templateForm.value)

        viewModel.openEditTemplateForm(templates[1])
        assertEquals(setOf(DayOfWeek.MONDAY), viewModel.templateForm.value!!.scheduledDays)
        assertEquals("sigla nula aparece vazia, não como \"null\"", "", viewModel.templateForm.value!!.shortId)

        viewModel.openEditTemplateForm(templates[2])
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), viewModel.templateForm.value!!.scheduledDays)
        assertTrue(viewModel.templateForm.value!!.isEditing)
    }

    @Test
    fun `abrir para criar comeca vazio, sem dia e sem erro`() {
        val viewModel = newViewModel()

        viewModel.openCreateTemplateForm()

        val form = viewModel.templateForm.value!!
        assertFalse(form.isEditing)
        assertEquals("", form.name)
        assertEquals(emptySet<DayOfWeek>(), form.scheduledDays)
        assertFalse(form.nameError)
        assertFalse("o nome vazio ainda não é erro: só depois de tentar salvar", form.showErrors)
    }

    // --------------------------------------------------------------------------------- dias

    @Test
    fun `ligar e desligar dias e um conjunto, e desligar o ultimo deixa o conjunto vazio`() {
        val viewModel = newViewModel()
        viewModel.openCreateTemplateForm()

        viewModel.toggleTemplateDay(DayOfWeek.MONDAY)
        viewModel.toggleTemplateDay(DayOfWeek.THURSDAY)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), viewModel.templateForm.value!!.scheduledDays)

        // Ligar de novo não duplica: é um conjunto.
        viewModel.toggleTemplateDay(DayOfWeek.MONDAY)
        assertEquals(setOf(DayOfWeek.THURSDAY), viewModel.templateForm.value!!.scheduledDays)

        viewModel.toggleTemplateDay(DayOfWeek.THURSDAY)
        assertEquals(emptySet<DayOfWeek>(), viewModel.templateForm.value!!.scheduledDays)
        assertTrue("zero dias continua sendo um formulário válido", viewModel.templateForm.value!!.copy(name = "x").isValid)
    }

    // ---------------------------------------------------------------------------- validação

    @Test
    fun `salvar sem nome nao salva, marca o campo e mantem o formulario aberto`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        awaitTemplates(viewModel) { it.size == 3 }
        viewModel.openCreateTemplateForm()
        viewModel.onTemplateNameChanged("   ")
        viewModel.onTemplateShortIdChanged("X")
        viewModel.toggleTemplateDay(DayOfWeek.FRIDAY)

        viewModel.submitTemplateForm()
        advanceUntilIdle()

        val form = viewModel.templateForm.value
        assertNotNull("o formulário continua aberto", form)
        assertTrue(form!!.nameError)
        assertEquals("o rascunho não se perde", setOf(DayOfWeek.FRIDAY), form.scheduledDays)
        assertEquals(3, database.workoutDao().getTemplatesForProgramSync(programId).size)

        // Preencher o nome resolve — a sigla e os dias nunca foram obrigatórios.
        viewModel.onTemplateNameChanged("Pernas")
        assertFalse(viewModel.templateForm.value!!.nameError)
    }

    // ------------------------------------------------------------------------------- salvar

    @Test
    fun `criar salva pelo repositorio com os dias escolhidos e fecha o formulario`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        awaitTemplates(viewModel) { it.size == 3 }
        viewModel.openCreateTemplateForm()
        viewModel.onTemplateNameChanged("Pernas")
        viewModel.onTemplateShortIdChanged("  ")
        viewModel.toggleTemplateDay(DayOfWeek.THURSDAY)
        viewModel.toggleTemplateDay(DayOfWeek.MONDAY)

        viewModel.submitTemplateForm()
        advanceUntilIdle()

        assertNull("fecha ao salvar", viewModel.templateForm.value)
        val created = awaitTemplates(viewModel) { it.size == 4 }.single { it.template.name == "Pernas" }
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), created.scheduledDays)
        assertNull("sigla em branco é null", created.template.shortIdentifier)
        assertEquals("entra no fim do programa", 3, created.template.orderInProgram)
    }

    @Test
    fun `criar sem dia nenhum salva um treino sem dia fixo`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        awaitTemplates(viewModel) { it.size == 3 }
        viewModel.openCreateTemplateForm()
        viewModel.onTemplateNameChanged("Livre")

        viewModel.submitTemplateForm()
        advanceUntilIdle()

        val created = awaitTemplates(viewModel) { it.size == 4 }.single { it.template.name == "Livre" }
        assertEquals(emptyList<DayOfWeek>(), created.scheduledDays)
    }

    @Test
    fun `editar troca os dias do mesmo treino, sem criar outro e sem mexer na ordem`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val templates = awaitTemplates(viewModel) { it.size == 3 }
        viewModel.openEditTemplateForm(templates[2])
        // Seg + Qui → Ter + Sex
        viewModel.toggleTemplateDay(DayOfWeek.MONDAY)
        viewModel.toggleTemplateDay(DayOfWeek.THURSDAY)
        viewModel.toggleTemplateDay(DayOfWeek.TUESDAY)
        viewModel.toggleTemplateDay(DayOfWeek.FRIDAY)
        viewModel.onTemplateNameChanged("Dois dias v2")

        viewModel.submitTemplateForm()
        advanceUntilIdle()

        assertNull(viewModel.templateForm.value)
        val edited = awaitTemplates(viewModel) { list -> list.any { it.template.name == "Dois dias v2" } }
        assertEquals("o mesmo treino, não um novo", 3, edited.size)
        val template = edited.single { it.template.id == manyDaysId }
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), template.scheduledDays)
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY), daysOf(manyDaysId))
        assertEquals(2, template.template.orderInProgram)
        assertEquals(templates[2].template.syncId, template.template.syncId)
        assertTrue(
            database.syncOutboxDao().pendingFor(ownerUid)
                .any { it.entityType == SyncEntityType.WORKOUT_TEMPLATE.name && it.entitySyncId == template.template.syncId }
        )
    }

    @Test
    fun `cancelar descarta o rascunho sem escrever nada`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        val templates = awaitTemplates(viewModel) { it.size == 3 }
        viewModel.openEditTemplateForm(templates[1])
        viewModel.toggleTemplateDay(DayOfWeek.SUNDAY)
        viewModel.onTemplateNameChanged("Outro nome")

        viewModel.dismissTemplateForm()
        advanceUntilIdle()

        assertNull(viewModel.templateForm.value)
        assertEquals(listOf(DayOfWeek.MONDAY), daysOf(oneDayId))
        assertEquals("Um dia", database.workoutDao().getTemplateById(oneDayId)!!.name)
        // Reabrir parte do persistido, não do rascunho descartado.
        viewModel.openEditTemplateForm(templates[1])
        assertEquals(setOf(DayOfWeek.MONDAY), viewModel.templateForm.value!!.scheduledDays)
    }
}
