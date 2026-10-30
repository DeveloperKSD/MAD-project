package `in`.yashd.bitchat.mesh

/**
 * Pure decision logic for nearby chat requests; persistence lives in NearbyRequestsStore.
 * accepted: peers whose messages go straight to the chat list.
 * pending: first messages waiting for an Accept/Decline decision.
 * acceptedBy: peers who accepted a request of ours.
 * acceptOutbox: signed accepts that still need (re)delivery to the mesh.
 */
data class NearbyRequestsState(
    val accepted: Set<String> = emptySet(),
    val pending: Map<String, Long> = emptyMap(),
    val acceptedBy: Set<String> = emptySet(),
    val acceptOutbox: Set<String> = emptySet()
)

object NearbyRequestsCore {
    fun isAccepted(state: NearbyRequestsState, peerId: String): Boolean = peerId in state.accepted

    /** A new incoming request; true when it is genuinely new (worth notifying). */
    fun addPending(state: NearbyRequestsState, peerId: String, atMs: Long): Pair<NearbyRequestsState, Boolean> =
        if (peerId in state.accepted || peerId in state.pending) state to false
        else state.copy(pending = state.pending + (peerId to atMs)) to true

    /**
     * Accept: the chat joins the list and their messages flow freely.
     * [notifyPeer] queues the signed accept for (re)delivery; starting a chat ourselves
     * passes false so they are not told they were accepted for a request they never sent.
     */
    fun accept(state: NearbyRequestsState, peerId: String, notifyPeer: Boolean): NearbyRequestsState = state.copy(
        accepted = state.accepted + peerId,
        pending = state.pending - peerId,
        acceptOutbox = if (notifyPeer) state.acceptOutbox + peerId else state.acceptOutbox
    )

    /** Decline is silent: the request leaves this phone, they are never told. */
    fun decline(state: NearbyRequestsState, peerId: String): NearbyRequestsState =
        state.copy(pending = state.pending - peerId)

    /** The signed accept was handed to the mesh; stop retrying it. */
    fun acceptDispatched(state: NearbyRequestsState, peerId: String): NearbyRequestsState =
        state.copy(acceptOutbox = state.acceptOutbox - peerId)

    /** Their acceptance of our request; true the first time (worth notifying). */
    fun markAcceptedBy(state: NearbyRequestsState, peerId: String): Pair<NearbyRequestsState, Boolean> =
        if (peerId in state.acceptedBy) state to false
        else state.copy(acceptedBy = state.acceptedBy + peerId) to true

    /** Migration seed: only when nothing was stored yet, so real decisions are never overwritten. */
    fun seedAccepted(state: NearbyRequestsState, peerIds: Set<String>, alreadyStored: Boolean): NearbyRequestsState =
        if (alreadyStored) state else state.copy(accepted = peerIds)
}
