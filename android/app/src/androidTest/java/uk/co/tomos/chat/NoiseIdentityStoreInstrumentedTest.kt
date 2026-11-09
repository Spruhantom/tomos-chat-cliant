package uk.co.tomos.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NoiseIdentityStoreInstrumentedTest {
    @Test
    fun persistsSameNoiseKeyAcrossStoreInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "noise-identity-test").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val first = NoiseIdentityStore(directory).getOrCreate()
            val second = NoiseIdentityStore(directory).getOrCreate()
            assertArrayEquals(first.publicKey, second.publicKey)
            assertArrayEquals(first.privateKey, second.privateKey)
            assertNotEquals(0, first.privateKey.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
