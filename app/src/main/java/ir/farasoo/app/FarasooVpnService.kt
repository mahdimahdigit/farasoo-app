package ir.farasoo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FarasooVpnService : VpnService() {

    companion object {
        const val ACTION_START = "ir.farasoo.app.START_VPN"
        const val ACTION_STOP = "ir.farasoo.app.STOP_VPN"
        const val CHANNEL_ID = "farasoo_vpn_channel"
        const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning = false
            private set
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var forwarder: PacketForwarder? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Log.d("FarasooVpn", "Already running")
            return
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        val builder = Builder()
            .setSession("Farasoo")
            .addAddress("10.99.99.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setBlocking(false)
            .setMtu(1500)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w("FarasooVpn", "addDisallowedApplication failed", e)
        }

        tunInterface = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e("FarasooVpn", "establish failed", e)
            null
        }

        if (tunInterface == null) {
            Log.e("FarasooVpn", "tunInterface is null")
            stopSelf()
            return
        }

        isRunning = true
        Log.d("FarasooVpn", "VPN started, fd=${tunInterface!!.fd}")

        try {
            forwarder = PacketForwarder(this, tunInterface!!)
            forwarder?.start()
            Log.d("FarasooVpn", "PacketForwarder started")
        } catch (e: Exception) {
            Log.e("FarasooVpn", "PacketForwarder start failed", e)
        }

        scope.launch {
            val prefs = getSharedPreferences("farasoo", MODE_PRIVATE)
            val userId = prefs.getInt("user_id", -1)
            if (userId > 0) ApiClient.reportVpnStatus(userId, true)
        }

        startHeartbeat()
    }

    private fun stopVpn() {
        heartbeatJob?.cancel()
        heartbeatJob = null

        try {
            forwarder?.stop()
        } catch (e: Exception) {
            Log.e("FarasooVpn", "forwarder stop failed", e)
        }
        forwarder = null

        if (!isRunning) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        isRunning = false
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            // ignore
        }
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d("FarasooVpn", "VPN stopped")

        scope.launch {
            val prefs = getSharedPreferences("farasoo", MODE_PRIVATE)
            val userId = prefs.getInt("user_id", -1)
            if (userId > 0) ApiClient.reportVpnStatus(userId, false)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val prefs = getSharedPreferences("farasoo", MODE_PRIVATE)
                    val userId = prefs.getInt("user_id", -1)
                    if (userId > 0) {
                        ApiClient.sendHeartbeat(userId, true, true, "Farasoo.Space")
                    }
                } catch (e: Exception) {
                    Log.e("Heartbeat", "error", e)
                }
                delay(30_000)
            }
        }
    }

    override fun onRevoke() {
        Log.d("FarasooVpn", "VPN revoked")
        val prefs = getSharedPreferences("farasoo", MODE_PRIVATE)
        val userId = prefs.getInt("user_id", -1)
        if (userId > 0) {
            scope.launch { ApiClient.reportVpnRevoked(userId) }
        }
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "فراسو VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "اتصال VPN فراسو"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, DashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("فراسو فعال است")
            .setContentText("اینترنت شما از طریق فراسو محافظت می‌شود")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
