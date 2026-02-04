package com.callrecorder.app.data.repository

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getContactName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null

        val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
            .appendPath(phoneNumber)
            .build()

        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, projection, null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }

        return null
    }

    fun formatPhoneNumber(phoneNumber: String): String {
        // Remove all non-digit characters except +
        val cleaned = phoneNumber.replace(Regex("[^+\\d]"), "")
        return if (cleaned.isNotEmpty()) cleaned else phoneNumber
    }
}
