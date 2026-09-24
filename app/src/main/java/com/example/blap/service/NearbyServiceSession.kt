package com.example.blap.service

import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.NearbyChatController

class NearbyServiceSession(
    private val coordinator: ChatCoordinator,
    private val controller: NearbyChatController,
    private val onStopRequested: () -> Unit = {},
) {
    private var attached = false
    private var closed = false

    fun start(): Boolean {
        if (closed) return false
        if (!attached) {
            coordinator.attachNearbyController(controller, onStopRequested)
            attached = true
        }
        return coordinator.startChat()
    }

    fun stop() {
        if (closed) return
        closed = true
        try {
            coordinator.stopChat()
        } finally {
            try {
                if (attached) coordinator.detachNearbyController(controller)
            } finally {
                attached = false
                controller.close()
            }
        }
    }
}
