package com.example.data.sync

import com.example.data.backup.BackupCanonicalJson
import com.example.data.local.BodyMeasurementDao
import com.example.data.local.BodyMeasurementEntity
import com.example.data.local.CheckInEntity
import com.example.data.local.ExerciseEntity
import com.example.data.local.ExerciseSessionEntity
import com.example.data.local.SetLogEntity
import com.example.data.local.WorkoutDao
import com.example.data.local.WorkoutProgramEntity
import com.example.data.local.WorkoutSessionEntity
import com.example.data.local.WorkoutTemplateEntity
import com.example.data.local.WorkoutTemplateExerciseEntity
import com.example.data.sync.dto.BodyMeasurementSyncDto
import com.example.data.sync.dto.CheckInSyncDto
import com.example.data.sync.dto.CustomExerciseSyncDto
import com.example.data.sync.dto.ExerciseRefDto
import com.example.data.sync.dto.WorkoutProgramSyncDto
import com.example.data.sync.dto.WorkoutSessionSyncDto
import com.example.data.sync.dto.WorkoutTemplateSyncDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Aplica no Room as mudanças que vieram do servidor (T16.6).
 *
 * ```text
 * pull → validação → [ transação Room:  domínio + metadata + conflitos + cursor ] → COMMIT
 * ```
 *
 * ## Este é o `MutationOrigin.REMOTE_SYNC` do Spark
 *
 * A escrita acontece **fora** do [SyncMutationCoordinator], de propósito e em um único lugar —
 * exatamente como a `RestoreTransaction` da T16.5. Pelo coordenador, aplicar 40 mudanças remotas
 * registraria 40 `UPSERT` na Outbox, e o aparelho devolveria ao servidor o que acabou de receber
 * dele: um laço que nunca fecha. Há teste sobre isso.
 *
 * ## Nenhum efeito colateral de domínio
 *
 * As escritas passam por DAOs, não por `WorkoutRepository`/`WorkoutEngine`. Quem publica evento de
 * gamificação no Spark é o motor de execução, quando o usuário termina uma série ou um treino —
 * inserir uma sessão concluída que veio de outro aparelho **não** publica nada. Receber histórico
 * não dá XP, não desbloqueia conquista, não celebra recorde e não notifica "treino concluído".
 * Gamificação é derivada, e as reconciliações que já existem a reconstroem.
 *
 * ## O que ele nunca faz
 *
 * - **não sobrescreve dado local sujo.** Se o agregado tem alteração pendente e o conteúdo remoto
 *   é outro, a mudança remota é preservada como conflito e o Room não é tocado;
 * - **não reescreve histórico.** Uma sessão concluída que já existe localmente com conteúdo
 *   divergente é conflito de integridade, nunca sobrescrita;
 * - **não adivinha identidade.** Um `canonicalId` que não resolve localmente faz a página inteira
 *   falhar e o cursor não avançar — nunca uma aproximação por nome.
 */
