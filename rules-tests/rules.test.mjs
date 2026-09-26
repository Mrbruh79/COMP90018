import { readFileSync } from 'node:fs';
import { after, before, beforeEach, test } from 'node:test';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, query, setDoc, where, writeBatch } from 'firebase/firestore';

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
  await assertFails(setDoc(doc(alice, 'directChatsV2/chat-1/messages/m4'), { ...message, text: 'x'.repeat(1001) }));
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
