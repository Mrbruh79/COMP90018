package com.example.blap.event

/** Account-local event storage and cloud/access services used by the current coordinator. */
data class EventServices(
    val store: EventStore,
    val remoteRepository: EventRemoteRepository,
    val adminKeyStore: EventAdminKeyStore,
    val meshGateway: EventMeshGateway,
)
