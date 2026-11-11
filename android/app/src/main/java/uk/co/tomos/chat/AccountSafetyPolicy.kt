package uk.co.tomos.chat

/**
 * Age bands determine the available experience. Minor accounts still require
 * explicit guardian approval; age assurance is not used as an account gate.
 */
enum class AgeAssuranceStatus { NOT_VERIFIED, VERIFIED }
enum class GuardianConsentStatus { NOT_REQUIRED, PENDING, APPROVED, DENIED }

enum class TomChatCapability {
    COMPLETE_LOCAL_SETUP,
    EXCHANGE_CONTACT_CARD,
    MESSAGE_APPROVED_CONTACT,
    VOICE_CALL_APPROVED_CONTACT,
    SHARE_FILE_WITH_APPROVED_CONTACT,
    CREATE_PRIVATE_GROUP,
    PUBLIC_PROFILE_DISCOVERY,
    PUBLIC_SOCIAL_POSTING
}

data class AccountSafetyState(
    val ageBand: AgeBand,
    val ageAssurance: AgeAssuranceStatus,
    val guardianConsent: GuardianConsentStatus
)

object AccountSafetyPolicy {
    fun isAccountCleared(state: AccountSafetyState): Boolean {
        if (state.ageBand == AgeBand.UNDER_FIVE) return false
        return when (state.ageBand) {
            AgeBand.UNDER_FIVE -> false
            AgeBand.CHILD_5_TO_8, AgeBand.CHILD_9_TO_12, AgeBand.TEEN_13_TO_17 ->
                state.guardianConsent == GuardianConsentStatus.APPROVED
            AgeBand.ADULT_18_PLUS ->
                state.guardianConsent == GuardianConsentStatus.NOT_REQUIRED
        }
    }

    fun allows(
        state: AccountSafetyState,
        capability: TomChatCapability,
        allowUnverifiedAdultDebug: Boolean = false
    ): Boolean {
        if (capability == TomChatCapability.COMPLETE_LOCAL_SETUP) return state.ageBand != AgeBand.UNDER_FIVE

        // Debug-only testing may let an explicitly adult-classified account
        // exercise the private contact, messaging and voice-call prototype.
        // This never applies to child/teen accounts or public/social features.
        if (
            allowUnverifiedAdultDebug &&
            state.ageBand == AgeBand.ADULT_18_PLUS &&
            state.guardianConsent == GuardianConsentStatus.NOT_REQUIRED &&
            capability in setOf(
                TomChatCapability.EXCHANGE_CONTACT_CARD,
                TomChatCapability.MESSAGE_APPROVED_CONTACT,
                TomChatCapability.VOICE_CALL_APPROVED_CONTACT
            )
        ) return true

        if (!isAccountCleared(state)) return false

        return when (state.ageBand) {
            AgeBand.UNDER_FIVE -> false
            AgeBand.CHILD_5_TO_8, AgeBand.CHILD_9_TO_12 -> capability in setOf(
                TomChatCapability.EXCHANGE_CONTACT_CARD,
                TomChatCapability.MESSAGE_APPROVED_CONTACT
            )
            AgeBand.TEEN_13_TO_17 -> capability in setOf(
                TomChatCapability.EXCHANGE_CONTACT_CARD,
                TomChatCapability.MESSAGE_APPROVED_CONTACT,
                TomChatCapability.VOICE_CALL_APPROVED_CONTACT,
                TomChatCapability.SHARE_FILE_WITH_APPROVED_CONTACT,
                TomChatCapability.CREATE_PRIVATE_GROUP
            )
            AgeBand.ADULT_18_PLUS -> capability != TomChatCapability.COMPLETE_LOCAL_SETUP
        }
    }

    fun restrictionReason(state: AccountSafetyState): String = when {
        state.ageBand == AgeBand.UNDER_FIVE ->
            "A guardian-led setup is required before an account can be created."
        state.ageBand != AgeBand.ADULT_18_PLUS && state.guardianConsent == GuardianConsentStatus.PENDING ->
            "A parent or guardian must approve this account before communication is enabled."
        state.ageBand != AgeBand.ADULT_18_PLUS && state.guardianConsent == GuardianConsentStatus.DENIED ->
            "Guardian approval was declined. Communication features remain disabled."
        state.ageBand == AgeBand.ADULT_18_PLUS && state.guardianConsent != GuardianConsentStatus.NOT_REQUIRED ->
            "Adult accounts must not use a guardian-consent state."
        else -> "This feature is not available for this age group."
    }
}
