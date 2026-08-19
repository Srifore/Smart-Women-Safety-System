package com.voicesafety

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("safety", MODE_PRIVATE)
        if (prefs.getBoolean("logged_in", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        val identifier = findViewById<EditText>(R.id.loginIdentifier)
        val password = findViewById<EditText>(R.id.loginPassword)
        val statusText = findViewById<TextView>(R.id.loginStatus)
        val hasAccount = prefs.getBoolean("account_created", false)

        statusText.text = if (hasAccount) {
            "Enter your saved email or mobile and password to sign in."
        } else {
            "No account found yet. Register first to continue."
        }

        findViewById<Button>(R.id.loginButton).setOnClickListener {
            val user = identifier.text.toString().trim()
            val pass = password.text.toString().trim()

            if (user.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "Enter email/mobile and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val savedEmail = prefs.getString("saved_email", null)
            val savedMobile = prefs.getString("saved_mobile", null)
            val savedPassword = prefs.getString("saved_password", null)
            if (!hasAccount || savedPassword == null || (savedEmail == null && savedMobile == null)) {
                Toast.makeText(this, "No account found. Tap Register first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val matchesIdentifier = user.equals(savedEmail, ignoreCase = true) || user == savedMobile
            if (!matchesIdentifier || pass != savedPassword) {
                Toast.makeText(this, "Invalid email/mobile or password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit().putBoolean("logged_in", true).apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.registerButton).setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}