class SyncRemoteApplier(
    private val transactions: TransactionRunner,
    private val workoutDao: WorkoutDao,
    private val bodyMeasurementDao: BodyMeasurementDao,
    private val outboxDao: SyncOutboxDao,
    private val metadataDao: EntitySyncMetadataDao,
    private val cursorDao: SyncCursorDao,
    private val conflictDao: SyncConflictDao,
    private val snapshotBuilder: SyncAggregateSnapshotBuilder,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * `Json` estrito: campo desconhecido é **erro**, não algo a ignorar.
     *
     * É o que impede uma versão antiga do app de aceitar pela metade um payload criado por uma
     * versão nova, gravar o que entendeu e destruir o resto na próxima escrita. Preferimos pausar
     * o sync naquele cursor e pedir atualização.
     */
    private val json = Json { ignoreUnknownKeys = false }

    /**
     * Aplica [changes] e avança o cursor — em **uma** transação.
     *
     * A página é processada em duas passagens:
     *
     * 1. **fora** da transação, cada mudança é decodificada e validada em ordem de sequência. A
     *    primeira que este app não sabe ler trunca a página: o que vem antes dela é aplicado, o
     *    resto fica para quando o app for atualizado, e o cursor para exatamente ali. Ignorar e
     *    seguir perderia a mudança para sempre;
     * 2. **dentro** da transação, o prefixo é aplicado em ordem de dependência (exercícios
     *    personalizados e programas antes dos treinos; treinos antes das sessões; sessões antes
     *    dos check-ins). A ordem entre mudanças **do mesmo agregado** continua sendo a da
     *    sequência, então o estado final é o mesmo de aplicar uma a uma.
     *
     * Qualquer falha dentro da transação desfaz tudo, inclusive o cursor: o aparelho volta a
     * pedir a mesma página, e reaplicá-la é seguro porque cada passo é idempotente.
     */
    suspend fun apply(
        ownerUid: String,
        changes: List<SyncChangeDto>
    ): SyncApplyResult {
        if (changes.isEmpty()) return SyncApplyResult()

        // Uma leitura por página, e não uma por item: a sessão em execução não muda no meio do
        // processamento de uma página — quem a inicia e a encerra é o motor de execução, e ele não
        // roda enquanto o sync está segurando a `CloudOperationLock`.
        val activeTemplateId = workoutDao.getActiveSessionTemplateId()

        val decoded = mutableListOf<DecodedChange>()
        var stop: SyncApplyStop? = null
        for (change in changes) {
            val result = decode(change)
            if (result == null) {
                stop = SyncApplyStop.Unsupported(change.serverSequence, change.entityType)
                break
            }
            if (interferesWithActiveWorkout(result, activeTemplateId)) {
                // O treino está sendo executado agora. A mudança fica no change log, o cursor para
                // antes dela, e o ciclo seguinte — depois que o treino terminar — a aplica.
                stop = SyncApplyStop.DeferredActiveWorkout(change.serverSequence)
                break
            }
            decoded += result
        }

        if (decoded.isEmpty()) return SyncApplyResult(stop = stop)

        val ordered = decoded.sortedWith(compareBy({ it.rank }, { it.change.serverSequence }))
        val cursor = decoded.last().change.serverSequence

        return try {
            transactions.runInTransaction {
                var applied = 0
                var converged = 0
                var conflicts = 0

                ordered.forEach { item ->
                    when (handle(ownerUid, item)) {
                        Outcome.APPLIED -> applied++
                        Outcome.CONVERGED -> converged++
                        Outcome.CONFLICT -> conflicts++
                    }
                }

                // O cursor é gravado **aqui dentro**, depois de tudo ter dado certo. Fora da
                // transação ele descreveria um progresso que talvez não exista.
                cursorDao.upsert(
                    SyncCursorEntity(
                        ownerUid = ownerUid,
                        lastPulledServerSequence = cursor,
                        lastSyncedAt = clock()
                    )
                )

                SyncApplyResult(
                    cursor = cursor,
                    applied = applied,
                    converged = converged,
                    conflicts = conflicts,
                    stop = stop
                )
            }
        } catch (e: SyncRemoteApplyException) {
            // Referência que não resolve neste aparelho. Nada foi escrito, o cursor não andou, e
            // a próxima tentativa pede a mesma página.
            SyncApplyResult(stop = SyncApplyStop.Failed(e.serverSequence, e.reason))
        } catch (e: android.database.sqlite.SQLiteException) {
            // O banco recusou a escrita — colisão de índice único, chave estrangeira, `RESTRICT`.
            // Sem isto a exceção sobe por `syncNow()`/`onAppForeground()` e **derruba o processo**:
            // um aparelho nesse estado fecha a cada abertura, e o cursor nunca avança.
            //
            // O desfecho é o mesmo de uma referência que não resolve, e pelo mesmo motivo: a
            // transação desfez tudo, o cursor ficou onde estava e a próxima tentativa pede a mesma
            // página. Pausar com um motivo legível é o que permite diagnosticar; morrer não é.
            SyncApplyResult(
                stop = SyncApplyStop.Failed(
                    // A primeira da página: nada foi escrito, então é dali que a próxima tentativa
                    // recomeça. Apontar para a última sugeriria um progresso que não houve.
                    decoded.first().change.serverSequence,
                    // O nome da classe, nunca a mensagem do SQLite: ela carrega valores da linha
                    // recusada, e este pacote não registra conteúdo em lugar nenhum.
                    "${REASON_LOCAL_WRITE_REFUSED}:${e.javaClass.simpleName}"
                )
            )
        }
    }

    // ------------------------------------------------------------------ validação

    private fun decode(change: SyncChangeDto): DecodedChange? {
        val type = SyncEntityType.entries.firstOrNull { it.name == change.entityType } ?: return null
        val operation = SyncOperation.entries.firstOrNull { it.name == change.operation } ?: return null
        if (change.entitySchemaVersion != SyncProtocol.SUPPORTED_ENTITY_SCHEMA_VERSION) return null
        if (change.entitySyncId.isBlank()) return null

        if (operation == SyncOperation.DELETE) {
            // Um tombstone não carrega conteúdo: identidade e `serverRevision` são tudo. Não há
            // payload para decodificar, e exigir um recusaria uma exclusão perfeitamente válida.
            return DecodedChange(change = change, type = type, operation = operation, payload = null)
        }

        val body = change.payload ?: return null

        val payload = try {
            when (type) {
                SyncEntityType.WORKOUT_PROGRAM ->
                    json.decodeFromJsonElement(WorkoutProgramSyncDto.serializer(), body)
                SyncEntityType.WORKOUT_TEMPLATE ->
                    json.decodeFromJsonElement(WorkoutTemplateSyncDto.serializer(), body)
                SyncEntityType.WORKOUT_SESSION ->
                    json.decodeFromJsonElement(WorkoutSessionSyncDto.serializer(), body)
                SyncEntityType.CUSTOM_EXERCISE ->
                    json.decodeFromJsonElement(CustomExerciseSyncDto.serializer(), body)
                SyncEntityType.BODY_MEASUREMENT ->
                    json.decodeFromJsonElement(BodyMeasurementSyncDto.serializer(), body)
                SyncEntityType.CHECK_IN ->
                    json.decodeFromJsonElement(CheckInSyncDto.serializer(), body)
            }
        } catch (e: Exception) {
            // Payload que não casa com o contrato deste app. Servidor confiável não significa
            // aceitar qualquer coisa cegamente.
            return null
        }

        // A identidade declarada precisa bater com a do payload. Um cabeçalho dizendo uma coisa e
        // um conteúdo dizendo outra viraria dado gravado no lugar errado.
        val declared = when (payload) {
            is WorkoutProgramSyncDto -> payload.syncId
            is WorkoutTemplateSyncDto -> payload.syncId
            is WorkoutSessionSyncDto -> payload.syncId
            is CustomExerciseSyncDto -> payload.syncId
            is BodyMeasurementSyncDto -> payload.syncId
            is CheckInSyncDto -> payload.syncId
            else -> null
        }
        if (declared != change.entitySyncId) return null

        // Só sessão concluída viaja: `IN_PROGRESS`/`PAUSED` é execução **daquele** aparelho.
        if (payload is WorkoutSessionSyncDto && payload.status != COMPLETED_STATUS) return null

        return DecodedChange(
            change = change,
            type = type,
            operation = operation,
            payload = payload
        )
    }

    /**
     * A mudança mexeria no treino que está sendo executado **agora**?
     *
     * O motor de execução lê a configuração de série do template durante o treino
     * (`getTemplateExercise`): alvo de séries, faixa de repetições e descanso. Substituir os
     * exercícios do template no meio de uma execução mudaria esses valores debaixo do usuário — ou
     * removeria a linha do exercício que ele está fazendo.
     *
     * A política é **adiar**, não descartar e não aplicar: a mudança continua no change log do
     * servidor, o cursor para antes dela, e o próximo ciclo a aplica quando não houver mais sessão
     * ativa. Uma execução dura minutos; o sync não é tempo real e pode esperar.
     *
     * Só o treino **em execução** é adiado. Outro treino, uma medida ou uma sessão concluída que
     * chegue no mesmo ciclo continua sendo aplicada normalmente, desde que venha antes na
     * sequência.
     */
    private suspend fun interferesWithActiveWorkout(
        item: DecodedChange,
        activeTemplateId: Long?
    ): Boolean {
        if (activeTemplateId == null) return false
        return when (item.type) {
            SyncEntityType.WORKOUT_TEMPLATE ->
                workoutDao.getTemplateBySyncId(item.change.entitySyncId)?.id == activeTemplateId

            // Apagar o programa leva os treinos dele junto (cascade). Se o treino em execução for
            // um deles, aplicar agora removeria por baixo do usuário a configuração de série que o
            // motor está lendo. A sessão continua; o programa é apagado quando ela terminar.
            SyncEntityType.WORKOUT_PROGRAM -> {
                if (item.operation != SyncOperation.DELETE) return false
                workoutDao.getTemplateById(activeTemplateId)?.programId ==
                    workoutDao.getProgramBySyncId(item.change.entitySyncId)?.id
            }

            else -> false
        }
    }

    // ------------------------------------------------------------------ decisão por mudança

    private suspend fun handle(ownerUid: String, item: DecodedChange): Outcome {
        val change = item.change
        val type = item.type

        val metadata = metadataDao.get(ownerUid, type.name, change.entitySyncId)
        if (metadata != null && metadata.lastKnownServerRevision >= change.serverRevision) {
            // Eco: esta revision já é conhecida — normalmente porque foi este aparelho que a
            // produziu. Nada a escrever, nenhuma Outbox nova, nenhum laço.
            return Outcome.CONVERGED
        }

        val entries = outboxDao.entriesFor(ownerUid, type.name, change.entitySyncId)
        val localIntent = entries.lastOrNull()?.operation

        if (item.operation == SyncOperation.DELETE) {
            return handleRemoteDelete(ownerUid, item, localIntent)
        }

        if (entries.isNotEmpty()) {
            if (localIntent == SyncOperation.DELETE.name) {
                // Este aparelho apagou; o servidor tem uma alteração mais nova. Nem se recria o
                // que o usuário apagou, nem se descarta a edição que ele nunca viu.
                recordConflict(ownerUid, item, SyncConflictKind.LOCAL_DELETED_REMOTE_MODIFIED, null)
                return Outcome.CONFLICT
            }
            val localHash = localHashOf(type, change.entitySyncId)
            if (localHash != null && localHash == change.payloadHash) {
                // Local sujo, mas com o **mesmo conteúdo** do remoto. Não é conflito: as duas
                // cópias já dizem a mesma coisa, e provar isso é o que o hash canônico faz.
                outboxDao.acknowledge(ownerUid, entries.map { it.id })
                conflictDao.clear(ownerUid, type.name, change.entitySyncId)
                rememberRevision(ownerUid, type, change)
                return Outcome.CONVERGED
            }
            recordConflict(ownerUid, item, SyncConflictKind.REMOTE_AHEAD_LOCAL_DIRTY, localHash)
            return Outcome.CONFLICT
        }

        // Local limpo: a cópia remota é a mais nova e pode ser aplicada.
        if (type == SyncEntityType.WORKOUT_SESSION) {
            val local = workoutDao.getSessionIdBySyncId(change.entitySyncId)
            if (local != null) {
                val localHash = localHashOf(type, change.entitySyncId)
                if (localHash != change.payloadHash) {
                    // Histórico não é documento colaborativo. Divergência é conflito, e a sessão
                    // local continua exatamente como está.
                    recordConflict(ownerUid, item, SyncConflictKind.IMMUTABLE_HISTORY, localHash)
                    return Outcome.CONFLICT
                }
                rememberRevision(ownerUid, type, change)
                return Outcome.CONVERGED
            }
        }

        write(item)
        conflictDao.clear(ownerUid, type.name, change.entitySyncId)
        rememberRevision(ownerUid, type, change)
        return Outcome.APPLIED
    }

    /**
     * Uma exclusão feita em outro aparelho (T16.7).
     *
     * ```text
     * local limpo          →  apaga aqui também, e **não** registra Outbox
     * local com DELETE     →  os dois apagaram: converge
     * local com alteração  →  conflito, e nada é apagado
     * ```
     *
     * O caso do meio da lista é o que impede um laço: aplicar uma exclusão remota pelo caminho de
     * domínio registraria um `DELETE` de saída, que voltaria ao servidor como se fosse uma decisão
     * nova deste aparelho. Aqui a escrita é por DAO, fora do coordenador de mutações — a mesma
     * regra de sempre.
     *
     * O último é o que impede perda: uma alteração local pendente é trabalho que a pessoa fez e
     * que quem apagou nunca viu. As duas intenções ficam guardadas até ela escolher.
     */
    private suspend fun handleRemoteDelete(
        ownerUid: String,
        item: DecodedChange,
        localIntent: String?
    ): Outcome {
        val change = item.change
        val type = item.type

        if (localIntent == SyncOperation.DELETE.name) {
            // Os dois aparelhos apagaram a mesma coisa. A intenção local foi cumprida pelo
            // tombstone que já existe no servidor: confirmar a entrada aqui evita um push que
            // voltaria como `ALREADY_APPLIED` de qualquer forma.
            outboxDao.acknowledge(
                ownerUid,
                outboxDao.entriesFor(ownerUid, type.name, change.entitySyncId).map { it.id }
            )
            conflictDao.clear(ownerUid, type.name, change.entitySyncId)
            rememberRevision(ownerUid, type, change)
            return Outcome.CONVERGED
        }

        if (localIntent != null) {
            recordConflict(ownerUid, item, SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED, null)
            return Outcome.CONFLICT
        }

        if (!deleteLocally(ownerUid, item)) return Outcome.CONFLICT

        conflictDao.clear(ownerUid, type.name, change.entitySyncId)
        // A revision do tombstone é guardada: é ela que faz este aparelho reconhecer a exclusão
        // como conhecida se a mesma mudança voltar, e é ela que impede um `UPSERT` futuro deste
        // agregado de nascer achando que o servidor está atrás.
        rememberRevision(ownerUid, type, change)
        return Outcome.APPLIED
    }

    /**
     * Apaga localmente o que o servidor diz ter sido apagado.
     *
     * Devolve `false` quando a exclusão foi **registrada como conflito** em vez de aplicada; nada
     * é escrito nessa hipótese.
     */
    private suspend fun deleteLocally(ownerUid: String, item: DecodedChange): Boolean {
        return when (deleteAggregateLocally(ownerUid, item.type, item.change.entitySyncId)) {
            SyncLocalDeleteGuard.Deleted -> true

            // Um treino deste programa tem alteração local que ainda não subiu. Apagar levaria o
            // trabalho da pessoa junto, sem ela ver nada — então nada é apagado e a decisão volta
            // para ela.
            SyncLocalDeleteGuard.PendingChildMutations -> {
                recordConflict(ownerUid, item, SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED, null)
                false
            }

            // `ON DELETE RESTRICT`: o exercício ainda é usado por um treino deste aparelho.
            //
            // A ordenação "exclusões por último, do filho para o pai" resolve isto **dentro** de
            // uma página — e só dentro dela. Quando a exclusão do exercício e a edição do treino
            // que ainda o referencia caem em páginas diferentes, pausar aqui era um impasse
            // permanente: a página falhava, o cursor não andava, e a edição que removeria a
            // referência ficava do outro lado do cursor, inalcançável para sempre.
            //
            // Registrar conflito é o que quebra o impasse sem apagar nada: o exercício continua
            // aqui, a exclusão remota fica guardada com a revision do tombstone, o cursor anda, e
            // a página seguinte — que pode ser exatamente a que remove a referência — é aplicada.
            // A partir daí "confirmar a exclusão" funciona, porque o `ON DELETE RESTRICT` já não
            // tem o que restringir.
            SyncLocalDeleteGuard.StillReferenced -> {
                recordConflict(ownerUid, item, SyncConflictKind.REMOTE_DELETED_LOCAL_MODIFIED, null)
                false
            }
        }
    }

    /**
     * Apaga um agregado deste aparelho pela identidade global, com as guardas do domínio (T16.7).
     *
     * Público porque a **resolução de conflito** também precisa dele: quando o usuário confirma
     * uma exclusão feita em outro aparelho, o que acontece aqui é exatamente o que aconteceria se
     * a exclusão tivesse chegado com o local limpo. Duplicar esse caminho criaria duas regras de
     * exclusão, e a segunda esqueceria uma das guardas.
     *
     * **Não abre transação**: quem chama já está em uma. E não registra Outbox — apagar o que o
     * servidor mandou apagar não é uma decisão nova deste aparelho.
     *
     * Os cascades são os do schema, os mesmos que a exclusão feita pelo usuário já usa: um treino
     * leva seus exercícios de treino, uma sessão leva `exercise_sessions` e `set_logs`, um programa
     * leva seus treinos. O sync não inventa uma segunda regra de exclusão.
     */
    suspend fun deleteAggregateLocally(
        ownerUid: String,
        type: SyncEntityType,
        entitySyncId: String
    ): SyncLocalDeleteGuard {
        when (type) {
            SyncEntityType.WORKOUT_PROGRAM -> {
                val program = workoutDao.getProgramBySyncId(entitySyncId)
                    ?: return SyncLocalDeleteGuard.Deleted
                if (workoutDao.countPendingTemplateMutationsForProgram(ownerUid, program.id) > 0) {
                    return SyncLocalDeleteGuard.PendingChildMutations
                }
                workoutDao.deleteProgramBySyncId(entitySyncId)
            }

            SyncEntityType.WORKOUT_TEMPLATE -> workoutDao.deleteTemplateBySyncId(entitySyncId)

            // Apagar uma sessão concluída não toca em template nenhum, e apagar um template nunca
            // toca em sessão: `workout_sessions` não tem chave estrangeira para
            // `workout_templates` — o histórico carrega `templateNameSnapshot` e sobrevive ao
            // plano que o originou.
            SyncEntityType.WORKOUT_SESSION -> workoutDao.deleteSessionBySyncId(entitySyncId)

            SyncEntityType.CUSTOM_EXERCISE -> {
                val exercise = workoutDao.getExerciseBySyncId(entitySyncId)
                    ?: return SyncLocalDeleteGuard.Deleted
                if (workoutDao.countTemplateReferencesToExercise(exercise.id) > 0) {
                    return SyncLocalDeleteGuard.StillReferenced
                }
                workoutDao.deleteCustomExerciseBySyncId(entitySyncId)
            }

            SyncEntityType.CHECK_IN -> workoutDao.deleteCheckInBySyncId(entitySyncId)

            SyncEntityType.BODY_MEASUREMENT ->
                bodyMeasurementDao.deleteMeasurementBySyncId(entitySyncId)
        }
        return SyncLocalDeleteGuard.Deleted
    }

    /**
     * Escreve no Room um agregado que veio do servidor, fora de um ciclo de pull (T16.7).
     *
     * É o caminho da resolução "usar a versão da nuvem": o payload guardado no conflito passa pela
     * **mesma** decodificação estrita e pela **mesma** escrita que uma mudança recém-chegada. Um
     * segundo caminho de escrita aqui seria uma segunda autoridade sobre o que é um treino válido.
     *
     * `false` significa que o payload não pôde ser lido neste aparelho — versão futura, campo
     * desconhecido, identidade que não bate. Nada é escrito, e quem chama desfaz a transação.
     *
     * **Não abre transação**, e pode lançar [SyncRemoteApplyException] quando uma referência do
     * payload não resolve aqui; quem chama trata.
     */
    suspend fun writeRemoteAggregate(
        type: SyncEntityType,
        entitySyncId: String,
        entitySchemaVersion: Int,
        payload: JsonElement
    ): Boolean {
        val item = decode(
            SyncChangeDto(
                serverSequence = 0,
                entityType = type.name,
                entitySyncId = entitySyncId,
                entitySchemaVersion = entitySchemaVersion,
                serverRevision = 0,
                operation = SyncOperation.UPSERT.name,
                payloadHash = "",
                payload = payload
            )
        ) ?: return false
        write(item)
        return true
    }

    private suspend fun localHashOf(type: SyncEntityType, syncId: String): String? =
        snapshotBuilder.snapshot(type, syncId)
            ?.let { BackupCanonicalJson.canonicalHash(it.payload).hash }

    private suspend fun rememberRevision(
        ownerUid: String,
        type: SyncEntityType,
        change: SyncChangeDto
    ) {
        metadataDao.upsert(
            EntitySyncMetadataEntity(
                ownerUid = ownerUid,
                entityType = type.name,
                entitySyncId = change.entitySyncId,
                lastKnownServerRevision = change.serverRevision,
                lastSyncedPayloadHash = change.payloadHash,
                lastSyncedAt = clock()
            )
        )
    }

    private suspend fun recordConflict(
        ownerUid: String,
        item: DecodedChange,
        kind: SyncConflictKind,
        localHash: String?
    ) {
        val change = item.change
        // Um conflito do mesmo agregado pode ser detectado duas vezes no mesmo ciclo: primeiro no
        // push (o servidor respondeu `STALE`), depois no pull (a mudança remota chega). O segundo
        // **completa** o primeiro em vez de substituí-lo: a classificação de origem, a
        // `baseRevision` e a tentativa local que ficou bloqueada são justamente o que a T16.7
        // precisa para saber o que aconteceu.
        val existing = conflictDao.get(ownerUid, item.type.name, change.entitySyncId)
        conflictDao.upsert(
            SyncConflictEntity(
                ownerUid = ownerUid,
                entityType = item.type.name,
                entitySyncId = change.entitySyncId,
                // A classificação de origem é preservada — ela diz **onde** a divergência foi
                // detectada, e é o que a resolução precisa saber. A exceção é a exclusão: um
                // conflito de edição concorrente que depois recebe o tombstone deixou de ser
                // aquilo, e continuar oferecendo "usar a versão da nuvem" para algo que não existe
                // mais na nuvem seria mentira.
                kind = if (kind.isDeletion) kind.name else existing?.kind ?: kind.name,
                status = existing?.status ?: SyncConflictStatus.PENDING.name,
                baseRevision = existing?.baseRevision,
                localPayloadHash = localHash ?: existing?.localPayloadHash,
                remoteRevision = change.serverRevision,
                remoteServerSequence = change.serverSequence,
                remotePayloadHash = change.payloadHash.ifEmpty { null },
                // O payload remoto é guardado porque o pull é por cursor: depois que ele passar
                // desta sequência, buscar de novo **esta** versão exigiria um endpoint que não
                // existe. É o lado remoto entre os quais o usuário vai escolher.
                //
                // Numa exclusão não há o que guardar, e o que já estava guardado é preservado: se
                // o conflito nasceu de uma edição concorrente e depois chegou o tombstone, a
                // versão remota anterior continua sendo a única cópia daquele conteúdo aqui.
                remotePayload = change.payload
                    ?.let { BackupCanonicalJson.canonicalize(it) }
                    ?: existing?.remotePayload,
                clientMutationId = existing?.clientMutationId,
                detectedAt = existing?.detectedAt ?: clock()
            )
        )
    }

    // ------------------------------------------------------------------ escrita no Room

    private suspend fun write(item: DecodedChange) {
        when (val payload = item.payload) {
            is WorkoutProgramSyncDto -> writeProgram(payload)
            is WorkoutTemplateSyncDto -> writeTemplate(item, payload)
            is WorkoutSessionSyncDto -> writeSession(item, payload)
            is CustomExerciseSyncDto -> writeCustomExercise(payload)
            is BodyMeasurementSyncDto -> writeMeasurement(payload)
            is CheckInSyncDto -> writeCheckIn(payload)
        }
    }

    private suspend fun writeProgram(dto: WorkoutProgramSyncDto) {
        // "Um programa atual" é regra de domínio e continua valendo com o dado que chega de fora:
        // aplicar a mudança sem isso deixaria dois programas marcados como atuais, que nenhuma
        // tela sabe representar.
        if (dto.isCurrent) workoutDao.clearCurrentProgram()

        // A identidade global vem primeiro; o `externalId` é a rede de segurança.
        //
        // Programa importado de manifesto tem identidade **de conteúdo** (`externalId`, com índice
        // `UNIQUE`) e identidade global (`syncId`), e elas nascem separadas em cada aparelho: dois
        // celulares que importaram o mesmo programa antes de adotar a nuvem têm o mesmo
        // `externalId` e `syncId` diferentes. Inserir sem consultar o `externalId` estoura
        // `index_workout_programs_externalId` dentro da transação — e, antes desta correção, o
        // processo inteiro morria a cada abertura do app, com o cursor parado para sempre.
        //
        // Reconciliar é adotar a identidade global que veio da nuvem na linha que já existe aqui:
        // o `localId` é preservado (e com ele os treinos que apontam para ele) e o aparelho passa a
        // falar do mesmo programa que os outros. Duas linhas para o mesmo programa importado é que
        // seriam o dado duplicado que o índice existe para impedir.
        //
        // É a **única** reescrita de `syncId` do app, e ela não contradiz a imutabilidade da §13.1:
        // aquela regra diz que **editar** uma entidade nunca troca a identidade dela. Aqui não há
        // edição — há duas identidades locais para a mesma coisa, criadas independentemente por um
        // importador de conteúdo, e a da nuvem é a que todos os aparelhos já usam.
        val existing = workoutDao.getProgramBySyncId(dto.syncId)
            ?: dto.externalId
                ?.takeIf { it.isNotBlank() }
                ?.let { workoutDao.getProgramByExternalId(it) }
                ?.copy(syncId = dto.syncId)

        if (existing == null) {
            workoutDao.insertProgram(
                WorkoutProgramEntity(
                    name = dto.name,
                    description = dto.description,
                    isCurrent = dto.isCurrent,
                    externalId = dto.externalId,
                    contentVersion = dto.contentVersion,
                    syncId = dto.syncId
                )
            )
        } else {
            // `copy` sobre a linha lida: o `localId` deste aparelho é preservado, e com ele todas
            // as relações locais que apontam para ele.
            workoutDao.updateProgram(
                existing.copy(
                    name = dto.name,
                    description = dto.description,
                    isCurrent = dto.isCurrent,
                    externalId = dto.externalId,
                    contentVersion = dto.contentVersion
                )
            )
        }
    }

    private suspend fun writeTemplate(item: DecodedChange, dto: WorkoutTemplateSyncDto) {
        val existing = workoutDao.getTemplateBySyncId(dto.syncId)
        val programId = dto.programSyncId
            ?.let {
                workoutDao.getProgramBySyncId(it)?.id
                    ?: fail(item, REASON_MISSING_PROGRAM)
            }
            ?: existing?.programId
            ?: fail(item, REASON_MISSING_PROGRAM)

        val templateId = if (existing == null) {
            workoutDao.insertTemplate(
                WorkoutTemplateEntity(
                    programId = programId,
                    name = dto.name,
                    shortIdentifier = dto.shortIdentifier,
                    orderInProgram = dto.orderInProgram,
                    dayOfWeek = dto.dayOfWeek,
                    syncId = dto.syncId
                )
            )
        } else {
            workoutDao.updateTemplate(
                existing.copy(
                    programId = programId,
                    name = dto.name,
                    shortIdentifier = dto.shortIdentifier,
                    orderInProgram = dto.orderInProgram,
                    dayOfWeek = dto.dayOfWeek
                )
            )
            existing.id
        }

        // O agregado é um snapshot: os exercícios do treino são substituídos inteiros. Tentar
        // casar linha a linha exigiria identidade em filhos que deliberadamente não a têm (T16.3).
        workoutDao.deleteTemplateExercisesForTemplate(templateId)
        dto.exercises.sortedBy { it.position }.forEach { entry ->
            workoutDao.insertTemplateExercise(
                WorkoutTemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = resolveExercise(item, entry.exercise),
                    // A ordem vem de `position`, que é dado de domínio — nunca da ordem do JSON
                    // nem do `id` que o banco vai atribuir.
                    sortOrder = entry.position,
                    targetSets = entry.targetSets,
                    minReps = entry.minReps,
                    maxReps = entry.maxReps,
                    restDurationSeconds = entry.restDurationSeconds,
                    plannedWeight = entry.plannedWeight,
                    machineLabel = entry.machineLabel,
                    notes = entry.notes
                )
            )
        }
    }

    /**
     * Uma sessão concluída vinda de outro aparelho, recriada **fielmente**.
     *
     * Nada é recalculado: duração, carga, repetições, RPE/RIR, `startedAt` e `finishedAt` entram
     * como estão. E nada é publicado: sem XP, sem conquista, sem recorde, sem notificação.
     */
    private suspend fun writeSession(item: DecodedChange, dto: WorkoutSessionSyncDto) {
        val sessionId = workoutDao.insertSession(
            WorkoutSessionEntity(
                // Histórico sobrevive ao treino que o originou: se o template ainda não chegou, a
                // sessão entra sem ele em vez de ser recusada.
                templateId = dto.templateSyncId?.let { workoutDao.getTemplateBySyncId(it)?.id },
                startedAt = dto.startedAt,
                finishedAt = dto.finishedAt,
                status = dto.status,
                notes = dto.notes,
                templateNameSnapshot = dto.templateNameSnapshot,
                syncId = dto.syncId
            )
        )

        dto.exercises.sortedBy { it.executionOrder }.forEach { exercise ->
            val exerciseSessionId = workoutDao.insertExerciseSession(
                ExerciseSessionEntity(
                    sessionId = sessionId,
                    // Referência que não resolve fica nula, sem inventar substituto: a sessão
                    // continua íntegra porque carrega `exerciseNameSnapshot`.
                    plannedExerciseId = exercise.plannedExercise?.let { optionalExercise(it) },
                    actualExerciseId = exercise.actualExercise?.let { optionalExercise(it) },
                    exerciseNameSnapshot = exercise.exerciseNameSnapshot,
                    sortOrder = exercise.plannedOrder,
                    plannedOrder = exercise.plannedOrder,
                    executionOrder = exercise.executionOrder,
                    startedAt = exercise.startedAt,
                    finishedAt = exercise.finishedAt,
                    notes = exercise.notes,
                    replacementReason = exercise.replacementReason,
                    machineLabelSnapshot = exercise.machineLabelSnapshot,
                    primaryMuscleSnapshot = exercise.primaryMuscleSnapshot,
                    restDurationSecondsSnapshot = exercise.restDurationSecondsSnapshot
                )
            )

            if (exercise.sets.isNotEmpty()) {
                workoutDao.insertSetLogs(
                    exercise.sets.sortedBy { it.setNumber }.map { set ->
                        SetLogEntity(
                            exerciseSessionId = exerciseSessionId,
                            setNumber = set.setNumber,
                            type = set.type,
                            weight = set.weight,
                            repetitions = set.repetitions,
                            completed = set.completed,
                            startedAt = set.startedAt,
                            finishedAt = set.finishedAt,
                            rpe = set.rpe,
                            rir = set.rir,
                            durationSeconds = set.durationSeconds
                        )
                    }
                )
            }
        }
    }

    private suspend fun writeCustomExercise(dto: CustomExerciseSyncDto) {
        val existing = workoutDao.getExerciseBySyncId(dto.syncId)
        if (existing == null) {
            workoutDao.insertExercise(
                ExerciseEntity(
                    name = dto.name,
                    description = dto.description,
                    primaryMuscle = dto.primaryMuscle,
                    equipment = dto.equipment,
                    active = dto.active,
                    rirEnabled = dto.rirEnabled,
                    isBodyweight = dto.isBodyweight,
                    isUserCreated = true,
                    origin = USER_ORIGIN,
                    syncId = dto.syncId
                )
            )
        } else {
            workoutDao.updateExercise(
                existing.copy(
                    name = dto.name,
                    description = dto.description,
                    primaryMuscle = dto.primaryMuscle,
                    equipment = dto.equipment,
                    active = dto.active,
                    rirEnabled = dto.rirEnabled,
                    isBodyweight = dto.isBodyweight
                )
            )
        }
    }

    private suspend fun writeMeasurement(dto: BodyMeasurementSyncDto) {
        val existing = bodyMeasurementDao.getMeasurementBySyncId(dto.syncId)
        val entity = BodyMeasurementEntity(
            id = existing?.id ?: 0,
            date = dto.date,
            createdAt = dto.createdAt,
            weightKg = dto.weightKg,
            heightCm = dto.heightCm,
            bodyFatPercentage = dto.bodyFatPercentage,
            waistCm = dto.waistCm,
            abdomenCm = dto.abdomenCm,
            chestCm = dto.chestCm,
            leftArmCm = dto.leftArmCm,
            rightArmCm = dto.rightArmCm,
            leftThighCm = dto.leftThighCm,
            rightThighCm = dto.rightThighCm,
            calfCm = dto.calfCm,
            hipCm = dto.hipCm,
            syncId = dto.syncId
        )
        if (existing == null) {
            bodyMeasurementDao.insertMeasurement(entity)
        } else {
            bodyMeasurementDao.updateMeasurement(entity)
        }
    }

    private suspend fun writeCheckIn(dto: CheckInSyncDto) {
        val existing = workoutDao.getCheckInBySyncId(dto.syncId)
        val sessionId = dto.sessionSyncId?.let { workoutDao.getSessionIdBySyncId(it) }
        if (existing == null) {
            workoutDao.insertCheckIn(
                CheckInEntity(
                    checkInTime = dto.checkInTime,
                    checkOutTime = dto.checkOutTime,
                    gymName = dto.gymName,
                    // Pode apontar para uma sessão que ainda não chegou: o check-in continua
                    // válido sem o vínculo, e o contrato já previa isso no backup.
                    sessionId = sessionId,
                    syncId = dto.syncId
                )
            )
        } else {
            workoutDao.updateCheckIn(
                existing.copy(
                    checkInTime = dto.checkInTime,
                    checkOutTime = dto.checkOutTime,
                    gymName = dto.gymName,
                    sessionId = sessionId ?: existing.sessionId
                )
            )
        }
    }

    /**
     * A identidade portátil de um exercício traduzida para o `localId` **deste** banco.
     *
     * Catálogo pelo `canonicalId`, criado pelo usuário pelo `syncId`. Nunca por nome, nunca por
     * aproximação: um `canonicalId` que este app não conhece faz a página inteira falhar, e o
     * cursor não avança. Melhor pausar e pedir atualização do que gravar o exercício errado no
     * treino de alguém.
     */
    private suspend fun resolveExercise(item: DecodedChange, ref: ExerciseRefDto): Long =
        optionalExercise(ref) ?: fail(
            item,
            if (ref.kind == ExerciseRefDto.CANONICAL) REASON_UNKNOWN_CANONICAL else REASON_MISSING_CUSTOM
        )

    private suspend fun optionalExercise(ref: ExerciseRefDto): Long? = when (ref.kind) {
        ExerciseRefDto.CANONICAL -> workoutDao.getExerciseByCanonicalId(ref.id)?.id
        else -> workoutDao.getExerciseBySyncId(ref.id)?.id
    }

    private fun fail(item: DecodedChange, reason: String): Nothing =
        throw SyncRemoteApplyException(item.change.serverSequence, reason)

    private data class DecodedChange(
        val change: SyncChangeDto,
        val type: SyncEntityType,
        val operation: SyncOperation,
        /** `null` numa exclusão: um tombstone não traz conteúdo. */
        val payload: Any?
    ) {
        /**
         * A ordem de aplicação dentro da página.
         *
         * As criações e edições vêm primeiro, em ordem de **dependência**: exercícios
         * personalizados e programas antes dos treinos (que os referenciam), treinos antes das
         * sessões, sessões antes dos check-ins. Medidas não dependem de nada.
         *
         * As exclusões vêm depois, em ordem **inversa** — e isso não é simetria estética. Um
         * aparelho que tirou um exercício de um treino e depois apagou o exercício produz duas
         * mudanças; aplicar a exclusão antes da edição esbarraria no `ON DELETE RESTRICT` da
         * referência que a edição ia justamente remover. Excluindo por último, e do filho para o
         * pai, a página aplica inteira.
         */
        val rank: Int
            get() {
                val dependency = when (type) {
                    SyncEntityType.CUSTOM_EXERCISE -> 0
                    SyncEntityType.WORKOUT_PROGRAM -> 1
                    SyncEntityType.WORKOUT_TEMPLATE -> 2
                    SyncEntityType.WORKOUT_SESSION -> 3
                    SyncEntityType.CHECK_IN -> 4
                    SyncEntityType.BODY_MEASUREMENT -> 5
                }
                return if (operation == SyncOperation.DELETE) DELETE_RANK_OFFSET - dependency else dependency
            }
    }

    private enum class Outcome { APPLIED, CONVERGED, CONFLICT }

    private companion object {
        const val COMPLETED_STATUS = "COMPLETED"

        /** O mesmo `origin` que a criação manual de exercício usa. */
        const val USER_ORIGIN = "USER"

        const val REASON_MISSING_PROGRAM = "MISSING_PROGRAM"
        const val REASON_UNKNOWN_CANONICAL = "UNKNOWN_CANONICAL_EXERCISE"
        const val REASON_MISSING_CUSTOM = "MISSING_CUSTOM_EXERCISE"

        /** O SQLite recusou a escrita local (índice único, FK, `RESTRICT`). Nada foi gravado. */
        const val REASON_LOCAL_WRITE_REFUSED = "LOCAL_WRITE_REFUSED"

        /**
         * Onde a faixa das exclusões começa. Maior que qualquer `dependency`, e subtraindo para
         * inverter a ordem: check-in e medida saem antes de sessão, sessão antes de treino,
         * treino antes de programa e de exercício.
         */
        const val DELETE_RANK_OFFSET = 100
    }
}

