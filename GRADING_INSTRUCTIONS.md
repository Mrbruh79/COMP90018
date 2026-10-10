# CommonGround: build and run instructions

1. Install Android Studio with support for the project's Android Gradle Plugin, Android SDK Platform 37, and the SDK tools requested during Gradle sync. The app supports Android 7.1 (API 25) and later. Use JDK 21 for Gradle. The repository's daemon toolchain configuration selects Java 21 and includes download URLs if it is not installed; allow those downloads. CI starts with Java 25, but the project still selects Java 21 for its Gradle daemon.

2. Clone the repository, then open its root folder in Android Studio. Repository access must be available to the grader.

   ```text
   git clone https://github.com/Mrbruh79/COMP90018.git
   cd COMP90018
   ```

3. Wait for Gradle sync and dependency downloads to finish. Use the included Gradle wrapper. Android Studio creates `local.properties` with the SDK path for this laptop; do not copy another computer's SDK path.

4. Keep `signing/grading-debug.keystore` and `app/google-services.json` in place. Debug builds automatically use the included grading key. Its fingerprints are registered with the team Firebase project, so the grader does not need to register their laptop's default debug certificate or obtain Firebase console access. The grading key is not used for release builds.

5. Use the existing team Firebase project, `comp90018-4a590`. Authentication, Firestore rules and indexes are maintained on that backend. Cloning does not deploy backend settings, and the grader does not need to redeploy them. Internet access is required for account registration, sign-in and cloud operations.

6. Choose a physical Android device or an emulator with Google Play services. Google sign-in also needs an available Google account. For Nearby communication, use two compatible physical Android phones with Bluetooth and Wi-Fi enabled. An emulator can be used to inspect screens and local workflows, but it does not establish physical Nearby delivery.

7. Build and check the project. On Windows, run these commands from the repository root in PowerShell or Android Studio Terminal:

   ```powershell
   .\gradlew.bat :app:assembleDebug
   .\gradlew.bat :app:testDebugUnitTest
   .\gradlew.bat :app:lintDebug
   ```

   On macOS or Linux:

   ```sh
   chmod +x gradlew
   ./gradlew :app:assembleDebug
   ./gradlew :app:testDebugUnitTest
   ./gradlew :app:lintDebug
   ```

8. Select the `app` run configuration in Android Studio, choose the device and press Run. Alternatively, install `app/build/outputs/apk/debug/app-debug.apk` after the build completes. To inspect signing, run `:app:signingReport` with the same wrapper. The debug certificate should match the fingerprints in `signing/README.md`.

9. On first launch, complete onboarding. Create a test email/password account or use Google sign-in. Complete the username and display-name prompt if shown; the username must be unique. If the test involves finding an account by email, verify that email first. Grant Nearby, location, contacts, microphone or notification access when the feature being tested requests it. QR scanning uses Google Code Scanner and does not request a separate app camera permission.

10. For messaging, use separate test identities on the two phones, turn on Nearby, approve the connection and exchange a message. For events, create a test event or use the team's supplied event details. Join the event, accepting an invitation first if it is private. GPS check-in requires a location inside the event's configured area; otherwise use a valid event QR provided by an event administrator. Test announcements with an administrator account and discussions with a participant account. Enable notifications before checking notification behaviour. Keep internet access available for cloud operations.

Google sign-in's signing configuration is supplied with the project. The shared key removes the need to register a new certificate for each grading laptop. Live Google sign-in, GPS and two-phone delivery still depend on the device, account, permissions and backend being available; automated build and unit-test results do not establish those device workflows.
