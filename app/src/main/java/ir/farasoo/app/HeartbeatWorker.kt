package ir.farasoo.app

import android.content.Context
import android.net.wifi.WifiManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class HeartbeatWorker(appContext: Context, params: WorkerParameters) :
    Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("farasoo", Context.MODE_PRIVATE)
            val userId = prefs.getInt("user_id", -1)
            if (userId <= 0) return Result.success()

            val wifiManager = applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo?.ssid?.trim('"') ?: ""

            val onFarasooWifi = ssid == WifiChangeReceiver.TARGET_SSID
            val vpnActive = FarasooVpnService.isRunning

            // گزارش heartbeat
            kotlinx.coroutines.runBlocking {
                ApiClient.sendHeartbeat(
                    userId = userId,
                    vpnActive = vpnActive,
                    onFarasooWifi = onFarasooWifi,
                    ssid = ssid
                )
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
