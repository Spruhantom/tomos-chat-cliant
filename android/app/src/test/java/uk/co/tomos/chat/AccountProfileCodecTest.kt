package uk.co.tomos.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountProfileCodecTest {
    @Test fun roundTripsMinimalProfileWithoutDateOfBirth() {
        val profile = AccountProfile(
            displayName = "Tom",
            ageBand = AgeBand.TEEN_13_TO_17,
            ageAssurance = AgeAssuranceStatus.NOT_VERIFIED,
            guardianConsent = GuardianConsentStatus.PENDING
        )
        val encoded = AccountProfileCodec.encode(profile)
        val decoded = AccountProfileCodec.decode(encoded)
        assertEquals(profile, decoded)
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains("dateOfBirth"))
        assertFalse(String(encoded, Charsets.ISO_8859_1).contains("2010-01-01"))
    }

    @Test fun rejectsTrailingProfileData() {
        val profile = AccountProfile(
            "Adult", AgeBand.ADULT_18_PLUS,
            AgeAssuranceStatus.NOT_VERIFIED, GuardianConsentStatus.NOT_REQUIRED
        )
        val encoded = AccountProfileCodec.encode(profile) + byteArrayOf(1)
        assertThrows(IllegalArgumentException::class.java) { AccountProfileCodec.decode(encoded) }
    }

    @Test fun rejectsAdultGuardianState() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountProfile("Adult", AgeBand.ADULT_18_PLUS, AgeAssuranceStatus.NOT_VERIFIED, GuardianConsentStatus.PENDING)
        }
    }

    @Test fun rejectsMinorWithoutExplicitGuardianState() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountProfile("Child", AgeBand.CHILD_5_TO_8, AgeAssuranceStatus.NOT_VERIFIED, GuardianConsentStatus.NOT_REQUIRED)
        }
    }
}
