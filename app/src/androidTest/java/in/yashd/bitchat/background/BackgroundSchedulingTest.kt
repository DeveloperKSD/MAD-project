package `in`.yashd.bitchat.background

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BackgroundSchedulingTest {
    @Test fun uniquePeriodicWorkUsesNetworkConstraintKeepsScheduleAndCancels() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val completed = AtomicInteger()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                if (workerClassName != NostrBackgroundWorker::class.java.name) return null
                // Exercise WorkManager scheduling without touching production identity/history or relays.
                return object : Worker(appContext, workerParameters) {
                    override fun doWork(): Result { completed.incrementAndGet(); return Result.success() }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        val name = "background-sync-test-${UUID.randomUUID()}"
        val scheduler = WorkManagerBackgroundSyncScheduler(manager, name)
        try {
            val request = WorkManagerBackgroundSyncScheduler.request()
            assertEquals(TimeUnit.MINUTES.toMillis(15), request.workSpec.intervalDuration)
            assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
            repeat(10) { scheduler.ensureScheduled() }
            val before = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
            assertEquals(1, before.size)
            val id = before.single().id
            val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
            driver.setAllConstraintsMet(id)
            assertEquals(1, completed.get())
            withTimeout(5_000) {
                while (manager.getWorkInfoById(id).get()?.state != WorkInfo.State.ENQUEUED) delay(10)
            }
            driver.setPeriodDelayMet(id)
            driver.setAllConstraintsMet(id) // The test driver resets constraints after each period.
            withTimeout(5_000) { while (completed.get() < 2) delay(10) }
            assertEquals(2, completed.get())
            scheduler.ensureScheduled()
            assertEquals(id, manager.getWorkInfosForUniqueWork(name).get().single().id)
            scheduler.cancel()
            assertEquals(WorkInfo.State.CANCELLED, requireNotNull(manager.getWorkInfoById(id).get()).state)
        } finally { manager.cancelUniqueWork(name).result.get() }
    }
}
