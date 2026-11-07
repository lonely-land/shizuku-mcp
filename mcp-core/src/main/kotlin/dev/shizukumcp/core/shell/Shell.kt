package dev.shizukumcp.core.shell

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

@Serializable
data class ExecResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
)

data class FileRead(
    val bytes: ByteArray,
    val size: Long,
    val truncated: Boolean,
)

class ShellException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ShellExecutor {
    fun exec(command: String, timeoutMs: Long): ExecResult
    fun readFile(path: String, maxBytes: Int): FileRead
    fun writeFile(path: String, bytes: ByteArray)
    fun deleteFile(path: String): Boolean
}

object ShellQuote {
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "NUL in shell argument" }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

object ExecCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @JvmStatic
    fun toJson(result: ExecResult): String = json.encodeToString(result)

    @JvmStatic
    fun fromJson(text: String): ExecResult = json.decodeFromString(text)
}

/**
 * Runs commands with the JDK process API. On Android this class is used inside the
 * Shizuku user-service process, which already has the shell (or root) uid.
 */
class JdkShellExecutor @JvmOverloads constructor(
    private val maxStdoutBytes: Int = 200_000,
    private val maxStderrBytes: Int = 40_000,
) : ShellExecutor {
    private val lock = ReentrantLock()
    private val shellBin: String = if (File("/system/bin/sh").canExecute()) "/system/bin/sh" else "sh"
    private val workDir: File? = File("/data/local/tmp").takeIf { it.isDirectory }

    override fun exec(command: String, timeoutMs: Long): ExecResult {
        val budget = timeoutMs.coerceIn(1, 120_000)
        if (!acquire(budget + 5_000)) {
            return ExecResult(-1, "", "another shell command is still running", timedOut = false, truncated = false)
        }
        try {
            return execLocked(command, budget)
        } finally {
            lock.unlock()
        }
    }

    private fun execLocked(command: String, timeoutMs: Long): ExecResult {
        val builder = ProcessBuilder(listOf(shellBin, "-c", command)).redirectErrorStream(false)
        if (workDir != null) builder.directory(workDir)
        val process = try {
            builder.start()
        } catch (e: Exception) {
            throw ShellException("failed to start shell", e)
        }
        process.outputStream.closeQuietly()
        val stdoutAcc = StreamAccum(process.inputStream, maxStdoutBytes)
        val stderrAcc = StreamAccum(process.errorStream, maxStderrBytes)
        val outThread = thread(name = "sh-stdout", isDaemon = true) { stdoutAcc.drain() }
        val errThread = thread(name = "sh-stderr", isDaemon = true) { stderrAcc.drain() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.inputStream.closeQuietly()
            process.errorStream.closeQuietly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        outThread.join(2_000)
        errThread.join(2_000)
        val truncated = stdoutAcc.truncated || stderrAcc.truncated
        return if (!finished) {
            ExecResult(-1, decode(stdoutAcc.bytes()), decode(stderrAcc.bytes()), timedOut = true, truncated = truncated)
        } else {
            ExecResult(
                process.exitValue(),
                decode(stdoutAcc.bytes()),
                decode(stderrAcc.bytes()),
                timedOut = false,
                truncated = truncated,
            )
        }
    }

    override fun readFile(path: String, maxBytes: Int): FileRead {
        if (!acquire(30_000)) throw ShellException("another shell command is still running")
        try {
            val file = Paths.get(path)
            if (!Files.exists(file)) throw ShellException("file not found: $path")
            if (Files.isDirectory(file)) throw ShellException("path is a directory: $path")
            val size = Files.size(file)
            val limit = maxBytes.coerceAtLeast(0)
            val toRead = minOf(size, limit.toLong()).toInt()
            val bytes = if (toRead == 0) {
                ByteArray(0)
            } else {
                Files.newInputStream(file).use { input ->
                    val buf = ByteArray(toRead)
                    var off = 0
                    while (off < toRead) {
                        val n = input.read(buf, off, toRead - off)
                        if (n < 0) break
                        off += n
                    }
                    if (off == buf.size) buf else buf.copyOf(off)
                }
            }
            return FileRead(bytes, size, truncated = size > limit)
        } finally {
            lock.unlock()
        }
    }

    override fun writeFile(path: String, bytes: ByteArray) {
        if (!acquire(30_000)) throw ShellException("another shell command is still running")
        try {
            val file = Paths.get(path)
            val parent = file.parent
            if (parent != null) Files.createDirectories(parent)
            Files.write(file, bytes)
        } finally {
            lock.unlock()
        }
    }

    override fun deleteFile(path: String): Boolean {
        if (!acquire(30_000)) throw ShellException("another shell command is still running")
        try {
            return Files.deleteIfExists(Paths.get(path))
        } finally {
            lock.unlock()
        }
    }

    private fun acquire(waitMs: Long): Boolean = try {
        lock.tryLock(waitMs, TimeUnit.MILLISECONDS)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw ShellException("interrupted", e)
    }

    private fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }
}

private class StreamAccum(private val input: InputStream, private val limit: Int) {
    private val out = ByteArrayOutputStream()

    @Volatile
    var truncated: Boolean = false
        private set

    fun drain() {
        val buf = ByteArray(8192)
        var total = 0
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (total < limit) {
                    val take = minOf(n, limit - total)
                    out.write(buf, 0, take)
                }
                total += n
                if (total > limit) truncated = true
            }
        } catch (_: Exception) {
            // The process was destroyed or the pipe closed.
        }
    }

    fun bytes(): ByteArray = out.toByteArray()
}

private fun java.io.OutputStream.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
