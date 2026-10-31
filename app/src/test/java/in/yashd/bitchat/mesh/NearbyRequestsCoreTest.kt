package `in`.yashd.bitchat.mesh

import org.junit.Assert.*
import org.junit.Test

class NearbyRequestsCoreTest {
    @Test fun firstRequestIsFreshAndGatesUntilAccepted() {
        val (state, fresh) = NearbyRequestsCore.addPending(NearbyRequestsState(), "peer", 1000L)
        assertTrue(fresh)
        assertFalse(NearbyRequestsCore.isAccepted(state, "peer"))
        assertEquals(1000L, state.pending["peer"])
    }

    @Test fun duplicateRequestDoesNotNotifyAgain() {
        val (first, _) = NearbyRequestsCore.addPending(NearbyRequestsState(), "peer", 1000L)
        val (second, fresh) = NearbyRequestsCore.addPending(first, "peer", 2000L)
        assertFalse(fresh)
        assertEquals(1000L, second.pending["peer"]) // original timestamp kept
    }

    @Test fun acceptedPeerNeverGoesBackToPending() {
        val accepted = NearbyRequestsCore.accept(NearbyRequestsState(), "peer", notifyPeer = true)
        val (state, fresh) = NearbyRequestsCore.addPending(accepted, "peer", 1000L)
        assertFalse(fresh)
        assertTrue(state.pending.isEmpty())
    }

    @Test fun acceptJoinsListClearsPendingAndQueuesSignedAccept() {
        val (pending, _) = NearbyRequestsCore.addPending(NearbyRequestsState(), "peer", 1000L)
        val state = NearbyRequestsCore.accept(pending, "peer", notifyPeer = true)
        assertTrue(NearbyRequestsCore.isAccepted(state, "peer"))
        assertFalse(state.pending.containsKey("peer"))
        assertEquals(setOf("peer"), state.acceptOutbox)
    }

    @Test fun startingAChatOurselvesDoesNotQueueAnAcceptNotice() {
        val state = NearbyRequestsCore.accept(NearbyRequestsState(), "peer", notifyPeer = false)
        assertTrue(NearbyRequestsCore.isAccepted(state, "peer"))
        assertTrue(state.acceptOutbox.isEmpty())
    }

    @Test fun dispatchedAcceptLeavesTheOutbox() {
        val accepted = NearbyRequestsCore.accept(NearbyRequestsState(), "peer", notifyPeer = true)
        val state = NearbyRequestsCore.acceptDispatched(accepted, "peer")
        assertTrue(state.acceptOutbox.isEmpty())
        assertTrue(NearbyRequestsCore.isAccepted(state, "peer"))
    }

    @Test fun declineIsSilentAndKeepsThemOut() {
        val (pending, _) = NearbyRequestsCore.addPending(NearbyRequestsState(), "peer", 1000L)
        val state = NearbyRequestsCore.decline(pending, "peer")
        assertTrue(state.pending.isEmpty())
        assertFalse(NearbyRequestsCore.isAccepted(state, "peer"))
        assertTrue(state.acceptOutbox.isEmpty())
    }

    @Test fun theirAcceptanceNotifiesOnlyOnce() {
        val (first, firstTime) = NearbyRequestsCore.markAcceptedBy(NearbyRequestsState(), "peer")
        assertTrue(firstTime)
        val (_, again) = NearbyRequestsCore.markAcceptedBy(first, "peer")
        assertFalse(again)
    }

    @Test fun migrationSeedNeverOverwritesStoredDecisions() {
        val stored = NearbyRequestsState(accepted = setOf("kept"))
        val seeded = NearbyRequestsCore.seedAccepted(stored, setOf("other"), alreadyStored = true)
        assertEquals(setOf("kept"), seeded.accepted)
        val fresh = NearbyRequestsCore.seedAccepted(NearbyRequestsState(), setOf("a", "b"), alreadyStored = false)
        assertEquals(setOf("a", "b"), fresh.accepted)
    }
}
