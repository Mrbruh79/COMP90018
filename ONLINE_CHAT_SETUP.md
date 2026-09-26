# Online chat setup

Direct and private group chats now use both the local Nearby mesh and Firestore. Messages retain the same ID on both paths, so a second delivery does not create a duplicate. The open mesh chat remains Nearby only.

## Firebase project setup

1. In Firebase Authentication, enable Email/Password and Google providers. Phone sign-in is not offered because real Firebase verification SMS requires billing. Do not put passwords, service account keys, or sign-in tokens in the repository.
2. The Android debug build's SHA-1 and SHA-256 were registered on 2026-09-26 for Firebase app `1:354368020263:android:8ebdaed02e6515442f5302`. The checked-in `app/google-services.json` now contains its Android OAuth client and the project's web OAuth client, so the build generates `default_web_client_id`. If you distribute a release or Google Play build, register that build's different signing fingerprints too. Google sign-in still needs an on-device check with a Google account.
3. Use the existing `(default)` Cloud Firestore database in `australia-southeast2`. The reviewed `firestore.rules` were deployed to project `comp90018-4a590` on 2026-09-26. Redeploy them if you change the rules. Use an authorised Firebase account, not a key copied into chat.
4. On first launch, tap Continue to setup. The Join CommonGround screen has Email and Google choices. Existing users can find them in Settings. One Firebase account can link both sign-in methods. Nearby messaging remains available without authentication. Phone numbers are contact details, not a sign-in method.

Saved contacts can be added and searched locally by phone number, email address, or Google account email. A contact QR includes the app's mesh identity so scanning it also pairs the offline identity. Older contact QR codes still import, but do not contain that identity. Nearby discovery continues to link saved contacts by their phone hash. A phone number found this way is user-supplied and does not prove ownership of an online account.

Contacts and local profiles do not require a phone number. A contact can be saved using an email address, a Google account email address, a phone number, or a paired QR identity. Device contact import includes email-only entries. QR cards use version 3 to carry both email fields and still read older card versions. A Google account is identified by its email address, not a public Google user ID. These saved addresses are user-entered contact details, not proof of ownership.

The cloud chat route uses Firebase account IDs. Signed-in users publish a small account card with their app peer ID. Their verified sign-in email is available for exact lookup. A number becomes searchable only when its owner enters it on their profile and turns on number lookup. That number is not verified, and any account could claim it. A saved contact can be resolved by QR or Nearby identity, verified account email, or an opted-in number, in that order. Online direct and private group messages use the same local message IDs as Nearby delivery.

Account cards do not publish the profile's phone number, bio or social links. Exact lookup entries are readable to signed-in accounts. Number hashes are deterministic, so lookup should not be treated as a private address book. If a client bypasses the app and leaves an old lookup entry after turning off discovery, the current prototype rules do not remove it automatically. Do not rely on number lookup as proof of identity. Confirm a number match by QR or Nearby before sharing anything sensitive.

## Checks

Run Android unit tests with `./gradlew testDebugUnitTest`. For rules tests, run `npm install` in `rules-tests`, then from the project root run:

```text
npx firebase-tools emulators:exec --only firestore --project demo-blap-rules "npm --prefix rules-tests test"
```

The demo project name keeps this check local. It does not deploy or read the live Firebase project.

## Current limits

- Phone numbers remain local contact details and a Nearby matching hint. They also support opt-in online discovery, but are unverified. Imported local-format device numbers are converted using the phone's region setting. Check imported numbers if the phone's region differs from the contact's country. Manually entered numbers need a country code.
- Messages are stored in Firestore without end-to-end encryption. Nearby private-group relays can also read packet contents. Group membership rules limit cloud access, but neither transport should be described as end-to-end encrypted.
- Existing phone-addressed cloud messages are not silently migrated to a different account identity.
- A message written while no Firebase account is signed in remains local. It is not uploaded later under another person's account. Messages written under one account are not uploaded under a different account.
- Local chats are not separated by signed-in account, so account switching and merging need deliberate handling.
- The automated tests do not prove multi-device delivery on physical phones. Test two phones, one online and one offline, before relying on either route in a demonstration.
