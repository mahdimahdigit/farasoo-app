package ir.farasoo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat

class FarasooVpnService : VpnService() {

    companion object {
        const val ACTION_START = "ir.farasoo.app.START_VPN"
        const val ACTION_STOP = "ir.farasoo.app.STOP_VPN"
        const val CHANNEL_ID = "farasoo_vpn_channel"
        const val NOTIFICATION_ID = 1001
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        createNotificationChannel()

        // اعلان دائمی
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        // ساخت tun با یک route جعلی (TEST-NET-2 - هرگز در شبکه‌های واقعی استفاده نمی‌شه)
        val builder = Builder()
            .setSession("Farasoo")
            .addAddress("10.99.99.1", 32)
            .addRoute("198.51.100.0", 24)
            .addDnsServer("1.1.1.1")

        // تنظیم MTU
        builder.setMtu(1500)

        // اینترفیس رو بساز
        tunInterface = try {
            builder.establish()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if (tunInterface == null) {
            stopSelf()
            return
        }

        isRunning = true
        // VPN فقط "باز" می‌مونه - نیازی به forward ترافیک نیست
        // Kill Switch سیستمی، وقتی VPN قطع شه همه چیز رو بلاک می‌کنه
    }

    private fun stopVpn() {
        isRunning = false
        try {
            tunInterface?.close()
        } catch (e: Exception) {
            // ignore
        }
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
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
            this,
            0,
            intent,
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
