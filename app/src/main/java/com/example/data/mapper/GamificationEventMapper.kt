package com.example.data.mapper

import com.example.data.local.GamificationEventEntity
import com.example.domain.gamification.model.GamificationEvent
import com.example.domain.gamification.model.GamificationEventSource
import com.example.domain.gamification.model.GamificationEventType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Tradução entre o fato de domínio e sua forma persistida.
 *
 * A metadata é gravada como JSON de texto: novos campos podem aparecer sem exigir migração de
 * schema, e eventos antigos continuam legíveis.
 */
object GamificationEventMapper {

    private val metadataAdapter by lazy {
        Moshi.Builder().build().adapter<Map<String, String>>(
            Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
        )
    }

    fun toEntity(event: GamificationEvent): GamificationEventEntity = GamificationEventEntity(
        id = event.id,
        type = event.type.name,
        timestamp = event.timestamp,
        source = event.source,
        dedupeKey = event.dedupeKey,
        metadataJson = encodeMetadata(event.metadata)
    )

    /** Eventos de um tipo desconhecido (versão futura reinstalada por cima) são descartados na leitura. */
    fun toDomain(entity: GamificationEventEntity): GamificationEvent? {
        val type = runCatching { GamificationEventType.valueOf(entity.type) }.getOrNull() ?: return null
        return GamificationEvent(
            id = entity.id,
            type = type,
            timestamp = entity.timestamp,
            metadata = decodeMetadata(entity.metadataJson),
            source = entity.source.ifBlank { GamificationEventSource.UNKNOWN },
            dedupeKey = entity.dedupeKey
        )
    }

    fun encodeMetadata(metadata: Map<String, String>): String = metadataAdapter.toJson(metadata)

    fun decodeMetadata(json: String): Map<String, String> =
        runCatching { metadataAdapter.fromJson(json) }.getOrNull() ?: emptyMap()
}
