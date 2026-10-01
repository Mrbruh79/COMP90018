package com.example.blap.chat

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProfileBackup(private val session: MessagingSession) {
    private val privateProfileWriteMutex = Mutex()
    private val newestPrivateProfileVersion = AtomicLong(0)
    private val privateProfileStore get() = session.privateProfileStore

    fun restorePrivateProfile(snapshot: PrivateProfileSnapshot?, username: String, displayName: String) {
        val localTime = session.identityStore.profileUpdatedAt()
        val localProfile = session.identityStore.getProfile()
        val localHasDetails = localProfile.hasPrivateDetails()
        val remoteHasDetails = snapshot?.profile?.hasPrivateDetails() == true
        val accountId = session.state.value.onlineAccountId
        val restoreRemote = snapshot != null && (
            (snapshot.updatedAt > localTime &&
                !(localTime == 0L && localHasDetails && !remoteHasDetails)) ||
            (localTime == 0L && !localHasDetails && remoteHasDetails)
        )
        if (restoreRemote) {
            val remote = requireNotNull(snapshot)
            val restored = remote.profile.copy(username = username, displayName = displayName)
            session.identityStore.saveProfileAt(restored, remote.updatedAt)
            session.localPhoneHash = PhoneIdentity.hash(restored.phoneNumber).orEmpty()
            session.applyProfile(restored)
            newestPrivateProfileVersion.updateAndGet { maxOf(it, remote.updatedAt) }
        } else if (accountId.isNotBlank() && (localHasDetails || localTime > 0L) &&
            (snapshot == null || localTime > snapshot.updatedAt || !remoteHasDetails)) {
            val local = localProfile.copy(username = username, displayName = displayName)
            val savedAt = if (localTime == 0L) System.currentTimeMillis() else localTime
            session.identityStore.saveProfileAt(local, savedAt)
            save(local, savedAt)
        }
    }

    fun ContactProfile.hasPrivateDetails(): Boolean =
        phoneNumber.isNotBlank() || email.isNotBlank() || googleAccountEmail.isNotBlank() ||
            bio.isNotBlank() || websiteUrl.isNotBlank() || instagramUrl.isNotBlank() ||
            xUrl.isNotBlank() || linkedinUrl.isNotBlank() || githubUrl.isNotBlank() ||
            lookupPhoneNumber.isNotBlank() || discoverableByPhone

    fun save(profile: ContactProfile, updatedAt: Long) {
        val uid = session.state.value.onlineAccountId.takeIf(String::isNotBlank) ?: return
        val store = privateProfileStore ?: return
        newestPrivateProfileVersion.updateAndGet { maxOf(it, updatedAt) }
        session.workScope.launch {
            runCatching {
                privateProfileWriteMutex.withLock {
                    if (updatedAt >= newestPrivateProfileVersion.get()) {
                        store.save(uid, PrivateProfileSnapshot(profile, updatedAt))
                    }
                }
            }
                .onFailure { session.showCloudError("Profile backup failed. Your changes are saved on this phone.") }
        }
    }

}
