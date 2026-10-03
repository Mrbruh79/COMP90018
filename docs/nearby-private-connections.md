# Nearby private connections

The public prototype room is no longer available. Its legacy identifier remains only to recognise and reject old traffic. Existing local public-room history is hidden, not erased. It is not replayed to new connections and cannot trigger notifications.

Private chats and private groups still use Nearby. Unknown devices require approval and verification-code comparison. Saved online contacts and previously approved devices can reconnect without a prompt when their signed device identity matches the saved public key. A username, display name or phone hash advertised by another device is not enough to skip approval.

Private Nearby advertisements include the unique username and peer ID. The first packet proves ownership of the device key with a signature bound to that connection's raw Nearby authentication token. No private traffic is accepted before this check. Signed identities are pinned locally in account-scoped storage. Online account cards contain only the public key, username, UID, display name and current peer ID. A changed key or a copied username cannot reuse the saved approval. Old app versions without signed identities need manual approval; an unsigned reconnect is rejected when a signed key is already pinned.

Nearby and online contacts with a verified matching account are joined into one saved contact and one account conversation. Existing messages and drafts are carried over. Online sending still uses the account UID; Nearby sending uses its connected peer ID. A shared number does not merge different accounts.

Event connections keep their isolated service and automatic connection flow. Leaving the Events tab switches back to the normal private-chat service. Returning to an active, checked-in event can resume its event mesh; viewing saved history alone cannot grant access.

Opening a joined, active event starts GPS check-in from the details page. The page shows whether the user is checked in. Location denial or a failed fix leaves a retry button and venue QR fallback. A completed check-in is retained locally for that membership until the event ends, the user leaves, or their access is removed. Cloud membership refreshes keep the local check-in while adopting remote role changes. Reopening a checked-in event restores Nearby without checking GPS again.

Automatic startup follows the selected event session across Details, Announcements, On-site chat and Discussion. If membership arrives after the user opens Announcements, a cached valid check-in starts Nearby there without another GPS request. An unchecked eligible membership can complete the initial GPS check-in without leaving that page. Startup waits for another platform request to finish. It does not repeat a denied permission request when pages change, activate from the private-chat tab, or start check-in from event lists or editors.

Opening announcements in a checked-in active event asks connected event peers to replay stored announcements and on-site chat history. Discovery and advertising are refreshed only when there are no live links or pending handshakes. Starting discovery again while connected can produce an out-of-order SDK error. Nearby must remain enabled. Opening announcements does not bypass venue check-in.

New announcements received from the cloud are also offered to the active event mesh. Announcement IDs and revisions prevent repeated snapshots from being relayed again. New event peers receive up to 100 stored announcements. The admin signature is retained, and receiving participants verify it before saving the announcement. An offline publication is described as saved locally, not confirmed delivered.

Stopping Nearby clears pending approvals, endpoints and cached routing state. The Android adapter ignores callbacks from an earlier transport session. When the final link drops or the last connection attempt fails, idle recovery clears SDK endpoint traces and restarts discovery and advertising. Stale entries leave the reconnect list until fresh discovery reports them. Other live links are retained when just one peer fails. Rediscovery received before an old connection closes is replayed after cleanup.

Temporary already-advertising, already-discovering, out-of-order and radio startup errors receive at most three retries, delayed by 250, 500 and 1,000 milliseconds. Stopping or superseding an attempt cancels queued retries. Missing permissions are not bypassed or treated as permission grants. Fatal radio and endpoint send failures close the failed link rather than leaving a stale connected peer.

Account publication preserves the current device's public key inside a Firestore transaction, so a concurrent key upload cannot be overwritten by a profile update. A different device does not inherit the old device's key. Starting Nearby retries publication without delaying the approved radio link. Unsaved or unverified devices still require approval.

Replies use left swipes on sent messages and right swipes on received messages. Reversing or cancelling the gesture does not send a reply.

## Verification

On 3 October 2026, the event-session startup fix passed all 333 Android unit tests. Five new tests reproduced skipped startup before the fix. Eight added cases cover late membership on Announcements, initial GPS admission there, restored On-site chat, deferred startup, denied permissions, private-tab isolation, admission page boundaries and announcement replay after check-in. The debug APK built and lint passed with no errors. No Firebase changes or phone installation were performed. Direct Announcements mesh delivery still needs testing on both updated phones.

On 3 October 2026, all 320 Android unit tests and all 22 Firestore emulator tests passed. The debug APK built successfully. Android lint reported no errors, 15 warnings and one hint. Three new tests reproduced failed-attempt, stale-peer and active-event scan problems before the changes. Physical radio delivery has not been verified with this version.

The four event indexes were redeployed to `comp90018-4a590` and verified READY. Administrative upcoming, search and Nearby event queries completed without an index error. The owner-only optional public-device-key rule was deployed and the live source was checked against the local rules. These checks do not verify a particular phone's account session or Android runtime permissions. No phone installation was performed during this verification.

A follow-up test reproduced an approved Nearby connection remaining on Connecting while its cloud identity lookup waited. Approved links now open immediately, pin their signed device key locally and allow radio messaging before that lookup finishes. Cloud account matching runs separately with a seven-second timeout. No account binding is granted when lookup fails or times out.

Unit coverage includes signed identity verification, copied usernames, offline remembered-device reconnects, saved online contacts, contact/history merging, event-entry check-in, cached check-in restoration, membership changes and denied permissions. Earlier coverage includes private approval and decline, requests arriving during contact editing, cancelled approvals after stopping, rediscovery on both sides, local disconnect without an SDK callback, old public packets, event-mode selection and reply direction. Debug build and lint are also checked. The Firestore emulator tests owner-only public-key writes and rejects account impersonation, private fields, collection listing and unauthenticated reads.

Physical radio behaviour still needs two phones running the updated app. Test a first connection, send in both directions, stop Nearby on one phone, restart it, and reconnect without another prompt. Repeat from each side and after both phones restart. Decline an unknown request and confirm no messages arrive. Repeat inside an event to check that event reconnection remains automatic. The deployed account-card public-key rule supports the first silent saved-contact connection, but both phones need the updated app and published keys. No phone installation has been performed for this change.

For announcements, check both phones into the same event, turn internet off while leaving Nearby enabled, then open announcements without reopening on-site chat. Post as an admin and confirm the other phone receives it. Disconnect and return with a new Nearby endpoint to check stored-history replay. Finally, keep one phone online and the other offline, publish an announcement from a third online device, and check that the connected online participant relays it to the offline phone. These radio and cloud-delivery checks have not been completed by the unit tests.

Connection approval does not provide end-to-end encryption for relayed private-group packets. Private-group encryption is separate work.

Reference: [Google Nearby connection approval](https://developers.google.com/nearby/connections/android/manage-connections).
