# Incoming Message Notifications Design

## Summary

BLAP will show Android notifications for accepted incoming chat messages when the message's conversation is not currently visible. Nearby messaging will continue after the task is swiped away by running the existing Nearby Connections session in a foreground service.

The design introduces a transport-neutral message boundary so a future Firebase transport can use the same persistence, deduplication, UI, and notification path. This feature does not add Firebase or require internet access.

## Goals

- Receive Nearby messages while the app task is not visible, as long as the user has enabled Nearby messaging.
- Show the conversation or sender name and a message preview in an Android notification.
- Suppress a notification only while that exact conversation is visible.
- Open the relevant conversation when its notification is tapped.
- Preserve the existing `PENDING`, `SENT`, and `DELIVERED` behavior for outgoing messages.
- Store and notify for a logical message only once, even if a future transport delivers the same message ID.
- Keep message processing independent of Nearby- and Firebase-specific SDK types.

## Non-goals

- Firebase chat delivery or Firebase Cloud Messaging.
- Restarting messaging after the user explicitly turns Nearby off or force-stops the app.
- Starting Nearby automatically after device reboot.
- Notification replies, reactions, sounds configuration, or per-conversation settings.
- Changing the existing message protocol or delivery-status semantics.

## Architecture

### Application-scoped chat coordinator

A process-scoped `ChatCoordinator`, provided by a custom `Application` container, becomes the single owner of chat persistence and runtime chat state. It replaces the current arrangement in which `ChatViewModel` directly owns the `ChatStore` and `NearbyChatController`.

The coordinator:

- Validates and persists incoming messages.
- Uses the existing message ID as the cross-transport deduplication key.
- Updates conversation and message state flows consumed by `ChatViewModel`.
- Tracks the active transport registered by the foreground service.
- Preserves acknowledgement and send-status updates.
- Emits an `AcceptedIncomingMessage` event only after a new incoming message has been saved.

The application container owns the coordinator and store for the lifetime of the process. `ChatViewModel.onCleared()` must not close either shared object.

### Transport boundary

A small `MessageTransport` interface covers only shared chat behavior: sending messages, acknowledgements, and reporting incoming messages and send/delivery results. Nearby-only behavior such as discovery, connection authentication, peer announcements, and group synchronization remains behind a Nearby-specific session interface rather than being forced into the generic contract.

`NearbyChatManager` is adapted to these interfaces without changing its wire protocol. The foreground service registers the active Nearby message transport with `ChatCoordinator` while it is running and unregisters it when stopped.

A future Firebase adapter can submit the same transport-neutral incoming envelope to `ChatCoordinator`. If Nearby and Firebase deliver the same message ID, the store insertion fails as a duplicate and no second accepted-message event or notification is emitted.

### Foreground service

`NearbyMessagingService` owns the `NearbyChatManager` instance and the active Nearby session. It is non-exported and declared with `android:foregroundServiceType="connectedDevice"`.

When the user enables Nearby:

1. `MainActivity` obtains all required Nearby permissions.
2. While the activity is visible, it calls `startForegroundService`.
3. The service immediately calls `ServiceCompat.startForeground` with a persistent, low-priority service notification.
4. The service starts advertising and discovery using the saved profile.

Turning Nearby off sends an explicit stop action. The service stops connections, unregisters its transport, removes its foreground notification, and stops itself.

The service may remain alive after the task is swiped away. Android force-stop always terminates it and prevents restart until the user opens the app again; the application cannot override that platform behavior.

### Notification manager

`MessageNotificationManager` is application-scoped and observes accepted incoming-message events. It creates:

- A low-importance channel for the persistent Nearby service status.
- A default-importance channel for incoming messages.

Incoming alerts use `NotificationCompat.MessagingStyle`. A stable notification ID derived from the conversation ID updates one notification per conversation, while separate conversations retain separate notifications.

The notification title is the conversation name. For group conversations, the message entry includes the sender name. The body contains the message preview selected in the approved requirements.

## Visibility and Notification Policy

The UI reports visibility as application foreground state plus the currently visible conversation ID. An incoming notification is suppressed only when:

- The application is in the foreground,
- The current screen is `CONVERSATION`, and
- The selected conversation ID equals the incoming message's conversation ID.

Messages for another conversation still notify while the app is open. Messages received while the app is backgrounded notify even if that conversation was the last selected screen.

