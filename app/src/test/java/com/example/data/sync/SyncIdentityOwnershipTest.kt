package com.example.data.sync

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.AppDatabase
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.WorkoutProgramEntity
import com.example.data.repository.BodyMeasurementRepository
import com.example.data.repository.WorkoutRepository
import com.example.domain.auth.AuthOutcome
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Identidade global sem conta, e conta sem adoção silenciosa (T16.3).
 *
 * Duas invariantes que precisam valer juntas:
 *
 * 1. `syncId` responde "qual entidade é esta" e é gerado **offline**, sem login, sem backend, sem
 *    rede. Entrar, sair e trocar de conta não o regeneram.
 * 2. Ter um Firebase UID **não** dá dono a dado local. Se bastasse existir um login, a Conta B
 *    herdaria em silêncio o histórico da Conta A no mesmo aparelho.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class SyncIdentityOwnershipTest {

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
    fun `sem conta, criar e editar continuam funcionando e a entidade ja nasce com syncId`() = runTest {
        // Nenhum gateway de autenticação participa deste caminho: é o Spark local-first.
        val repository = WorkoutRepository(database.workoutDao())
        val measurements = BodyMeasurementRepository(database.bodyMeasurementDao())

        val programId = repository.let { database.workoutDao().insertProgram(WorkoutProgramEntity(name = "Programa")) }
        val templateId = repository.addTemplate(programId, "Treino A", "A", 0)
        val measurementId = measurements.insertMeasurement(BodyMeasurementEntity(date = 10, weightKg = 80f))

        val template = database.workoutDao().getTemplateById(templateId)!!
        assertTrue("template deveria nascer com syncId", template.syncId.isNotBlank())
        assertNotNull("o syncId precisa ser um UUID", UUID.fromString(template.syncId))
        assertTrue(database.bodyMeasurementDao().getMeasurementByIdSync(measurementId)!!.syncId.isNotBlank())

        // Editar não troca a identidade.
        val renamed = template.copy(name = "Treino A (revisado)")
        database.workoutDao().updateTemplate(renamed)
        assertEquals(template.syncId, database.workoutDao().getTemplateById(templateId)!!.syncId)

        // E, sem nuvem associada, nada é registrado para envio.
        assertEquals(0, database.syncOutboxDao().count())
    }

    @Test
    fun `entrar, sair e trocar de conta nao regeneram syncId nem dao dono ao dado local`() = runTest {
        val dao = database.workoutDao()
        val programId = dao.insertProgram(WorkoutProgramEntity(name = "Programa"))
        val repository = WorkoutRepository(dao)
        val templateId = repository.addTemplate(programId, "Treino A", "A", 0)
        val sessionId = dao.insertSession(
            com.example.data.local.WorkoutSessionEntity(
                templateId = templateId,
                startedAt = 1_000L,
                finishedAt = 2_000L,
                status = com.example.data.local.SessionStatus.COMPLETED.name
            )
        )

        val identitiesBefore = identities()
        assertTrue(identitiesBefore.isNotEmpty())

        val gateway = FakeAuthGateway()
        val viewModel = trackedAccountViewModel(AccountViewModel(gateway))

        gateway.nextOutcome = AuthOutcome.Success(SparkAccount(uid = "uid-A"))
        viewModel.signIn(context)
        assertEquals("login não pode regenerar syncId", identitiesBefore, identities())
        assertEquals("login não pode criar mutação remota", 0, database.syncOutboxDao().count())

        viewModel.signOut()
        assertEquals("logout não pode regenerar syncId", identitiesBefore, identities())

        gateway.nextOutcome = AuthOutcome.Success(SparkAccount(uid = "uid-B"))
        viewModel.signIn(context)
        assertEquals("troca de conta não pode regenerar syncId", identitiesBefore, identities())

        // O dado continua sem dono: nenhuma entrada da Outbox aponta para a Conta B.
        assertEquals(0, database.syncOutboxDao().count())
        assertEquals("uid-B", viewModel.uiState.value.account?.uid)
        assertNotNull(dao.getSessionById(sessionId))
    }

    @Test
    fun `o estado padrao da nuvem e desligado e ele nao deriva do login`() = runTest {
        // Desde a T16.4 quem responde "de quem é este banco" é o vínculo do dataset, no Room.
        // A pergunta e a resposta são as mesmas da T16.3; o que mudou é onde o estado mora.
        val provider = com.example.data.backup.CloudDataBindingScopeProvider(
            database.cloudDataBindingDao()
        )

        assertEquals(CloudSyncScope.Disabled, provider.current())

        val gateway = FakeAuthGateway()
        val viewModel = trackedAccountViewModel(AccountViewModel(gateway))
        gateway.nextOutcome = AuthOutcome.Success(SparkAccount(uid = "uid-A"))
        viewModel.signIn(context)

        // Autenticado, e a nuvem continua desligada: a adoção é um ato explícito (T16.4).
        assertEquals(CloudSyncScope.Disabled, provider.current())
    }

    @Test
    fun `so a ativacao explicita associa o dado a uma conta`() = runTest {
        val dao = database.cloudDataBindingDao()
        val provider = com.example.data.backup.CloudDataBindingScopeProvider(dao)

        dao.insertIfAbsent(
            com.example.data.backup.CloudDataBindingEntity(
                ownerUid = "uid-A",
                state = CloudSyncState.PREPARING.name,
                boundAt = 1L,
                deviceId = "device"
            )
        )
        assertEquals(CloudSyncScope.Preparing("uid-A"), provider.current())

        dao.markBackupSucceeded(
            ownerUid = "uid-A",
            state = CloudSyncState.ENABLED.name,
            backupId = "backup-1",
            backupAt = 2L
        )
        assertEquals(CloudSyncScope.Enabled("uid-A"), provider.current())
    }

    @Test
    fun `um vinculo existente nao e sobrescrito por outra conta`() = runTest {
        val dao = database.cloudDataBindingDao()
        val provider = com.example.data.backup.CloudDataBindingScopeProvider(dao)

        dao.insertIfAbsent(
            com.example.data.backup.CloudDataBindingEntity(
                ownerUid = "uid-A",
                state = CloudSyncState.ENABLED.name,
                boundAt = 1L,
                deviceId = "device"
            )
        )
        // A conta B tentando adotar o mesmo banco não substitui o dono: trocar de conta no
        // aparelho não pode transferir o histórico de A para B.
        dao.insertIfAbsent(
            com.example.data.backup.CloudDataBindingEntity(
                ownerUid = "uid-B",
                state = CloudSyncState.ENABLED.name,
                boundAt = 2L,
                deviceId = "device"
            )
        )

        assertEquals(CloudSyncScope.Enabled("uid-A"), provider.current())
        assertEquals("uid-A", dao.get()?.ownerUid)
    }

    @Test
    fun `editar uma medida corporal preserva a identidade global dela`() = runTest {
        val measurements = BodyMeasurementRepository(database.bodyMeasurementDao())
        val id = measurements.insertMeasurement(BodyMeasurementEntity(date = 10, weightKg = 80f))
        val original = database.bodyMeasurementDao().getMeasurementByIdSync(id)!!

        // A tela de medidas remonta a entidade do formulário ao editar: ela chega com `syncId`
        // novo e o mesmo `id`. Gravar esse valor trocaria a identidade de uma medida existente.
        measurements.updateMeasurement(
            BodyMeasurementEntity(id = id, date = 10, weightKg = 79f)
        )

        val updated = database.bodyMeasurementDao().getMeasurementByIdSync(id)!!
        assertEquals(79f, updated.weightKg)
        assertEquals("editar não pode trocar o syncId", original.syncId, updated.syncId)
    }

    @Test
    fun `deviceId e aleatorio, estavel e nao vem do hardware`() = runTest {
        val settings = com.example.data.datastore.SettingsManager(context)
        val provider = DeviceIdProvider(settings)

        val first = provider.deviceId()
        assertNotNull("deviceId precisa ser um UUID aleatório", UUID.fromString(first))
        assertEquals("a identidade da instalação não é rotativa", first, provider.deviceId())
        // Outro provider sobre o mesmo armazenamento devolve o mesmo valor — é a instalação, não
        // o objeto, que tem identidade.
        assertEquals(first, DeviceIdProvider(settings).deviceId())
        assertFalse(first == android.provider.Settings.Secure.ANDROID_ID)
    }

    private fun identities(): String = buildString {
        listOf("workout_programs", "workout_templates", "workout_sessions").forEach { table ->
            database.query("SELECT id, syncId FROM $table ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    append(table).append('#').append(cursor.getLong(0)).append('=')
                        .append(cursor.getString(1)).append('\n')
                }
            }
        }
    }
}
