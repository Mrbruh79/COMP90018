# Dependency foundation

This document records the initial foundation stage. The current ViewModel and session wiring is described in [Stage 7](chat-refactor.md), which supersedes the single-ViewModel and shared-listener descriptions below.

This stage adds contracts and a composition root. It does not replace the chat, GPS, event or mesh algorithms. Feature development and screen decomposition remain separate work.

## Ownership and review

| Owner | Boundary to review |
| --- | --- |
| Pranjal | `LocationProvider`, `VenueRepository` and `EventServices`, including the existing event repository, storage and access-key contracts |
| William | `NearbyTransport`, `EventMeshGateway`, notification delivery and notification preferences |
| Arnav | `AppContainer`, account scoping, ViewModel factories and integration |
| Octavio/Astellion | Screen wiring against these contracts during the later UI decomposition |

The initial contract shapes follow the current call sites. They are ready for review, not a claim that Pranjal or William has approved new requirements. Karthik remains credited for the original GPS implementation. Pranjal owns the upcoming location refactor.

## Composition and lifetime

`CommonGroundApplication` owns a lazy `AppContainer`. `DefaultAppContainer` is the production composition root and retains only application context.

The container exposes shared location, venue and private-profile services. Identity and notification preferences are created for an explicit account ID, using the existing `LocalDataScope` paths. Neither signed-in account resources nor transports are cached globally.

`ChatViewModelFactory` captures a dependency supplier. It validates the requested ViewModel type before constructing anything. Android's ViewModel owner retains the ViewModel across Activity recreation; each new ViewModel gets fresh SQLite stores, a cloud chat controller and a Nearby manager. Signing out still clears the ViewModel owner and recreates the Activity, as before.

`ChatDependencies` contains interface-typed dependencies and an injectable IO dispatcher. Its `EventServices` bundle includes local event storage, the remote repository, admin keys and the mesh gateway. There is currently one ViewModel, so there is one dedicated factory. New screen ViewModels should get their own factories when they are introduced.

The ViewModel closes the Nearby session and local chat store, stops the cloud controller, cancels its work scope and closes the event coordinator. The coordinator closes event observers and its local event store. An event gateway must not close the shared transport independently.

## Repository contracts

- `ContactRepository`: saved contacts and identities learned from peers.
- `GroupRepository`: local private groups, membership and cloud-sync state.
- `ChatRepository`: local conversations, messages and delivery/sync state.
- `ChatStore`: compatibility contract combining those three boundaries. `SqliteChatStore` still implements it, with no schema changes.
- `IdentityStore` and `PrivateProfileStore`: existing local identity and owner-only cloud profile contracts.
- `EventStore`, `EventRemoteRepository` and `EventAdminKeyStore`: existing event persistence, remote operations and access-key contracts, grouped in `EventServices`.
- `NotificationSettingsRepository`: account-scoped preferences shared by the settings screen and notifier.
- `VenueRepository`: current nearby venue lookup, including location acquisition and cloud radius matching.

Local SQLite methods remain blocking and belong on the injected IO dispatcher. Cloud operations retain their existing suspend/callback APIs. Event observer handles belong to the coordinator and must be closed when no longer needed.

## Location requirements for Pranjal's review

`LocationProvider.getFreshLocation()` returns a platform-neutral `LocationFix`: latitude and longitude in degrees, horizontal accuracy in metres and capture time in Unix milliseconds. The caller requests permission first. No fix returns null; permission and provider failures propagate for the existing UI error handling.

Stage 4 replaced the temporary adapters with `FusedLocationProvider`, `FirebaseVenueRepository` and `NominatimPlaceSearchRepository`. Venue lookup and event creation now use the shared provider through the composition root. Distance and accuracy validation are platform-neutral and covered by unit tests. Event entry still consumes coordinates and accuracy exactly as before; the capture time is available but is not a new entry rule.

## Transport requirements for William's review

`NearbyTransport` covers advertising, discovery, connection, messaging, acknowledgements, private-group synchronization and shutdown. `stop()` preserves the existing stop behavior; `close()` releases the owner’s session resources.

`EventMeshGateway` covers event selection, access requests/grants, mutations, announcements and bounded history synchronization. It does not perform GPS checks or replace event membership/privacy rules. The existing optional no-op event methods remain for compatibility with chat-only test implementations.

`NearbyChatController` combines both contracts, and `NearbyChatManager` still implements it. Production wiring supplies the same manager as transport and event gateway. Incoming event callbacks still travel through the transport's single listener, then through `ChatViewModel` to `EventCoordinator`. Independent listener subscriptions are later work; creating two managers now would split discovery, connections and mesh state.

## Deliberately unchanged

Authentication and public account-profile operations still use the existing managers. UI permission requests, QR scanning and device-contact import remain in the Activity. No Firebase configuration, queries, rules, indexes or deployed services change in this stage. The existing notification policy, GPS radius matching, mesh packet formats and message synchronization remain active.

## Verification

Run the debug build, Android unit tests and lint:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Factory tests cover rejection before resource creation, account/identity wiring, fresh resources per ViewModel and cleanup when the owner is cleared. The existing messaging, contact lookup, event access, storage and notification tests remain the regression checks. Two-phone testing is deferred by request.

Checked on this foundation change:

- Debug APK assembled successfully.
- 131 Android unit tests passed, including four new factory tests.
- Lint passed with no errors, 13 existing warnings and one hint.
- Debug APK installed and launched on the existing Android emulator. The Activity remained running with no recent fatal runtime errors.
- `git diff --check` passed.

Firestore implementations and rules were not modified, so the rules suite was not rerun for this stage. These checks do not establish two-device delivery, live cloud access or physical GPS behavior.
