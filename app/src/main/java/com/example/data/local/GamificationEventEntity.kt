package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Histórico persistente de fatos de gamificação (T13.0).
 *
 * Guarda apenas o acontecimento, a data e a origem. Nenhuma recompensa é armazenada aqui.
 * `dedupeKey` é único: o mesmo acontecimento nunca entra duas vezes no histórico.
 */
@Entity(
    tableName = "gamification_events",
    indices = [
        Index(value = ["dedupeKey"], unique = true),
        Index(value = ["type"]),
        Index(value = ["timestamp"])
    ]
)
data class GamificationEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    val timestamp: Long,
    val source: String,
    val dedupeKey: String,
    val metadataJson: String
)
