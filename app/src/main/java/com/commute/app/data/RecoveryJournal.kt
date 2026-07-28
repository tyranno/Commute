package com.commute.app.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * An append-only, plain-text mirror of every commute event, kept as a safety net *independent of
 * the Room DB*. The Room DB lives under `databases/` and is dropped whole by a destructive
 * migration ([CommuteDatabase] uses [fallbackToDestructiveMigration][androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration]),
 * by corruption, or by a reinstall — the exact way a week of 출퇴근 records vanished before. This
 * file is a separate file in [Context.getFilesDir], untouched by Room and preserved across
 * `adb install -r` and app updates, so a wiped or partially-lost history can be rebuilt from it
 * with [readMissing] + a re-insert (see [CommuteViewModel.recoverFromJournal]).
 *
 * One JSON object per line (JSONL) so an append is O(1) and a single corrupt line can't take the
 * whole log down. Bounded by line count: once it passes [MAX_LINES] it's rewritten keeping only
 * the newest [KEEP_LINES], so it can't grow without limit even after years of daily use.
 *
 * Not a full mirror of every edit — it is a *disaster* backup, matched on content ([journalKey],
 * type+timestamp), not row id. Deletes and time-corrections keep it honest via [remove], but the
 * design goal is "rebuild a lost history", not "reproduce every intermediate state".
 */
class RecoveryJournal(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val lock = Any()

    /** Records one event. Best-effort: a journal write must never break event recording, so any
     * IO failure is swallowed — the DB insert has already happened and is the source of truth. */
    fun append(event: CommuteEvent) {
        synchronized(lock) {
            try {
                file.appendText(encodeJournalLine(event) + "\n")
                trimIfNeeded()
            } catch (e: Exception) {
                // Safety net only — never surface a journal failure to the caller.
            }
        }
    }

    /** Appends any of [events] not already in the journal (matched by [journalKey]). Used to seed
     * the log from an existing DB on first run, and to re-sync after a direct DB edit — additive,
     * so it never removes anything and is safe to run on every startup. */
    fun reconcile(events: List<CommuteEvent>) {
        synchronized(lock) {
            try {
                val known = readEntriesLocked().map { journalKey(it) }.toHashSet()
                val missing = events.filter { journalKey(it) !in known }
                if (missing.isEmpty()) return
                file.appendText(missing.joinToString("") { encodeJournalLine(it) + "\n" })
                trimIfNeeded()
            } catch (e: Exception) {
            }
        }
    }

    /** Drops every journal line whose id or content matches [event] — for a user-initiated delete
     * or the old half of an edit, so a corrected/removed record doesn't linger as "recoverable". */
    fun remove(event: CommuteEvent) {
        synchronized(lock) {
            try {
                if (!file.exists()) return
                val kept = readEntriesLocked().filterNot {
                    (event.id != 0L && it.id == event.id) || journalKey(it) == journalKey(event)
                }
                writeAllLocked(kept)
            } catch (e: Exception) {
            }
        }
    }

    /** Events present in the journal but absent from [current] (matched by [journalKey]) — the
     * records a wipe or partial loss dropped, ready to be re-inserted. */
    fun readMissing(current: List<CommuteEvent>): List<CommuteEvent> {
        val have = current.map { journalKey(it) }.toHashSet()
        val entries = synchronized(lock) {
            try {
                readEntriesLocked()
            } catch (e: Exception) {
                emptyList()
            }
        }
        return entries.filter { journalKey(it) !in have }.map { it.copy(id = 0L) }
    }

    /** Deletes the whole journal — used by the user-initiated data reset, which wipes every record
     * on purpose, so the safety net must be cleared too or [readMissing] would immediately offer to
     * "recover" everything that was just deleted. */
    fun clear() {
        synchronized(lock) {
            try {
                if (file.exists()) file.delete()
            } catch (e: Exception) {
            }
        }
    }

    private fun readEntriesLocked(): List<CommuteEvent> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { parseJournalLine(it) }
    }

    private fun writeAllLocked(events: List<CommuteEvent>) {
        file.writeText(events.joinToString("") { encodeJournalLine(it) + "\n" })
    }

    /** Caps the file so it can't grow unbounded: past [MAX_LINES], keep only the newest
     * [KEEP_LINES]. Append order is roughly chronological, and the cap is generous enough
     * (years of daily commutes) that trimming the oldest lines loses nothing that still matters. */
    private fun trimIfNeeded() {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
    }

    companion object {
        const val FILE_NAME = "commute_journal.jsonl"
        const val MAX_LINES = 4000
        const val KEEP_LINES = 3000
    }
}

/** Stable content key for de-duplicating journal lines against the DB. Row ids don't survive a
 * restore (Room re-autogenerates them), so identity is (type, start timestamp) — unique in
 * practice: two events of the same type never share a millisecond stamp. */
fun journalKey(event: CommuteEvent): String = "${event.type.name}@${event.timestamp}"

/** One JSONL line for [event]. Keys are terse (t/s/ts/e/x/id) because this file is written on every
 * recorded event and kept for years. `id` is stored only to support [RecoveryJournal.remove], and
 * `x` carries [CommuteEvent.excluded] so a recovery doesn't quietly reinstate records the user has
 * taken out of their history. */
fun encodeJournalLine(event: CommuteEvent): String = JSONObject().apply {
    put("id", event.id)
    put("t", event.type.name)
    put("s", event.ssid)
    put("ts", event.timestamp)
    put("e", event.endTimestamp ?: JSONObject.NULL)
    put("x", event.excluded)
}.toString()

/** Parses a line written by [encodeJournalLine]; returns null for a blank or corrupt line so one
 * bad line never fails the whole recovery. */
fun parseJournalLine(line: String): CommuteEvent? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    return try {
        val o = JSONObject(trimmed)
        CommuteEvent(
            id = o.optLong("id", 0L),
            type = CommuteEventType.valueOf(o.getString("t")),
            ssid = o.optString("s", ""),
            timestamp = o.getLong("ts"),
            endTimestamp = if (o.isNull("e")) null else o.getLong("e"),
            // Absent in lines written before exclusion existed — those are all still on record.
            excluded = o.optBoolean("x", false)
        )
    } catch (e: Exception) {
        null
    }
}
