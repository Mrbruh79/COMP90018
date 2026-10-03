# Nearby account identity field

The existing Standard default database is used. Account cards already expose only UID, unique username, display name and current app peer ID to signed-in accounts by exact document read. Listing account cards is denied. Phone and email details remain in owner-only settings and profile documents. Existing email, phone, peer and username lookup paths are unchanged.

The optional `nearbyPublicKey` field holds the current peer's Base64-encoded P-256 public key, never its private key. The app publishes it after its account card, using a transaction that checks the card still points to the same peer. Owner-only create and update rules share the existing card validator, including UID, reserved username, peer binding and field limits. Legacy cards without a key remain readable.

Nearby advertisements provide username and peer ID only as hints. The connected device signs the Hello identity and the long raw Nearby authentication token for that connection. Silent acceptance requires a remembered key or an exact saved account lookup. The signed proof must match before chat messages, group replay or mesh traffic are allowed. A signature copied from another connection cannot authenticate a different raw token. New unknown contacts still compare the displayed code. Approved keys are cached in account-scoped local storage for offline reconnection.

Checks cover owner publication, cross-account writes, changed ownership, stolen username, missing required fields, invalid types, oversized keys, extra private fields, signed-out and anonymous reads, and disallowed collection listing. Syntax is checked by loading the complete rules into the Firestore emulator. There are no new queries, indexes or collections. Timestamp, counter and privilege transitions are not introduced by this field. Emulator tests do not establish live radio behaviour or prove the absence of all security issues.

These rules are a prototype and should be reviewed before broad distribution. Deploying the rules is separate from building the app.
