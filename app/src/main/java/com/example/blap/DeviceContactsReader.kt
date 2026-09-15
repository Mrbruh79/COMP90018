package com.example.blap

import android.content.Context
import android.provider.ContactsContract
import com.example.blap.chat.DeviceContact
import com.example.blap.chat.PhoneIdentity

object DeviceContactsReader {
    fun read(context: Context): List<DeviceContact> {
        val contacts = linkedMapOf<String, DeviceContact>()
        val projection = arrayOf(
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
            val nameIndex = cursor.getColumnIndexOrThrow(projection[0])
            val numberIndex = cursor.getColumnIndexOrThrow(projection[1])
            val normalizedIndex = cursor.getColumnIndexOrThrow(projection[2])
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val rawNumber = cursor.getString(normalizedIndex)
                    ?.takeIf { it.isNotBlank() }
                    ?: cursor.getString(numberIndex).orEmpty()
                val normalized = PhoneIdentity.normalize(rawNumber) ?: continue
                val hash = PhoneIdentity.hash(normalized) ?: continue
                if (name.isNotBlank()) contacts[hash] = DeviceContact(name, normalized)
            }
        }
        return contacts.values.toList()
    }
}
