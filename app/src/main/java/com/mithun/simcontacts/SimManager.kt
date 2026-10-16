package com.mithun.simcontacts

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.SimPhonebookContract
import android.provider.SimPhonebookContract.ElementaryFiles
import android.provider.SimPhonebookContract.SimRecords
import android.telephony.SubscriptionManager
import java.util.Collections

/**
 * Reads and writes contacts on the SIM card (the ADN phonebook).
 *  - Android 12+ : official SimPhonebookContract API
 *  - Older / OEM : the classic content://icc/adn provider (works on almost every phone)
 * If the modern API fails on a device, the legacy provider is tried automatically.
 */
class SimManager(private val context: Context) {

    data class SimCard(val subId: Int, val slotIndex: Int, val label: String)
    data class SimInfo(val maxRecords: Int, val used: Int, val nameMaxLen: Int, val numberMaxLen: Int)
    data class SimEntry(val name: String, val number: String)

    private val resolver = context.contentResolver
    private val _errors = Collections.synchronizedList(ArrayList<String>())
    val errors: List<String> get() = synchronized(_errors) { _errors.toList() }
    val lastError: String? get() = synchronized(_errors) { _errors.lastOrNull() }

    private fun addError(msg: String?) {
        if (!msg.isNullOrBlank()) {
            _errors.add(msg)
        }
    }

    companion object {
        /** Number key used to detect duplicates (last 9 digits ignores +94 / 0 prefixes). */
        fun key(number: String): String = number.filter { it.isDigit() }.takeLast(9)
        private const val LEGACY_NAME_BYTES = 14
        private const val LEGACY_NUMBER_DIGITS = 20
    }

    private fun hasPhoneState() = Build.VERSION.SDK_INT < 23 ||
        context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun getSims(): List<SimCard> {
        val list = ArrayList<SimCard>()
        if (Build.VERSION.SDK_INT >= 22 && hasPhoneState()) {
            try {
                val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                sm?.activeSubscriptionInfoList?.forEach { info ->
                    val carrier = (info.displayName ?: info.carrierName ?: "").toString()
                    list.add(SimCard(info.subscriptionId, info.simSlotIndex, "SIM ${info.simSlotIndex + 1}  ·  $carrier"))
                }
            } catch (e: Exception) {
                addError(e.message)
            }
        }
        if (list.isEmpty()) {
            val subId = if (Build.VERSION.SDK_INT >= 24) SubscriptionManager.getDefaultSubscriptionId() else -1
            list.add(SimCard(subId, 0, "Default SIM"))
        }
        return list.sortedBy { it.slotIndex }
    }

    private fun modernUri(sim: SimCard): Uri? =
        if (Build.VERSION.SDK_INT >= 31 && sim.subId >= 0)
            SimRecords.getContentUri(sim.subId, ElementaryFiles.EF_ADN)
        else null

    private fun legacyUris(sim: SimCard): List<Uri> {
        val l = ArrayList<Uri>()
        if (sim.subId >= 0) l.add(Uri.parse("content://icc/adn/subId/${sim.subId}"))
        l.add(Uri.parse("content://icc/adn"))
        return l
    }

