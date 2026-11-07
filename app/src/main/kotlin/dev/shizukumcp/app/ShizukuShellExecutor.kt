package dev.shizukumcp.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import dev.shizukumcp.core.shell.ExecCodec
import dev.shizukumcp.core.shell.ExecResult
import dev.shizukumcp.core.shell.FileRead
import dev.shizukumcp.core.shell.ShellException
import dev.shizukumcp.core.shell.ShellExecutor
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class ShizukuShellBridge(private val context: Context) {
    private val lock = ReentrantLock()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var closing = false

    private var args: Shizuku.UserServiceArgs? = null
    private var connection: ServiceConnection? = null
    var onDisconnected: (() -> Unit)? = null

    fun connect(timeoutMs: Long = 20_000) {
        closing = false
        val component = ComponentName(context.packageName, ShellUserService::class.java.name)
        val userArgs = Shizuku.UserServiceArgs(component)
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("shizuku-mcp-shell")
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                if (!closing) onDisconnected?.invoke()
            }
        }
        args = userArgs
        connection = conn
        main.post {
            try {
                Shizuku.bindUserService(userArgs, conn)
            } catch (t: Throwable) {
                service = null
                latch.countDown()
                AppRuntime.log("绑定 Shizuku shell 失败：${t.message}", "error")
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || service == null) {
            throw IllegalStateException("等待 Shizuku shell 服务超时。请确认 Shizuku 正在运行且已授权。")
        }
    }

    fun disconnect() {
        closing = true
        val conn = connection
        val userArgs = args
        service = null
        connection = null
        if (conn != null && userArgs != null) {
            main.post {
                try {
                    Shizuku.unbindUserService(userArgs, conn, true)
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun executor(): ShellExecutor = RemoteShell(this)

    private fun <T> withService(block: (IShellService) -> T): T {
        if (!lock.tryLock(180, TimeUnit.SECONDS)) throw ShellException("another shell command is still running")
        try {
            val svc = service ?: throw ShellException("Shizuku shell 服务未连接")
            return try {
                block(svc)
            } catch (e: RemoteException) {
                throw ShellException("Shizuku shell 服务已断开", e)
            }
        } finally {
            lock.unlock()
        }
    }

    private class RemoteShell(private val bridge: ShizukuShellBridge) : ShellExecutor {
        override fun exec(command: String, timeoutMs: Long): ExecResult = bridge.withService { svc ->
            ExecCodec.fromJson(svc.exec(command, timeoutMs.coerceIn(1, 120_000).toInt()))
        }

        override fun readFile(path: String, maxBytes: Int): FileRead = bridge.withService { svc ->
            val size = svc.fileSize(path)
            if (size < 0) throw ShellException("file not found: $path")
            val limit = maxBytes.coerceAtLeast(0)
            val toRead = minOf(size, limit.toLong())
            val out = ByteArrayOutputStream()
            var offset = 0L
            while (offset < toRead) {
                val ask = minOf(256L * 1024, toRead - offset).toInt()
                val chunk = svc.readChunk(path, offset, ask)
                if (chunk.isEmpty()) break
                out.write(chunk)
                offset += chunk.size
            }
            FileRead(out.toByteArray(), size, truncated = size > limit)
        }

        override fun writeFile(path: String, bytes: ByteArray) {
            bridge.withService { svc ->
                if (bytes.isEmpty()) {
                    svc.writeChunk(path, 0, false, ByteArray(0))
                } else {
                    var offset = 0
                    while (offset < bytes.size) {
                        val end = minOf(offset + 256 * 1024, bytes.size)
                        svc.writeChunk(path, offset.toLong(), offset > 0, bytes.copyOfRange(offset, end))
                        offset = end
                    }
                }
            }
        }

        override fun deleteFile(path: String): Boolean = bridge.withService { it.deleteFile(path) }
    }
}
