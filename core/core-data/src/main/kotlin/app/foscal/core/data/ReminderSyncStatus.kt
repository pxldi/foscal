package app.foscal.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reminderStatusStore by preferencesDataStore(name = "foscal_reminder_status")

/**
 * Last known result of a reminder sync.
 *
 * Reminder scheduling is invisible by design — when it works the user sees notifications, and when
 * it silently stops they have no way to tell whether the app is broken, the OS killed it, or they
 * simply have no events. Recording each pass gives the "Not getting reminders?" diagnostics screen
 * something factual to show, and gives a bug report something better than "it didn't notify me".
 *
 * Kept separate from [UserPreferencesRepository] because this is written by a background worker on
 * every sync, and it is not a user preference — a settings backup should not restore it.
 */
@Singleton
class ReminderSyncStatus @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    enum class Outcome {
        /** Alarms were re-armed from a successful provider read. */
        Success,

        /** The provider could not be read; the previously armed alarms were deliberately left alone. */
        ReadFailed,

        /** Calendar permission is not granted, so there was nothing to read. */
        NoPermission,

        ;

        companion object {
            fun fromKey(key: String?): Outcome =
                entries.firstOrNull { it.name == key } ?: Success
        }
    }

    data class Snapshot(
        val lastSyncAt: Instant?,
        val outcome: Outcome,
        val alarmsArmed: Int,
    )

    val snapshot: Flow<Snapshot> = context.reminderStatusStore.data.map { prefs ->
        Snapshot(
            lastSyncAt = prefs[LAST_SYNC_AT]?.let(Instant::ofEpochMilli),
            outcome = Outcome.fromKey(prefs[LAST_OUTCOME]),
            alarmsArmed = prefs[ALARMS_ARMED] ?: 0,
        )
    }

    suspend fun record(outcome: Outcome, armed: Int) {
        context.reminderStatusStore.edit { prefs ->
            prefs[LAST_SYNC_AT] = System.currentTimeMillis()
            prefs[LAST_OUTCOME] = outcome.name
            prefs[ALARMS_ARMED] = armed
        }
    }

    private companion object {
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_OUTCOME = stringPreferencesKey("last_outcome")
        val ALARMS_ARMED = intPreferencesKey("alarms_armed")
    }
}
