import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import {
  collection, deleteDoc, doc, getDoc, getDocs, query, runTransaction, setDoc, updateDoc, where, writeBatch,
} from 'firebase/firestore';

let environment;
const emailFor = uid => `${uid}@example.com`;
const client = (uid, verified = true) => environment.authenticatedContext(uid, {
  email: emailFor(uid), email_verified: verified,
}).firestore();

const settingsFor = (uid, phoneHash = '', discoverableByPhone = false) => ({
  uid, email: emailFor(uid), phoneHash, peerId: `${uid}-peer`, discoverableByPhone,
});

async function publishAccount(uid, phoneHash = '', discoverableByPhone = false) {
  const db = client(uid);
  const batch = writeBatch(db);
  batch.set(doc(db, `usernames/${uid}`), { uid });
  batch.set(doc(db, `accountSettings/${uid}`), settingsFor(uid, phoneHash, discoverableByPhone));
  batch.set(doc(db, `accountCards/${uid}`), { uid, name: uid, peerId: `${uid}-peer`, username: uid });
  batch.set(doc(db, `emailLookup/${emailFor(uid)}/accounts/${uid}`), { uid });
  batch.set(doc(db, `peerLookup/${uid}-peer/accounts/${uid}`), { uid });
  if (phoneHash) batch.set(doc(db, `phoneLookup/${phoneHash}/accounts/${uid}`), { uid });
  await assertSucceeds(batch.commit());
}

const privateProfileFor = uid => ({
  uid, updatedAt: 1, displayName: 'Alice', username: uid,
  phoneNumber: '+61412345678', email: 'alice@example.com', googleAccountEmail: '',
  discoverableByPhone: true, lookupPhoneNumber: '+61412345678', bio: 'Hello',
  websiteUrl: '', instagramUrl: '', xUrl: '', linkedinUrl: '', githubUrl: '',
});

test('private profile survives owner relogin and rejects other readers and malformed writes', async () => {
  const alice = client('alice');
  const bob = client('bob');
  const guest = environment.authenticatedContext('guest').firestore();
  const signedOut = environment.unauthenticatedContext().firestore();
  const path = 'privateProfiles/alice';
  await assertSucceeds(setDoc(doc(alice, path), privateProfileFor('alice')));
  await assertSucceeds(getDoc(doc(client('alice'), path)));
  await assertFails(getDoc(doc(bob, path)));
  await assertFails(getDoc(doc(guest, path)));
  await assertFails(getDoc(doc(signedOut, path)));
  await assertFails(getDocs(collection(alice, 'privateProfiles')));
  await assertFails(setDoc(doc(bob, path), privateProfileFor('bob')));
  await assertFails(setDoc(doc(alice, path), { ...privateProfileFor('alice'), uid: 'bob' }));
  await assertFails(updateDoc(doc(alice, path), { bio: 'a'.repeat(1_000_000) }));
  await assertFails(updateDoc(doc(alice, path), { admin: true }));
  await assertFails(updateDoc(doc(alice, path), { phoneNumber: 123 }));
  await assertFails(updateDoc(doc(alice, path), { lookupPhoneNumber: '', discoverableByPhone: true }));
  await assertFails(updateDoc(doc(alice, path), { username: null }));
  const missingField = privateProfileFor('alice');
  delete missingField.bio;
  await assertFails(setDoc(doc(alice, path), missingField));
  await assertFails(deleteDoc(doc(alice, path)));
  await assertSucceeds(updateDoc(doc(alice, path), { bio: 'Updated', updatedAt: 2 }));
});

before(async () => {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST || '127.0.0.1:8082').split(':');
  environment = await initializeTestEnvironment({
    projectId: 'demo-blap-rules',
    firestore: {
      host, port: Number(port),
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
    },
  });
});
beforeEach(async () => environment.clearFirestore());
after(async () => environment?.cleanup());

