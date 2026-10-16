package com.mithun.simcontacts

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract.CommonDataKinds.Phone

data class PhoneContact(
    val id: Long,
    val name: String,
    val number: String,
    val type: String,
    val source: String,
    var simName: String = name,
    var selected: Boolean = false
)

/** Reads every phone number from the phone's contacts (Google, Samsung, local, etc.). */
object ContactsRepository {

    fun load(context: Context): List<PhoneContact> {
        val resolver = context.contentResolver
        val base = arrayOf(Phone._ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE)
        val cursor: Cursor? = try {
            resolver.query(Phone.CONTENT_URI, base + "account_type", null, null, null)
        } catch (e: Exception) {
            resolver.query(Phone.CONTENT_URI, base, null, null, null)
        }

        val out = ArrayList<PhoneContact>()
        val seen = HashSet<String>()
        cursor?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(Phone._ID)
            val nameIdx = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val numberIdx = c.getColumnIndexOrThrow(Phone.NUMBER)
            val typeIdx = c.getColumnIndexOrThrow(Phone.TYPE)
            val accIdx = c.getColumnIndex("account_type")
            while (c.moveToNext()) {
                val number = c.getString(numberIdx)?.trim().orEmpty()
                if (number.isEmpty()) continue
                val name = c.getString(nameIdx)?.trim().takeUnless { it.isNullOrEmpty() } ?: number
                val key = name.lowercase() + "|" + SimManager.key(number)
                if (!seen.add(key)) continue // same person + same number from 2 accounts
                val account = if (accIdx >= 0) c.getString(accIdx) else null
                out.add(
                    PhoneContact(
                        id = c.getLong(idIdx),
                        name = name,
                        number = number,
                        type = typeLabel(c.getInt(typeIdx)),
                        source = sourceLabel(account)
                    )
                )
            }
        }

        // A SIM entry holds one number. If a person has several numbers, add a suffix.
        // Group by lowercase name, and for groups > 1 append a type initial with counter
        // to disambiguate (e.g. "Amal M1", "Amal M2", "Amal H").
        val groups = out.groupBy { it.name.lowercase() }
        for ((_, contacts) in groups) {
            if (contacts.size <= 1) continue
            // Count occurrences of each type initial within the group
            val initialCounts = HashMap<Char, Int>()
            for (c in contacts) {
                val initial = c.type.firstOrNull()?.uppercaseChar() ?: 'O'
                initialCounts[initial] = (initialCounts[initial] ?: 0) + 1
            }
            // Assign suffixes: if a type initial appears more than once, number them
            val initialCounters = HashMap<Char, Int>()
            for (c in contacts) {
                val initial = c.type.firstOrNull()?.uppercaseChar() ?: 'O'
                val totalForInitial = initialCounts[initial] ?: 1
                if (totalForInitial > 1) {
                    val counter = (initialCounters[initial] ?: 0) + 1
                    initialCounters[initial] = counter
                    c.simName = "${c.name} $initial$counter"
                } else {
                    c.simName = "${c.name} $initial"
                }
            }
        }
        out.sortBy { it.name.lowercase() }
        return out
    }

    private fun typeLabel(t: Int) = when (t) {
        Phone.TYPE_MOBILE -> "Mobile"
        Phone.TYPE_HOME -> "Home"
        Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "Work"
        else -> "Other"
    }

    private fun sourceLabel(accountType: String?): String {
        val a = accountType?.lowercase() ?: return "Phone"
        return when {
            "google" in a -> "Google"
            "sim" in a || "icc" in a -> "SIM"
            "whatsapp" in a -> "WhatsApp"
            "samsung" in a || "sec" in a || "local" in a || "phone" in a -> "Phone"
            else -> a.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}
