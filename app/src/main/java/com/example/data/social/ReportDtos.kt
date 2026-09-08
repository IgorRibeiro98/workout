package com.example.data.social

import kotlinx.serialization.Serializable

@Serializable
data class CreateReportRequestDto(
    val reportedSocialId: String,
    val reason: String
)

@Serializable
data class CreateReportResponseDto(
    val result: String,
    val reportId: String
)