test('account cards and exact lookups require an email account, settings stay private', async () => {
  const alice = client('alice');
  const bob = client('bob');
  const anonymous = environment.authenticatedContext('guest').firestore();
  await publishAccount('alice');
  await assertSucceeds(getDoc(doc(alice, 'accountSettings/alice')));
  await assertFails(getDoc(doc(bob, 'accountSettings/alice')));
  await assertSucceeds(getDoc(doc(bob, 'accountCards/alice')));
  await assertFails(getDocs(collection(bob, 'accountCards')));
  await assertSucceeds(getDocs(collection(bob, 'emailLookup/alice@example.com/accounts')));
  await assertSucceeds(getDocs(collection(bob, 'peerLookup/alice-peer/accounts')));
  await assertFails(getDocs(collection(anonymous, 'emailLookup/alice@example.com/accounts')));
  await assertFails(setDoc(doc(anonymous, 'accountCards/guest'), {
    uid: 'guest', name: 'Guest', peerId: 'guest-peer',
  }));
});

test('account owner cannot claim a different verified email or another uid', async () => {
  const alice = client('alice');
  await assertFails(setDoc(doc(alice, 'accountSettings/alice'), {
    ...settingsFor('alice'), email: 'bob@example.com',
  }));
  await assertFails(setDoc(doc(alice, 'accountSettings/bob'), settingsFor('bob')));
  await assertFails(setDoc(doc(client('alice', false), 'emailLookup/alice@example.com/accounts/alice'), { uid: 'alice' }));
  await publishAccount('alice');
  await assertFails(setDoc(doc(alice, 'accountCards/alice'), {
    uid: 'bob', name: 'Spoofed', peerId: 'alice-peer', username: 'alice',
  }));
  await assertFails(setDoc(doc(alice, 'accountCards/alice'), {
    uid: 'alice', name: 'A'.repeat(1_000_000), peerId: 'alice-peer', username: 'alice',
  }));
  await assertFails(setDoc(doc(alice, 'accountCards/alice'), {
    uid: 'alice', name: 'Alice', peerId: 'alice-peer', username: 'alice', admin: true,
  }));
});

test('usernames are unique and cannot be reassigned or changed on an account card', async () => {
  await publishAccount('alice');
  const alice = client('alice');
  const bob = client('bob');
  await assertFails(setDoc(doc(bob, 'usernames/alice'), { uid: 'bob' }));
  await assertFails(setDoc(doc(bob, 'usernames/Bob'), { uid: 'bob' }));
  await assertFails(setDoc(doc(bob, 'usernames/bob'), { uid: 'alice' }));
  await assertFails(setDoc(doc(alice, 'usernames/alice'), { uid: 'bob' }));
  await assertFails(setDoc(doc(alice, 'accountCards/alice'), {
    uid: 'alice', name: 'Alice', peerId: 'alice-peer', username: 'another',
  }));
  await assertSucceeds(getDoc(doc(bob, 'usernames/alice')));
  await assertFails(getDocs(collection(bob, 'usernames')));
});

test('phone lookup is opt-in, unverified, and limited to the chosen hash', async () => {
  const hash = 'a'.repeat(64);
  const alice = client('alice');
  const bob = client('bob');
  await publishAccount('alice', hash, true);
  await assertSucceeds(getDocs(collection(bob, `phoneLookup/${hash}/accounts`)));
  await assertFails(setDoc(doc(bob, `phoneLookup/${hash}/accounts/alice`), { uid: 'alice' }));
  await assertFails(setDoc(doc(alice, `phoneLookup/${'b'.repeat(64)}/accounts/alice`), { uid: 'alice' }));
  const batch = writeBatch(alice);
  batch.set(doc(alice, 'accountSettings/alice'), settingsFor('alice'));
  batch.delete(doc(alice, `phoneLookup/${hash}/accounts/alice`));
  await assertSucceeds(batch.commit());
  const result = await getDocs(collection(bob, `phoneLookup/${hash}/accounts`));
  if (!result.empty) throw new Error('Phone alias should have been removed');
});

test('enabling phone lookup after account creation publishes a readable exact alias', async () => {
  const hash = 'c'.repeat(64);
  await publishAccount('alice');
  const alice = client('alice');
  const bob = client('bob');
  const batch = writeBatch(alice);
  batch.set(doc(alice, 'accountSettings/alice'), settingsFor('alice', hash, true));
  batch.set(doc(alice, 'phoneLookup/' + hash + '/accounts/alice'), { uid: 'alice' });
  await assertSucceeds(batch.commit());
  const matches = await getDocs(collection(bob, `phoneLookup/${hash}/accounts`));
  if (matches.docs.map(match => match.id).join(',') !== 'alice') {
    throw new Error('The opted-in phone alias was not found');
  }
});

