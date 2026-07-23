package com.prismai.llmhost.tools

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only data connectors for contacts, calendar, and SMS.
 * ALL operations are CONFIRM-gated — the model must request and user must approve.
 * Results include an "untrusted_data" marker for the model.
 *
 * Privacy architecture: never store sensitive data in transcripts.
 * Only return the requested fields, not the raw cursor.
 */
class DataConnectorTools(private val context: Context) {

    companion object {
        /** Permissions required for each connector category. */
        val CONTACTS_PERMISSION = android.Manifest.permission.READ_CONTACTS
        val CALENDAR_PERMISSION = android.Manifest.permission.READ_CALENDAR
        val SMS_PERMISSION = android.Manifest.permission.READ_SMS

        private val DATE_FORMAT = SimpleDateFormat("EEE MMM dd h:mm a", Locale.US)

        /** Format a timestamp as a human-readable string. */
        fun formatDate(millis: Long): String =
            DATE_FORMAT.format(Date(millis))
    }

    /** Check which data connector permissions are granted. */
    fun grantedPermissions(): Set<String> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        return setOfNotNull(
            CONTACTS_PERMISSION.takeIf {
                pm.checkPermission(it, selfPkg) == PackageManager.PERMISSION_GRANTED
            },
            CALENDAR_PERMISSION.takeIf {
                pm.checkPermission(it, selfPkg) == PackageManager.PERMISSION_GRANTED
            },
            SMS_PERMISSION.takeIf {
                pm.checkPermission(it, selfPkg) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    /** Check whether a specific permission is granted. */
    fun hasPermission(permission: String): Boolean =
        context.packageManager.checkPermission(permission, context.packageName) == PackageManager.PERMISSION_GRANTED

    /**
     * Search contacts by name. Returns list of matches with minimal fields.
     * Uses CONTENT_FILTER_URI for fast prefix/name matching.
     * Throws SecurityException if READ_CONTACTS not granted.
     */
    fun searchContacts(query: String, maxResults: Int = 10): List<ContactResult> {
        val uri = ContactsContract.Contacts.CONTENT_FILTER_URI.buildUpon()
            .appendPath(query)
            .appendQueryParameter(ContactsContract.LIMIT_PARAM_KEY, maxResults.toString())
            .build()

        val projection = arrayOf(
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts._ID,
        )

        val results = mutableListOf<ContactResult>()
        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC",
        )
        cursor?.use { c ->
            val nameIdx = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIdx = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val lookupIdx = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val idIdx = c.getColumnIndex(ContactsContract.Contacts._ID)

            while (c.moveToNext() && results.size < maxResults) {
                val name = c.getString(nameIdx) ?: continue
                val hasPhone = c.getInt(hasPhoneIdx) > 0
                val lookupKey = c.getString(lookupIdx) ?: ""
                val contactId = c.getLong(idIdx)

                // Check for email via the email table
                val hasEmail = hasEmailAddress(contactId)

                results.add(
                    ContactResult(
                        name = name,
                        hasPhone = hasPhone,
                        hasEmail = hasEmail,
                        lookupKey = lookupKey,
                    )
                )
            }
        }
        return results
    }

    /**
     * Check whether a contact has at least one email address.
     */
    private fun hasEmailAddress(contactId: Long): Boolean {
        val emailCursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email._ID),
            ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
            arrayOf(contactId.toString()),
            null,
        )
        return emailCursor?.use { it.count > 0 } ?: false
    }

    /**
     * Get calendar events for a date range.
     * Uses CalendarContract.Instances for efficient date-range queries.
     * Throws SecurityException if READ_CALENDAR not granted.
     */
    fun getCalendarEvents(
        startMillis: Long = System.currentTimeMillis(),
        endMillis: Long = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L,
        maxResults: Int = 20,
    ): List<CalendarEventResult> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendEncodedPath(startMillis.toString())
            .appendEncodedPath(endMillis.toString())
            .build()

        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )

        val results = mutableListOf<CalendarEventResult>()
        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            CalendarContract.Instances.BEGIN + " ASC",
        )
        cursor?.use { c ->
            val titleIdx = c.getColumnIndex(CalendarContract.Instances.TITLE)
            val beginIdx = c.getColumnIndex(CalendarContract.Instances.BEGIN)
            val endIdx = c.getColumnIndex(CalendarContract.Instances.END)
            val allDayIdx = c.getColumnIndex(CalendarContract.Instances.ALL_DAY)
            val locationIdx = c.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

            while (c.moveToNext() && results.size < maxResults) {
                val title = c.getString(titleIdx) ?: continue
                val begin = c.getLong(beginIdx)
                val end = c.getLong(endIdx)
                val isAllDay = c.getInt(allDayIdx) != 0
                val location = c.getString(locationIdx)

                results.add(
                    CalendarEventResult(
                        title = title,
                        startMillis = begin,
                        endMillis = end,
                        isAllDay = isAllDay,
                        location = location,
                    )
                )
            }
        }
        return results
    }

    /**
     * List recent SMS conversation threads (metadata only — snippets, not full messages).
     * Returns the address, a short snippet of the last message, message count, and date.
     * Throws SecurityException if READ_SMS not granted.
     */
    fun listSmsThreads(limit: Int = 10): List<SmsThreadResult> {
        val uri = Telephony.Sms.Conversations.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.Conversations.ADDRESS,
            Telephony.Sms.Conversations.SNIPPET,
            Telephony.Sms.Conversations.MESSAGE_COUNT,
            Telephony.Sms.Conversations.DATE,
        )
        val results = mutableListOf<SmsThreadResult>()
        val cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            Telephony.Sms.Conversations.DATE + " DESC",
        )
        cursor?.use { c ->
            val addrIdx = c.getColumnIndex(Telephony.Sms.Conversations.ADDRESS)
            val snippetIdx = c.getColumnIndex(Telephony.Sms.Conversations.SNIPPET)
            val countIdx = c.getColumnIndex(Telephony.Sms.Conversations.MESSAGE_COUNT)
            val dateIdx = c.getColumnIndex(Telephony.Sms.Conversations.DATE)

            while (c.moveToNext() && results.size < limit) {
                val address = c.getString(addrIdx) ?: "unknown"
                val snippet = c.getString(snippetIdx)?.take(60) ?: ""
                val msgCount = c.getInt(countIdx)
                val dateMillis = c.getLong(dateIdx)

                results.add(
                    SmsThreadResult(
                        address = address,
                        snippet = snippet,
                        messageCount = msgCount,
                        dateMillis = dateMillis,
                    )
                )
            }
        }
        return results
    }

}

/**
 * Result from a contacts search — minimal fields, privacy-conscious.
 */
data class ContactResult(
    val name: String,
    val hasPhone: Boolean,
    val hasEmail: Boolean,
    val lookupKey: String,
)

/**
 * Result from a calendar events query — minimal fields, privacy-conscious.
 */
data class CalendarEventResult(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val location: String?,
)

/**
 * Result from an SMS thread listing — metadata only, not message content.
 */
data class SmsThreadResult(
    val address: String,
    val snippet: String,
    val messageCount: Int,
    val dateMillis: Long,
)
