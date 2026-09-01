package com.example.domain.provider

data class MediaResult(
    val mediaUri: String? = null,
    val isGif: Boolean = false,
    val isCustomPhoto: Boolean = false,
    val providerName: String = "",
    val externalId: String? = null,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null
)