test('direct chats are limited to two account UIDs, with immutable sender data', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  await publishAccount('carol');
  const alice = client('alice');
  const bob = client('bob');
  const carol = client('carol');
  const chat = doc(alice, 'directChatsV2/chat-1');
  await assertSucceeds(setDoc(chat, { memberIds: ['alice', 'bob'] }));
  await assertSucceeds(getDoc(doc(bob, 'directChatsV2/chat-1')));
  await assertFails(getDoc(doc(carol, 'directChatsV2/chat-1')));
  await assertSucceeds(getDocs(query(collection(bob, 'directChatsV2'),
    where('memberIds', 'array-contains', 'bob'))));
  await assertFails(setDoc(doc(bob, 'directChatsV2/chat-1'), { memberIds: ['bob', 'carol'] }));
  const message = {
    senderUid: 'alice', senderPeerId: 'alice-peer', senderName: 'Alice', text: 'Hello', sentAt: 100,
  };
  await assertSucceeds(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m1'), message));
  await assertSucceeds(setDoc(doc(alice, 'directChatsV2/chat-1/messages/poll'), {
    ...message, text: '\u001fCG2|P|TWVldD8|UGFyaw|TGlicmFyeQ', sentAt: 102,
  }));
  await assertSucceeds(getDoc(doc(bob, 'directChatsV2/chat-1/messages/poll')));
  await assertSucceeds(getDoc(doc(bob, 'directChatsV2/chat-1/messages/m1')));
  await assertSucceeds(setDoc(doc(bob, 'directChatsV2/chat-1'), { memberIds: ['alice', 'bob'] }));
  await assertSucceeds(setDoc(doc(bob, 'directChatsV2/chat-1/messages/reply'), {
    senderUid: 'bob', senderPeerId: 'bob-peer', senderName: 'Bob', text: 'Reply', sentAt: 101,
  }));
  await assertSucceeds(getDoc(doc(alice, 'directChatsV2/chat-1/messages/reply')));
  await assertSucceeds(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m1'), message));
  await assertFails(setDoc(doc(bob, 'directChatsV2/chat-1/messages/m2'), message));
  await assertFails(setDoc(doc(carol, 'directChatsV2/chat-1/messages/m3'), {
    ...message, senderUid: 'carol', senderPeerId: 'carol-peer',
  }));
  await assertFails(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m1'), { ...message, text: 'Changed' }));
  await assertSucceeds(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m4'), {
    ...message, text: '\u001fCG2|A|1500|' + 'A'.repeat(20_000), sentAt: 103,
  }));
  await assertFails(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m6'), { ...message, text: 'x'.repeat(30_001) }));
  await assertFails(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m5'), { ...message, extra: true }));
});

test('private groups restrict reads and membership changes to the owner', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  await publishAccount('carol');
  const alice = client('alice');
  const bob = client('bob');
  const carol = client('carol');
  const group = {
    name: 'Friends', ownerUid: 'alice', revision: 100,
    memberIds: ['alice', 'bob'],
    members: [
      { uid: 'alice', peerId: 'alice-peer', name: 'Alice' },
      { uid: 'bob', peerId: 'bob-peer', name: 'Bob' },
    ],
  };
  await assertSucceeds(setDoc(doc(alice, 'privateChatsV2/g1'), group));
  await assertSucceeds(getDoc(doc(bob, 'privateChatsV2/g1')));
  await assertFails(getDoc(doc(carol, 'privateChatsV2/g1')));
  await assertSucceeds(getDocs(query(collection(bob, 'privateChatsV2'),
    where('memberIds', 'array-contains', 'bob'))));
  await assertFails(setDoc(doc(bob, 'privateChatsV2/g1'), { ...group, name: 'Taken over' }));
  await assertFails(setDoc(doc(alice, 'privateChatsV2/g1'), {
    ...group, memberIds: ['alice', 'bob', 42], members: [...group.members, { uid: 42, peerId: 'x', name: 'X' }],
  }));
  await assertSucceeds(setDoc(doc(alice, 'privateChatsV2/g1'), { ...group, revision: 101, name: 'Renamed' }));
  await assertSucceeds(setDoc(doc(bob, 'privateChatsV2/g1/messages/m1'), {
    senderUid: 'bob', senderPeerId: 'bob-peer', senderName: 'Bob', text: 'Hi', sentAt: 101,
  }));
  await assertSucceeds(setDoc(doc(alice, 'privateChatsV2/g1'), {
    ...group, revision: 102, memberIds: ['alice', 'carol'],
    members: [group.members[0], { uid: 'carol', peerId: 'carol-peer', name: 'Carol' }],
  }));
  await assertFails(getDoc(doc(bob, 'privateChatsV2/g1')));
  await assertFails(getDoc(doc(bob, 'privateChatsV2/g1/messages/m1')));
  await assertSucceeds(getDoc(doc(carol, 'privateChatsV2/g1')));
});

const eventFor = (id, creator, visibility, requiresSignIn = false) => {
  const startsAt = Date.now() + 60_000;
  const endsAt = startsAt + 3_600_000;
  return {
    title: `${visibility} event`, description: 'Event description', venueName: 'Venue',
    latitude: -37.8136, longitude: 144.9631, radiusMetres: 100,
    startsAt, endsAt, createdBy: creator, adminIds: [creator], memberIds: [creator],
    adminPublicKeys: { [creator]: 'public-key' }, visibility,
    requiresSignIn: visibility === 'PUBLIC' && requiresSignIn,
    privateMeshSecret: visibility === 'PRIVATE' ? 's'.repeat(43) : '',
    venueCheckInPayload: visibility === 'PUBLIC' ? `signed-static-qr-${id}` : '',
    createdAt: Date.now(), updatedAt: Date.now(), deletedAt: null,
  };
};

const membershipFor = (eventId, uid, role = 'ATTENDEE') => ({
  userId: uid, displayName: uid, role, joinedAt: Date.now(),
  blockedAt: null, leftAt: null, accessMethod: null, checkedInAt: null,
});

async function createEventAs(uid, eventId, visibility, requiresSignIn = false) {
  const db = client(uid);
  const event = eventFor(eventId, uid, visibility, requiresSignIn);
  const batch = writeBatch(db);
  batch.set(doc(db, `events/${eventId}`), event);
  batch.set(doc(db, `events/${eventId}/members/${uid}`), membershipFor(eventId, uid, 'PRIMARY_ADMIN'));
  await assertSucceeds(batch.commit());
  return event;
}

test('public event venue QR is created once and cannot be replaced', async () => {
  await publishAccount('alice');
  const alice = client('alice');
  const event = await createEventAs('alice', 'static-qr-event', 'PUBLIC');

  await assertFails(updateDoc(doc(alice, 'events/static-qr-event'), {
    venueCheckInPayload: 'replacement-qr', updatedAt: event.updatedAt + 1,
  }));
  await assertFails(setDoc(doc(alice, 'events/private-with-qr'), {
    ...eventFor('private-with-qr', 'alice', 'PRIVATE'), venueCheckInPayload: 'not-allowed',
  }));
  await assertFails(setDoc(doc(alice, 'events/public-without-qr'), {
    ...eventFor('public-without-qr', 'alice', 'PUBLIC'), venueCheckInPayload: '',
  }));
});

test('private events are invisible until an in-app invitation is accepted', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  await publishAccount('carol');
  const privateEvent = await createEventAs('alice', 'private-event', 'PRIVATE');
  await createEventAs('alice', 'public-event', 'PUBLIC');
  const alice = client('alice');
  const bob = client('bob');
  const carol = client('carol');

  await assertFails(getDoc(doc(bob, 'events/private-event')));
  await assertSucceeds(getDoc(doc(carol, 'events/public-event')));
  await assertSucceeds(getDocs(query(collection(carol, 'events'), where('visibility', '==', 'PUBLIC'))));
  await assertFails(getDocs(collection(carol, 'events')));

  const invitationId = 'private-event_bob';
  const invitation = {
    eventId: 'private-event', eventTitle: privateEvent.title,
    inviterUid: 'alice', inviterName: 'alice', recipientUid: 'bob',
    recipientName: 'bob', recipientUsername: 'bob', startsAt: privateEvent.startsAt,
    endsAt: privateEvent.endsAt, createdAt: Date.now(), expiresAt: privateEvent.endsAt,
    status: 'PENDING',
  };
  await assertSucceeds(setDoc(doc(alice, `eventInvitations/${invitationId}`), invitation));
  await assertSucceeds(getDoc(doc(bob, `eventInvitations/${invitationId}`)));
  await assertFails(getDoc(doc(carol, `eventInvitations/${invitationId}`)));

  const accept = writeBatch(bob);
  accept.update(doc(bob, `eventInvitations/${invitationId}`), { status: 'ACCEPTED' });
  accept.update(doc(bob, 'events/private-event'), { memberIds: ['alice', 'bob'] });
  accept.set(doc(bob, 'events/private-event/members/bob'), membershipFor('private-event', 'bob'));
  await assertSucceeds(accept.commit());
  await assertSucceeds(getDoc(doc(bob, 'events/private-event')));
  await assertSucceeds(getDocs(query(collection(bob, 'events'), where('memberIds', 'array-contains', 'bob'))));
  await assertFails(getDoc(doc(carol, 'events/private-event')));
});

