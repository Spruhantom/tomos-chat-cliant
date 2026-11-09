package uk.co.tomos.chat

import java.util.UUID

/**
 * UI/domain projection of group membership events received from an authenticated,
 * future MLS adapter. This is NOT a group cryptographic protocol and must never
 * authorise membership or decrypt messages by itself.
 */
data class GroupMember(val fingerprint: String, val displayName: String) {
    init {
        require(fingerprint.isNotBlank() && fingerprint.length <= 128)
        require(displayName.isNotBlank() && displayName.length <= 64)
    }
}

data class GroupRoster(
    val groupId: UUID,
    val epoch: Long,
    val ownerFingerprint: String,
    val members: Map<String, GroupMember>,
    val appliedEventIds: Set<UUID> = emptySet()
) {
    init {
        require(epoch >= 0)
        require(ownerFingerprint in members)
        require(members.size in 1..MAX_MEMBERS)
        require(appliedEventIds.size <= MAX_REMEMBERED_EVENTS)
    }

    companion object {
        const val MAX_MEMBERS = 32
        const val MAX_REMEMBERED_EVENTS = 4096
    }
}

sealed interface GroupMembershipEvent {
    val eventId: UUID
    val groupId: UUID
    val epoch: Long
    val actorFingerprint: String

    data class AddMember(
        override val eventId: UUID,
        override val groupId: UUID,
        override val epoch: Long,
        override val actorFingerprint: String,
        val member: GroupMember
    ) : GroupMembershipEvent

    data class RemoveMember(
        override val eventId: UUID,
        override val groupId: UUID,
        override val epoch: Long,
        override val actorFingerprint: String,
        val memberFingerprint: String
    ) : GroupMembershipEvent
}

sealed interface GroupRosterResult {
    data class Applied(val roster: GroupRoster) : GroupRosterResult
    data class Duplicate(val roster: GroupRoster) : GroupRosterResult
    data class Rejected(val reason: Reason) : GroupRosterResult

    enum class Reason {
        WRONG_GROUP, UNEXPECTED_EPOCH, UNAUTHORISED_ACTOR, OWNER_CANNOT_LEAVE,
        MEMBER_ALREADY_EXISTS, MEMBER_NOT_FOUND, GROUP_FULL, EVENT_WINDOW_FULL
    }
}

/**
 * Applies a previously authenticated and MLS-committed membership event.
 * Signature, MLS transcript and epoch authentication must be checked upstream.
 */
object GroupRosterReducer {
    fun apply(roster: GroupRoster, event: GroupMembershipEvent): GroupRosterResult {
        if (event.groupId != roster.groupId) return GroupRosterResult.Rejected(GroupRosterResult.Reason.WRONG_GROUP)
        if (event.eventId in roster.appliedEventIds) return GroupRosterResult.Duplicate(roster)
        if (roster.appliedEventIds.size >= GroupRoster.MAX_REMEMBERED_EVENTS) {
            return GroupRosterResult.Rejected(GroupRosterResult.Reason.EVENT_WINDOW_FULL)
        }
        if (event.epoch != roster.epoch + 1) return GroupRosterResult.Rejected(GroupRosterResult.Reason.UNEXPECTED_EPOCH)
        if (event.actorFingerprint != roster.ownerFingerprint) {
            return GroupRosterResult.Rejected(GroupRosterResult.Reason.UNAUTHORISED_ACTOR)
        }

        val updated = roster.members.toMutableMap()
        when (event) {
            is GroupMembershipEvent.AddMember -> {
                if (event.member.fingerprint in updated) return GroupRosterResult.Rejected(GroupRosterResult.Reason.MEMBER_ALREADY_EXISTS)
                if (updated.size >= GroupRoster.MAX_MEMBERS) return GroupRosterResult.Rejected(GroupRosterResult.Reason.GROUP_FULL)
                updated[event.member.fingerprint] = event.member
            }
            is GroupMembershipEvent.RemoveMember -> {
                if (event.memberFingerprint == roster.ownerFingerprint) {
                    return GroupRosterResult.Rejected(GroupRosterResult.Reason.OWNER_CANNOT_LEAVE)
                }
                if (updated.remove(event.memberFingerprint) == null) {
                    return GroupRosterResult.Rejected(GroupRosterResult.Reason.MEMBER_NOT_FOUND)
                }
            }
        }
        return GroupRosterResult.Applied(
            roster.copy(
                epoch = event.epoch,
                members = updated.toMap(),
                appliedEventIds = roster.appliedEventIds + event.eventId
            )
        )
    }
}
