# Contact lookup and chat identity

Manual contacts are looked up before they are saved. A match stores the account's unique username, Firebase UID and Nearby peer ID. If lookup fails, the editor keeps the draft and does not save an unlinked online contact. Legacy QR cards with a paired Nearby identity can still be saved offline.

A username selects an account directly. Without a username, lookup collects phone, email, Google email and paired-peer matches, then removes duplicate UIDs. Several results require the user to choose a username before saving. Phone discovery remains opt-in and does not verify ownership of the number.

The username is the visible account identifier. Firebase UID remains the stable internal key. Local online DMs use `account:<uid>`, while cloud DMs use the existing deterministic pair of UIDs. Email and phone are lookup inputs, not separate chat identities. Nearby sending translates the account conversation back to the paired device's peer ID.

Linking a contact moves identified legacy phone, email and peer conversations into its account conversation. Messages keep their IDs. A draft is carried into the joined chat when that chat does not already have one. Known accounts sharing a number are not merged. Repeated saves of the same account reuse its contact record and preserve previously saved details omitted from the new form.

Find online reads the server. Sending to an already linked account uses its saved UID instead of repeating discovery for each message. Phone discovery is still opt-in. Firebase providers are unchanged.

Nearby now shares the account's unique username, UID and public device key in a signed handshake. A cloud-verified or previously verified binding joins the Nearby peer to the existing account conversation and saved contact. Duplicate local contact records with that same account or paired peer are removed after their histories have been merged. A username claim without the matching account key does not join an online conversation. Known signed devices can reconnect without a prompt. New devices still need approval.

When duplicate records are joined, populated contact-card fields fill gaps in the retained record. Existing non-empty values are kept. Messages arriving during account resolution are moved into the same chat when the binding is confirmed. Different accounts sharing a number remain separate.

Verified Nearby accounts are matched by Firebase UID before a stored username or peer alias. A stale alias must not relink another saved account. Nearby routing also ignores connected devices whose verified UID differs from the conversation's account.

Cloud copies of sent messages keep the recipient's chat name. Their sender peer ID belongs to the local account and is not merged into the recipient's chat. Incoming peer aliases are not merged when they belong to another saved account. Replayed snapshots still refresh conversation labels even when the message ID is already stored.

Profile publication preserves the current peer's public key in a transaction. When the peer changes, the old key is omitted until the new device publishes its own. Starting Nearby retries publication, so a previous server-rule rejection does not require editing the profile or signing out. Recognition still requires a verified key or a remembered approved device, not just a matching username.

## Saving someone from an incoming chat

Online DMs read the other participant's public account card by UID in the background. Their unique username appears below the display name in the chat contact profile, including before they are saved. Save contact fills that username into the editor and retains the same account UID. A sent-message echo reads the recipient's card, not the local sender's card. Private phone and email settings are not read or exposed.

SQLite version 13 stores the username with the conversation. Peer updates, app restarts and alias merging retain it. Nearby handshakes also populate this field, so disconnecting does not remove the username. A contact saved from an existing account chat can reuse its cached username and UID when offline. A failed public-card read neither blocks messages nor clears a previously stored username. Old online chats fetch the public card when their contact profile is opened. Late reads cannot fill an unrelated contact editor.

## Delete chat

The chat's More menu includes Delete chat with a confirmation. It removes local messages and the draft, but keeps the contact and group membership. SQLite version 12 stores a deletion cutoff and removed message IDs so cloud history does not immediately restore the chat. A newer incoming message can reopen it. Messaging a saved contact again opens an empty chat without restoring deleted history. Deletion is local to this device, not deletion for everyone or across devices.

## Verification

On 3 October 2026, the incoming-chat username fix passed all 325 Android unit tests, including five new cases for incoming account identity, sent-message echoes, disconnected Nearby contacts, failed public-card reads and late responses reaching another draft. All nine SQLite tests passed on a headless emulator, including version 12 migration and username retention after peer updates, reopening and alias merging. The debug and test APKs built successfully. Lint reported no errors, 15 warnings and one hint. No physical phone was changed, and live two-account delivery was not retested. This fix did not change or deploy Firebase rules or data.

On 3 October 2026, four additional regression tests covered sent-message names, local identity separation, stale Nearby account aliases and cross-account history merging. Three tests reproduced failures before the fix. All 310 Android unit tests passed after the changes, and the debug APK built successfully. These tests do not establish delivery between physical phones. No account reset or phone installation was performed.

On 2 October 2026, the debug APK and Android test APK built successfully. All 237 unit tests passed. Six SQLite tests passed on a headless Android emulator, including version 11 migration, alias merging, persistent deletion, cloud replay suppression, reopening and separate accounts sharing a number. Lint reported no errors, 13 warnings and one hint.

Neither connected physical phone was changed. Live two-account Firebase messaging and physical Nearby delivery still need testing with the updated APK.
