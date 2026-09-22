package app.foscal.notifications

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnoozeIntentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun snoozedAlarmTargetsTheReminderReceiver() {
        val source = Intent(context, ReminderAlarmReceiver::class.java)
            .putExtra(AlarmReminderScheduler.EXTRA_EVENT_ID, 42L)

        val intent = ReminderAlarmReceiver.snoozedFireIntent(context, source)

        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(ReminderAlarmReceiver::class.java.name, intent.component?.className)
        assertEquals(42L, intent.getLongExtra(AlarmReminderScheduler.EXTRA_EVENT_ID, -1L))
    }

    @Test
    fun snoozedAlarmResolvesToADeclaredReceiver() {
        val intent = ReminderAlarmReceiver.snoozedFireIntent(context, Intent())

        // The int overload, because the flags object only exists from API 33 and minSdk is 26.
        @Suppress("DEPRECATION")
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)

        assertEquals(
            listOf(ReminderAlarmReceiver::class.java.name),
            receivers.map { it.activityInfo.name },
        )
    }
}
