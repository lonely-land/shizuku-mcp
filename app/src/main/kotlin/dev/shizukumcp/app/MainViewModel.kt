package dev.shizukumcp.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    val port = MutableStateFlow(settings.port())
    val token = MutableStateFlow(settings.token())
    val shizuku = MutableStateFlow(ShizukuGate.status(app))
    val logs = AppRuntime.logs
    val phase = AppRuntime.phase
    val lastError = AppRuntime.lastError
    val boundPort = AppRuntime.boundPort

    private var listenersReady = false
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshShizuku() }
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizuku() }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            listenersReady = true
        } catch (t: Throwable) {
            AppRuntime.log("无法注册 Shizuku 监听：${t.message}", "error")
        }
        refreshShizuku()
    }

    override fun onCleared() {
        if (!listenersReady) return
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
    }

    fun refreshShizuku() {
        shizuku.value = ShizukuGate.status(getApplication())
    }

    fun openShizuku() {
        ShizukuGate.open(getApplication())
    }

    fun requestPermission() {
        if (Shizuku.isPreV11()) {
            AppRuntime.fail("请将 Shizuku 升级到较新版本")
            return
        }
        ShizukuGate.requestPermission(REQUEST_CODE)
    }

    fun savePort(value: Int): Boolean {
        if (value !in SettingsStore.PORT_MIN..SettingsStore.PORT_MAX) return false
        settings.setPort(value)
        port.value = value
        return true
    }

    fun regenerateToken() {
        if (phase.value != AppRuntime.Phase.STOPPED) return
        token.value = settings.regenerateToken()
    }

    fun start() {
        AppRuntime.clearError()
        AppRuntime.phase.value = AppRuntime.Phase.STARTING
        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(app, Intent(app, McpServerService::class.java))
        } catch (t: Throwable) {
            AppRuntime.fail(t.message ?: "无法启动服务")
        }
    }

    fun stop() {
        val app = getApplication<Application>()
        app.startService(Intent(app, McpServerService::class.java).setAction(McpServerService.ACTION_STOP))
    }

    companion object {
        const val REQUEST_CODE = 27
    }
}
