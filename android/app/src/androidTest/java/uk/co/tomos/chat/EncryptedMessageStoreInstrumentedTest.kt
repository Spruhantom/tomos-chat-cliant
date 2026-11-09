package uk.co.tomos.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedMessageStoreInstrumentedTest {
    @Test
    fun persistsMessagesAcrossStoreInstancesAndFiltersByPeer() {
        val directory = File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "message-store-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val store = EncryptedMessageStore(directory)
            val first = StoredChatMessage(
                UUID.randomUUID(), "peer-a", StoredMessageDirection.OUTGOING,
                StoredMessageStatus.QUEUED, 100L, "hello from device A"
            )
            val second = StoredChatMessage(
                UUID.randomUUID(), "peer-b", StoredMessageDirection.INCOMING,
                StoredMessageStatus.RECEIVED, 200L, "private to peer B"
            )
            store.append(first)
            store.append(second)

            val reopened = EncryptedMessageStore(directory)
            assertEquals(listOf(first), reopened.load("peer-a"))
            assertEquals(listOf(second), reopened.load("peer-b"))
            assertTrue(reopened.load("unknown-peer").isEmpty())
            reopened.updateStatus(first.id, StoredMessageStatus.SENT)
            assertEquals(StoredMessageStatus.SENT, reopened.load("peer-a").single().status)
        } finally {
            directory.deleteRecursively()
        }
    }
}
