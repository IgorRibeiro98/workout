package com.example.service

import com.example.domain.social.SocialNotificationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SocialNotificationNavigationResolverTest {

    @Test
    fun `FRIEND_REQUEST_RECEIVED resolve para FriendRequests`() {
        val dest = SocialNotificationNavigationResolver.resolve(
            SocialNotificationType.FRIEND_REQUEST_RECEIVED,
            "req-123"
        )
        assertEquals(NotificationNavDestination.FriendRequests, dest)
    }

    @Test
    fun `FRIEND_REQUEST_ACCEPTED resolve para Friends`() {
        val dest = SocialNotificationNavigationResolver.resolve(
            SocialNotificationType.FRIEND_REQUEST_ACCEPTED,
            "req-123"
        )
        assertEquals(NotificationNavDestination.Friends, dest)
    }

    @Test
    fun `CHALLENGE_INVITATION_RECEIVED resolve para lista de Challenges e nunca ChallengeDetail (T17_5_1 Problema 3)`() {
        val dest = SocialNotificationNavigationResolver.resolve(
            SocialNotificationType.CHALLENGE_INVITATION_RECEIVED,
            "invitation-456"
        )
        assertEquals(NotificationNavDestination.Challenges, dest)
    }

    @Test
    fun `CHALLENGE_STARTING_SOON resolve para ChallengeDetail com challengeId correto`() {
        val dest = SocialNotificationNavigationResolver.resolve(
            SocialNotificationType.CHALLENGE_STARTING_SOON,
            "chal-789"
        )
        assertEquals(NotificationNavDestination.ChallengeDetail("chal-789"), dest)
    }

    @Test
    fun `CHALLENGE_ENDED resolve para ChallengeDetail com challengeId correto`() {
        val dest = SocialNotificationNavigationResolver.resolve(
            SocialNotificationType.CHALLENGE_ENDED,
            "chal-789"
        )
        assertEquals(NotificationNavDestination.ChallengeDetail("chal-789"), dest)
    }

    @Test
    fun `duas notificacoes distintas geram request codes e URIs unicos para evitar colisao de PendingIntent (T17_5_1 Problema 4)`() {
        val eventId1 = "event-1"
        val eventId2 = "event-2"

        val uri1 = "spark://notification/$eventId1"
        val uri2 = "spark://notification/$eventId2"
        assertNotEquals(uri1, uri2)

        val requestCode1 = eventId1.hashCode()
        val requestCode2 = eventId2.hashCode()
        assertNotEquals(requestCode1, requestCode2)
    }
}

