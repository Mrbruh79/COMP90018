package com.example.blap.service

import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.NearbyChatController

class NearbyServiceSession(
    private val coordinator: ChatCoordinator,
    private val controller: NearbyChatController,
) {
    private var attached = false
    private var closed = false

    fun start(): Boolean {
        if (closed) return false
        if (!attached) {
            coordinator.attachNearbyController(controller)
            attached = true
        }
        return coordinator.startChat()
    }

    fun stop() {
        if (closed) return
        coordinator.stopChat()
        if (attached) coordinator.detachNearbyController(controller)
        controller.close()
        attached = false
        closed = true
    }
}
