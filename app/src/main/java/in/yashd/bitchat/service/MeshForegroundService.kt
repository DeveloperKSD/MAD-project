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
import `in`.yashd.bitchat.mesh.MeshRuntime

class MeshForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        MeshRuntime.initialize(applicationContext)
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification())
        MeshRuntime.start()
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
        MeshRuntime.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("bitchat mesh running")
        .setContentText("Scanning, advertising, and keeping nearby links alive.")
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
        .build()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "bitchat mesh",
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
