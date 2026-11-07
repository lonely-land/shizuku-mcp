package dev.shizukumcp.app

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("shizuku_mcp", Context.MODE_PRIVATE)

    fun port(): Int {
        val stored = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        return if (stored in PORT_MIN..PORT_MAX) stored else DEFAULT_PORT
    }

    fun setPort(port: Int) {
        require(port in PORT_MIN..PORT_MAX)
        prefs.edit().putInt(KEY_PORT, port).apply()
    }

    fun token(): String {
        val existing = prefs.getString(KEY_TOKEN, null)
        if (!existing.isNullOrBlank()) return existing
        return regenerateToken()
    }

    fun regenerateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val created = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        prefs.edit().putString(KEY_TOKEN, created).apply()
        return created
    }

    companion object {
        const val DEFAULT_PORT = 27183
        const val PORT_MIN = 1024
        const val PORT_MAX = 65535
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
    }
}
