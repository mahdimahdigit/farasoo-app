package ir.farasoo.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
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

        binding.logoutButton.setOnClickListener {
            prefs.edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.refreshButton.setOnClickListener {
            loadUserData()
            loadChartData()
        }

        binding.tab7Days.setOnClickListener { switchPeriod("day") }
        binding.tab4Weeks.setOnClickListener { switchPeriod("week") }
        binding.tab6Months.setOnClickListener { switchPeriod("month") }

        setupCharts()
        loadUserData()
        loadChartData()
    }

    private fun switchPeriod(period: String) {
        currentPeriod = period

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

            val usedGb = data.usedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            binding.usedGbText.text = String.format("%.2f", usedGb)

            val usedHours = data.usedSeconds.toDouble() / 3600.0
            binding.usedHoursText.text = String.format("%.1f", usedHours)

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
                for (p in data) {
                    result.add(GroupedData(p.date.takeLast(5), p.bytes, p.seconds))
                }
            }
            "week" -> {
                var i = 0
                while (i < data.size) {
                    val end = (i + 7).coerceAtMost(data.size)
                    val slice = data.subList(i, end)
                    var totalBytes = 0L
                    var totalSeconds = 0L
                    for (item in slice) {
                        totalBytes += item.bytes
                        totalSeconds += item.seconds
                    }
                    val label = slice[0].date.takeLast(5)
                    result.add(GroupedData(label, totalBytes, totalSeconds))
                    i += 7
                }
            }
            else -> {
                var i = 0
                while (i < data.size) {
                    val end = (i + 30).coerceAtMost(data.size)
                    val slice = data.subList(i, end)
                    var totalBytes = 0L
                    var totalSeconds = 0L
                    for (item in slice) {
                        totalBytes += item.bytes
                        totalSeconds += item.seconds
                    }
                    val label = slice[0].date.takeLast(5)
                    result.add(GroupedData(label, totalBytes, totalSeconds))
                    i += 30
                }
            }
        }
        return result
    }

    private fun setupCharts() {
        val chartList = listOf(binding.chartVolume, binding.chartTime, binding.chartCost)
        for (chart in chartList) {
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

    private fun updateChart(
        chart: com.github.mikephil.charting.charts.LineChart,
        values: List<Float>,
        labels: List<String>,
        color: Int
    ) {
        val entries = mutableListOf<Entry>()
        for (i in values.indices) {
            entries.add(Entry(i.toFloat(), values[i]))
        }

        val dataSet = LineDataSet(entries, "")
        dataSet.color = color
        dataSet.setCircleColor(color)
        dataSet.lineWidth = 2.5f
        dataSet.circleRadius = 4f
        dataSet.setDrawCircleHole(true)
        dataSet.circleHoleColor = Color.WHITE
        dataSet.setDrawValues(false)
        dataSet.mode = LineDataSet.Mode.CUBIC_BEZIER
        dataSet.setDrawFilled(true)
        dataSet.fillColor = color
        dataSet.fillAlpha = 50
        dataSet.highLightColor = color
        dataSet.setDrawHorizontalHighlightIndicator(false)

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
