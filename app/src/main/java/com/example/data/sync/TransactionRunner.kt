package com.example.data.sync

import androidx.room.RoomDatabase
import androidx.room.withTransaction

/**
 * Executa um bloco dentro de uma transação.
 *
 * Existe para que o coordenador de mutações não dependa de `AppDatabase` — o que tornaria
 * impossível testar rollback sem subir o banco inteiro — e para que o caminho sem Outbox não
 * pague por uma transação que não precisa.
 */
interface TransactionRunner {

    suspend fun <R> runInTransaction(block: suspend () -> R): R

    /** Sem transação: executa o bloco como está. Usado quando a nuvem está desligada. */
    object Direct : TransactionRunner {
        override suspend fun <R> runInTransaction(block: suspend () -> R): R = block()
    }
}

/**
 * A transação real do Room.
 *
 * `withTransaction` mantém o bloco inteiro — inclusive as chamadas suspensas de DAO — na mesma
 * transação. É o que garante que a alteração de domínio e a entrada da Outbox tenham um único
 * commit e um único rollback.
 */
class RoomTransactionRunner(private val database: RoomDatabase) : TransactionRunner {
    override suspend fun <R> runInTransaction(block: suspend () -> R): R =
        database.withTransaction { block() }
}