    /** Capacity info (Android 12+ only). Returns null when the phone doesn't report it. */
    fun getInfo(sim: SimCard): SimInfo? {
        if (Build.VERSION.SDK_INT >= 31 && sim.subId >= 0) {
            try {
                resolver.query(
                    ElementaryFiles.getItemUri(sim.subId, ElementaryFiles.EF_ADN),
                    arrayOf(
                        ElementaryFiles.MAX_RECORDS, ElementaryFiles.RECORD_COUNT,
                        ElementaryFiles.NAME_MAX_LENGTH, ElementaryFiles.PHONE_NUMBER_MAX_LENGTH
                    ), null, null, null
                )?.use { c ->
                    if (c.moveToFirst() && c.getInt(0) > 0)
                        return SimInfo(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
                }
            } catch (e: Exception) {
                addError(e.message)
            }
        }
        return null
    }

    fun readSimContacts(sim: SimCard): List<SimEntry> {
        modernUri(sim)?.let { uri ->
            try {
                resolver.query(uri, arrayOf(SimRecords.NAME, SimRecords.PHONE_NUMBER), null, null, null)?.use { c ->
                    val out = ArrayList<SimEntry>()
                    while (c.moveToNext()) out.add(SimEntry(c.getString(0).orEmpty(), c.getString(1).orEmpty()))
                    return out
                }
            } catch (e: Exception) {
                addError(e.message)
            }
        }
        for (uri in legacyUris(sim)) {
            try {
                val c = resolver.query(uri, null, null, null, null) ?: continue
                c.use {
                    var ni = it.getColumnIndex("name")
                    if (ni < 0) ni = it.getColumnIndex("tag")
                    val pi = it.getColumnIndex("number")
                    if (pi >= 0) {
                        val out = ArrayList<SimEntry>()
                        while (it.moveToNext()) {
                            out.add(SimEntry(if (ni >= 0) it.getString(ni).orEmpty() else "", it.getString(pi).orEmpty()))
                        }
                        return out
                    }
                }
            } catch (e: Exception) {
                addError(e.message)
            }
        }
        return emptyList()
    }

    /** Writes one contact. Returns true if the SIM accepted it. */
    fun writeContact(sim: SimCard, name: String, number: String, info: SimInfo?): Boolean {
        val maxLen = info?.numberMaxLen?.takeIf { it > 0 } ?: LEGACY_NUMBER_DIGITS
        val num = cleanNumber(number, maxLen)
        if (num.isEmpty()) {
            addError("Invalid number")
            return false
        }

        modernUri(sim)?.let { uri ->
            try {
                val values = ContentValues().apply {
                    put(SimRecords.NAME, fitNameModern(name, info?.nameMaxLen?.takeIf { it > 0 } ?: LEGACY_NAME_BYTES))
                    put(SimRecords.PHONE_NUMBER, num)
                }
                if (resolver.insert(uri, values) != null) return true
            } catch (e: Exception) {
                addError(e.message)
            }
        }

        val legacyName = fitNameLegacy(name, LEGACY_NAME_BYTES)
        for (uri in legacyUris(sim)) {
            try {
                val values = ContentValues().apply {
                    put("tag", legacyName)
                    put("number", num)
                }
                if (resolver.insert(uri, values) != null) return true
            } catch (e: Exception) {
                addError(e.message)
            }
        }
        return false
    }

    /** Deletes one contact from the SIM. */
    fun deleteContact(sim: SimCard, name: String, number: String): Boolean {
        modernUri(sim)?.let { uri ->
            try {
                val where = "${SimRecords.NAME} = ? AND ${SimRecords.PHONE_NUMBER} = ?"
                val deleted = resolver.delete(uri, where, arrayOf(name, number))
                if (deleted > 0) return true
            } catch (e: Exception) {
                addError(e.message)
            }
        }

        for (uri in legacyUris(sim)) {
            try {
                val where = "tag = ? AND number = ?"
                val deleted = resolver.delete(uri, where, arrayOf(name, number))
                if (deleted > 0) return true
            } catch (e: Exception) {
                addError(e.message)
            }
        }

        for (uri in legacyUris(sim)) {
            try {
                val where = "number = ?"
                val deleted = resolver.delete(uri, where, arrayOf(number))
                if (deleted > 0) return true
            } catch (e: Exception) {
                addError(e.message)
            }
        }
        return false
    }

    /** Deletes all contacts from the SIM. Returns the count of deleted contacts. */
    fun deleteAllContacts(sim: SimCard): Int {
        val entries = readSimContacts(sim)
        var deletedCount = 0
        for (entry in entries) {
            if (deleteContact(sim, entry.name, entry.number)) {
                deletedCount++
            }
        }
        return deletedCount
    }

    /** Keeps digits, * and #, and a leading +. SIM numbers can't hold spaces or dashes. */
    private fun cleanNumber(raw: String, maxDigits: Int): String {
        val plus = raw.trim().startsWith("+")
        val effectiveMax = if (plus) (maxDigits - 1).coerceAtLeast(1) else maxDigits
        val digits = raw.filter { it.isDigit() || it == '*' || it == '#' }.take(effectiveMax)
        if (digits.isEmpty()) return ""
        return if (plus) "+$digits" else digits
    }

    private fun fitNameModern(name: String, maxLen: Int): String {
        if (Build.VERSION.SDK_INT < 31) return fitNameLegacy(name, maxLen)
        return try {
            var n = name.trim()
            while (n.isNotEmpty() && SimRecords.getEncodedNameLength(resolver, n) > maxLen) n = n.dropLast(1)
            n.trim()
        } catch (e: Exception) {
            fitNameLegacy(name, maxLen)
        }
    }

    /** English names: 1 byte per letter. Sinhala/Tamil/emoji need UCS-2: 2 bytes + 1 header byte. */
    private fun fitNameLegacy(name: String, maxBytes: Int): String {
        val n = name.trim()
        val ascii = n.all { it.code in 32..126 }
        return (if (ascii) n.take(maxBytes) else n.take((maxBytes - 1) / 2)).trim()
    }
}
