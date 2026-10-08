package ir.farasoo.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.farasoo.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("farasoo", MODE_PRIVATE)
        val savedUserId = prefs.getInt("user_id", -1)

        if (savedUserId > 0) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val savedNationalId = prefs.getString("national_id", "")
        if (!savedNationalId.isNullOrEmpty()) {
            binding.nationalIdInput.setText(savedNationalId)
        }

        binding.loginButton.setOnClickListener {
            val nationalId = binding.nationalIdInput.text.toString().trim()

            if (nationalId.length != 10 || !nationalId.all { it.isDigit() }) {
                showError("کد ملی باید ۱۰ رقم باشد")
                return@setOnClickListener
            }

            performLogin(nationalId)
        }
    }

    private fun performLogin(nationalId: String) {
        binding.loginButton.isEnabled = false
        binding.loginProgress.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE

        lifecycleScope.launch {
            val result = ApiClient.login(this@LoginActivity, nationalId)

            binding.loginProgress.visibility = View.GONE
            binding.loginButton.isEnabled = true

            if (result.success) {
                Toast.makeText(this@LoginActivity, "خوش آمدید", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                finish()
            } else {
                showError(result.error ?: "خطا در ورود")
            }
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }
}
