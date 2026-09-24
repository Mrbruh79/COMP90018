# Incoming Message Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep Nearby messaging active in a foreground service and notify the user about accepted incoming messages whenever that conversation is not currently visible.

**Architecture:** Move the current process-lifetime chat logic from the activity-scoped `ChatViewModel` into an application-scoped `ChatCoordinator`, leaving the ViewModel as a thin UI adapter. A `NearbyMessagingService` owns `NearbyChatManager`; accepted transport-neutral messages flow through the coordinator into SQLite, then into an application-scoped notification manager that applies foreground/conversation visibility rules.

**Tech Stack:** Kotlin 2.3.21, Android SDK 25–37, Jetpack Compose, AndroidX lifecycle 2.11.0, AndroidX Core 1.19.0 (`NotificationCompat` and `ServiceCompat`), Google Nearby Connections 19.4.0, SQLite, JUnit 4, AndroidX Test.

## Global Constraints

- Do not add Firebase chat delivery, Firebase Cloud Messaging, or a new Firebase dependency.
- Preserve the existing Nearby wire protocol and `PENDING`, `SENT`, and `DELIVERED` semantics.
- Use the existing message ID as the cross-transport deduplication key.
- Notify only after a valid incoming message is newly stored.
- A valid duplicate may be acknowledged but must not emit a second accepted-message event or notification.
- Suppress an alert only when the app is foregrounded with that exact conversation visible.
- Notification denial must not block message storage, acknowledgement, or Nearby service startup.
- The service is non-exported and uses foreground service type `connectedDevice`.
- Turning Nearby off stops the service; task removal does not. Android force-stop remains authoritative.
- Use one low-importance service channel and one default-importance incoming-message channel.
- Use one tagged notification per conversation and include sender/conversation plus message preview.

## File Structure

- Create `app/src/main/java/com/example/blap/chat/ChatCoordinator.kt`: application-scoped chat state, persistence, Nearby callbacks, and accepted-message events.
- Replace `app/src/main/java/com/example/blap/chat/ChatViewModel.kt`: thin lifecycle-safe delegate to `ChatCoordinator`.
- Create `app/src/main/java/com/example/blap/chat/MessageTransport.kt`: transport-neutral send, acknowledgement, and incoming envelope contracts.
- Create `app/src/main/java/com/example/blap/BlapApplication.kt`: process-scoped dependency container.
- Create `app/src/main/java/com/example/blap/notifications/ConversationVisibilityTracker.kt`: foreground visibility state.
- Create `app/src/main/java/com/example/blap/notifications/IncomingNotificationPolicy.kt`: pure notification decision.
- Create `app/src/main/java/com/example/blap/notifications/MessageNotificationManager.kt`: channels, message notifications, status notification, tap intents, and cancellation.
- Create `app/src/main/java/com/example/blap/service/NearbyServiceSession.kt`: testable coordinator/controller lifecycle.
- Create `app/src/main/java/com/example/blap/service/NearbyMessagingService.kt`: Android foreground-service wrapper.
- Modify `MainActivity.kt`: permission flow, service commands, visibility, and notification-intent routing.
- Modify `AndroidManifest.xml`: application, notification/foreground permissions, and service.
- Modify `strings.xml`: user-visible channel and service text.
- Modify existing chat tests and add focused policy, coordinator, service-session, and instrumented notification tests.

---

### Task 1: Extract the application-scoped chat coordinator

**Files:**
- Create: `app/src/main/java/com/example/blap/chat/ChatCoordinator.kt`
- Create: `app/src/main/java/com/example/blap/BlapApplication.kt`
- Create: `app/src/test/java/com/example/blap/chat/ChatTestFakes.kt`
- Modify: `app/src/main/java/com/example/blap/chat/ChatViewModel.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt`

**Interfaces:**
- Produces: `ChatCoordinator.uiState: StateFlow<ChatUiState>`
- Produces: `ChatViewModel.factory(application: BlapApplication): ViewModelProvider.Factory`
- Preserves every existing public `ChatViewModel` action and constant.

- [ ] **Step 1: Extract reusable test fakes**

Move the existing nested `FakeIdentityStore`, `FakeChatStore`, and `FakeNearbyChatController` classes from `ChatViewModelTest` into `ChatTestFakes.kt` in package `com.example.blap.chat`. Remove `private`, mark each class `internal`, and retain every current method body and test-observable field. This is a file move only; do not alter fake behavior in this step.

- [ ] **Step 2: Change the test fixture to construct a coordinator and wrapper**

Replace `makeViewModel` in `ChatViewModelTest.kt` with:

```kotlin
private fun makeViewModel(
    controller: FakeNearbyChatController,
    store: FakeChatStore = FakeChatStore(),
    identityStore: FakeIdentityStore = FakeIdentityStore(),
): ChatViewModel {
    val coordinator = ChatCoordinator(
        nearbyChatController = controller,
        chatStore = store,
        identityStore = identityStore,
        ioDispatcher = Dispatchers.Unconfined,
    )
    return ChatViewModel(coordinator)
}
```

