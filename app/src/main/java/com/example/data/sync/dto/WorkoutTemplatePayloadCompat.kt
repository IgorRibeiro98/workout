package com.example.data.sync.dto

import com.example.domain.workout.template.WeekdaySchedule
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A fronteira entre as versões do payload de `WORKOUT_TEMPLATE` (T19.8).
 *
 * ```text
 * v1  { ..., "dayOfWeek": "Seg" | null }           →  v2  { ..., "scheduledDays": ["MONDAY"] }
 * v2  { ..., "scheduledDays": ["MONDAY", ...] }    →  v2  (identidade)
 * ```
 *
 * Um só lugar lê as duas formas — o sync (`SyncRemoteApplier`), o restore (`RestoreSnapshotReader`)
 * e a resolução de conflito passam por aqui —, e a saída é sempre o DTO atual, já validado: nomes
 * canônicos, sem repetição, na ordem da semana. Nada além do dia é reinterpretado; um `dayOfWeek`
 * legado que não descreve um dia da semana vira "sem dia fixo", que é o mesmo que a migração do
 * Room faz com a coluna antiga.
 *
 * A leitura continua **estrita** (`ignoreUnknownKeys = false`): a v1 é reconhecida pela chave
 * `dayOfWeek`, convertida e então decodificada pelo mesmo serializer da v2 — um payload com campo
 * desconhecido continua sendo recusado.
 */
object WorkoutTemplatePayloadCompat {

    /** Lança [SerializationException] ou [IllegalArgumentException] quando o payload não é um treino válido. */
    fun decode(json: Json, entitySchemaVersion: Int, payload: JsonElement): WorkoutTemplateSyncDto {
        require(entitySchemaVersion in WorkoutTemplateSyncDto.READABLE_SCHEMA_VERSIONS) {
            "versão de treino não suportada: $entitySchemaVersion"
        }
        val body = payload as? JsonObject ?: throw SerializationException("payload de treino não é um objeto")
        val dto = json.decodeFromJsonElement(WorkoutTemplateSyncDto.serializer(), upgrade(body))
        val days = WeekdaySchedule.parseCanonical(dto.scheduledDays)
            ?: throw SerializationException("scheduledDays fora do contrato")
        return dto.copy(scheduledDays = days.map { it.name })
    }

    /** O objeto na forma da v2. Um payload já na v2 volta como está. */
    fun upgrade(body: JsonObject): JsonObject {
        if (!body.containsKey(LEGACY_DAY_KEY)) return body
        if (body.containsKey(DAYS_KEY)) {
            // As duas chaves juntas não descrevem nenhuma versão conhecida.
            throw SerializationException("payload de treino com dayOfWeek e scheduledDays")
        }
        val legacy = body.getValue(LEGACY_DAY_KEY)
        val label = when (legacy) {
            is JsonNull -> null
            is JsonPrimitive -> if (legacy.isString) legacy.content else throw SerializationException("dayOfWeek não é texto")
            else -> throw SerializationException("dayOfWeek não é texto")
        }
        val days = listOfNotNull(WeekdaySchedule.fromLegacyLabel(label))
        val upgraded = body.toMutableMap()
        upgraded.remove(LEGACY_DAY_KEY)
        upgraded[DAYS_KEY] = JsonArray(days.map { JsonPrimitive(it.name) })
        return JsonObject(upgraded)
    }

    private const val LEGACY_DAY_KEY = "dayOfWeek"
    private const val DAYS_KEY = "scheduledDays"
}
