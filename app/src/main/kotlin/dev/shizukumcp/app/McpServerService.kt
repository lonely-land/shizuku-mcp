package dev.shizukumcp.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.shizukumcp.core.http.McpHttpServer
import dev.shizukumcp.core.mcp.McpEngine
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class McpServerService : Service() {
    private val generation = AtomicInteger(0)
    private val foreground = AtomicBoolean(false)
    private var http: McpHttpServer? = null
    private var bridge: ShizukuShellBridge? = null
    private val binderDead = Shizuku.OnBinderDeadListener {
        if (AppRuntime.phase.value != AppRuntime.Phase.STOPPED) {
            failAndStop("Shizuku 已断开，MCP 服务已停止")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Shizuku.addBinderDeadListener(binderDead)
    }

    override fun onDestroy() {
        Shizuku.removeBinderDeadListener(binderDead)
        shutdown()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (http != null || AppRuntime.phase.value == AppRuntime.Phase.STARTING) {
            return START_STICKY
        }
        val gen = generation.incrementAndGet()
        AppRuntime.phase.value = AppRuntime.Phase.STARTING
        startInForeground("正在连接 Shizuku…")
        thread(name = "mcp-start", isDaemon = true) {
            try {
                bringUp(gen)
            } catch (t: Throwable) {
                if (generation.get() == gen) failAndStop(t.message ?: "启动失败")
            }
        }
        return START_STICKY
    }

    private fun bringUp(gen: Int) {
        if (!ShizukuGate.isGranted()) throw IllegalStateException("尚未获得 Shizuku 授权")
        val settings = SettingsStore(this)
        val bridge = ShizukuShellBridge(this)
        bridge.onDisconnected = {
            if (itIsCurrent(gen)) failAndStop("Shizuku shell 服务已断开")
        }
        this.bridge = bridge
        bridge.connect()
        if (!itIsCurrent(gen)) return
        val executor = bridge.executor()
        val identity = executor.exec("id", 5_000)
        AppRuntime.log(identity.stdout.trim().ifBlank { "Shizuku shell 已连接" })
        val server = McpHttpServer(
            port = settings.port(),
            token = settings.token(),
            engine = McpEngine(executor) { level, message -> AppRuntime.log(message, level) },
        )
        server.start()
        if (!itIsCurrent(gen)) {
            server.stop()
            return
        }
        http = server
        AppRuntime.boundPort.value = server.boundPort
        AppRuntime.phase.value = AppRuntime.Phase.RUNNING
        AppRuntime.clearError()
        AppRuntime.log("MCP 服务已启动 http://127.0.0.1:${server.boundPort}/mcp")
        startInForeground("正在监听 127.0.0.1:${server.boundPort}")
    }

    private fun itIsCurrent(gen: Int): Boolean = generation.get() == gen

    private fun failAndStop(message: String) {
        generation.incrementAndGet()
        AppRuntime.fail(message)
        shutdownQuiet()
        stopSelf()
    }

    private fun shutdown() {
        generation.incrementAndGet()
        shutdownQuiet()
        AppRuntime.phase.value = AppRuntime.Phase.STOPPED
        AppRuntime.boundPort.value = null
    }

    private fun shutdownQuiet() {
        http?.stop()
        http = null
        bridge?.onDisconnected = null
        bridge?.disconnect()
        bridge = null
        if (foreground.compareAndSet(true, false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foreground.set(true)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, McpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mcp)
            .setContentTitle("Shizuku MCP")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "MCP 服务", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shizuku MCP 在本机监听时显示"
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "dev.shizukumcp.app.STOP"
        private const val CHANNEL_ID = "shizuku_mcp_server"
        private const val NOTIFICATION_ID = 42
    }
}
