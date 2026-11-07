package dev.shizukumcp.core.http

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpException(val status: Int, message: String) : Exception(message)

data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
)

class HttpResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray? = null,
    val stream: ((ChunkedWriter) -> Unit)? = null,
)

class ChunkedWriter(private val out: OutputStream) {
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        out.write("${bytes.size.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }

    fun finish() {
        out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        out.flush()
    }
}

class MiniHttpServer(
    private val host: String,
    private val port: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mcp-http").apply { isDaemon = true }
    }

    val boundPort: Int
        get() = server?.localPort ?: error("server is not started")

    fun start() {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(host), port), 32)
        server = socket
        running.set(true)
        Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    if (!running.get()) break else continue
                }
                pool.execute {
                    try {
                        serve(client)
                    } catch (_: Exception) {
                        client.closeQuietly()
                    }
                }
            }
        }, "mcp-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        server?.closeQuietly()
        pool.shutdownNow()
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            client.tcpNoDelay = true
            client.soTimeout = 20_000
            try {
                val request = readRequest(client.getInputStream())
                val response = handler(request)
                if (response.stream != null) {
                    client.soTimeout = 0
                }
                writeResponse(client.getOutputStream(), response)
            } catch (e: HttpException) {
                writeResponse(client.getOutputStream(), textResponse(e.status, e.message ?: "bad request"))
            } catch (_: Exception) {
                try {
                    writeResponse(client.getOutputStream(), textResponse(500, "internal error"))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readRequest(input: InputStream): HttpRequest {
        val buffered = input.buffered()
        val lines = readHeaderLines(buffered)
        if (lines.isEmpty()) throw HttpException(400, "empty request")
        val parts = lines[0].split(" ")
        if (parts.size < 2) throw HttpException(400, "bad request line")
        val target = parts[1]
        if (!target.startsWith("/")) throw HttpException(400, "unsupported request target")
        val pathPart = target.substringBefore("?")
        val queryPart = target.substringAfter("?", missingDelimiterValue = "")
        val path = urlDecode(pathPart)
        if (path.contains("..")) throw HttpException(400, "bad path")
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return HttpRequest(
            method = parts[0].uppercase(),
            path = path,
            query = parseQuery(queryPart),
            headers = headers,
            body = readBody(buffered, headers),
        )
    }

    private fun readHeaderLines(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        var total = 0
        while (true) {
            val line = readAsciiLine(input)
            total += line.length + 2
            if (total > 32_768) throw HttpException(431, "headers too large")
            if (line.isEmpty()) break
            lines += line
        }
        return lines
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val encoding = headers["transfer-encoding"]?.lowercase()
        if (encoding != null && "chunked" in encoding) return readChunked(input)
        val length = headers["content-length"]?.toIntOrNull() ?: return ByteArray(0)
        if (length < 0) throw HttpException(400, "bad content length")
        if (length > MAX_BODY) throw HttpException(413, "body too large")
        if (length == 0) return ByteArray(0)
        val buf = ByteArray(length)
        readFully(input, buf)
        return buf
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readAsciiLine(input).substringBefore(';').trim().toIntOrNull(16)
                ?: throw HttpException(400, "bad chunk size")
            if (size == 0) {
                while (readAsciiLine(input).isNotEmpty()) Unit
                break
            }
            if (size < 0 || out.size() + size > MAX_BODY) throw HttpException(413, "body too large")
            val buf = ByteArray(size)
            readFully(input, buf)
            out.write(buf)
            val trailer = readAsciiLine(input)
            if (trailer.isNotEmpty()) throw HttpException(400, "bad chunk trailer")
        }
        return out.toByteArray()
    }

    private fun writeResponse(out: OutputStream, response: HttpResponse) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status)).append("\r\n")
            append("Server: shizuku-mcp\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            response.headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            if (response.stream != null) {
                append("Transfer-Encoding: chunked\r\n\r\n")
            } else {
                val body = response.body ?: ByteArray(0)
                append("Content-Length: ").append(body.size).append("\r\n\r\n")
            }
        }
        out.write(head.toByteArray(StandardCharsets.UTF_8))
        if (response.stream != null) {
            val writer = ChunkedWriter(out)
            response.stream.invoke(writer)
            writer.finish()
        } else {
            val body = response.body
            if (body != null && body.isNotEmpty()) out.write(body)
        }
        out.flush()
    }

    companion object {
        const val MAX_BODY = 1_000_000
    }
}

fun textResponse(status: Int, message: String): HttpResponse = HttpResponse(
    status = status,
    headers = listOf("Content-Type" to "text/plain; charset=utf-8"),
    body = message.toByteArray(StandardCharsets.UTF_8),
)

fun jsonResponse(status: Int, body: String, extra: List<Pair<String, String>> = emptyList()): HttpResponse = HttpResponse(
    status = status,
    headers = extra + ("Content-Type" to "application/json; charset=utf-8"),
    body = body.toByteArray(StandardCharsets.UTF_8),
)

private fun readAsciiLine(input: InputStream, max: Int = 8192): String {
    val out = ByteArrayOutputStream()
    while (out.size() <= max) {
        val b = input.read()
        if (b < 0) break
        if (b == '\n'.code) break
        if (b != '\r'.code) out.write(b)
    }
    if (out.size() > max) throw HttpException(431, "line too long")
    return out.toString(StandardCharsets.US_ASCII)
}

private fun readFully(input: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n < 0) throw HttpException(400, "unexpected end of body")
        off += n
    }
}

private fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val values = linkedMapOf<String, String>()
    raw.split('&').forEach { pair ->
        if (pair.isEmpty()) return@forEach
        val name = urlDecode(pair.substringBefore('='))
        val value = urlDecode(pair.substringAfter('=', ""))
        values[name] = value
    }
    return values
}

private fun urlDecode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun statusText(status: Int): String = when (status) {
    200 -> "OK"
    202 -> "Accepted"
    204 -> "No Content"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    413 -> "Payload Too Large"
    431 -> "Request Header Fields Too Large"
    500 -> "Internal Server Error"
    503 -> "Service Unavailable"
    else -> "OK"
}

private fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}

private fun ServerSocket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
