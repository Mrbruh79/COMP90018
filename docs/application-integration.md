# Stage 9: Final application integration

The starting point was `2b259c4` on `main`, pulled on 2 October 2026. The Stage 8 action bundles and screen wiring remain in use.

## Activity and composition

`MainActivity` now hosts Compose, configures system bars and the splash screen, registers Android operations, collects launcher/account-change requests, and handles Activity recreation. It no longer reads or writes notification/onboarding preferences, queries venues, reads contacts, selects event-entry paths or decides Back destinations.

`ApplicationViewModels.obtain` creates one account-scoped session owner and gets the feature ViewModels from the Activity's ViewModelStore. Recreating the Activity reuses that graph. The Activity no longer constructs individual feature factories.

`AppContainer` supplies session resources, feature factories and application service contracts. Application context is used for device contacts and preferences. No Activity is retained by the container or a ViewModel. `ApplicationRoute` collects state and connects the existing action bundles to the appropriate ViewModels.

## Application workflows

`ApplicationViewModel` owns onboarding completion, notification preferences, venue lookup, device-contact import, permission outcomes, event-entry sequencing and top-level Back decisions. It calls the existing chat, contact, auth and event ViewModels through injected commands.

`AndroidPlatformBridge` owns Activity Result launchers, permission checks, the Google credential chooser, Google code scanner and the app-settings Intent. It reports results with request IDs. Raw QR payloads still go to the existing contact/event parsers; the bridge does not decode contact identities or check event access.

Only one platform workflow is pending at a time. Nearby permission is checked before event GPS, event QR or admin-access entry. GPS entry then checks location permission before requesting a fresh fix. Denied permission clears the pending workflow rather than silently changing the entry method.

Pending operations and launch markers survive Activity configuration changes. Permission request IDs are saved in the Activity instance bundle, so restored launcher callbacks reach the retained request. A cancelled Google coroutine can retry its launcher after recreation. Scanner task callbacks hold the retained model, not the old Activity. Scanner cancellation uses Google's documented [CODE_SCANNER_CANCELLED status](https://developers.google.com/android/reference/com/google/mlkit/common/MlKitException#CODE_SCANNER_CANCELLED).

Process death does not replay an event check-in or an account credential operation. A returned result without a matching retained request is discarded, and the user can start the action again.

## Account switching

A successful sign-in/signup closes the old session before requesting Activity recreation. Sign-out immediately clears visible chat/contact state. Application work is cancelled and the route shows a loading indicator while the account graph is being replaced.

Late profile-load/sign-in callbacks cannot replace the sign-out restart request or restore the old profile. Auth coroutine cancellation is propagated rather than treated as a failed profile lookup. Resume checks detect a changed Firebase UID and request a new graph instead of syncing a new account into the old local scope.

The new graph reads the new account's notification preferences. Firebase project settings, provider configuration, schemas, rules and storage formats are unchanged. No Firebase deployment was performed.

## Navigation

All system Back decisions go through `ApplicationViewModel.handleBack`. Existing chat/contact/profile/group return paths remain in `MessagingNavigation`. Nested event pages delegate to `EventViewModel`; Back from the Events list returns to Messages.

Back cancels pending application work. A permission result for a cancelled action is ignored. Event permission/QR/location results are also rejected if the selected event changed while the request was outstanding. Root Messages and Welcome retain the existing platform Back behavior.

## Verification

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon
```

Tests cover retained graph creation, account-scoped preferences, late auth/profile callbacks, event-entry permission order, denied permissions, stale request IDs, QR cancellation, microphone/notification settings, contact import, delayed GPS and auth results, account closure and event Back navigation.

Completed on 2 October 2026:

- Debug APK build passed.
- All 226 Android unit tests passed, with no failures or errors.
- Lint passed with 0 errors, 13 warnings and 1 hint.
- The APK installed and launched on the existing Medium_Phone emulator without an Android runtime crash.
- The Contacts permission dialog survived rotation; denying permission returned to Contacts with the expected message.
- The Google code scanner opened. Its close button cancelled back to Contacts without an error.
- Android Back inside Google's scanner returned `MlKitException` code 13 (internal error) on the emulator. The app returned to Contacts and reported the failure without crashing. This is not treated as a documented cancellation status; check it on a physical device. Temporary status logging was removed.
- Android Back from the Events list returned to Messages.
- `git diff --check` passed. The local Android Studio JDK setting was not changed.

Firestore rules/configuration were unchanged, so the rules suite was not rerun during this stage.

Physical two-phone delivery, real GPS check-in, actual QR decoding through a camera, and live email/Google account switching still require device regression testing. Unit tests and launcher checks do not establish those outcomes.