- [ ] **Step 3: Run the ViewModel tests to verify the new type is missing**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.chat.ChatViewModelTest"
```

Expected: compilation fails because `ChatCoordinator` and the new `ChatViewModel` constructor do not exist.

- [ ] **Step 4: Move the existing implementation into `ChatCoordinator`**

Run:

```powershell
git mv app/src/main/java/com/example/blap/chat/ChatViewModel.kt app/src/main/java/com/example/blap/chat/ChatCoordinator.kt
```

In the moved file:

```kotlin
class ChatCoordinator(
    private val nearbyChatController: NearbyChatController,
    private val chatStore: ChatStore,
    private val identityStore: IdentityStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : NearbyChatController.Listener
```

Replace the existing lifecycle callback with:

```kotlin
fun close() {
    nearbyChatController.close()
    workScope.cancel()
    chatStore.close()
}
```

Remove the Android `Context`, `ViewModel`, and `ViewModelProvider` imports and delete only the old factory from the companion object. Retain the seven existing `MAX_*` constants and every chat method other than the lifecycle rename above without changing their bodies.

- [ ] **Step 5: Add the thin ViewModel delegate**

Create `ChatViewModel.kt`:

```kotlin
package com.example.blap.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.blap.BlapApplication

class ChatViewModel internal constructor(
    internal val coordinator: ChatCoordinator,
) : ViewModel() {
    val uiState = coordinator.uiState

    fun updateDisplayName(value: String) = coordinator.updateDisplayName(value)
    fun updatePhoneNumber(value: String) = coordinator.updatePhoneNumber(value)
    fun startChat() = coordinator.startChat()
    fun completeSetup() = coordinator.completeSetup()
    fun connectToDevice(id: String) = coordinator.connectToDevice(id)
    fun openConversation(id: String) = coordinator.openConversation(id)
    fun showConversationList() = coordinator.showConversationList()
    fun beginManageContacts() = coordinator.beginManageContacts()
    fun beginAddContact() = coordinator.beginAddContact()
    fun openContact(id: String) = coordinator.openContact(id)
    fun messageContact(id: String) = coordinator.messageContact(id)
    fun updateContactDraft(profile: ContactProfile) = coordinator.updateContactDraft(profile)
    fun importScannedContactCard(payload: String) = coordinator.importScannedContactCard(payload)
    fun updateContactName(value: String) = coordinator.updateContactName(value)
    fun updateContactPhone(value: String) = coordinator.updateContactPhone(value)
    fun saveContact() = coordinator.saveContact()
    fun deleteContact() = coordinator.deleteContact()
    fun importDeviceContacts(contacts: List<DeviceContact>) = coordinator.importDeviceContacts(contacts)
    fun beginCreateGroup() = coordinator.beginCreateGroup()
    fun beginGroupSettings() = coordinator.beginGroupSettings()
    fun saveGroupSettings() = coordinator.saveGroupSettings()
    fun deleteCurrentGroup() = coordinator.deleteCurrentGroup()
    fun showMyCard() = coordinator.showMyCard()
    fun editProfile() = coordinator.editProfile()
    fun updateProfile(profile: ContactProfile) = coordinator.updateProfile(profile)
    fun cancelProfileEdit() = coordinator.cancelProfileEdit()
    fun saveProfile() = coordinator.saveProfile()
    fun showSettings() = coordinator.showSettings()
    fun updateConversationSearch(value: String) = coordinator.updateConversationSearch(value)
    fun updateContactSearch(value: String) = coordinator.updateContactSearch(value)
    fun updateGroupName(value: String) = coordinator.updateGroupName(value)
    fun toggleGroupMember(id: String) = coordinator.toggleGroupMember(id)
    fun createPrivateGroup() = coordinator.createPrivateGroup()
    fun handleBack() = coordinator.handleBack()
    fun sendMessage(text: String) = coordinator.sendMessage(text)
    fun disconnect(id: String) = coordinator.disconnect(id)
    fun dismissError() = coordinator.dismissError()
    fun updateMessageDraft(text: String) = coordinator.updateMessageDraft(text)
    fun showError(message: String) = coordinator.showError(message)
    fun updateVenueStatus(message: String, checking: Boolean = false) =
        coordinator.updateVenueStatus(message, checking)
    fun stopChat() = coordinator.stopChat()

    companion object {
        const val MAX_NAME_LENGTH = ChatCoordinator.MAX_NAME_LENGTH
        const val MAX_MESSAGE_LENGTH = ChatCoordinator.MAX_MESSAGE_LENGTH
        const val MAX_GROUP_NAME_LENGTH = ChatCoordinator.MAX_GROUP_NAME_LENGTH
        const val MAX_PHONE_LENGTH = ChatCoordinator.MAX_PHONE_LENGTH
        const val MAX_EMAIL_LENGTH = ChatCoordinator.MAX_EMAIL_LENGTH
        const val MAX_BIO_LENGTH = ChatCoordinator.MAX_BIO_LENGTH
        const val MAX_URL_LENGTH = ChatCoordinator.MAX_URL_LENGTH

        fun factory(application: BlapApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(application.chatCoordinator) as T
            }
    }
}
```

- [ ] **Step 6: Add the initial process-scoped container**

Create `BlapApplication.kt`:

```kotlin
package com.example.blap

import android.app.Application
import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.LocalIdentityStore
import com.example.blap.chat.NearbyChatManager
import com.example.blap.chat.SqliteChatStore

class BlapApplication : Application() {
    lateinit var chatCoordinator: ChatCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        chatCoordinator = ChatCoordinator(
            nearbyChatController = NearbyChatManager(this),
            chatStore = SqliteChatStore(this),
            identityStore = LocalIdentityStore(this),
        )
    }
}
```

Set `android:name=".BlapApplication"` on `<application>`. Change `MainActivity` to use:

```kotlin
private val viewModel: ChatViewModel by viewModels {
    ChatViewModel.factory(application as BlapApplication)
}
```

- [ ] **Step 7: Run focused and full unit tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.chat.ChatViewModelTest"
.\gradlew.bat testDebugUnitTest
```

