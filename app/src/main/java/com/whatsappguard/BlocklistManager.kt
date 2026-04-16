package com.whatsappguard

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object BlocklistManager {

    private const val PREF_BLOCKLIST = "phone_blocklist"
    private const val PREF_CALL_LOG = "blocked_call_log"
    private const val PREF_BLOCKING_ENABLED = "phone_blocking_enabled"

    private fun getPrefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun isBlockingEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(PREF_BLOCKING_ENABLED, true)

    fun setBlockingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_BLOCKING_ENABLED, enabled).apply()
    }

    fun getBlockedNumbers(context: Context): MutableList<String> {
        val json = getPrefs(context).getString(PREF_BLOCKLIST, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    fun addNumber(context: Context, number: String) {
        val normalized = normalizeNumber(number)
        if (normalized.isEmpty()) return
        val list = getBlockedNumbers(context)
        if (!list.any { normalizeNumber(it) == normalized }) {
            list.add(number.trim())
            saveBlockedNumbers(context, list)
        }
    }

    fun removeNumber(context: Context, number: String) {
        val list = getBlockedNumbers(context)
        list.remove(number)
        saveBlockedNumbers(context, list)
    }

    fun isNumberBlocked(context: Context, number: String): Boolean {
        val normalized = normalizeNumber(number)
        if (normalized.isEmpty()) return false
        return getBlockedNumbers(context).any { stored ->
            val storedNorm = normalizeNumber(stored)
            storedNorm == normalized ||
                (normalized.length >= 9 && storedNorm.endsWith(normalized.takeLast(9))) ||
                (storedNorm.length >= 9 && normalized.endsWith(storedNorm.takeLast(9)))
        }
    }

    fun logBlockedCall(context: Context, number: String) {
        val log = getCallLogRaw(context)
        val entry = JSONObject().apply {
            put("number", number)
            put("timestamp", System.currentTimeMillis())
        }
        log.put(entry)
        // Manter últimas 200 entradas
        while (log.length() > 200) {
            log.remove(0)
        }
        getPrefs(context).edit().putString(PREF_CALL_LOG, log.toString()).apply()
    }

    private fun getCallLogRaw(context: Context): JSONArray {
        val json = getPrefs(context).getString(PREF_CALL_LOG, "[]") ?: "[]"
        return JSONArray(json)
    }

    fun getCallLogFormatted(context: Context): List<Pair<String, String>> {
        val log = getCallLogRaw(context)
        val list = mutableListOf<Pair<String, String>>()
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        for (i in log.length() - 1 downTo 0) {
            val entry = log.getJSONObject(i)
            val number = entry.getString("number")
            val time = sdf.format(Date(entry.getLong("timestamp")))
            list.add(Pair(number, time))
        }
        return list
    }

    fun getLogCount(context: Context): Int = getCallLogRaw(context).length()

    fun clearLog(context: Context) {
        getPrefs(context).edit().putString(PREF_CALL_LOG, "[]").apply()
    }

    private fun saveBlockedNumbers(context: Context, list: List<String>) {
        val array = JSONArray()
        list.forEach { array.put(it) }
        getPrefs(context).edit().putString(PREF_BLOCKLIST, array.toString()).apply()
    }

    private fun normalizeNumber(number: String): String =
        number.replace(Regex("[^0-9+]"), "")
}
