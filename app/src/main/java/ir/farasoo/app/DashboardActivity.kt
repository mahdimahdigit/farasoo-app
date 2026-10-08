package ir.farasoo.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import ir.farasoo.app.databinding.ActivityDashboardBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private var userId: Int = -1
    private var currentPeriod: String = "day"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("farasoo", MODE_PRIVATE)
        userId = prefs.getInt("user_id", -1)

        if (userId <= 0) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.userNameText.text = "👋 " + (prefs.getString("full_name", "") ?: "")
        val seat = prefs.getString("seat_number", "") ?: ""
        if (seat.isNotEmpty()) {
            binding.userSeatText.text = "🪑 صندلی $seat"
        } else {
            binding.userSeatText.visibility = View.GONE
        }

        // Logout
        binding.logoutButton.setOnClickListener {
            prefs.edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        // Refresh
        binding.refreshButton.setOnClickListener {
            loadUserData()
            loadChartData()
        }

        // Tabs
        binding.tab7Days.setOnClickListener { switchPeriod("day") }
        binding.tab4Weeks.setOnClickListener { switchPeriod("week") }
        binding.tab6Months.setOnClickListener { switchPeriod("month") }

        setupCharts()

        loadUserData()
        loadChartData()
    }

    private fun switchPeriod(period: String) {
        currentPeriod = period

        // رنگ دکمه‌ها
        val activeBg = ContextCompat.getColor(this, R.color.farasoo_white)
        val activeText = ContextCompat.getColor(this, R.color.farasoo_blue_dark)
        val inactiveBg = Color.TRANSPARENT
        val inactiveText = ContextCompat.getColor(this, R.color.farasoo_gray)

        binding.tab7Days.setBackgroundColor(if (period == "day") activeBg else inactiveBg)
        binding.tab7Days.setTextColor(if (period == "day") activeText else inactiveText)

        binding.tab4Weeks.setBackgroundColor(if (period == "week") activeBg else inactiveBg)
        binding.tab4Weeks.setTextColor(if (period == "week") activeText else inactiveText)

        binding.tab6Months.setBackgroundColor(if (period == "month") activeBg else inactiveBg)
        binding.tab6Months.setTextColor(if (period == "month") activeText else inactiveText)

        loadChartData()
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            val data = ApiClient.getUserInfo(userId) ?: return@launch

            // Bytes
            val usedGb = data.usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            binding.usedGbText.text = String.format("%.2f", usedGb)

            // Seconds
            val usedHours = data.usedSeconds.toDouble() / 3600.0
            binding.usedHoursText.text = String.format("%.1f", usedHours)

            // Percentages
            if (data.quotaBytes > 0) {
                val pct = (data.usedBytes.toDouble() / data.quotaBytes.toDouble() * 100).coerceAtMost(100.0)
                binding.bytesPercentText.text = "${pct.roundToInt()}%"
            } else {
                binding.bytesPercentText.text = "نامحدود"
            }

            if (data.quotaSeconds > 0) {
                val pct = (data.usedSeconds.toDouble() / data.quotaSeconds.toDouble() * 100).coerceAtMost(100.0)
                binding.timePercentText.text = "${pct.roundToInt()}%"
            } else {
                binding.timePercentText.text = "نامحدود"
            }

            // Cost
            binding.costText.text = data.currentCost.toString().reversed().chunked(3).joinToString(",").reversed()
        }
    }

    private fun loadChartData() {
        val days = when (currentPeriod) {
            "day" -> 7
            "week" -> 28
            else -> 180
        }

        lifecycleScope.launch {
            val data = ApiClient.getChartData(userId, days)
            if (data.isEmpty()) return@launch

            // گروه‌بندی بر اساس period
            val grouped = groupData(data)

            updateChart(binding.chartVolume, grouped.map { it.bytes / (1024f * 1024f) }, grouped.map { it.label }, Color.parseColor("#29A8DF"))
            updateChart(binding.chartTime, grouped.map { it.seconds / 60f }, grouped.map { it.label }, Color.parseColor("#A78BFA"))
            updateChart(binding.chartCost, grouped.map { (it.bytes / (1024f * 1024f) * 10f) }, grouped.map { it.label }, Color.parseColor("#F59E0B"))
        }
    }

    private fun groupData(data: List<ChartPoint>): List<GroupedData> {
        val result = mutableListOf<GroupedData>()

        when (currentPeriod) {
            "day" -> {
                data.forEach {
                    result.add(GroupedData(it.date.takeLast(5), it.bytes, it.seconds))
                }
            }
            "week" -> {
                var i = 0
                while (i < data.size) {
                    val slice = data.subList(i, (i + 7).coerceAtMost(data.size))
                    val bytes = slice.sumOf { it.bytes }
                    val seconds = slice.sumOf { it.seconds }
                    val label = slice.firstOrNull()?.date?.takeLast(5) ?: ""
                    result.add(GroupedData(label, bytes, seconds))
                    i += 7
                }
            }
            else -> {
                var i = 0
                while (i < data.size) {
                    val slice = data.subList(i, (i + 30).coerceAtMost(data.size))
                    val bytes = slice.sumOf { it.bytes }
                    val seconds = slice.sumOf { it.seconds }
                    val label = slice.firstOrNull()?.date?.takeLast(5) ?: ""
                    result.add(GroupedData(label, bytes, seconds))
                    i += 30
                }
            }
        }
        return result
    }

    private fun setupCharts() {
        listOf(binding.chartVolume, binding.chartTime, binding.chartCost).forEach { chart ->
            chart.description.isEnabled = false
            chart.legend.isEnabled = false
            chart.setTouchEnabled(true)
            chart.setDrawGridBackground(false)
            chart.setDrawBorders(false)
            chart.axisRight.isEnabled = false

            chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
            chart.xAxis.setDrawGridLines(false)
            chart.xAxis.granularity = 1f
            chart.xAxis.textColor = Color.parseColor("#64748B")
            chart.xAxis.textSize = 9f

            chart.axisLeft.textColor = Color.parseColor("#64748B")
            chart.axisLeft.textSize = 9f
            chart.axisLeft.setDrawGridLines(true)
            chart.axisLeft.gridColor = Color.parseColor("#E0F2FE")
            chart.axisLeft.gridLineWidth = 0.5f
        }
    }

    private fun updateChart(chart: com.github.mikephil.charting.charts.LineChart, values: List<Float>, labels: List<String>, color: Int) {
        val entries = values.mapIndexed { i, v -> Entry(i.toFloat(), v) }

        val dataSet = LineDataSet(entries, "").apply {
            this.color = color
            setCircleColor(color)
            lineWidth = 2.5f
            circleRadius = 4f
            setDrawCircleHole(true)
            circleHoleColor = Color.WHITE
            setDrawValues(false)
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = color
            fillAlpha = 50
            highLightColor = color
            setDrawHorizontalHighlightIndicator(false)
        }

        chart.data = LineData(dataSet)
        chart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        chart.xAxis.labelCount = labels.size.coerceAtMost(7)
        chart.invalidate()
        chart.animateX(500)
    }
}

data class GroupedData(
    val label: String,
    val bytes: Long,
    val seconds: Long
)
