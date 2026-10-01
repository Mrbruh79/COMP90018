# Stage 5: Nearby and mesh refactor

It preserves the existing mesh packet format and routing rules while separating Google Nearby Connections, hop/dedup logic, event isolation, notifications and voice recording.

## Boundaries

- `NearbyTransport` remains the chat-facing session API used by `ChatViewModel`.
- `NearbyChatGateway` is that chat-facing contract. `EventMeshGateway` remains the event-facing contract. `NearbyChatController` still combines both because production uses one Nearby session.
- `GoogleNearbyTransport` is the only class that calls Google Play services Nearby Connections. It sends and receives raw bytes.
- `MeshRouter` owns hop limits, duplicate suppression, group caches and the decision to deliver or forward a decoded packet.
- `EventMeshSession` owns the active event identity, hashed service IDs, event endpoint membership and presence packets.
- `NearbyChatManager` composes those three pieces and remains the production `EventMeshGateway`.
- `ChatNotifier` / `ChatNotificationPolicy` remain the notification boundary. Alerts are in-process only.
- `VoiceNoteService` / `VoiceNoteCache` isolate microphone capture and playback cache files from Compose and `ChatViewModel`.

## Behavior intentionally preserved

- Nearby protocol magic `0x424C4150` and version 8, including all existing packet types.
- Maximum hop count of 16. Packets with hop counts outside `0..16` are dropped.
- Seen-ID sets prevent relay loops. Open-mesh group messages are still delivered locally on a duplicate, but are not forwarded again.
- Event traffic uses a hashed per-event service ID. Private events also mix in the mesh secret. Endpoint names expose only the stable peer ID.
- Chat packets on event endpoints are dropped, and event packets on the open chat mesh are dropped.
- Event chat, announcements and mutations require local access. Access requests can still be relayed without access.
- Foreground alerts use `ChatNotificationPolicy` while the app process is running. Messages received after the process is closed appear the next time the app opens, without a background worker.
- Voice notes keep the previous duration and encoded-length limits.

## Verification

Automated tests cover protocol version, hop decrement, duplicate suppression, event isolation, event service IDs and notification visibility. Two-phone verification should still cover advertising, direct chat, private-group relay, event mesh join and voice notes.

The debug APK, Android unit tests and lint should be run before merging:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```