Expected: both commands pass; no existing behavior changes.

- [ ] **Step 8: Commit the extraction**

```powershell
git add app/src/main/java/com/example/blap/chat/ChatCoordinator.kt app/src/main/java/com/example/blap/chat/ChatViewModel.kt app/src/main/java/com/example/blap/BlapApplication.kt app/src/main/AndroidManifest.xml app/src/main/java/com/example/blap/MainActivity.kt app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt app/src/test/java/com/example/blap/chat/ChatTestFakes.kt
git commit -m "refactor: move chat state into app coordinator"
```

---

### Task 2: Add the transport-neutral accepted-message path

**Files:**
- Create: `app/src/main/java/com/example/blap/chat/MessageTransport.kt`
- Modify: `app/src/main/java/com/example/blap/chat/ChatModels.kt`
- Modify: `app/src/main/java/com/example/blap/chat/NearbyChatController.kt`
- Modify: `app/src/main/java/com/example/blap/chat/NearbyChatManager.kt`
- Modify: `app/src/main/java/com/example/blap/chat/ChatCoordinator.kt`
- Modify: `app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt`

**Interfaces:**
- Produces: `MessageTransport.sendMessage(OutgoingMessageEnvelope)`
- Produces: `MessageTransport.acknowledgeMessage(String, String, String)`
- Produces: `ChatCoordinator.acceptedIncomingMessages: SharedFlow<AcceptedIncomingMessage>`
- Produces: `ChatCoordinator.receiveIncomingMessage(IncomingMessageEnvelope, MessageTransport)`

- [ ] **Step 1: Add failing tests for accepted events and duplicate suppression**

Add to `ChatViewModelTest.kt`:

```kotlin
@Test
fun newIncomingMessageEmitsOneAcceptedEvent() {
    val controller = FakeNearbyChatController()
    val store = FakeChatStore()
    val viewModel = makeViewModel(controller, store)
    val accepted = mutableListOf<AcceptedIncomingMessage>()
    val job = CoroutineScope(Dispatchers.Unconfined).launch {
        viewModel.coordinator.acceptedIncomingMessages.take(1).toList(accepted)
    }

    controller.listener?.onMessageReceived(
        IncomingMessageEnvelope("m1", "bob", "bob", "Bob", "", "Hello", 100L),
    )

    assertEquals(listOf("m1"), accepted.map { it.message.id })
    job.cancel()
}

@Test
fun duplicateIncomingMessageIsAcknowledgedButNotAcceptedTwice() {
    val controller = FakeNearbyChatController()
    val store = FakeChatStore()
    val viewModel = makeViewModel(controller, store)
    val accepted = mutableListOf<AcceptedIncomingMessage>()
    val job = CoroutineScope(Dispatchers.Unconfined).launch {
        viewModel.coordinator.acceptedIncomingMessages.toList(accepted)
    }
    val message = IncomingMessageEnvelope("m1", "bob", "bob", "Bob", "", "Hello", 100L)

    controller.listener?.onMessageReceived(message)
    controller.listener?.onMessageReceived(message)

    assertEquals(1, store.getMessages("bob").size)
    assertEquals(1, accepted.size)
    assertEquals(2, controller.acknowledgements.size)
    job.cancel()
}
```

Add imports for `CoroutineScope`, `launch`, `take`, and `toList`.

- [ ] **Step 2: Run the tests and verify missing transport-neutral types**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.chat.ChatViewModelTest.newIncomingMessageEmitsOneAcceptedEvent" --tests "com.example.blap.chat.ChatViewModelTest.duplicateIncomingMessageIsAcknowledgedButNotAcceptedTwice"
```

Expected: compilation fails for `AcceptedIncomingMessage`, `IncomingMessageEnvelope`, and `acceptedIncomingMessages`.

- [ ] **Step 3: Define transport-neutral contracts**

Create `MessageTransport.kt`:

```kotlin
package com.example.blap.chat

interface MessageTransport {
    fun sendMessage(message: OutgoingMessageEnvelope)
    fun acknowledgeMessage(conversationId: String, senderId: String, messageId: String)
}

data class AcceptedIncomingMessage(
    val message: ChatMessage,
    val conversation: ConversationSummary,
)
```

Rename the two models in `ChatModels.kt` without changing fields:

```kotlin
data class OutgoingMessageEnvelope(
    val messageId: String,
    val peerId: String,
    val text: String,
    val sentAt: Long,
    val isGroup: Boolean = false,
)

data class IncomingMessageEnvelope(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val senderPhoneHash: String,
    val text: String,
    val sentAt: Long,
)
```

Update all references from `OutgoingNearbyMessage` and `IncomingNearbyMessage`. Make `NearbyChatController : MessageTransport`, remove its duplicate `sendMessage` and `acknowledgeMessage` declarations, and keep its Nearby-specific methods and listener.

- [ ] **Step 4: Emit only newly accepted stored messages**

In `ChatCoordinator` add:

```kotlin
private val _acceptedIncomingMessages = MutableSharedFlow<AcceptedIncomingMessage>(
    extraBufferCapacity = 64,
)
val acceptedIncomingMessages: SharedFlow<AcceptedIncomingMessage> =
    _acceptedIncomingMessages.asSharedFlow()

