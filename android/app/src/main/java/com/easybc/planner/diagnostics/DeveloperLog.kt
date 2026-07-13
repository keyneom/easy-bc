package com.easybc.planner.diagnostics

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

@Serializable
data class DeveloperLogEntry(
    val timestamp: String,
    val area: String,
    val event: String,
    val details: Map<String, String> = emptyMap(),
)

fun formatDeveloperLogEntries(entries: List<DeveloperLogEntry>): String =
    if (entries.isEmpty()) {
        "No diagnostic events recorded."
    } else {
        entries.joinToString("\n") { entry ->
            val details = entry.details.entries.joinToString(" ") { (key, value) ->
                "$key=$value"
            }
            "${entry.timestamp} [${entry.area}] ${entry.event}" +
                if (details.isBlank()) "" else " $details"
        }
    }

class DeveloperLog(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun append(
        area: String,
        event: String,
        details: Map<String, Any?> = emptyMap(),
    ) {
        synchronized(lock) {
            val next = entriesInternal().plus(
                DeveloperLogEntry(
                    timestamp = Instant.now().toString(),
                    area = area,
                    event = event,
                    details = details.mapNotNull { (key, value) ->
                        value?.let { key to clean(it) }
                    }.toMap(),
                ),
            ).takeLast(MAX_ENTRIES)
            preferences.edit().putString(
                ENTRIES_KEY,
                json.encodeToString(ListSerializer(DeveloperLogEntry.serializer()), next),
            ).apply()
        }
    }

    fun entries(): List<DeveloperLogEntry> = synchronized(lock) { entriesInternal() }

    fun clear() {
        synchronized(lock) {
            preferences.edit().remove(ENTRIES_KEY).apply()
        }
    }

    fun formatted(entries: List<DeveloperLogEntry> = entries()): String =
        formatDeveloperLogEntries(entries)

    private fun entriesInternal(): List<DeveloperLogEntry> {
        val raw = preferences.getString(ENTRIES_KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(DeveloperLogEntry.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun clean(value: Any): String {
        val text = if (value is Throwable) {
            "${value::class.simpleName}: ${value.message.orEmpty()}"
        } else {
            value.toString()
        }
        return text.replace(Regex("\\s+"), " ").trim().take(500)
    }

    private companion object {
        const val PREFERENCES_NAME = "easy-bc-developer-log"
        const val ENTRIES_KEY = "entries"
        const val MAX_ENTRIES = 100
        val json = Json { ignoreUnknownKeys = true }
        val lock = Any()
    }
}
