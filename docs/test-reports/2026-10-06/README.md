# Android test reports

Tested source: `ba57fb4` on `main`.

| Report | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| [Instrumented tests](instrumented/index.html) | 12 | 0 | 0 | 0 |
| [Unit tests](unit/index.html) | 381 | 0 | 0 | 0 |

The instrumented suite ran on the Medium_Phone emulator with Android 17 on 6 October 2026. It covers application context, encrypted preferences, SQLite encryption and migration, chat deletion, account identity separation, and conversation merging. Physical multi-phone delivery requires separate testing.

The unit-test report was generated earlier on the same day. Gradle reused its passing results during the combined report run because the test inputs were unchanged.

The complete HTML reports include their stylesheets, scripts, and individual test pages. Download the repository and open either `index.html` in a browser. GitHub displays HTML files as source.

Commands used from the project root in PowerShell:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug --no-daemon
```

The connected phone rejected installation because its existing app had a different signing key. Instrumented testing then completed successfully with:

```powershell
$env:ANDROID_USER_HOME = 'C:\Users\arnav\.android'
$env:ANDROID_SERIAL = 'emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

The emulator was closed after testing. Lint reports are excluded from this archive.