/**
 * O que a guarda de exclusão local decidiu (T16.7).
 *
 * Três respostas, e não um booleano: "apagou", "não apaguei porque o usuário perderia trabalho" e
 * "não apaguei porque o banco não deixa" pedem tratamentos diferentes — conflito, pausa e
 * mensagem. Um booleano colapsaria os três em "não deu".
 */
sealed interface SyncLocalDeleteGuard {

    /** A linha foi apagada — ou já não existia, o que dá no mesmo. */
    data object Deleted : SyncLocalDeleteGuard

    /** Um filho do agregado tem alteração local pendente. Nada foi apagado. */
    data object PendingChildMutations : SyncLocalDeleteGuard

    /** Outra linha ainda referencia esta (`ON DELETE RESTRICT`). Nada foi apagado. */
    data object StillReferenced : SyncLocalDeleteGuard
}

/** Uma referência do payload remoto que não resolve neste aparelho. Desfaz a transação inteira. */
class SyncRemoteApplyException(
    val serverSequence: Long,
    val reason: String
) : IllegalStateException("mudança $serverSequence não pôde ser aplicada: $reason")

/** O que uma página de pull produziu. */
data class SyncApplyResult(
    /** A sequência até onde o cursor avançou, ou `null` se ele não andou. */
    val cursor: Long? = null,
    val applied: Int = 0,
    /** Mudanças que já estavam refletidas localmente — eco ou conteúdo idêntico. */
    val converged: Int = 0,
    val conflicts: Int = 0,
    /** Por que a página não foi processada até o fim, quando foi o caso. */
    val stop: SyncApplyStop? = null
)

/** Por que o sync parou naquele ponto do change log. */
sealed interface SyncApplyStop {

    /**
     * Uma mudança que esta versão do app não sabe ler.
     *
     * Tipo de agregado novo, `entitySchemaVersion` futura ou payload fora do contrato. O cursor
     * para **antes** dela: ignorar e seguir perderia a mudança para sempre.
     */
    data class Unsupported(val serverSequence: Long, val entityType: String) : SyncApplyStop

    /** Uma referência do payload não resolve neste aparelho. Nada foi escrito. */
    data class Failed(val serverSequence: Long, val reason: String) : SyncApplyStop

    /**
     * A mudança altera o treino que está sendo executado agora.
     *
     * Não é erro e não é conflito: é uma espera. Ela continua no change log, o cursor para antes
     * dela, e o ciclo seguinte a aplica quando a execução terminar.
     */
    data class DeferredActiveWorkout(val serverSequence: Long) : SyncApplyStop
}
