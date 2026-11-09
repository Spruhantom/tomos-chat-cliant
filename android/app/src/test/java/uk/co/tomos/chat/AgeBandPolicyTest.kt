package uk.co.tomos.chat

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeBandPolicyTest {
    private val today = LocalDate.of(2026, 11, 9)

    private fun band(year: Int, month: Int = 11, day: Int = 9) =
        (AgeBandPolicy.assess(LocalDate.of(year, month, day), today) as AgeAssessment.Assigned).band

    @Test fun assignsFiveToEight() {
        assertEquals(AgeBand.CHILD_5_TO_8, band(2018))
    }

    @Test fun assignsNineToTwelve() {
        assertEquals(AgeBand.CHILD_9_TO_12, band(2014))
    }

    @Test fun assignsTeenBand() {
        assertEquals(AgeBand.TEEN_13_TO_17, band(2010))
    }

    @Test fun assignsAdultBandOnEighteenthBirthday() {
        assertEquals(AgeBand.ADULT_18_PLUS, band(2008))
    }

    @Test fun underFiveRequiresGuardianSetup() {
        assertEquals(AgeBand.UNDER_FIVE, band(2022))
    }

    @Test fun rejectsFutureBirthDate() {
        assertTrue(AgeBandPolicy.assess(today.plusDays(1), today) is AgeAssessment.InvalidFutureDate)
    }

    @Test fun birthdayHasNotBeenReachedYet() {
        val result = AgeBandPolicy.assess(LocalDate.of(2018, 11, 10), today) as AgeAssessment.Assigned
        assertEquals(7, result.age)
        assertEquals(AgeBand.CHILD_5_TO_8, result.band)
    }
}
