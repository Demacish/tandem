package casa.moller.tandem.core.model.account

import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ServerAccountTest {
    private val account = ServerAccount(
        id = "8a5f2f0e-6a43-4d55-9d8c-1f1f7f1f7f1f",
        baseUrl = "https://recipes.example.org",
        username = "cook",
        displayName = "Kitchen Cook",
        userId = 3,
        spaceId = 1,
        addedAt = Instant.parse("2026-10-09T08:00:00Z"),
    )

    @Test
    fun labelPrefersDisplayName() {
        assertEquals("Kitchen Cook", account.label)
    }

    @Test
    fun labelFallsBackToUsernameWhenDisplayNameIsMissingOrBlank() {
        assertEquals("cook", account.copy(displayName = null).label)
        assertEquals("cook", account.copy(displayName = "  ").label)
    }

    @Test
    fun toStringNeverContainsATokenField() {
        assertFalse(account.toString().contains("token=", ignoreCase = true))
    }
}