If `POST_NOTIFICATIONS` is unavailable, the message is still validated, saved, acknowledged, and exposed to the UI, but the incoming alert is skipped.

## Notification Tap Routing

Each incoming notification contains an immutable, update-current `PendingIntent` targeting `MainActivity` with the conversation ID.

`MainActivity` handles the intent in both initial creation and `onNewIntent`. It forwards the requested conversation ID to `ChatViewModel`, which waits until that conversation exists in repository state and then opens it. Opening the conversation cancels that conversation's notification.

Invalid or unknown conversation IDs fall back to the conversation list without crashing or creating a conversation.

## Permissions and Manifest

Add:

- `android.permission.POST_NOTIFICATIONS`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE`
- A non-exported `NearbyMessagingService` with foreground service type `connectedDevice`
- The custom `Application` class

On Android 13 and newer, the app requests notification permission in context when the user enables Nearby messaging. A denial does not block Nearby messaging, but the UI explains that message alerts are disabled. Existing Bluetooth, Wi-Fi, location, and Nearby permission handling remains authoritative for starting the Nearby session.

The foreground service is started only from the visible activity to comply with Android background-start restrictions.

## Data Flow

### Incoming

1. `NearbyChatManager` decodes and validates the wire packet.
2. The service transport sends a transport-neutral incoming envelope to `ChatCoordinator`.
3. The coordinator applies existing direct/group membership checks.
4. The store inserts the message by message ID.
5. The coordinator requests the transport acknowledgement for every valid addressed message, including a duplicate retry.
6. If the insert is new, the coordinator refreshes state and emits `AcceptedIncomingMessage`.
7. The notification manager evaluates visibility and permission, then posts or suppresses the alert.

### Outgoing

1. `ChatViewModel` sends user input to `ChatCoordinator`.
2. The coordinator creates and stores a `PENDING` message and updates UI state.
3. If an appropriate transport is active, it sends the stored message.
4. Transport callbacks advance status to `SENT` and `DELIVERED` exactly as they do today.
5. Pending messages are retried when the relevant Nearby peer reconnects.

## Error Handling

- A foreground-service start failure leaves Nearby disabled and reports a recoverable UI error.
- Missing Nearby permissions prevent service startup and retain the existing permission guidance.
- Notification permission denial suppresses incoming alerts but not message receipt.
- Notification construction or posting failure must not roll back an already stored message or prevent acknowledgement.
- Duplicate messages are treated as successful no-ops and are still safe to acknowledge where the protocol requires it.
- An invalid notification intent opens the conversation list.
- Service shutdown is idempotent so repeated stop actions do not leak or duplicate connections.

## Testing

### Unit tests

- The coordinator stores and emits an event for a new incoming message.
- Duplicate message IDs produce one stored message and one accepted event.
- Existing direct and private-group validation is preserved.
- Incoming storage occurs before acknowledgement.
- Outgoing status advances remain monotonic.
- The notification policy suppresses only the currently visible conversation.
- Permission denial suppresses alerts without suppressing storage.
- Notification IDs are stable per conversation and distinct across conversations.
- A notification route opens its conversation once repository state is available.

### Service and integration tests

- The service promotes itself immediately and uses the `connectedDevice` type.
- Starting or stopping repeatedly creates at most one Nearby manager.
- Turning Nearby off stops the service and removes the persistent notification.
- Task removal does not invoke the explicit stop path.
- Notification channels have the intended importance.
- Notification taps are handled for both a cold activity start and `onNewIntent`.

Manual verification should cover Android 13 or newer permission grant and denial, app foreground/background behavior, task swipe behavior, direct messages, group messages, and multiple conversations.

## Acceptance Criteria

- With Nearby enabled, a message received while its conversation is not visible creates one incoming-message notification containing the conversation or sender name and message preview.
- No notification is created while the exact conversation is visible.
- Tapping an incoming notification opens that conversation.
- Nearby receipt continues after the task is swiped away while the foreground service remains active.
- Turning Nearby off stops background receipt and removes the service notification.
- Denying notification permission does not lose incoming messages.
- Existing send, retry, acknowledgement, and delivery-status tests continue to pass.
- The implementation contains a transport-neutral incoming-message boundary but no Firebase implementation or dependency.
