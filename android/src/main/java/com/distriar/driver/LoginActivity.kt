package com.distriar.driver

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.distriar.driver.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var tokenStore: TokenStore
    private lateinit var repo: DriverRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tokenStore = TokenStore(this)
        val api = ApiClient.create { tokenStore.getToken() }
        repo = DriverRepository(api)

        binding.loginButton.setOnClickListener {
            attemptLogin()
        }

        checkExistingSession()
    }

    private fun checkExistingSession() {
        val token = tokenStore.getToken() ?: return
        lifecycleScope.launch {
            try {
                val me = withContext(Dispatchers.IO) { repo.me(token) }
                if (me.role.lowercase() == "repartidor" && me.isActive) {
                    goToMain()
                } else {
                    tokenStore.clear()
                }
            } catch (e: Exception) {
                tokenStore.clear()
            }
        }
    }

    private fun attemptLogin() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString()?.trim().orEmpty()
        if (username.isBlank() || password.isBlank()) {
            showError("Completa usuario y contraseña")
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { repo.login(username, password) }
                tokenStore.saveToken(token.accessToken)
                val me = withContext(Dispatchers.IO) { repo.me(token.accessToken) }
                if (me.role.lowercase() != "repartidor") {
                    tokenStore.clear()
                    showError("Este usuario no es repartidor")
                } else if (!me.isActive) {
                    tokenStore.clear()
                    showError("Usuario desactivado")
                } else {
                    goToMain()
                }
            } catch (e: Exception) {
                showError(getString(R.string.login_error))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginButton.isEnabled = !loading
        binding.loginError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.loginError.text = message
        binding.loginError.visibility = View.VISIBLE
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
