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

    // ⚠️ آدرس سرور
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

            Log.d(TAG, "Login response: $responseBody")

            if (!response.isSuccessful) {
                return@withContext LoginResult(false, null, "خطا در ورود")
            }

            val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
            if (jsonResponse.get("ok")?.asBoolean == true) {
                val user = jsonResponse.getAsJsonObject("user")
                val userId = user.get("id").asInt
                val fullName = user.get("full_name")?.asString ?: ""
                val seatNumber = user.get("seat_number")?.asString ?: ""

                // ذخیره در SharedPreferences
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
            Log.d(TAG, "User info: $responseBody")

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
                usedBytes = (bill.get("totalMB")?.asString?.toDoubleOrNull() ?: 0.0).let { (it * 1024 * 1024).toLong() },
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
    // Save Traffic to Server
    // ============================================================
    suspend fun reportTraffic(userId: Int, seconds: Long, bytes: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JsonObject().apply {
                addProperty("user_id", userId)
                addProperty("seconds", seconds)
                addProperty("bytes", bytes)
            }
            val body = json.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/api/usage")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "Report error", e)
            false
        }
    }

    // ============================================================
    // Check if user should be blocked
    // ============================================================
    suspend fun checkUserStatus(userId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/users/$userId/status")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) return@withContext false

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            json.get("shouldBlock")?.asBoolean ?: false
        } catch (e: Exception) {
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
