# Shared debug certificate

Debug builds use `shared-debug.keystore` from this directory. The key is included in the repository so local and CI builds use the same certificate. Its path in `app/build.gradle.kts` is relative to the repository root.

The alias is `AndroidDebugKey`. Both development passwords are `android`. This key is for debug builds only. Release builds do not use it, and production signing keys must remain private.

The certificate was created on 10 October 2026. Both fingerprints were registered with Firebase project `comp90018-4a590`, Android app `1:354368020263:android:8ebdaed02e6515442f5302`. The checked-in `app/google-services.json` includes the corresponding Android OAuth client and the existing web OAuth client.

| Fingerprint | Value |
| --- | --- |
| SHA-1 | `16:FD:46:75:A8:58:88:6D:61:D0:61:E5:6B:6A:94:05:0A:71:8D:CD` |
| SHA-256 | `40:2E:75:91:1E:76:49:DB:DC:89:47:AA:39:CF:DD:36:F1:57:F5:96:B1:5E:59:CA:6B:5A:05:3D:4E:51:56:C9` |

Run `./gradlew :app:signingReport` or `.\gradlew.bat :app:signingReport` to inspect the selected certificate. The debug variant should point to this directory and report the fingerprints above. Do not regenerate the key on another laptop. Replacing it requires registering the replacement fingerprints and refreshing the Firebase configuration.
