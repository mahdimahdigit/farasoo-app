package ir.farasoo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") return

        Log.d("BootReceiver", "Boot completed, checking WiFi")

        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo?.ssid?.trim('"') ?: ""

            val prefs = context.getSharedPreferences("farasoo", Context.MODE_PRIVATE)
            val userId = prefs.getInt("user_id", -1)
            if (userId <= 0) return

            if (ssid == WifiChangeReceiver.TARGET_SSID) {
                val vpnIntent = Intent(context, FarasooVpnService::class.java).apply {
                    action = FarasooVpnService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "error", e)
        }
    }
}