fun receiveIncomingMessage(
    message: IncomingMessageEnvelope,
    source: MessageTransport,
) {
    val savedMessage = ChatMessage(
        id = message.messageId,
        peerId = message.conversationId,
        text = message.text,
        author = if (message.senderId == localPeerId) MessageAuthor.ME else MessageAuthor.PEER,
        sentAt = message.sentAt,
        status = MessageStatus.DELIVERED,
        senderId = message.senderId,
        senderName = message.senderName,
        senderPhoneHash = message.senderPhoneHash,
    )
    workScope.launch {
        val isOpenMesh = message.conversationId == MeshGroup.ID
        val isDirect = message.conversationId == message.senderId
        val isPrivateMember = !isOpenMesh && !isDirect &&
            chatStore.isGroupMember(message.conversationId, localPeerId, localPhoneHash) &&
            chatStore.isGroupMember(
                message.conversationId,
                message.senderId,
                message.senderPhoneHash,
            )
        if (!isOpenMesh && !isDirect && !isPrivateMember) return@launch

        if (isOpenMesh) {
            chatStore.savePeer(MeshGroup.ID, MeshGroup.NAME)
        } else if (isDirect) {
            chatStore.savePeer(message.senderId, message.senderName, message.senderPhoneHash)
        }

        val inserted = chatStore.saveMessage(savedMessage)
        source.acknowledgeMessage(message.conversationId, message.senderId, message.messageId)
        if (!inserted) return@launch

        reloadConversationsNow()
        if (_uiState.value.selectedPeerId == message.conversationId) {
            reloadMessagesNow(message.conversationId)
        }
        val conversation = _uiState.value.conversations
            .first { it.peerId == message.conversationId }
        _acceptedIncomingMessages.emit(AcceptedIncomingMessage(savedMessage, conversation))
    }
}

override fun onMessageReceived(message: IncomingMessageEnvelope) {
    receiveIncomingMessage(message, nearbyChatController)
}
```

Replace the old `onMessageReceived` body. Keep the existing validation order and ensure acknowledgement remains after persistence.

- [ ] **Step 5: Run chat tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.chat.ChatViewModelTest"
```

Expected: all tests pass, including one event for two deliveries of the same ID and two acknowledgements.

- [ ] **Step 6: Commit the transport boundary**

```powershell
git add app/src/main/java/com/example/blap/chat app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt
git commit -m "refactor: add transport neutral message ingress"
```

---

### Task 3: Build notification policy and Android notification manager

**Files:**
- Create: `app/src/main/java/com/example/blap/notifications/ConversationVisibilityTracker.kt`
- Create: `app/src/main/java/com/example/blap/notifications/IncomingNotificationPolicy.kt`
- Create: `app/src/main/java/com/example/blap/notifications/MessageNotificationManager.kt`
- Create: `app/src/test/java/com/example/blap/notifications/IncomingNotificationPolicyTest.kt`
- Create: `app/src/androidTest/java/com/example/blap/notifications/MessageNotificationManagerTest.kt`
- Modify: `app/src/main/java/com/example/blap/BlapApplication.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `ChatCoordinator.acceptedIncomingMessages`
- Produces: `ConversationVisibilityTracker.setAppForeground(Boolean)`
- Produces: `MessageNotificationManager.start()`, `serviceNotification()`, and `cancelConversation(String)`
- Produces constants `SERVICE_NOTIFICATION_ID`, `MESSAGE_NOTIFICATION_ID`, `SERVICE_CHANNEL_ID`, and `MESSAGE_CHANNEL_ID`.

- [ ] **Step 1: Write the pure notification-policy tests**

Create `IncomingNotificationPolicyTest.kt`:

```kotlin
package com.example.blap.notifications

import com.example.blap.chat.ChatScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingNotificationPolicyTest {
    @Test
    fun suppressesOnlyTheVisibleConversation() {
        assertFalse(IncomingNotificationPolicy.shouldNotify(
            permissionGranted = true,
            appForeground = true,
            screen = ChatScreen.CONVERSATION,
            selectedConversationId = "bob",
            incomingConversationId = "bob",
        ))
        assertTrue(IncomingNotificationPolicy.shouldNotify(
            permissionGranted = true,
            appForeground = true,
            screen = ChatScreen.CONVERSATION,
            selectedConversationId = "bob",
            incomingConversationId = "carol",
        ))
    }

    @Test
    fun backgroundMessageNotifiesAndPermissionDenialDoesNot() {
        assertTrue(IncomingNotificationPolicy.shouldNotify(
            true, false, ChatScreen.CONVERSATION, "bob", "bob",
        ))
        assertFalse(IncomingNotificationPolicy.shouldNotify(
            false, false, ChatScreen.CHATS, null, "bob",
        ))
    }
}
```

- [ ] **Step 2: Run the policy test and verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.notifications.IncomingNotificationPolicyTest"
```

Expected: compilation fails because `IncomingNotificationPolicy` does not exist.

- [ ] **Step 3: Implement visibility and policy**

Create `ConversationVisibilityTracker.kt`:

```kotlin
package com.example.blap.notifications

class ConversationVisibilityTracker {
    @Volatile
    private var appForeground = false

    fun setAppForeground(value: Boolean) {
        appForeground = value
    }

    fun isAppForeground(): Boolean = appForeground
}
```

Create `IncomingNotificationPolicy.kt`:

```kotlin
package com.example.blap.notifications

import com.example.blap.chat.ChatScreen

object IncomingNotificationPolicy {
    fun shouldNotify(
        permissionGranted: Boolean,
        appForeground: Boolean,
        screen: ChatScreen,
        selectedConversationId: String?,
        incomingConversationId: String,
    ): Boolean = permissionGranted && !(
        appForeground &&
            screen == ChatScreen.CONVERSATION &&
            selectedConversationId == incomingConversationId
        )
}
```

