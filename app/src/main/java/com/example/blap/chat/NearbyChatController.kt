package com.example.blap.chat

import com.example.blap.event.EventMeshGateway

/** Existing managers continue to provide chat and event traffic on one session. */
interface NearbyChatController : NearbyTransport, EventMeshGateway {
    interface Listener : NearbyTransport.Listener
}
