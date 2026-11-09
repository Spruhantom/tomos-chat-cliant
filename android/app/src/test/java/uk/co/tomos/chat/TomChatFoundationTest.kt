package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TomChatFoundationTest {
    @Test
    fun appVersionIsInitialDevelopmentVersion() {
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun packageUsesTomChatNamespace() {
        assertEquals("uk.co.tomos.chat", BuildConfig.APPLICATION_ID)
    }
}