- [ ] **Step 4: Add AndroidX Test Core and user-facing strings**

Add version and alias:

```toml
androidxTestCore = "1.7.0"
androidx-test-core = { group = "androidx.test", name = "core-ktx", version.ref = "androidxTestCore" }
```

Add to `androidTestImplementation`:

```kotlin
androidTestImplementation(libs.androidx.test.core)
```

Add strings:

```xml
<string name="notification_channel_nearby_name">Nearby messaging status</string>
<string name="notification_channel_messages_name">Incoming messages</string>
<string name="notification_nearby_active_title">Nearby messaging is active</string>
<string name="notification_nearby_active_text">BLAP can receive messages while the app is closed.</string>
```

- [ ] **Step 5: Implement `MessageNotificationManager`**

Create the class with these exact public constants and behavior:

```kotlin
package com.example.blap.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.example.blap.MainActivity
import com.example.blap.R
import com.example.blap.chat.AcceptedIncomingMessage
import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.ConversationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MessageNotificationManager(
    private val context: Context,
    private val coordinator: ChatCoordinator,
    private val visibility: ConversationVisibilityTracker,
    private val scope: CoroutineScope,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val recent = mutableMapOf<String, ArrayDeque<AcceptedIncomingMessage>>()

    fun start() {
        createChannels()
        scope.launch {
            coordinator.acceptedIncomingMessages.collect { event ->
                runCatching { onIncomingMessage(event) }
            }
        }
        scope.launch {
            coordinator.uiState
                .map { state ->
                    state.selectedPeerId.takeIf {
                        state.screen == com.example.blap.chat.ChatScreen.CONVERSATION
                    }
                }
                .distinctUntilChanged()
                .collect { conversationId ->
                    conversationId?.let(::cancelConversation)
                }
        }
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(context.getString(R.string.notification_nearby_active_title))
            .setContentText(context.getString(R.string.notification_nearby_active_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainPendingIntent())
            .build()

    fun cancelConversation(conversationId: String) {
        recent.remove(conversationId)
        manager.cancel(notificationTag(conversationId), MESSAGE_NOTIFICATION_ID)
    }

    internal fun notificationTag(conversationId: String) = "conversation:$conversationId"

    @SuppressLint("MissingPermission")
    private fun onIncomingMessage(event: AcceptedIncomingMessage) {
        val state = coordinator.uiState.value
        if (!IncomingNotificationPolicy.shouldNotify(
                permissionGranted = canPostNotifications(),
                appForeground = visibility.isAppForeground(),
                screen = state.screen,
                selectedConversationId = state.selectedPeerId,
                incomingConversationId = event.message.peerId,
            )
        ) return

        val history = recent.getOrPut(event.message.peerId) { ArrayDeque() }
        history.addLast(event)
        while (history.size > MAX_MESSAGES) history.removeFirst()

        val me = Person.Builder().setName(state.displayName.ifBlank { "You" }).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(event.conversation.name)
            .setGroupConversation(event.conversation.type != ConversationType.DIRECT)
        history.forEach { item ->
            val sender = Person.Builder()
                .setName(item.message.senderName.ifBlank { item.conversation.name })
                .build()
            style.addMessage(item.message.text, item.message.sentAt, sender)
        }

        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(event.conversation.name)
            .setContentText(event.message.text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setGroup(MESSAGE_GROUP_KEY)
            .setContentIntent(conversationPendingIntent(event.message.peerId))
            .build()
        manager.notify(notificationTag(event.message.peerId), MESSAGE_NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled()

    private fun conversationPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CONVERSATION
            data = Uri.parse("blap://conversation/${Uri.encode(conversationId)}")
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = context.getSystemService(NotificationManager::class.java)
        systemManager.createNotificationChannels(listOf(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                context.getString(R.string.notification_channel_nearby_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
            NotificationChannel(
                MESSAGE_CHANNEL_ID,
                context.getString(R.string.notification_channel_messages_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        ))
    }

    companion object {
        const val SERVICE_CHANNEL_ID = "nearby_service"
        const val MESSAGE_CHANNEL_ID = "incoming_messages"
        const val SERVICE_NOTIFICATION_ID = 1001
        const val MESSAGE_NOTIFICATION_ID = 1002
        const val ACTION_OPEN_CONVERSATION = "com.example.blap.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        private const val MESSAGE_GROUP_KEY = "blap_messages"
        private const val MAX_MESSAGES = 5
    }
}
```

- [ ] **Step 6: Initialize the visibility tracker and notification manager**

In `BlapApplication`, add an application coroutine scope and initialize after the coordinator:

```kotlin
private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

lateinit var conversationVisibility: ConversationVisibilityTracker
    private set
lateinit var messageNotifications: MessageNotificationManager
    private set

conversationVisibility = ConversationVisibilityTracker()
messageNotifications = MessageNotificationManager(
    this,
    chatCoordinator,
    conversationVisibility,
    applicationScope,
).also { it.start() }
```

- [ ] **Step 7: Add an instrumented channel test**

Create `MessageNotificationManagerTest.kt`:

```kotlin
package com.example.blap.notifications

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageNotificationManagerTest {
    @Test
    fun applicationCreatesNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)

        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(MessageNotificationManager.SERVICE_CHANNEL_ID).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel(MessageNotificationManager.MESSAGE_CHANNEL_ID).importance,
        )
    }
}
```