test('a private event cannot be joined without an accepted invitation', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  await createEventAs('alice', 'private-event', 'PRIVATE');
  const bob = client('bob');
  const join = writeBatch(bob);
  join.update(doc(bob, 'events/private-event'), { memberIds: ['alice', 'bob'] });
  join.set(doc(bob, 'events/private-event/members/bob'), membershipFor('private-event', 'bob'));
  await assertFails(join.commit());
});

test('guest users retain public event access but cannot access private events', async () => {
  await publishAccount('alice');
  await createEventAs('alice', 'public-event', 'PUBLIC');
  await createEventAs('alice', 'private-event', 'PRIVATE');
  const guest = environment.authenticatedContext('guest').firestore();

  await assertSucceeds(getDoc(doc(guest, 'events/public-event')));
  await assertSucceeds(getDocs(query(collection(guest, 'events'), where('visibility', '==', 'PUBLIC'))));
  await assertFails(getDoc(doc(guest, 'events/private-event')));

  const joinPublic = writeBatch(guest);
  joinPublic.update(doc(guest, 'events/public-event'), { memberIds: ['alice', 'guest'] });
  joinPublic.set(
    doc(guest, 'events/public-event/members/guest'),
    membershipFor('public-event', 'guest'),
  );
  await assertSucceeds(joinPublic.commit());

  const createAsGuest = writeBatch(guest);
  createAsGuest.set(doc(guest, 'events/guest-created'), eventFor('guest-created', 'guest', 'PUBLIC'));
  createAsGuest.set(
    doc(guest, 'events/guest-created/members/guest'),
    membershipFor('guest-created', 'guest', 'PRIMARY_ADMIN'),
  );
  await assertFails(createAsGuest.commit());
});

