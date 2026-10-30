package `in`.yashd.bitchat.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import `in`.yashd.bitchat.MainActivity
import `in`.yashd.bitchat.R
import `in`.yashd.bitchat.attachments.transferNotificationText
import `in`.yashd.bitchat.mesh.MeshRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        MeshRuntime.initialize(applicationContext)
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification())
        MeshRuntime.start()
        // Mirror live transfer progress into the ongoing notification so BLE file
        // transfers stay visible and keep running while the app is in the background.
        scope.launch {
            MeshRuntime.fileTransfers
                .map { transferNotificationText(it) }
                .distinctUntilChanged()
                .collect { update ->
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, notification(update))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        MeshRuntime.start()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        MeshRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(transfer: Pair<String, Int?>? = null): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Relay Nearby is on")
            .setContentText(transfer?.first ?: "Scanning, advertising, and keeping nearby links alive.")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .addAction(
                R.mipmap.ic_launcher,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, MeshForegroundService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        transfer?.second?.let { builder.setProgress(100, it, false) }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Relay Nearby",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mesh"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "in.yashd.bitchat.STOP_MESH"
    }
}
