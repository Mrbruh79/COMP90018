# Incoming notification final fixes report

Date: 2026-09-24
Branch: `feature/notifications`
Starting HEAD: `8f87c5cb844f205c404f52abddabcb9979cee770`

## Scope and design compliance

The implementation was checked against `docs/superpowers/specs/2026-09-24-incoming-message-notifications-design.md` before editing. The reviewed application-scoped coordinator, transport-neutral message boundary, foreground-service ownership, notification routing, and persistence/deduplication architecture were preserved. No Firebase code or dependency was added, and no unrelated refactor was performed.

## Findings, root causes, and RED/GREEN evidence

### 1. Visibility-aware notification cancellation

Root cause: `MessageNotificationManager` observed only `ChatCoordinator.uiState`. A background transition did not alter the selected conversation, so the collector could cancel a valid background alert when UI state changed, while a foreground return with unchanged UI state emitted nothing and left stale notification history.

Changes:

- Replaced the volatile foreground boolean in `ConversationVisibilityTracker` with an observable `StateFlow<Boolean>`.
- Added a shared cancellation policy that returns a conversation only when the app is foregrounded and the selected screen is that conversation.
- Combined coordinator UI state with foreground state and applied `distinctUntilChanged()` to the nullable visible-conversation result.
- Kept `null` transitions in the stream so background -> foreground for the same conversation emits the conversation again and cancels stale notification/history.

Tests:

- `backgroundConversationDoesNotRequestNotificationCancellation`
- `foregroundReturnRequestsCancellationForAlreadySelectedConversation`

RED:

```text
> Task :app:compileDebugUnitTestKotlin FAILED
IncomingNotificationPolicyTest.kt:51:13 Unresolved reference 'notificationCancellationConversationIds'.
IncomingNotificationPolicyTest.kt:74:13 Unresolved reference 'notificationCancellationConversationIds'.
BUILD FAILED
```

The initial test-first pass also demonstrated that the old tracker exposed no observable state (`appForeground` was private and not a `StateFlow`).

GREEN:

```text
.\gradlew.bat testDebugUnitTest \
  --tests "com.example.blap.notifications.IncomingNotificationPolicyTest.backgroundConversationDoesNotRequestNotificationCancellation" \
  --tests "com.example.blap.notifications.IncomingNotificationPolicyTest.foregroundReturnRequestsCancellationForAlreadySelectedConversation"

BUILD SUCCESSFUL in 3s
25 actionable tasks: 6 executed, 19 up-to-date
```

### 2. Acknowledgement exceptions after persistence

Root cause: `source.acknowledgeMessage(...)` ran after `saveMessage(...)` but before conversation/message refresh and accepted-event emission. A synchronous transport exception terminated the coroutine after persistence. A retry then deduplicated the stored message, so the refresh/event could be lost permanently.

Changes:

- Isolated synchronous acknowledgement failure from the post-insert refresh/emission path.
- Kept acknowledgement after persistence.
- Kept acknowledgement before the duplicate early-return, so every valid duplicate still attempts acknowledgement.
- Kept refresh and accepted-event emission behind `inserted`, preserving exactly-once behavior.

Test:

- `acknowledgementFailureStillRefreshesAndEmitsNewMessageExactlyOnce`

RED:

```text
ChatViewModelTest > acknowledgementFailureStillRefreshesAndEmitsNewMessageExactlyOnce FAILED
java.lang.AssertionError at ChatViewModelTest.kt:242
1 test completed, 1 failed
BUILD FAILED
```

At the failed assertion, the message had been stored and acknowledgement attempted, but the accepted-event list was empty because the exception prevented refresh/emission.

GREEN:

```text
.\gradlew.bat testDebugUnitTest \
  --tests "com.example.blap.chat.ChatViewModelTest.acknowledgementFailureStillRefreshesAndEmitsNewMessageExactlyOnce"

BUILD SUCCESSFUL in 3s
25 actionable tasks: 2 executed, 23 up-to-date
```

