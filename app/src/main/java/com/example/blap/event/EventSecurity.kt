package com.example.blap.event

import android.content.Context
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

interface EventAdminKeyStore {
    fun getOrCreate(userId: String): KeyPair
    fun get(userId: String): KeyPair?
}

class LocalEventAdminKeyStore(context: Context) : EventAdminKeyStore {
    private val preferences = context.getSharedPreferences("event_admin_keys", Context.MODE_PRIVATE)

    override fun getOrCreate(userId: String): KeyPair = get(userId) ?: EventCheckInCodec
        .generateAdminKeyPair()
        .also { keys ->
            preferences.edit {
                putString("$userId.private", EventCheckInCodec.encodePrivateKey(keys.private))
                putString("$userId.public", EventCheckInCodec.encodePublicKey(keys.public))
            }
        }

    override fun get(userId: String): KeyPair? = runCatching {
        val privateKey = preferences.getString("$userId.private", null) ?: return null
        val publicKey = preferences.getString("$userId.public", null) ?: return null
        KeyPair(
            EventCheckInCodec.decodePublicKey(publicKey),
            EventCheckInCodec.decodePrivateKey(privateKey),
        )
    }.getOrNull()
}

object EventAnnouncementSigner {
    fun sign(announcement: EventAnnouncement, privateKey: PrivateKey): String {
        val bytes = canonicalBytes(announcement)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    fun verify(announcement: EventAnnouncement, publicKey: PublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonicalBytes(announcement))
            verify(Base64.getUrlDecoder().decode(announcement.signature))
        }
    }.getOrDefault(false)

    private fun canonicalBytes(announcement: EventAnnouncement): ByteArray = listOf(
        announcement.id,
        announcement.eventId,
        announcement.adminId,
        announcement.adminName,
        announcement.text,
        announcement.createdAt.toString(),
        announcement.revision.toString(),
    ).joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
}

object EventMutationSigner {
    fun sign(mutation: EventMutation, privateKey: PrivateKey): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(canonicalBytes(mutation))
            sign()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    fun verify(mutation: EventMutation, publicKey: PublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonicalBytes(mutation))
            verify(Base64.getUrlDecoder().decode(mutation.signature))
        }
    }.getOrDefault(false)

    private fun canonicalBytes(mutation: EventMutation): ByteArray = with(mutation.event) {
        listOf(
            id,
            mutation.adminId,
            title,
            description,
            venueName,
            latitude.toString(),
            longitude.toString(),
            radiusMetres.toString(),
            startsAt.toString(),
            endsAt.toString(),
            createdBy,
            adminIds.sorted().joinToString("\u001E"),
            adminPublicKeys.toSortedMap().entries.joinToString("\u001E") { "${it.key}=${it.value}" },
            visibility.name,
            requiresSignIn.toString(),
            createdAt.toString(),
            updatedAt.toString(),
            deletedAt?.toString().orEmpty(),
        ).joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
    }
}

object EventAccessGrantSigner {
    fun sign(grant: EventAccessGrant, privateKey: PrivateKey): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(canonicalBytes(grant))
            sign()
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
    }

    fun verify(grant: EventAccessGrant, publicKey: PublicKey): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(canonicalBytes(grant))
            verify(Base64.getUrlDecoder().decode(grant.signature))
        }
    }.getOrDefault(false)

    private fun canonicalBytes(grant: EventAccessGrant): ByteArray = listOf(
        grant.id,
        grant.requestId,
        grant.eventId,
        grant.userId,
        grant.peerId,
        grant.adminId,
        grant.issuedAt.toString(),
        grant.expiresAt.toString(),
    ).joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
}
