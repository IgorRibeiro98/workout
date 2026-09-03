package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "xp_transactions",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["createdAt"])
    ]
)
data class XpTransactionEntity(
    @PrimaryKey
    val id: String,
    val eventId: String,
    val amount: Int,
    val reason: String,
    val createdAt: Long
)