The test submits the same valid envelope twice through a throwing transport and verifies two acknowledgement attempts, one stored message, one accepted event, and refreshed conversation preview state.

### 3. Foreground-service session startup exception boundary

Root cause: `session.start()` was outside the service's exception boundary. Synchronous attachment, advertising, discovery, permission, or runtime failures could escape `onStartCommand`, bypass safe teardown, and leave service ownership inconsistent.

Changes:

- Added `NearbyServiceBoundary.startSafely`.
- Caught synchronous runtime failures, reported a recoverable coordinator error, and invoked idempotent safe teardown.
- Also invoked teardown when session startup returns `false`.
- Protected error reporting and teardown from escaping the Android callback.

Test:

- `serviceBoundaryReportsSynchronousStartupFailureAndTearsDown`

RED:

```text
> Task :app:compileDebugUnitTestKotlin FAILED
NearbyServiceSessionTest.kt:135:32 Unresolved reference 'startSafely'.
BUILD FAILED
```

GREEN:

```text
.\gradlew.bat testDebugUnitTest \
  --tests "com.example.blap.service.NearbyServiceSessionTest.serviceBoundaryReportsSynchronousStartupFailureAndTearsDown"

BUILD SUCCESSFUL
```

The test injects a synchronous `SecurityException` and verifies no escape, a recoverable error message, and ordered session/foreground/service teardown.

### 4. Connected-device foreground-service type on API 29

Root cause: the type was supplied only from API 30 (`R`), although typed foreground services and the three-argument start call are supported from API 29 (`Q`).

Changes:

- Centralized foreground-service type selection.
- Return `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` from `Build.VERSION_CODES.Q`, otherwise `0`.
- Suppressed the guarded inlined-API lint warning locally.

Test:

- `connectedDeviceForegroundTypeIsUsedFromApi29`

RED:

```text
> Task :app:compileDebugUnitTestKotlin FAILED
NearbyServiceSessionTest.kt:149:13 Unresolved reference 'foregroundServiceType'.
NearbyServiceSessionTest.kt:151:25 Unresolved reference 'foregroundServiceType'.
BUILD FAILED
```

GREEN: included in the focused service test run and the complete unit suite below.

### 5. Missing/blank open-conversation notification route

Root cause: a recognized `ACTION_OPEN_CONVERSATION` with a missing extra returned before clearing the action. A blank extra was passed through. Either could remain on the activity intent and be ignored/reprocessed rather than falling back to the conversation list.

Changes:

- Treat missing and blank conversation IDs as invalid routes.
- Consume the action and remove the extra before dispatching.
- Fall back to `showConversationList()` for invalid routes.

Test:

- `blankConversationIntentIsConsumedAndFallsBackToConversationList` covers both missing and whitespace-only extras.

RED:

```text
> Task :app:compileDebugAndroidTestKotlin FAILED
MessageNotificationManagerTest.kt:29:13 No parameter with name 'showConversationList' found.
MessageNotificationManagerTest.kt:54:17 No parameter with name 'showConversationList' found.
BUILD FAILED
```

GREEN:

```text
.\gradlew.bat compileDebugAndroidTestKotlin

BUILD SUCCESSFUL in 1s
25 actionable tasks: 6 executed, 19 up-to-date
```

### 6. Nearby UI copy

The stale active-state text, “Keep BLAP open on both phones to connect,” now says, “Nearby messaging stays active while BLAP runs in the background.” This reflects foreground-service behavior without changing UI architecture.

## Files changed

