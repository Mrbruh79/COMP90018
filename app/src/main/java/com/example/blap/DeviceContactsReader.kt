package com.example.blap

import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import com.example.blap.chat.DeviceContact
import com.example.blap.chat.ContactIdentity
import com.example.blap.chat.PhoneIdentity
import java.util.Locale

object DeviceContactsReader {
    fun read(context: Context): List<DeviceContact> {
        val contacts = linkedMapOf<Long, DeviceContact>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(projection[0])
            val nameIndex = cursor.getColumnIndexOrThrow(projection[1])
            val numberIndex = cursor.getColumnIndexOrThrow(projection[2])
            val normalizedIndex = cursor.getColumnIndexOrThrow(projection[3])
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val rawNumber = cursor.getString(normalizedIndex)
                    ?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(numberIndex).orEmpty()
                val international = if (rawNumber.trim().startsWith("+") || rawNumber.trim().startsWith("00")) {
                    rawNumber
                } else {
                    PhoneNumberUtils.formatNumberToE164(rawNumber, Locale.getDefault().country) ?: continue
                }
                val normalized = PhoneIdentity.normalize(international) ?: continue
                if (name.isNotBlank() && id !in contacts) contacts[id] = DeviceContact(name, normalized)
            }
        }
        val emailProjection = arrayOf(
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Email.ADDRESS,
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            emailProjection,
            null,
            null,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(emailProjection[0])
            val nameIndex = cursor.getColumnIndexOrThrow(emailProjection[1])
            val emailIndex = cursor.getColumnIndexOrThrow(emailProjection[2])
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val email = ContactIdentity.normalizeEmail(cursor.getString(emailIndex).orEmpty()) ?: continue
                if (name.isBlank()) continue
                val previous = contacts[id]
                contacts[id] = if (previous == null) DeviceContact(name, "", email)
                    else previous.copy(email = previous.email.ifBlank { email })
            }
        }
        return contacts.values.toList()
    }
}
