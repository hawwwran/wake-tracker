package com.hawwwran.waketracker.data

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.hawwwran.waketracker.WakeTrackerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reads the usage log every few hours, so the record keeps up while the app is not opened.
 *
 * Nothing depends on it running on time, or at all for days: the system keeps the log for
 * about ten days and any later read catches up. A phone that holds background work back
 * (vivo does) only makes the record a little stale until the app is opened.
 */
class SyncJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as WakeTrackerApp
        app.scope.launch(Dispatchers.IO) {
            app.readLog()
            jobFinished(params, false)
        }
        return true
    }

    // A read takes a moment and cannot be interrupted halfway; the next period reads again.
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val ID = 1
        private const val EVERY = 3 * 60 * 60 * 1000L

        /** Safe to call on every start: a job that is already scheduled as wanted is left alone. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val pending = scheduler.getPendingJob(ID)
            if (pending != null && pending.isPeriodic && pending.intervalMillis == EVERY && pending.isPersisted) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, SyncJob::class.java))
                    .setPeriodic(EVERY)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}

/**
 * After a restart and after an update of the app. Starting the process is all it is for:
 * WakeTrackerApp.onCreate schedules the job again (an update drops it) and reads the log.
 */
class BootReceiver : BroadcastReceiver() {
    // Lint wants the action checked. There is nothing here a forged broadcast could set off,
    // and the receiver is not exported.
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) = Unit
}
