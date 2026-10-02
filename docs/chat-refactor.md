# Stage 7: Chat, contacts, groups, profiles and auth

Stage 9 later centralized Activity wiring, retained launcher requests and account recreation. See [Final application integration](application-integration.md).

The remaining event forwarding was removed from `ChatViewModel` before splitting the other features. Pranjal's event coordinators and location refactor remain in use. `EventViewModel` now owns event actions, state and incoming mesh callbacks.

## Feature ownership

| ViewModel | Responsibility |
| --- | --- |
| `ChatViewModel` | Nearby discovery/connections, message composition, chat actions, direct/open-mesh message reception and delivery acknowledgements |
| `ContactsViewModel` | Saved-contact editing/import, QR cards, contact profiles, online matching and account selection |
| `GroupsViewModel` | Group creation/settings navigation, name/member drafts and group-management commands |
| `ProfileViewModel` | Profile/card editing, initial identity setup and phone-discovery preferences |
| `AuthViewModel` | Email/Google operations, account setup, verification refresh, public/private profile restoration and sign-out |
| `EventViewModel` | Existing event coordinators, event navigation, account changes and event-mesh callbacks |

The Activity now wires callbacks to their owning ViewModels. It still handles Android permissions, the Google credential chooser, QR scanning, device-contact access and Activity recreation.

## Separate services

- `LocalChatPersistence` reloads local conversations/messages/contacts, applies delivery status, migrates synthetic phone conversation IDs and prepares notification delivery. `ChatStore` still uses the existing SQLite implementation and repository contracts.
- `CloudChatSynchronizer` owns cloud subscriptions, incoming online messages, pending-message/group upload and upload deduplication. It no longer calls through `ChatViewModel`.
- `ContactAccountLookup` resolves accounts by stored UID, username, paired peer identity, email and phone. Multiple matches retain the username-selection flow, and phone matches retain the unverified-number warning.
- `ProfileBackup` owns timestamp reconciliation, serialized private-profile writes and rejection of stale writes. `AccountPublisher` separately updates public cards and discovery data.
- `GroupManagement` owns local/mesh/cloud group reception, owner checks, membership changes, deletion and mesh history synchronization.
- `ProfileIdentity` owns display-name/phone validation and local identity persistence. `AuthRepository` and `AccountProfileRepository` adapt the existing Firebase managers without changing provider-linking or username-reservation behavior.

## Scope and state

`MessagingSessionOwner` retains one account-scoped `MessagingSession` in the Activity's ViewModelStore. `MessagingSessionFactory` constructs its resources; `MessagingViewModelFactory` creates the feature ViewModels against that same session. Creating a contacts/profile/group ViewModel does not create another database, cloud listener or mesh connection.

The existing `ChatUiState` rendering snapshot is retained to keep the decomposed Compose screens compatible. Each feature now owns its commands and draft changes, but the UI state fields have not been split into separate state models. Shared updates still use atomic `MutableStateFlow.update` operations. Auth and events have separate state flows.

`MessagingNavigation` owns cross-screen Back behavior and return destinations. Returning from a profile or contacts screen does not start or stop Nearby discovery.

Feature ViewModels do not independently close shared resources. Clearing the session owner cancels messaging work, stops cloud subscriptions, disposes event observers/work, closes Nearby and closes the chat store once. `EventViewModel` disposal is idempotent and closes its event store. Clearing the owner before or after feature ViewModels is safe. The standalone `ChatViewModelFactory` remains available for isolated chat use and owns the session it creates.

Sign-out immediately closes the old session and clears visible chats/contacts. The account-change request is retained in auth state until the Activity clears its owner and recreates, including across Activity configuration changes. Signup uses the new account's scoped identity store rather than writing into the signed-out store.

## Independent mesh callbacks

`NearbyTransport.listener` carries chat/discovery callbacks. `EventMeshGateway.eventListener` carries event callbacks. `NearbyChatManager` supplies both on the same transport session, with the existing mesh router and event isolation implementation. `ChatViewModel` has no event imports, coordinator, event state or event forwarding methods.

## Preserved behavior

No SQLite migration, Firebase schema, rule, index, project configuration or provider setting changed. Public-card fields and opt-in lookup fields remain separate. QR compatibility, phone normalization, username matching, online conversation IDs, membership checks, replies, polls, edit/delete actions, notifications and voice packet limits retain their existing implementations.

The old messaging regression scenarios now exercise the separate models through a test-only helper. There is no equivalent forwarding facade in production. New tests cover shared lifetime ownership, immediate sign-out privacy, retained restart requests, signup account scoping and independent event callback routing.

## Checks

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
firebase emulators:exec --only firestore --project demo-blap "npm --prefix rules-tests test"
```

Verification completed on 1 October 2026:

- Debug APK build passed.
- All 198 Android unit tests passed, with no failures or errors.
- Lint passed with 0 errors, 13 warnings and 1 hint.
- All 15 Firestore rules tests passed against the local `demo-blap` emulator. Nothing was deployed to Firebase.
- The APK installed and launched on the Android `Medium_Phone` emulator. Messages, Contacts, Profile Card, Settings, Events and group-creation screens opened without an Android runtime crash. Android Back returned from group creation to Contacts.
- `git diff --check` passed.

Physical two-phone regression remains deferred. These checks do not establish live two-device/cloud delivery, Google/email sign-in on a real account or real GPS behavior.
