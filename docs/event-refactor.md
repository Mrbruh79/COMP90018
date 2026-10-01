# Stage 6: Event feature refactor

Stage 6 keeps the existing event screens, Firestore schema, SQLite schema and Nearby packet formats while splitting the former all-in-one `EventCoordinator` into focused components.

## Runtime flow

`ChatViewModel` continues to call the public methods on `EventCoordinator`. The coordinator owns the shared `EventUiState` and delegates work to four account-local components:

| Component | Responsibility |
| --- | --- |
| `EventLifecycleCoordinator` | Account/event observers, refresh, creation, editing, deletion, event selection, local cleanup, member refresh and announcements |
| `EventMembershipCoordinator` | Public joining, private invitations, invitation responses, participant lookup, leaving, removal and co-admin promotion |
| `EventOnSiteCoordinator` | GPS/QR admission, private-event admin approval, on-site messages and event-mesh packet handling |
| `EventDiscussionCoordinator` | Discussion navigation, paging, observers, comments, replies, likes and deletion |

`EventCoordinator` remains the stable facade used by `ChatViewModel`, so this stage does not require UI callback or navigation changes.

## State and lifetime

- `EventUiState` is defined separately from the coordinator facade and remains exposed as the same `StateFlow`.
- Each child coordinator receives read/update access to that single state owner; no second event state store is created.
- Firestore event and invitation listeners belong to `EventLifecycleCoordinator` and are restarted when the Firebase account changes.
- Discussion listeners belong to `EventDiscussionCoordinator` and are closed when leaving an event, changing account, deleting an event or closing the parent coordinator.
- The shared `EventMeshGateway` is not closed by a child coordinator because it is owned by the parent `ChatViewModel` transport session.

## Preserved behavior and security rules

- Public events may be open to guests or protected so only signed-in accounts can join.
- Private events remain invitation-only and use an event-specific mesh secret.
- GPS, signed static QR and nearby admin approval keep their existing admission rules.
- Incoming access grants, announcements and event mutations are signature-checked before local state changes.
- Only the primary admin can delete an event; leaving as the primary admin still deletes it for everyone.
- Admin deletion purges local event data and propagates a signed deletion mutation to connected peers.
- Discussion author deletion and admin branch deletion retain their existing Firestore operations.
- A non-authoritative cached Firestore snapshot never purges an event merely because it is missing. Explicit tombstones still remove events; a complete server snapshot may remove events absent from the authoritative result.
- On-site chat remains local/offline and stores the latest received history on each device. Announcements continue to use local mesh delivery plus cloud synchronization.

## Test boundaries

Pure policies now cover:

- discussion nesting, text limits, write access and deletion permissions;
- event-mesh peer isolation, request/grant lifetime, sender membership and signed mutations;
- public/protected/private membership rules, invitations, rejoining and admin restrictions;
- lifecycle validation, model construction, immutable security fields, revision ordering and cached-versus-authoritative reconciliation.

Static verification for the completed stage is:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

The completed Stage 6 checkout assembled successfully, passed 194 unit tests with no failures or skips, and passed lint with no errors. The remaining 14 warnings and one hint pre-date this finalization change.

This verifies compilation, JVM behavior and lint only. The final project regression must still test two physical phones for direct Nearby discovery, relay, event isolation, GPS/QR entry, private approval, announcements, on-site chat and voice notes.
