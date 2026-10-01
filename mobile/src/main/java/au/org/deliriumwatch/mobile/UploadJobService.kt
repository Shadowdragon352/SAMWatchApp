package au.org.deliriumwatch.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import java.util.concurrent.Executors

class UploadJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        executor.execute { Uploader.drain(this); jobFinished(params, false) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        private const val JOB_ID = 21941
        fun schedule(context: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, UploadJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        }
    }
}
