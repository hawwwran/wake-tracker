package com.hawwwran.waketracker.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import androidx.core.net.toUri

/** "Usage access", the special permission behind Android's usage log. The owner grants it in the system settings. */
object UsageAccess {

    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            // Some Androids open the app's own switch when given its package, others have no
            // such page and only know the list of all apps.
            context.startActivity(Intent(list).setData("package:${context.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(list)
        }
    }

    /**
     * Android's page about this app. From Android 13 on, an app installed from a file has
     * its usage access switch greyed out ("restricted setting") until the owner lifts that
     * on this page: the menu at the top right, "Allow restricted settings". The entry shows
     * up there only after the greyed-out switch was tapped once.
     */
    fun openAppPage(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

class SystemUsageLog(private val context: Context) : UsageLog {

    // Before the first unlock after a restart the log answers with nothing.
    override fun readable(): Boolean =
        UsageAccess.granted(context) && context.getSystemService(UserManager::class.java).isUserUnlocked

    override fun read(from: Long, until: Long, onEvent: (ts: Long, type: Int) -> Unit) {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val event = UsageEvents.Event()
        // A day at a time: the whole answer of a query crosses to this process in one piece.
        var start = from
        while (start < until) {
            val end = minOf(start + DAY, until)
            val events: UsageEvents? = manager.queryEvents(start, end)
            while (events != null && events.getNextEvent(event)) onEvent(event.timeStamp, event.eventType)
            start = end
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}
