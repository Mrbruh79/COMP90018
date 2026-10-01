package com.example.blap.chat

import kotlinx.coroutines.flow.update

class ProfileIdentity(private val session: MessagingSession) {

    fun checkIdentity(): IdentityCheck {
        val name = session.state.value.displayName.trim()
        val rawPhone = session.state.value.phoneNumber
        val phone = if (rawPhone.isBlank()) "" else PhoneIdentity.normalizeInternational(rawPhone)
        if (name.isBlank() || phone == null) {
            return IdentityCheck.Invalid(
                nameError = "Please enter a display name".takeIf { name.isBlank() },
                phoneError = "Enter a valid phone number with your country code".takeIf { phone == null },
            )
        }
        return IdentityCheck.Valid(name, phone)
    }

    fun saveIdentity(name: String, phone: String) {
        session.identityStore.saveProfile(session.currentProfile().copy(displayName = name, phoneNumber = phone))
        session.localPhoneHash = PhoneIdentity.hash(phone).orEmpty()
        session.state.update {
            it.copy(
                displayName = name,
                phoneNumber = phone,
                error = null,
                nameError = null,
                phoneError = null,
            )
        }
    }

}
