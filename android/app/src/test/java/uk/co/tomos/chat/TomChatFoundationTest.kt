package uk.co.tomos.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TomChatFoundationTest {
    @Test
    fun halfOpenedVerticalFoldUsesBookPosture() {
        assertEquals(FoldPosture.BOOK, FoldPosture.from(isHalfOpened = true, isVertical = true))
    }

    @Test
    fun halfOpenedHorizontalFoldUsesTabletopPosture() {
        assertEquals(FoldPosture.TABLETOP, FoldPosture.from(isHalfOpened = true, isVertical = false))
    }

    @Test
    fun flatFoldDoesNotForceSpecialPosture() {
        assertEquals(FoldPosture.FLAT, FoldPosture.from(isHalfOpened = false, isVertical = true))
    }

    @Test
    fun appVersionIsInitialDevelopmentVersion() {
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun packageUsesTomChatNamespace() {
        assertEquals("uk.co.tomos.chat", BuildConfig.APPLICATION_ID)
    }
}
