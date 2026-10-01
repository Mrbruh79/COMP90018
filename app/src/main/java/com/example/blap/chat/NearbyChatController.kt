package com.example.blap.chat

import com.example.blap.event.EventMeshGateway

/** Chat traffic on the shared Nearby session. This gateway does not own event isolation. */
interface NearbyChatGateway : NearbyTransport

/** Existing managers continue to provide chat and event traffic on one session. */
interface NearbyChatController : NearbyChatGateway, EventMeshGateway {
    interface Listener : NearbyTransport.Listener
}