- `.superpowers/sdd/final-fixes-report.md`
- `app/src/androidTest/java/com/example/blap/notifications/MessageNotificationManagerTest.kt`
- `app/src/main/java/com/example/blap/MainActivity.kt`
- `app/src/main/java/com/example/blap/chat/ChatCoordinator.kt`
- `app/src/main/java/com/example/blap/notifications/ConversationVisibilityTracker.kt`
- `app/src/main/java/com/example/blap/notifications/IncomingNotificationPolicy.kt`
- `app/src/main/java/com/example/blap/notifications/MessageNotificationManager.kt`
- `app/src/main/java/com/example/blap/service/NearbyMessagingService.kt`
- `app/src/main/java/com/example/blap/ui/NearbyChatApp.kt`
- `app/src/test/java/com/example/blap/chat/ChatViewModelTest.kt`
- `app/src/test/java/com/example/blap/notifications/IncomingNotificationPolicyTest.kt`
- `app/src/test/java/com/example/blap/service/NearbyServiceSessionTest.kt`

## Full verification

### Focused regression tests

```text
.\gradlew.bat testDebugUnitTest \
  --tests "com.example.blap.notifications.IncomingNotificationPolicyTest" \
  --tests "com.example.blap.chat.ChatViewModelTest.acknowledgementFailureStillRefreshesAndEmitsNewMessageExactlyOnce" \
  --tests "com.example.blap.service.NearbyServiceSessionTest.serviceBoundaryReportsSynchronousStartupFailureAndTearsDown" \
  --tests "com.example.blap.service.NearbyServiceSessionTest.connectedDeviceForegroundTypeIsUsedFromApi29"

> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2s
25 actionable tasks: 1 executed, 24 up-to-date
```

The stronger flow-level visibility tests were subsequently rerun independently after they replaced the first policy-only test form:

```text
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 3s
25 actionable tasks: 6 executed, 19 up-to-date
```

### Required complete verification run

Command:

```text
.\gradlew.bat testDebugUnitTest compileDebugAndroidTestKotlin lintDebug assembleDebug
```

Output:

```text
> Task :app:compileDebugKotlin
> Task :app:compileDebugAndroidTestKotlin
> Task :app:assembleDebug
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
> Task :app:lintAnalyzeDebugAndroidTest
> Task :app:lintAnalyzeDebugUnitTest
> Task :app:lintAnalyzeDebug
> Task :app:lintReportDebug
Wrote HTML report to file:///C:/Users/XWill/Documents/GitHub/COMP90018/app/build/reports/lint-results-debug.html
Wrote SARIF report to file:///C:/Users/XWill/Documents/GitHub/COMP90018/app/build/reports/lint-results-debug.sarif
> Task :app:lintDebug

BUILD SUCCESSFUL in 18s
59 actionable tasks: 17 executed, 42 up-to-date
Configuration cache entry reused.
```

Unit report:

```text
61 tests
0 failures
0 skipped
100% successful
2.342s
```

Lint report:

```text
0 errors, 9 warnings
```

The warnings are pre-existing dependency-update suggestions and the existing notification `Uri.parse` KTX suggestion. The guarded API-29 foreground-service constant introduced no remaining lint warning.

Diff check:

```text
git diff --check

Exit code: 0
Output: <empty>
```

## Self-review

- Cancellation now requires all three design predicates: app foreground, `CONVERSATION` screen, and matching selected conversation.
- A background transition emits `null`; returning foreground emits the same selected ID again after that `null`, so `distinctUntilChanged` does not suppress cancellation.
- Incoming persistence still precedes acknowledgement.
- Duplicate valid messages still acknowledge but cannot refresh or emit a second accepted event.
- Session-start exceptions cannot escape `onStartCommand`; teardown remains idempotent.
- Notification actions are consumed before route callbacks, including invalid actions with missing/blank IDs.
- No Firebase implementation or dependency was introduced.

## Concerns

- Instrumentation tests were compiled as requested but not executed because no emulator/device run was requested or available in this verification wave.
- Manual Android lifecycle checks (real background/foreground transitions, task swipe, and notification tray behavior) remain appropriate release smoke tests.
- Lint is successful with 9 non-blocking, pre-existing warnings noted above.
