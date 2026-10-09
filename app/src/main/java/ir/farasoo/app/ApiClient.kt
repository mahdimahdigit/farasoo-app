package ir.farasoo.app

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val TAG = "ApiClient"

    var baseUrl: String = "http://192.168.1.200:3000"
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // ============================================================
    // Login
    // ============================================================
    suspend fun login(context: Context, nationalId: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply { addProperty("national_id", nationalId) }
            val body = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/login")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext LoginResult(false, null, "خطا در ورود")
            }

            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            if (jsonResponse.get("ok")?.asBoolean == true) {
                val user = jsonResponse.getAsJsonObject("user")
                val userId = user.get("id").asInt
                val fullName = user.get("full_name")?.asString ?: ""
                val seatNumber = user.get("seat_number")?.asString ?: ""

                val prefs = context.getSharedPreferences("farasoo", Context.MODE_PRIVATE)
                prefs.edit()
                    .putInt("user_id", userId)
                    .putString("national_id", nationalId)
                    .putString("full_name", fullName)
                    .putString("seat_number", seatNumber)
                    .apply()

                LoginResult(true, UserInfo(userId, nationalId, fullName, seatNumber), null)
            } else {
                LoginResult(false, null, jsonResponse.get("error")?.asString ?: "خطا")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            LoginResult(false, null, "خطا در ارتباط با سرور")
        }
    }

    // ============================================================
    // Get User Info
    // ============================================================
    suspend fun getUserInfo(userId: Int): UserData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/user/$userId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) return@withContext null

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            if (json.get("ok")?.asBoolean != true) return@withContext null

            val user = json.getAsJsonObject("user")
            val bill = json.getAsJsonObject("bill")
            val usage = json.getAsJsonObject("usage")

            UserData(
                id = user.get("id").asInt,
                fullName = user.get("full_name")?.asString ?: "",
                seatNumber = user.get("seat_number")?.asString ?: "",
                quotaBytes = user.get("quota_bytes")?.asLong ?: 0L,
                quotaSeconds = user.get("quota_seconds")?.asLong ?: 0L,
                usedBytes = ((bill.get("totalMB")?.asString?.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong(),
                usedSeconds = bill.get("totalSeconds")?.asLong ?: 0L,
                currentCost = bill.get("currentCost")?.asLong ?: 0L,
                todayBytes = usage.get("today_bytes")?.asLong ?: 0L,
                todaySeconds = usage.get("today_seconds")?.asLong ?: 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "GetUserInfo error", e)
            null
        }
    }

    // ============================================================
    // Get Chart Data
    // ============================================================
    suspend fun getChartData(userId: Int, days: Int = 30): List<ChartPoint> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/user/$userId/chart?days=$days")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) return@withContext emptyList()

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            if (json.get("ok")?.asBoolean != true) return@withContext emptyList()

            val dataArray = json.getAsJsonArray("data")
            val result = mutableListOf<ChartPoint>()

            for (i in 0 until dataArray.size()) {
                val item = dataArray[i].asJsonObject
                result.add(
                    ChartPoint(
                        date = item.get("date").asString,
                        bytes = item.get("bytes").asLong,
                        seconds = item.get("seconds").asLong
                    )
                )
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Chart error", e)
            emptyList()
        }
    }

    // ============================================================
    // Report VPN Status
    // ============================================================
    suspend fun reportVpnStatus(userId: Int, active: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply {
                addProperty("user_id", userId)
                addProperty("vpn_active", active)
                addProperty("event", if (active) "vpn_started" else "vpn_stopped")
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/vpn/status")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "reportVpnStatus error", e)
            false
        }
    }

    // ============================================================
    // Report VPN Revoked (کاربر خاموش کرده)
    // ============================================================
    suspend fun reportVpnRevoked(userId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply {
                addProperty("user_id", userId)
                addProperty("event", "vpn_revoked")
                addProperty("warning", "کاربر VPN را خاموش کرده")
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/vpn/status")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "reportVpnRevoked error", e)
            false
        }
    }

    // ============================================================
    // Send Heartbeat
    // ============================================================
    suspend fun sendHeartbeat(
        userId: Int,
        vpnActive: Boolean,
        onFarasooWifi: Boolean,
        ssid: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply {
                addProperty("user_id", userId)
                addProperty("vpn_active", vpnActive)
                addProperty("on_farasoo_wifi", onFarasooWifi)
                addProperty("ssid", ssid)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/api/heartbeat")
                .post(body)
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "sendHeartbeat error", e)
            false
        }
    }
}

data class LoginResult(
    val success: Boolean,
    val user: UserInfo?,
    val error: String?
)

data class UserInfo(
    val id: Int,
    val nationalId: String,
    val fullName: String,
    val seatNumber: String
)

data class UserData(
    val id: Int,
    val fullName: String,
    val seatNumber: String,
    val quotaBytes: Long,
    val quotaSeconds: Long,
    val usedBytes: Long,
    val usedSeconds: Long,
    val currentCost: Long,
    val todayBytes: Long,
    val todaySeconds: Long
)

data class ChartPoint(
    val date: String,
    val bytes: Long,
    val seconds: Long
)
