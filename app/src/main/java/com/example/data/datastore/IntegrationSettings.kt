package com.example.data.datastore

enum class SyncStatus {
    DISABLED,
    READY,
    SYNCING,
    SUCCESS,
    ERROR
}

data class MediaProviderSettings(
    val exerciseDbEnabled: Boolean = false,
    val autoSyncEnabled: Boolean = false,
    val mediaSyncEnabled: Boolean = false,
    val lastSyncTimestamp: Long? = null,
    val lastSyncStatus: SyncStatus = SyncStatus.READY
)

typealias IntegrationSettings = MediaProviderSettings

