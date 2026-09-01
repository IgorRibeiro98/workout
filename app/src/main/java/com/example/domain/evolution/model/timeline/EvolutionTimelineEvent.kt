package com.example.domain.evolution.model.timeline

data class EvolutionTimelineEvent(
    val id: String,
    val date: Long,
    val title: String,
    val description: String,
    val icon: String,
    val category: TimelineEventCategory,
    val metadata: Map<String, String> = emptyMap()
)