test('protected public events remain visible but reject guest joins', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  await createEventAs('alice', 'protected-event', 'PUBLIC', true);
  const guest = environment.authenticatedContext('guest').firestore();
  const bob = client('bob');

  await assertSucceeds(getDoc(doc(guest, 'events/protected-event')));

  const guestJoin = writeBatch(guest);
  guestJoin.update(doc(guest, 'events/protected-event'), { memberIds: ['alice', 'guest'] });
  guestJoin.set(
    doc(guest, 'events/protected-event/members/guest'),
    membershipFor('protected-event', 'guest'),
  );
  await assertFails(guestJoin.commit());

  const accountJoin = writeBatch(bob);
  accountJoin.update(doc(bob, 'events/protected-event'), { memberIds: ['alice', 'bob'] });
  accountJoin.set(
    doc(bob, 'events/protected-event/members/bob'),
    membershipFor('protected-event', 'bob'),
  );
  await assertSucceeds(accountJoin.commit());
});

test('a co-admin can leave while preserving the primary admin and event validity', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  const event = await createEventAs('alice', 'co-admin-leave-event', 'PUBLIC');
  const alice = client('alice');
  const bob = client('bob');

  const join = writeBatch(bob);
  join.update(doc(bob, 'events/co-admin-leave-event'), { memberIds: ['alice', 'bob'] });
  join.set(
    doc(bob, 'events/co-admin-leave-event/members/bob'),
    membershipFor('co-admin-leave-event', 'bob'),
  );
  await assertSucceeds(join.commit());

  const promote = writeBatch(alice);
  promote.update(doc(alice, 'events/co-admin-leave-event'), {
    adminIds: ['alice', 'bob'],
    adminPublicKeys: { alice: 'public-key', bob: 'bob-public-key' },
    updatedAt: event.updatedAt + 1,
  });
  promote.update(
    doc(alice, 'events/co-admin-leave-event/members/bob'),
    { role: 'CO_ADMIN' },
  );
  await assertSucceeds(promote.commit());

  const leave = writeBatch(bob);
  leave.update(doc(bob, 'events/co-admin-leave-event'), {
    adminIds: ['alice'],
    memberIds: ['alice'],
    adminPublicKeys: { alice: 'public-key' },
    updatedAt: event.updatedAt + 2,
  });
  leave.update(doc(bob, 'events/co-admin-leave-event/members/bob'), { leftAt: Date.now() });
  await assertSucceeds(leave.commit());
});