- [ ] **Step 8: Run policy tests and compile instrumentation**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.notifications.IncomingNotificationPolicyTest"
.\gradlew.bat compileDebugAndroidTestKotlin
```

Expected: both pass.

- [ ] **Step 9: Commit notification infrastructure**

```powershell
git add app/src/main/java/com/example/blap/notifications app/src/test/java/com/example/blap/notifications app/src/androidTest/java/com/example/blap/notifications app/src/main/java/com/example/blap/BlapApplication.kt app/src/main/res/values/strings.xml gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add incoming message notification manager"
```

---

### Task 4: Move Nearby ownership into a foreground service

**Files:**
- Create: `app/src/main/java/com/example/blap/service/NearbyServiceSession.kt`
- Create: `app/src/main/java/com/example/blap/service/NearbyMessagingService.kt`
- Create: `app/src/test/java/com/example/blap/service/NearbyServiceSessionTest.kt`
- Modify: `app/src/main/java/com/example/blap/chat/ChatCoordinator.kt`
- Modify: `app/src/main/java/com/example/blap/BlapApplication.kt`
- Modify: `app/src/main/java/com/example/blap/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/com/example/blap/chat/ChatTestFakes.kt`
- Modify: `app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt`

**Interfaces:**
- Produces: `ChatCoordinator.attachNearbyController(NearbyChatController)`
- Produces: `ChatCoordinator.detachNearbyController(NearbyChatController)`
- Produces: `NearbyMessagingService.start(Context)` and `NearbyMessagingService.stop(Context)`
- Consumes: `MessageNotificationManager.serviceNotification()`.

- [ ] **Step 1: Write a failing idempotent service-session test**

Create `NearbyServiceSessionTest.kt` using the fake controller/store/identity equivalents from `ChatViewModelTest`:

```kotlin
package com.example.blap.service

import com.example.blap.chat.ChatCoordinator
import com.example.blap.chat.FakeChatStore
import com.example.blap.chat.FakeIdentityStore
import com.example.blap.chat.FakeNearbyChatController
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyServiceSessionTest {
@Test
fun repeatedStartAndStopOwnOneNearbySession() {
    val controller = FakeNearbyChatController()
    val coordinator = ChatCoordinator(
        chatStore = FakeChatStore(),
        identityStore = FakeIdentityStore(),
        ioDispatcher = Dispatchers.Unconfined,
    )
    coordinator.updateDisplayName("Alice")
    val session = NearbyServiceSession(coordinator, controller)

    assertTrue(session.start())
    assertTrue(session.start())
    assertEquals(1, controller.startAdvertisingCalls)

    session.stop()
    session.stop()
    assertEquals(1, controller.closeCalls)
    assertFalse(coordinator.uiState.value.nearbyActive)
}
}
```

In `ChatTestFakes.kt`, add `startAdvertisingCalls` and `closeCalls` integer fields. Increment them at the start of `startAdvertising` and `close`, respectively.

- [ ] **Step 2: Run the service-session test and verify it fails**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.service.NearbyServiceSessionTest"
```

Expected: compilation fails because `NearbyServiceSession` and attach/detach APIs do not exist.

- [ ] **Step 3: Make the coordinator controller attachable**

Change the coordinator constructor to:

```kotlin
class ChatCoordinator(
    private val chatStore: ChatStore,
    private val identityStore: IdentityStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    initialNearbyChatController: NearbyChatController? = null,
) : NearbyChatController.Listener {
    @Volatile
    private var nearbyChatController: NearbyChatController? = null

    init {
        initialNearbyChatController?.let(::attachNearbyController)
        workScope.launch {
            chatStore.savePeer(MeshGroup.ID, MeshGroup.NAME)
            reloadConversationsNow()
            reloadSavedContactsNow()
        }
    }

    fun attachNearbyController(controller: NearbyChatController) {
        if (nearbyChatController === controller) return
        check(nearbyChatController == null) { "A Nearby controller is already attached." }
        nearbyChatController = controller
        controller.listener = this
    }

    fun detachNearbyController(controller: NearbyChatController) {
        if (nearbyChatController !== controller) return
        controller.listener = null
        nearbyChatController = null
    }
}
```

Update each existing controller call to use a local non-null controller or `?.`. In particular, change the Nearby listener bridge to:

```kotlin
override fun onMessageReceived(message: IncomingMessageEnvelope) {
    val source = nearbyChatController ?: return
    receiveIncomingMessage(message, source)
}
```

Change `startChat()` to return `Boolean`: return `true` when already active; return `false` after identity validation failure or if no controller is attached; otherwise set `nearbyActive`, advertise/discover, and return `true`. `stopChat()` calls `nearbyChatController?.stop()` and always clears state. `close()` calls `nearbyChatController?.close()`, cancels the scope, and closes the store.

Update the `ChatViewModelTest.makeViewModel` coordinator construction to pass:

```kotlin
initialNearbyChatController = controller
```

instead of the old `nearbyChatController` named argument.

- [ ] **Step 4: Implement the testable session**

Create `NearbyServiceSession.kt`:

```kotlin
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
```

- [ ] **Step 5: Implement the Android service**

Create `NearbyMessagingService.kt`:

