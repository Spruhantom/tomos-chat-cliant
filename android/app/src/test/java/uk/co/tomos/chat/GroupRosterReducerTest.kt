package uk.co.tomos.chat

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class GroupRosterReducerTest {
    private val groupId = UUID.randomUUID()
    private val owner = GroupMember("owner-key", "Owner")
    private val guest = GroupMember("guest-key", "Guest")
    private fun roster() = GroupRoster(groupId, 0, owner.fingerprint, mapOf(owner.fingerprint to owner))
    private fun add(epoch: Long = 1, actor: String = owner.fingerprint, id: UUID = UUID.randomUUID()) =
        GroupMembershipEvent.AddMember(id, groupId, epoch, actor, guest)

    @Test fun appliesNextEpochAndSuppressesDuplicateEvent() {
        val event = add()
        val applied = GroupRosterReducer.apply(roster(), event) as GroupRosterResult.Applied
        assertEquals(1, applied.roster.epoch)
        assertTrue(guest.fingerprint in applied.roster.members)
        assertTrue(GroupRosterReducer.apply(applied.roster, event) is GroupRosterResult.Duplicate)
    }

    @Test fun rejectsWrongEpochAndUnauthorisedActor() {
        assertEquals(GroupRosterResult.Reason.UNEXPECTED_EPOCH,
            (GroupRosterReducer.apply(roster(), add(epoch = 2)) as GroupRosterResult.Rejected).reason)
        assertEquals(GroupRosterResult.Reason.UNAUTHORISED_ACTOR,
            (GroupRosterReducer.apply(roster(), add(actor = guest.fingerprint)) as GroupRosterResult.Rejected).reason)
    }

    @Test fun rejectsOwnerRemovalAndUnknownMemberRemoval() {
        val removeOwner = GroupMembershipEvent.RemoveMember(UUID.randomUUID(), groupId, 1, owner.fingerprint, owner.fingerprint)
        assertEquals(GroupRosterResult.Reason.OWNER_CANNOT_LEAVE,
            (GroupRosterReducer.apply(roster(), removeOwner) as GroupRosterResult.Rejected).reason)
        val removeUnknown = GroupMembershipEvent.RemoveMember(UUID.randomUUID(), groupId, 1, owner.fingerprint, "missing")
        assertEquals(GroupRosterResult.Reason.MEMBER_NOT_FOUND,
            (GroupRosterReducer.apply(roster(), removeUnknown) as GroupRosterResult.Rejected).reason)
    }

    @Test fun rejectsDuplicateMembers() {
        val withGuest = (GroupRosterReducer.apply(roster(), add()) as GroupRosterResult.Applied).roster
        val next = GroupMembershipEvent.AddMember(UUID.randomUUID(), groupId, 2, owner.fingerprint, guest)
        assertEquals(GroupRosterResult.Reason.MEMBER_ALREADY_EXISTS,
            (GroupRosterReducer.apply(withGuest, next) as GroupRosterResult.Rejected).reason)
    }
}