const discussionCommentFor = (eventId, threadId, id, authorId, options = {}) => ({
  eventId,
  threadId,
  parentId: options.parentId ?? null,
  ancestorIds: options.ancestorIds ?? [],
  depth: options.depth ?? 0,
  authorId,
  authorName: authorId,
  body: options.body ?? `Comment ${id}`,
  likeCount: 0,
  replyCount: 0,
  lastReplyId: '',
  createdAt: Date.now(),
  deletedAt: null,
  deletedByAdmin: false,
});

async function joinPublicEvent(eventId, uid) {
  const db = client(uid);
  const eventSnapshot = await getDoc(doc(db, `events/${eventId}`));
  const memberIds = [...eventSnapshot.data().memberIds, uid];
  const batch = writeBatch(db);
  batch.update(doc(db, `events/${eventId}`), { memberIds });
  batch.set(doc(db, `events/${eventId}/members/${uid}`), membershipFor(eventId, uid));
  await assertSucceeds(batch.commit());
}

async function createDiscussionReply(db, rootPath, replyPath, reply) {
  return runTransaction(db, async transaction => {
    const root = await transaction.get(doc(db, rootPath));
    transaction.set(doc(db, replyPath), reply);
    transaction.update(doc(db, rootPath), {
      replyCount: (root.data().replyCount ?? 0) + 1,
      lastReplyId: replyPath.substring(replyPath.lastIndexOf('/') + 1),
    });
  });
}

