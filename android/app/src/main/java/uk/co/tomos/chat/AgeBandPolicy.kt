package uk.co.tomos.chat

import java.time.LocalDate
import java.time.Period

/**
 * Local age-band classification for the first-run account flow.
 *
 * This is a safety-profile selector, NOT proof of age or guardian consent.
 * Do not persist the raw date of birth when the account is created.
 */
enum class AgeBand(val title: String, val description: String) {
    UNDER_FIVE("Guardian setup required", "TomChat is not available for independent accounts under 5."),
    CHILD_5_TO_8("TomChat Kids · 5–8", "Simplified experience with the strongest contact and sharing restrictions."),
    CHILD_9_TO_12("TomChat Kids · 9–12", "Age-appropriate messaging with guardian-approved contacts."),
    TEEN_13_TO_17("TomChat Teen · 13–17", "More independence with age-appropriate safety protections."),
    ADULT_18_PLUS("TomChat Adult · 18+", "Standard TomChat experience.")
}

sealed interface AgeAssessment {
    data class Assigned(val band: AgeBand, val age: Int) : AgeAssessment
    data object InvalidFutureDate : AgeAssessment
}

object AgeBandPolicy {
    fun assess(dateOfBirth: LocalDate, today: LocalDate): AgeAssessment {
        if (dateOfBirth.isAfter(today)) return AgeAssessment.InvalidFutureDate
        val age = Period.between(dateOfBirth, today).years
        val band = when (age) {
            in 0..4 -> AgeBand.UNDER_FIVE
            in 5..8 -> AgeBand.CHILD_5_TO_8
            in 9..12 -> AgeBand.CHILD_9_TO_12
            in 13..17 -> AgeBand.TEEN_13_TO_17
            else -> AgeBand.ADULT_18_PLUS
        }
        return AgeAssessment.Assigned(band, age)
    }
}
