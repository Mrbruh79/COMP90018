package com.example.blap.chat

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.tasks.await

data class CloudAccount(val uid: String, val name: String, val peerId: String)

internal object AccountLookup {
    fun phoneHash(profile: ContactProfile): String =
        if (profile.discoverableByPhone) PhoneIdentity.hash(profile.lookupPhoneNumber).orEmpty() else ""
}
data class CloudChatMessage(
    val id: String,
    val senderUid: String,
    val senderPeerId: String,
    val senderName: String,
    val text: String,
    val sentAt: Long,
)
data class CloudGroupMember(val uid: String, val peerId: String, val name: String)
data class CloudPrivateGroup(
    val id: String,
    val name: String,
    val ownerUid: String,
    val revision: Long,
    val members: List<CloudGroupMember>,
)

interface CloudChatController {
    interface Listener {
        fun onDirectMessage(otherUid: String, message: CloudChatMessage)
        fun onPrivateGroup(group: CloudPrivateGroup)
        fun onGroupMessage(groupId: String, message: CloudChatMessage)
        fun onCloudError(message: String)
    }
    fun start(accountUid: String, listener: Listener)
    fun stop()
    suspend fun publishAccount(profile: ContactProfile, peerId: String)
    suspend fun getAccount(uid: String): CloudAccount?
    suspend fun findAccounts(phoneNumber: String = "", email: String = "", peerId: String = ""): List<CloudAccount>
    suspend fun sendDirect(otherUid: String, message: CloudChatMessage)
    suspend fun saveGroup(group: CloudPrivateGroup)
    suspend fun deleteGroup(groupId: String)
    suspend fun sendGroup(groupId: String, message: CloudChatMessage)
}

