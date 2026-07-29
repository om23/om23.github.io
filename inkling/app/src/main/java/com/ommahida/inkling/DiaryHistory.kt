package com.ommahida.inkling

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists diary exchanges so they survive app restarts and can be read back in the history menu,
 * and so the diary can remember recent conversation across sessions. Stored as a JSON file in
 * app-private internal storage (not world-readable, never on external storage).
 */
class DiaryHistory(context: Context) {

    data class Entry(val time: Long, val written: String, val reply: String)

    private val file = File(context.filesDir, FILE)

    /** Append one exchange, trimming the store to the most recent [MAX] entries. */
    fun append(written: String, reply: String) {
        try {
            val entries = load().toMutableList()
            entries.add(Entry(System.currentTimeMillis(), written, reply))
            while (entries.size > MAX) entries.removeAt(0)
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject().put("t", e.time).put("w", e.written).put("r", e.reply))
            }
            file.writeText(arr.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "append failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** All stored exchanges, oldest first. */
    fun load(): List<Entry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.optLong("t"), o.optString("w"), o.optString("r"))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        }
    }

    /** The most recent [n] exchanges as (written, reply) pairs, for seeding the diary's memory. */
    fun recentPairs(n: Int): List<Pair<String, String>> =
        load().takeLast(n).map { it.written to it.reply }

    fun clear() {
        try {
            file.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "clear failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "InklingHistory"
        const val FILE = "diary_history.json"
        const val MAX = 500
    }
}