test('event discussion enforces membership, nesting, like and unlike', async () => {
  await publishAccount('alice');
  await publishAccount('bob');
  await publishAccount('carol');
  await createEventAs('alice', 'discussion-event', 'PUBLIC');
  await joinPublicEvent('discussion-event', 'bob');

  const alice = client('alice');
  const bob = client('bob');
  const carol = client('carol');
  const rootPath = 'events/discussion-event/discussionThreads/thread-1';
  const root = discussionCommentFor('discussion-event', 'thread-1', 'thread-1', 'bob');
  await assertSucceeds(setDoc(doc(bob, rootPath), root));
  await assertSucceeds(getDoc(doc(alice, rootPath)));
  await assertFails(getDoc(doc(carol, rootPath)));

  const replyPath = `${rootPath}/comments/reply-1`;
  const reply = discussionCommentFor('discussion-event', 'thread-1', 'reply-1', 'alice', {
    parentId: 'thread-1', ancestorIds: ['thread-1'], depth: 1, body: 'Nested reply',
  });
  await assertSucceeds(createDiscussionReply(alice, rootPath, replyPath, reply));
  const secondReplyPath = `${rootPath}/comments/reply-2`;
  await assertSucceeds(createDiscussionReply(bob, rootPath, secondReplyPath,
    discussionCommentFor('discussion-event', 'thread-1', 'reply-2', 'bob', {
      parentId: 'reply-1', ancestorIds: ['thread-1', 'reply-1'], depth: 2,
    })));
  if ((await getDoc(doc(alice, rootPath))).data().replyCount !== 2) {
    throw new Error('Thread reply count did not increment');
  }
  const badReplyPath = `${rootPath}/comments/bad-reply`;
  await assertFails(createDiscussionReply(bob, rootPath, badReplyPath, {
    ...reply, authorId: 'bob', parentId: 'missing', ancestorIds: ['missing'],
  }));
  await assertFails(updateDoc(doc(bob, replyPath), { body: 'Changed by Bob' }));

  const likePath = 'events/discussion-event/discussionLikes/thread-1_alice';
  const like = {
    eventId: 'discussion-event', threadId: 'thread-1', commentId: 'thread-1',
    userId: 'alice', createdAt: Date.now(), isRoot: true,
  };
  const likeBatch = writeBatch(alice);
  likeBatch.set(doc(alice, likePath), like);
  likeBatch.update(doc(alice, rootPath), { likeCount: 1 });
  await assertSucceeds(likeBatch.commit());

  const duplicateLike = writeBatch(alice);
  duplicateLike.set(doc(alice, likePath), like);
  duplicateLike.update(doc(alice, rootPath), { likeCount: 2 });
  await assertFails(duplicateLike.commit());

  await assertSucceeds(runTransaction(alice, async transaction => {
    const target = await transaction.get(doc(alice, rootPath));
    transaction.delete(doc(alice, likePath));
    transaction.update(doc(alice, rootPath), { likeCount: target.data().likeCount - 1 });
  }));

  const bobLikePath = 'events/discussion-event/discussionLikes/thread-1_bob';
  await assertSucceeds(runTransaction(bob, async transaction => {
    const target = await transaction.get(doc(bob, rootPath));
    transaction.set(doc(bob, bobLikePath), { ...like, userId: 'bob' });
    transaction.update(doc(bob, rootPath), { likeCount: target.data().likeCount + 1 });
  }));
  const threadLikes = query(
    collection(alice, 'events/discussion-event/discussionLikes'),
    where('threadId', '==', 'thread-1'),
  );
  await assertSucceeds(getDocs(threadLikes));
  await assertFails(getDocs(query(
    collection(bob, 'events/discussion-event/discussionLikes'),
    where('threadId', '==', 'thread-1'),
  )));
  await assertSucceeds(deleteDoc(doc(alice, bobLikePath)));

  await assertSucceeds(updateDoc(doc(alice, replyPath), {
    body: '', deletedAt: Date.now(), deletedByAdmin: false,
  }));
  await assertFails(updateDoc(doc(bob, rootPath), { likeCount: 1 }));
  const removeBranch = writeBatch(alice);
  removeBranch.delete(doc(alice, replyPath));
  removeBranch.delete(doc(alice, secondReplyPath));
  removeBranch.update(doc(alice, rootPath), { replyCount: 0 });
  await assertSucceeds(removeBranch.commit());
  if ((await getDoc(doc(alice, rootPath))).data().replyCount !== 0) {
    throw new Error('Thread reply count did not decrease after branch removal');
  }
  await assertSucceeds(deleteDoc(doc(alice, rootPath)));
});

test('ended event discussions are read-only for members', async () => {
  await publishAccount('alice');
  const alice = client('alice');
  const event = eventFor('ended-event', 'alice', 'PUBLIC');
  event.startsAt = Date.now() - 7_200_000;
  event.endsAt = Date.now() - 3_600_000;
  const create = writeBatch(alice);
  create.set(doc(alice, 'events/ended-event'), event);
  create.set(
    doc(alice, 'events/ended-event/members/alice'),
    membershipFor('ended-event', 'alice', 'PRIMARY_ADMIN'),
  );
  await assertSucceeds(create.commit());
  const root = discussionCommentFor('ended-event', 'thread-1', 'thread-1', 'alice');
  await assertFails(setDoc(doc(alice, 'events/ended-event/discussionThreads/thread-1'), root));
});