object CloudChatIds {
    fun direct(firstUid: String, secondUid: String): String {
        val members = listOf(firstUid, secondUid).sorted()
        return MessageDigest.getInstance("SHA-256")
            .digest("${members[0]}|${members[1]}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

class FirebaseCloudChatController(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : CloudChatController {
    private var accountUid = ""
    private val registrations = mutableListOf<ListenerRegistration>()
    private val directRegistrations = mutableMapOf<String, ListenerRegistration>()
    private val groupRegistrations = mutableMapOf<String, ListenerRegistration>()

    override fun start(accountUid: String, listener: CloudChatController.Listener) {
        stop()
        if (auth.currentUser?.uid != accountUid || auth.currentUser?.isAnonymous != false) {
            listener.onCloudError("Sign in with Email or Google for online chat.")
            return
        }
        this.accountUid = accountUid
        registrations += firestore.collection("directChatsV2")
            .whereArrayContains("memberIds", accountUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    listener.onCloudError(error.localizedMessage ?: "Could not sync direct chats.")
                    return@addSnapshotListener
                }
                val documents = snapshot?.documents.orEmpty()
                val visible = documents.map(DocumentSnapshot::getId).toSet()
                (directRegistrations.keys - visible).forEach { directRegistrations.remove(it)?.remove() }
                documents.forEach { chat ->
                    if (directRegistrations.containsKey(chat.id)) return@forEach
                    val members = (chat.get("memberIds") as? List<*>)?.filterIsInstance<String>().orEmpty()
                    val otherUid = members.singleOrNull { it != accountUid } ?: return@forEach
                    directRegistrations[chat.id] = chat.reference.collection("messages")
                        .addSnapshotListener { messages, messageError ->
                            if (messageError != null) {
                                listener.onCloudError(messageError.localizedMessage ?: "Could not sync messages.")
                                return@addSnapshotListener
                            }
                            messages?.documents.orEmpty().forEach { document ->
                                document.toCloudMessage()?.let { listener.onDirectMessage(otherUid, it) }
                            }
                        }
                }
            }
        registrations += firestore.collection("privateChatsV2")
            .whereArrayContains("memberIds", accountUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    listener.onCloudError(error.localizedMessage ?: "Could not sync private groups.")
                    return@addSnapshotListener
                }
                val documents = snapshot?.documents.orEmpty()
                val visible = documents.map(DocumentSnapshot::getId).toSet()
                (groupRegistrations.keys - visible).forEach { groupRegistrations.remove(it)?.remove() }
                documents.forEach { chat ->
                    val group = chat.toCloudGroup() ?: return@forEach
                    listener.onPrivateGroup(group)
                    if (groupRegistrations.containsKey(chat.id)) return@forEach
                    groupRegistrations[chat.id] = chat.reference.collection("messages")
                        .addSnapshotListener { messages, messageError ->
                            if (messageError != null) {
                                listener.onCloudError(messageError.localizedMessage ?: "Could not sync group messages.")
                                return@addSnapshotListener
                            }
                            messages?.documents.orEmpty().forEach { document ->
                                document.toCloudMessage()?.let { listener.onGroupMessage(chat.id, it) }
                            }
                        }
                }
            }
    }

    override fun stop() {
        registrations.forEach(ListenerRegistration::remove)
        directRegistrations.values.forEach(ListenerRegistration::remove)
        groupRegistrations.values.forEach(ListenerRegistration::remove)
        registrations.clear()
        directRegistrations.clear()
        groupRegistrations.clear()
        accountUid = ""
    }

    override suspend fun publishAccount(profile: ContactProfile, peerId: String) {
        val uid = requireAccountUid()
        val user = requireNotNull(auth.currentUser)
        val email = if (user.isEmailVerified) user.email.orEmpty().trim().lowercase(Locale.ROOT) else ""
        val phoneHash = AccountLookup.phoneHash(profile)
        val settingsRef = firestore.collection("accountSettings").document(uid)
        val previous = settingsRef.get().await()
        val oldEmail = previous.getString("email").orEmpty()
        val oldPhoneHash = previous.getString("phoneHash").orEmpty()
        val oldPeerId = previous.getString("peerId").orEmpty()
        val batch = firestore.batch()
        if (oldEmail.isNotBlank() && oldEmail != email) {
            batch.delete(firestore.collection("emailLookup").document(oldEmail).collection("accounts").document(uid))
        }
        if (oldPhoneHash.isNotBlank() && oldPhoneHash != phoneHash) {
            batch.delete(firestore.collection("phoneLookup").document(oldPhoneHash).collection("accounts").document(uid))
        }
        if (oldPeerId.isNotBlank() && oldPeerId != peerId) {
            batch.delete(firestore.collection("peerLookup").document(oldPeerId).collection("accounts").document(uid))
        }
        batch.set(settingsRef, mapOf(
            "uid" to uid, "email" to email, "phoneHash" to phoneHash,
            "peerId" to peerId, "discoverableByPhone" to profile.discoverableByPhone,
        ))
        batch.set(firestore.collection("accountCards").document(uid), mapOf(
            "uid" to uid, "name" to profile.displayName.trim().take(24), "peerId" to peerId,
            "username" to profile.username,
        ))
        if (email.isNotBlank()) {
            batch.set(firestore.collection("emailLookup").document(email).collection("accounts").document(uid), mapOf("uid" to uid))
        }
        if (phoneHash.isNotBlank()) {
            batch.set(firestore.collection("phoneLookup").document(phoneHash).collection("accounts").document(uid), mapOf("uid" to uid))
        }
        batch.set(firestore.collection("peerLookup").document(peerId).collection("accounts").document(uid), mapOf("uid" to uid))
        batch.commit().await()
    }

    override suspend fun findAccounts(phoneNumber: String, email: String, peerId: String): List<CloudAccount> {
        requireAccountUid()
        val ids = linkedSetOf<String>()
        val normalizedEmail = ContactIdentity.normalizeEmail(email).orEmpty()
        val phoneHash = PhoneIdentity.hash(phoneNumber).orEmpty()
        if (normalizedEmail.isNotBlank()) {
            firestore.collection("emailLookup").document(normalizedEmail).collection("accounts")
                .get().await().documents.mapTo(ids, DocumentSnapshot::getId)
        }
        if (phoneHash.isNotBlank()) {
            firestore.collection("phoneLookup").document(phoneHash).collection("accounts")
                .get().await().documents.mapTo(ids, DocumentSnapshot::getId)
        }
        if (peerId.isNotBlank() && '/' !in peerId) {
            firestore.collection("peerLookup").document(peerId).collection("accounts")
                .get().await().documents.mapTo(ids, DocumentSnapshot::getId)
        }
        return ids.filter { it != accountUid }.mapNotNull { getAccount(it) }
    }

    override suspend fun getAccount(uid: String): CloudAccount? {
        requireAccountUid()
        val card = firestore.collection("accountCards").document(uid).get().await()
        if (!card.exists() || card.getString("uid") != uid) return null
        return CloudAccount(uid, card.getString("name").orEmpty(), card.getString("peerId").orEmpty())
    }

    override suspend fun sendDirect(otherUid: String, message: CloudChatMessage) {
        requireSender(message.senderUid)
        val members = listOf(accountUid, otherUid).sorted()
        require(members[0] != members[1])
        val chat = firestore.collection("directChatsV2").document(CloudChatIds.direct(accountUid, otherUid))
        chat.set(mapOf("memberIds" to members)).await()
        chat.collection("messages").document(message.id).set(message.toMap()).await()
    }

    override suspend fun saveGroup(group: CloudPrivateGroup) {
        val uid = requireAccountUid()
        require(group.ownerUid == uid)
        val members = group.members.distinctBy(CloudGroupMember::uid)
        require(members.any { it.uid == uid } && members.size in 2..8)
        firestore.collection("privateChatsV2").document(group.id).set(mapOf(
            "name" to group.name, "ownerUid" to group.ownerUid, "revision" to group.revision,
            "memberIds" to members.map(CloudGroupMember::uid),
            "members" to members.map { member ->
                mapOf("uid" to member.uid, "peerId" to member.peerId, "name" to member.name)
            },
        )).await()
    }

    override suspend fun sendGroup(groupId: String, message: CloudChatMessage) {
        requireSender(message.senderUid)
        firestore.collection("privateChatsV2").document(groupId)
            .collection("messages").document(message.id).set(message.toMap()).await()
    }

    override suspend fun deleteGroup(groupId: String) {
        requireAccountUid()
        firestore.collection("privateChatsV2").document(groupId).delete().await()
    }

    private fun requireAccountUid(): String {
        check(accountUid.isNotBlank() && auth.currentUser?.uid == accountUid &&
            auth.currentUser?.isAnonymous == false) { "Sign in with Email or Google for online chat." }
        return accountUid
    }

    private fun requireSender(senderUid: String) {
        check(requireAccountUid() == senderUid) { "The signed-in account does not match this message." }
    }

    private fun CloudChatMessage.toMap(): Map<String, Any> = mapOf(
        "senderUid" to senderUid, "senderPeerId" to senderPeerId,
        "senderName" to senderName, "text" to text, "sentAt" to sentAt,
    )

    private fun DocumentSnapshot.toCloudMessage(): CloudChatMessage? {
        val senderUid = getString("senderUid") ?: return null
        val text = getString("text") ?: return null
        if (senderUid.isBlank() || text.isBlank()) return null
        return CloudChatMessage(
            id, senderUid, getString("senderPeerId").orEmpty(),
            getString("senderName").orEmpty().take(24), text.take(1_000),
            getLong("sentAt") ?: return null,
        )
    }

    private fun DocumentSnapshot.toCloudGroup(): CloudPrivateGroup? {
        val members = (get("members") as? List<*>)?.mapNotNull { raw ->
            val value = raw as? Map<*, *> ?: return@mapNotNull null
            CloudGroupMember(
                value["uid"] as? String ?: return@mapNotNull null,
                value["peerId"] as? String ?: "",
                value["name"] as? String ?: "",
            )
        }.orEmpty()
        if (members.isEmpty()) return null
        return CloudPrivateGroup(
            id, getString("name") ?: return null, getString("ownerUid") ?: return null,
            getLong("revision") ?: return null, members,
        )
    }
}
