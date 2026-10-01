package au.org.deliriumwatch.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

class UploadJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var work: Future<*>? = null
    private var generation = 0
    override fun onStartJob(params: JobParameters): Boolean {
        val current = ++generation
        work = executor.submit {
            val result = Uploader.drain(applicationContext)
            handler.post {
                if (current == generation) jobFinished(params, !result.ok && PhonePrefs(this).serverUrl.isNotBlank())
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        generation++
        work?.cancel(true)
        return true
    }
    override fun onDestroy() {
        generation++; work?.cancel(true); executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
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
