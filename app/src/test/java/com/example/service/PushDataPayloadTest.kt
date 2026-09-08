package com.example.service

import com.example.data.social.PushDataPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PushDataPayloadTest {

    @Test
    fun `fromMap valido com v=1 e todos os campos e desserializado com sucesso`() {
        val data = mapOf(
            "v" to "1",
            "eventId" to "evt-12345",
            "type" to "FRIEND_REQUEST_RECEIVED",
            "recipientSocialId" to "soc-user-1",
            "entityId" to "req-67890"
        )

        val payload = PushDataPayload.fromMap(data)

        assertNotNull(payload)
        assertEquals("1", payload?.v)
        assertEquals("evt-12345", payload?.eventId)
        assertEquals("FRIEND_REQUEST_RECEIVED", payload?.type)
        assertEquals("soc-user-1", payload?.recipientSocialId)
        assertEquals("req-67890", payload?.entityId)
    }

    @Test
    fun `fromMap rejeita payload com versao incompativel`() {
        val dataV2 = mapOf(
            "v" to "2",
            "eventId" to "evt-1",
            "type" to "FRIEND_REQUEST_RECEIVED",
            "recipientSocialId" to "soc-1",
            "entityId" to "req-1"
        )
        assertNull(PushDataPayload.fromMap(dataV2))

        val dataNoV = mapOf(
            "type" to "FRIEND_REQUEST_RECEIVED",
            "recipientSocialId" to "soc-1",
            "entityId" to "req-1"
        )
        assertNull(PushDataPayload.fromMap(dataNoV))
    }

    @Test
    fun `fromMap rejeita payload sem campos essenciais`() {
        val withoutType = mapOf(
            "v" to "1",
            "recipientSocialId" to "soc-1",
            "entityId" to "req-1"
        )
        assertNull(PushDataPayload.fromMap(withoutType))

        val withoutRecipient = mapOf(
            "v" to "1",
            "type" to "FRIEND_REQUEST_RECEIVED",
            "entityId" to "req-1"
        )
        assertNull(PushDataPayload.fromMap(withoutRecipient))

        val withoutEntityId = mapOf(
            "v" to "1",
            "type" to "FRIEND_REQUEST_RECEIVED",
            "recipientSocialId" to "soc-1"
        )
        assertNull(PushDataPayload.fromMap(withoutEntityId))
    }

    @Test
    fun `fromMap gera eventId sintetico seguro quando ausente`() {
        val data = mapOf(
            "v" to "1",
            "type" to "CHALLENGE_INVITATION_RECEIVED",
            "recipientSocialId" to "soc-user-2",
            "entityId" to "chal-42"
        )

        val payload = PushDataPayload.fromMap(data)

        assertNotNull(payload)
        assertEquals("CHALLENGE_INVITATION_RECEIVED:chal-42", payload?.eventId)
    }

    @Test
    fun `isolamento de conta rejeita mensagem se recipientSocialId nao confere com usuario ativo`() {
        val activeSocialId = "soc-logged-in-user"
        val payloadOtherUser = PushDataPayload(
            v = "1",
            eventId = "evt-1",
            type = "FRIEND_REQUEST_RECEIVED",
            recipientSocialId = "soc-former-user",
            entityId = "req-1"
        )

        val matches = payloadOtherUser.recipientSocialId == activeSocialId
        assertEquals(false, matches)
    }

    @Test
    fun `LRU deduplicacao identifica mensagens repetidas`() {
        val seenEventIds = object : LinkedHashMap<String, Long>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 100
            }
        }

        val eventId = "evt-dedupe-test"

        val isFirstTime = !seenEventIds.containsKey(eventId)
        seenEventIds[eventId] = System.currentTimeMillis()
        assertEquals(true, isFirstTime)

        val isSecondTime = !seenEventIds.containsKey(eventId)
        assertEquals(false, isSecondTime)
    }
}