```kotlin
package com.example.blap.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.blap.BlapApplication
import com.example.blap.chat.NearbyChatManager
import com.example.blap.notifications.MessageNotificationManager

class NearbyMessagingService : Service() {
    private lateinit var session: NearbyServiceSession
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        val app = application as BlapApplication
        session = NearbyServiceSession(app.chatCoordinator, NearbyChatManager(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        val app = application as BlapApplication
        try {
            ServiceCompat.startForeground(
                this,
                MessageNotificationManager.SERVICE_NOTIFICATION_ID,
                app.messageNotifications.serviceNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else 0,
            )
        } catch (exception: RuntimeException) {
            app.chatCoordinator.showError(
                exception.message ?: "Could not keep Nearby messaging active.",
            )
            stopSession()
            return START_NOT_STICKY
        }
        if (!session.start()) stopSession()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSession() {
        if (stopped) return
        stopped = true
        session.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "com.example.blap.START_NEARBY"
        private const val ACTION_STOP = "com.example.blap.STOP_NEARBY"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NearbyMessagingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NearbyMessagingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
```

- [ ] **Step 6: Move manager creation out of `BlapApplication`**

Construct `ChatCoordinator` without a Nearby controller:

```kotlin
chatCoordinator = ChatCoordinator(
    chatStore = SqliteChatStore(this),
    identityStore = LocalIdentityStore(this),
)
```

The application still owns the coordinator/store; only the service creates and closes `NearbyChatManager`.

- [ ] **Step 7: Add manifest requirements**

Add:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

Inside `<application>` add:

```xml
<service
    android:name=".service.NearbyMessagingService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice"
    android:stopWithTask="false" />
```

- [ ] **Step 8: Route the existing start/stop UI through the service**

In `MainActivity`, add:

```kotlin
private fun startNearbyService() {
    try {
        NearbyMessagingService.start(this)
    } catch (exception: RuntimeException) {
        viewModel.showError(exception.message ?: "Could not keep Nearby messaging active.")
    }
}
```

Replace direct `viewModel.startChat()` calls after Nearby permission success with `startNearbyService()`. Pass `onStopChat = { NearbyMessagingService.stop(this) }` to `NearbyChatApp`.

- [ ] **Step 9: Run service-session and regression tests**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.service.NearbyServiceSessionTest"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
```

Expected: all pass. Lint confirms foreground-service type and permissions are consistent.

- [ ] **Step 10: Commit the foreground service**

```powershell
git add app/src/main/java/com/example/blap/service app/src/main/java/com/example/blap/chat/ChatCoordinator.kt app/src/main/java/com/example/blap/BlapApplication.kt app/src/main/java/com/example/blap/MainActivity.kt app/src/main/AndroidManifest.xml app/src/test/java/com/example/blap/service app/src/test/java/com/example/blap/chat/ChatTestFakes.kt app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt
git commit -m "feat: keep nearby chat in foreground service"
```

---

### Task 5: Add notification permission, visibility, and tap routing

**Files:**
- Modify: `app/src/main/java/com/example/blap/MainActivity.kt`
- Modify: `app/src/main/java/com/example/blap/chat/ChatCoordinator.kt`
- Modify: `app/src/main/java/com/example/blap/chat/ChatViewModel.kt`
- Modify: `app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/example/blap/notifications/MessageNotificationManagerTest.kt`

**Interfaces:**
- Produces: `ChatCoordinator.openConversationFromNotification(String)`
- Produces: `ChatViewModel.openConversationFromNotification(String)`
- Consumes: `MessageNotificationManager.ACTION_OPEN_CONVERSATION` and `EXTRA_CONVERSATION_ID`.

- [ ] **Step 1: Write cold-state and unknown-route unit tests**

Add:

```kotlin
@Test
fun notificationRouteOpensConversationAfterReload() {
    val store = FakeChatStore().apply { savePeer("bob", "Bob") }
    val viewModel = makeViewModel(FakeNearbyChatController(), store)

    viewModel.openConversationFromNotification("bob")

    assertEquals(ChatScreen.CONVERSATION, viewModel.uiState.value.screen)
    assertEquals("bob", viewModel.uiState.value.selectedPeerId)
}

@Test
fun unknownNotificationRouteFallsBackToConversationList() {
    val viewModel = makeViewModel(FakeNearbyChatController())

    viewModel.openConversationFromNotification("missing")

    assertEquals(ChatScreen.CHATS, viewModel.uiState.value.screen)
    assertEquals(null, viewModel.uiState.value.selectedPeerId)
}
```

- [ ] **Step 2: Run the route tests and verify the API is missing**

Run:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.example.blap.chat.ChatViewModelTest.notificationRouteOpensConversationAfterReload" --tests "com.example.blap.chat.ChatViewModelTest.unknownNotificationRouteFallsBackToConversationList"
```

Expected: compilation fails because `openConversationFromNotification` does not exist.

- [ ] **Step 3: Implement repository-aware route opening**

In `ChatCoordinator`:

```kotlin
fun openConversationFromNotification(peerId: String) {
    workScope.launch {
        reloadConversationsNow()
        val exists = _uiState.value.conversations.any { it.peerId == peerId }
        if (exists) openConversation(peerId) else showConversationList()
    }
}
```

In `ChatViewModel`:

```kotlin
fun openConversationFromNotification(id: String) =
    coordinator.openConversationFromNotification(id)
```

- [ ] **Step 4: Request notification permission without blocking service startup**

Add a launcher:

```kotlin
private val notificationPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
) { granted ->
    startNearbyService()
    if (!granted) {
        viewModel.showNotice("Notifications are off. New messages will still appear in BLAP.")
    }
}
```

Add this method to `ChatCoordinator`:

```kotlin
fun showNotice(message: String) {
    _uiState.update { it.copy(notice = message, error = null) }
}
```

and delegate it from `ChatViewModel`:

```kotlin
fun showNotice(message: String) = coordinator.showNotice(message)
```

