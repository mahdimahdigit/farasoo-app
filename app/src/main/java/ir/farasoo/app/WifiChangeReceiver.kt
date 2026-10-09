package ir.farasoo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class WifiChangeReceiver : BroadcastReceiver() {

    companion object {
        const val TARGET_SSID = "Farasoo.Space"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            val wifiInfo = wifiManager.connectionInfo ?: return
            val ssid = wifiInfo.ssid?.trim('"') ?: ""

            Log.d("WifiReceiver", "WiFi changed: $ssid")

            val prefs = context.getSharedPreferences("farasoo", Context.MODE_PRIVATE)
            val userId = prefs.getInt("user_id", -1)
            if (userId <= 0) return

            if (ssid == TARGET_SSID) {
                // تو پانسیون → VPN روشن
                startVpn(context)
            } else {
                // تو خونه یا جای دیگه → VPN خاموش
                stopVpn(context)
            }
        } catch (e: Exception) {
            Log.e("WifiReceiver", "error", e)
        }
    }

    private fun startVpn(context: Context) {
        if (FarasooVpnService.isRunning) return

        val intent = Intent(context, FarasooVpnService::class.java).apply {
            action = FarasooVpnService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopVpn(context: Context) {
        if (!FarasooVpnService.isRunning) return

        val intent = Intent(context, FarasooVpnService::class.java).apply {
            action = FarasooVpnService.ACTION_STOP
        }
        context.startService(intent)
    }
}
