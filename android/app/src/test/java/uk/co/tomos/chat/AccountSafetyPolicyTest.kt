package uk.co.tomos.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSafetyPolicyTest {
    private fun state(
        band: AgeBand,
        assurance: AgeAssuranceStatus = AgeAssuranceStatus.VERIFIED,
        guardian: GuardianConsentStatus = if (band == AgeBand.ADULT_18_PLUS) GuardianConsentStatus.NOT_REQUIRED else GuardianConsentStatus.APPROVED
    ) = AccountSafetyState(band, assurance, guardian)

    @Test fun ageAssuranceDoesNotGateAdultMessaging() {
        val account = state(AgeBand.ADULT_18_PLUS, AgeAssuranceStatus.NOT_VERIFIED)
        assertTrue(AccountSafetyPolicy.isAccountCleared(account))
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.MESSAGE_APPROVED_CONTACT))
    }

    @Test fun ageAssuranceDoesNotGateMinorMessagingWhenGuardianApproved() {
        val account = state(AgeBand.CHILD_9_TO_12, AgeAssuranceStatus.NOT_VERIFIED, GuardianConsentStatus.APPROVED)
        assertTrue(AccountSafetyPolicy.isAccountCleared(account))
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.MESSAGE_APPROVED_CONTACT))
    }

    @Test fun minorStillNeedsGuardianApproval() {
        val account = state(AgeBand.CHILD_9_TO_12, AgeAssuranceStatus.NOT_VERIFIED, GuardianConsentStatus.PENDING)
        assertFalse(AccountSafetyPolicy.isAccountCleared(account))
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.MESSAGE_APPROVED_CONTACT))
    }

    @Test fun guardianDenialKeepsMinorLocked() {
        val account = state(AgeBand.TEEN_13_TO_17, guardian = GuardianConsentStatus.DENIED)
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.VOICE_CALL_APPROVED_CONTACT))
    }

    @Test fun youngChildCannotCreateGroupsOrDiscoverPublicProfiles() {
        val account = state(AgeBand.CHILD_5_TO_8)
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.MESSAGE_APPROVED_CONTACT))
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.CREATE_PRIVATE_GROUP))
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.PUBLIC_PROFILE_DISCOVERY))
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.PUBLIC_SOCIAL_POSTING))
    }

    @Test fun teenCanUseRestrictedPrivateFeaturesButNotPublicSocial() {
        val account = state(AgeBand.TEEN_13_TO_17)
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.VOICE_CALL_APPROVED_CONTACT))
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.CREATE_PRIVATE_GROUP))
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.PUBLIC_PROFILE_DISCOVERY))
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.PUBLIC_SOCIAL_POSTING))
    }

    @Test fun adultGetsStandardCapabilitiesWithoutAgeAssurance() {
        val account = state(AgeBand.ADULT_18_PLUS, AgeAssuranceStatus.NOT_VERIFIED)
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.PUBLIC_PROFILE_DISCOVERY))
        assertTrue(AccountSafetyPolicy.allows(account, TomChatCapability.PUBLIC_SOCIAL_POSTING))
    }

    @Test fun underFiveCannotCompleteIndependentSetup() {
        val account = state(AgeBand.UNDER_FIVE)
        assertFalse(AccountSafetyPolicy.allows(account, TomChatCapability.COMPLETE_LOCAL_SETUP))
        assertFalse(AccountSafetyPolicy.isAccountCleared(account))
    }

    @Test fun adultCannotBeMarkedAsGuardianManaged() {
        val account = state(AgeBand.ADULT_18_PLUS, guardian = GuardianConsentStatus.APPROVED)
        assertFalse(AccountSafetyPolicy.isAccountCleared(account))
    }
}