After Nearby permissions succeed, call:

```kotlin
private fun requestNotificationsAndStartNearby() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    ) {
        startNearbyService()
    } else {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
```

Use this method from both the already-granted path and `nearbyPermissionLauncher` callback. Notification denial still calls `startNearbyService()`.

- [ ] **Step 5: Report foreground visibility**

Add:

```kotlin
override fun onStart() {
    super.onStart()
    (application as BlapApplication).conversationVisibility.setAppForeground(true)
}

override fun onStop() {
    (application as BlapApplication).conversationVisibility.setAppForeground(false)
    super.onStop()
}
```

This combines with `ChatCoordinator.uiState.screen` and `selectedPeerId` in the notification policy.

- [ ] **Step 6: Handle notification intents on warm and cold starts**

At the end of `onCreate`, call `handleNotificationIntent(intent)`. Add:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleNotificationIntent(intent)
}

private fun handleNotificationIntent(intent: Intent?) {
    if (intent?.action != MessageNotificationManager.ACTION_OPEN_CONVERSATION) return
    val conversationId = intent.getStringExtra(
        MessageNotificationManager.EXTRA_CONVERSATION_ID,
    ) ?: return
    viewModel.openConversationFromNotification(conversationId)
    intent.action = null
    intent.removeExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID)
}
```

The notification manager already cancels a conversation notification when coordinator state changes to that open conversation.

- [ ] **Step 7: Verify PendingIntent routing in instrumentation**

Add to `MessageNotificationManagerTest`:

```kotlin
@Test
fun conversationIntentUsesDistinctDataAndExplicitAction() {
    val application = ApplicationProvider.getApplicationContext<BlapApplication>()
    val first = application.messageNotifications.conversationIntent("bob")
    val second = application.messageNotifications.conversationIntent("carol")

    assertNotEquals(first.data, second.data)
    assertEquals(MessageNotificationManager.ACTION_OPEN_CONVERSATION, first.action)
    assertEquals(
        "bob",
        first.getStringExtra(MessageNotificationManager.EXTRA_CONVERSATION_ID),
    )
}
```

Add the `BlapApplication`, `assertNotEquals`, and `ApplicationProvider` imports. Extract the intent construction currently inside `conversationPendingIntent` into:

```kotlin
internal fun conversationIntent(conversationId: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        action = ACTION_OPEN_CONVERSATION
        data = Uri.parse("blap://conversation/${Uri.encode(conversationId)}")
        putExtra(EXTRA_CONVERSATION_ID, conversationId)
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
```

Then have `conversationPendingIntent` pass `conversationIntent(conversationId)` to `PendingIntent.getActivity`.

- [ ] **Step 8: Run all automated checks**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat compileDebugAndroidTestKotlin
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Expected: all commands succeed.

- [ ] **Step 9: Commit permission and routing behavior**

```powershell
git add app/src/main/java/com/example/blap/MainActivity.kt app/src/main/java/com/example/blap/chat/ChatCoordinator.kt app/src/main/java/com/example/blap/chat/ChatViewModel.kt app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt app/src/androidTest/java/com/example/blap/notifications/MessageNotificationManagerTest.kt
git commit -m "feat: route incoming notifications to conversations"
```

---

### Task 6: Device verification and final regression

**Files:**
- Modify only files needed to correct failures found by this task.

**Interfaces:**
- Consumes all previous task outputs.
- Produces a verified foreground-service and notification feature.

- [ ] **Step 1: Run the complete local verification suite**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin
```

Expected: `BUILD SUCCESSFUL`; no test, lint, Kotlin, manifest, or resource errors.

- [ ] **Step 2: Install on two Android 13+ devices or emulators with Nearby-capable hardware**

Run for the selected device:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

- [ ] **Step 3: Verify notification grant behavior**

On device A, enable Nearby, grant Nearby permissions, grant notification permission, connect device B, then send from B while A shows the chat list.

Expected: A keeps a low-priority persistent service notification and shows an incoming notification with conversation/sender plus preview. Tapping it opens the correct conversation.

- [ ] **Step 4: Verify exact-conversation suppression**

Keep device A foregrounded in B's conversation and send another message from B.

Expected: the message appears in the open conversation with no incoming system notification.

- [ ] **Step 5: Verify another-conversation and background behavior**

Keep A in a different conversation, send from B, then background A and send again.

Expected: both messages notify; repeated B messages update B's existing notification rather than creating unrelated entries.

- [ ] **Step 6: Verify task removal and explicit stop**

Swipe A's task away while Nearby remains enabled, send from B, reopen A, then use the UI to turn Nearby off and send once more.

Expected: the first message arrives and notifies while the persistent service remains active; after explicit stop the service notification disappears and no further Nearby message is received.

- [ ] **Step 7: Verify notification denial**

Revoke A's notification permission, enable Nearby, and send from B.

Expected: the message is stored and visible after opening BLAP, outgoing acknowledgement/delivery behavior remains correct, and no incoming alert is posted.

- [ ] **Step 8: Confirm the final diff contains no Firebase delivery implementation**

Run:

```powershell
rg "FirebaseMessaging|FirebaseMessagingService|RemoteMessage" app/src
git status --short
git diff --check
```

Expected: the search has no matches; status contains only intended feature files; `git diff --check` has no output.

- [ ] **Step 9: Commit any device-only corrections**

If device verification required changes:

```powershell
git add app/src
git commit -m "fix: harden nearby notification lifecycle"
```

If no changes were required, do not create an empty commit.
