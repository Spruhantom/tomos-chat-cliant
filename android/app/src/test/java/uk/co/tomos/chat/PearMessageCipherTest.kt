package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PearMessageCipherTest {
    @Test
    fun encryptsAndDecryptsWithMessageBoundAssociatedData() {
        val key = ByteArray(32) { it.toByte() }
        val ciphertext = PearMessageCipher.encrypt(key, "message-1", "ABCD", 1234, "Hello Pear")
        assertEquals("Hello Pear", PearMessageCipher.decrypt(key, "message-1", "ABCD", 1234, ciphertext))
        assertThrows(Exception::class.java) {
            PearMessageCipher.decrypt(key, "message-2", "ABCD", 1234, ciphertext)
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val ciphertext = PearMessageCipher.encrypt(key, "message-2", "EFGH", 4567, "Confidential")
        val raw = java.util.Base64.getDecoder().decode(ciphertext)
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)
        assertThrows(Exception::class.java) {
            PearMessageCipher.decrypt(key, "message-2", "EFGH", 4567, tampered)
        }
    }
}
