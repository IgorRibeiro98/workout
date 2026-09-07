package com.example.data.auth

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.ExerciseEntity
import com.example.data.local.SessionStatus
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.domain.auth.AuthOutcome
import com.example.domain.auth.AuthSourceInspection
import com.example.domain.auth.FakeAuthGateway
import com.example.domain.auth.SparkAccount
import com.example.presentation.account.AccountViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Entrar e sair da conta **não podem tocar em dado local**.
 *
 * É o requisito bloqueante da T16.1: login não sobe, não baixa, não atribui dono e não gera
 * identidade global; logout não apaga histórico; e trocar de conta não reassocia o que já existe.
 * A associação entre dado local e conta é assunto da T16.3+.
 *
 * O teste usa Room de verdade, tira uma fotografia **linha a linha** das tabelas de treino, faz o
 * ciclo completo (entrar → sair → entrar com outra conta) e exige a mesma fotografia no fim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AuthLocalDataIsolationTest {

    private lateinit var database: AppDatabase
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Os ViewModels do teste, para que o `viewModelScope` deles seja encerrado.
     *
     * O `init` deles coleta um flow que não termina; sem cancelar, a corrotina segue viva em
     * `Dispatchers.Main` depois do teste, e o `setMain`/`resetMain` seguinte a encontra lendo o
     * dispatcher no meio da troca. Quem falha, então, é outra classe.
     */
    private val viewModels = ViewModelStore()
    private var viewModelKeys = 0

    private fun trackedAccountViewModel(vm: AccountViewModel) =
        vm.also { viewModels.put("account-${viewModelKeys++}", it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        // Encerra quem ainda coleta antes de fechar o banco e devolver o dispatcher.
        viewModels.clear()
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `entrar, sair e trocar de conta nao alteram treinos, sessoes e historico`() = runTest {
        val dao = database.workoutDao()
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa Base"))
        val exerciseId = dao.insertExercise(
            ExerciseEntity(name = "Supino Reto", primaryMuscle = "Peito")
        )
        val templateId = dao.insertTemplate(
            WorkoutTemplateEntity(programId = programId, name = "Treino A")
        )
        dao.insertSession(
            WorkoutSessionEntity(
                templateId = templateId,
                startedAt = 1_000L,
                finishedAt = 2_000L,
                status = SessionStatus.COMPLETED.name,
                templateNameSnapshot = "Treino A"
            )
        )

        val before = snapshot()
        assertTrue("o teste precisa de dado para provar alguma coisa", before.isNotBlank())

        val gateway = FakeAuthGateway()
        val viewModel = trackedAccountViewModel(AccountViewModel(gateway))

        gateway.nextOutcome = AuthOutcome.Success(
            SparkAccount(uid = "uid-A", displayName = "A", email = "a@example.com")
        )
        viewModel.signIn(context)
        assertEquals("login não pode alterar dado local", before, snapshot())

        viewModel.signOut()
        assertEquals("logout não pode apagar dado local", before, snapshot())

        gateway.nextOutcome = AuthOutcome.Success(SparkAccount(uid = "uid-B", displayName = "B"))
        viewModel.signIn(context)
        assertEquals("troca de conta não pode reassociar dado local", before, snapshot())

        // E o histórico continua resolvível pelos mesmos identificadores locais.
        assertEquals("Supino Reto", dao.getExerciseById(exerciseId)?.name)
        assertEquals("uid-B", viewModel.uiState.value.account?.uid)
    }

    @Test
    fun `a fronteira de autenticacao nao conhece Room nem DataStore`() {
        // Prova estrutural, não de comportamento: se um DAO, repositório ou DataStore aparecer em
        // `data/auth`, um login passa a ter *por onde* mexer em dado local.
        val forbidden = listOf(
            "AppDatabase",
            "WorkoutDao",
            "Repository",
            "SettingsManager",
            "androidx.room",
            "DataStore"
        )

        assertNoneReference("app/src/main/java/com/example/data/auth", forbidden)
    }

    @Test
    fun `o dominio de conta nao conhece persistencia`() {
        assertNoneReference(
            "app/src/main/java/com/example/domain/auth",
            listOf("AppDatabase", "Dao", "Repository", "androidx.room", "DataStore")
        )
    }

    @Test
    fun `o ViewModel de conta nao conhece persistencia`() {
        assertNoneReference(
            "app/src/main/java/com/example/presentation/account",
            listOf("AppDatabase", "Dao", "WorkoutRepository", "SettingsManager", "androidx.room")
        )
    }

    /** Fotografia linha a linha das tabelas que representam o treino do usuário. */
    private fun snapshot(): String = TRACKED_TABLES.joinToString("\n") { table ->
        val rows = mutableListOf<String>()
        database.query("SELECT * FROM $table ORDER BY id", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows += (0 until cursor.columnCount).joinToString(",") { column ->
                    "${cursor.getColumnName(column)}=${cursor.getString(column)}"
                }
            }
        }
        "$table: ${rows.joinToString(" | ")}"
    }

    private fun assertNoneReference(path: String, forbidden: List<String>) {
        val files = AuthSourceInspection.sources(path)
        assertTrue("nenhum arquivo encontrado em $path", files.isNotEmpty())

        // Comentários fora: a documentação desta fronteira cita justamente os nomes proibidos
        // para explicar por que eles não podem aparecer no código.
        val offenders = files.filter { file ->
            val code = AuthSourceInspection.code(file)
            forbidden.any { code.contains(it) }
        }

        assertTrue(
            "$path encostou em persistência: ${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    private companion object {
        val TRACKED_TABLES = listOf(
            "workout_programs",
            "workout_templates",
            "workout_template_exercises",
            "workout_sessions",
            "exercise_sessions",
            "set_logs",
            "exercises"
        )
    }
}
