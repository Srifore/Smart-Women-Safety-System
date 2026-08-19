package com.voicesafety

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RegisterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_register)

        val prefs = getSharedPreferences("safety", MODE_PRIVATE)

        val name = findViewById<EditText>(R.id.registerName)
        val mobile = findViewById<EditText>(R.id.registerMobile)
        val email = findViewById<EditText>(R.id.registerEmail)
        val password = findViewById<EditText>(R.id.registerPassword)
        val confirmPassword = findViewById<EditText>(R.id.registerConfirmPassword)
        val statusText = findViewById<TextView>(R.id.registerStatus)

        statusText.text = "This account is stored securely on this phone only."

        findViewById<Button>(R.id.createAccountButton).setOnClickListener {
            val fullName = name.text.toString().trim()
            val mobileValue = mobile.text.toString().trim()
            val emailValue = email.text.toString().trim()
            val passwordValue = password.text.toString()
            val confirmValue = confirmPassword.text.toString()

            if (fullName.isBlank() || mobileValue.isBlank() || emailValue.isBlank() || passwordValue.length < 4) {
                Toast.makeText(this, "Fill all fields with a 4+ digit password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (passwordValue != confirmValue) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putBoolean("account_created", true)
                .putString("saved_name", fullName)
                .putString("saved_mobile", mobileValue)
                .putString("saved_email", emailValue)
                .putString("saved_password", passwordValue)
                .putBoolean("logged_in", true)
                .apply()

            Toast.makeText(this, "Account created", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        findViewById<Button>(R.id.backToLoginButton).setOnClickListener {
            finish()
        }
    }
}
