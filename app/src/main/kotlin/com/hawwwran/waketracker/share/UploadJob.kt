package com.hawwwran.waketracker.share

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.hawwwran.waketracker.WakeTrackerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The second try of an upload that did not reach the relay. Android holds the job until
 * there is a connection, also across a restart, and runs it then; a failure there is
 * tried again with growing pauses. Every read of the log also tries, so this only shortens
 * the wait.
 */
class UploadJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as WakeTrackerApp
        app.scope.launch(Dispatchers.IO) {
            val result = app.sharing.push()
            jobFinished(params, result is Uploader.Result.Failed)
        }
        return true
    }

    // Stopped because the connection went: what was not sent is still pending, so run again.
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val ID = 2
        private const val FIRST_RETRY_AFTER = 5 * 60 * 1000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // Scheduling over a job that is running would stop it.
            if (scheduler.getPendingJob(ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(ID, ComponentName(context, UploadJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(FIRST_RETRY_AFTER, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
