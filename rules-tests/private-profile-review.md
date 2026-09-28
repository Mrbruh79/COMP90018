# Private profile rule review

The Android client uses Firebase email/password and Google accounts. `privateProfiles/{uid}` is a single-document get and set, with no collection query. It backs up a signed-in person's card fields and their opt-in discovery choice. It does not replace `accountCards`, `accountSettings`, or the phone lookup index. The document has a fixed `uid`, `updatedAt` (epoch milliseconds), short name and username, card phone and email fields, social URLs, bio, and a separate optional lookup number.

The existing Firestore code also accesses `venues`, public and member-filtered `events`, event invitations by recipient or event, ordered discussion and announcements, direct and private chats, exact username/email/phone/peer lookups, and account cards/settings. This change adds no query to those paths and leaves their rules unchanged.

Threat check outcomes from the Firestore emulator:

| Attempt | Outcome |
| --- | --- |
| Anonymous access, public list, cross-owner read and write | Denied |
| Create with another UID, then update the owner UID | Denied |
| Extra role or permission field, wrong field type, missing required field | Denied |
| Oversized text on update and malformed opted-in lookup number | Denied |
| Delete the profile document | Denied |
| Owner create, read after another sign-in context, and valid update | Allowed |

The document has no roles, counters, references, paths, publication state, or nested collections, so privilege transitions, counter replay, path traversal, and orphan access do not apply to this new path. Every string is length-limited. The account card remains separate and contains no private phone or card email field. This is a prototype rule set and still needs review before broad release.
