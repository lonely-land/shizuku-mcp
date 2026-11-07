package dev.shizukumcp.core.http

import dev.shizukumcp.core.mcp.HandleResult
import dev.shizukumcp.core.mcp.McpEngine
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SseSession(val id: String) {
    val queue: ArrayBlockingQueue<ByteArray> = ArrayBlockingQueue(32)

    @Volatile
    var closed: Boolean = false
}

class McpHttpServer(
    private val port: Int,
    token: String,
    engine: McpEngine,
    events: (level: String, message: String) -> Unit = { _, _ -> },
) {
    private val app = McpHttpApp(engine, token, { boundPort }, events)
    private val server = MiniHttpServer("127.0.0.1", port, app::handle)

    val boundPort: Int
        get() = server.boundPort

    fun start() = server.start()

    fun stop() {
        app.close()
        server.stop()
    }
}

class McpHttpApp(
    private val engine: McpEngine,
    private val token: String,
    private val portOf: () -> Int,
    private val events: (level: String, message: String) -> Unit,
) {
    init {
        require(token.isNotBlank()) { "token is required" }
    }

    private val sessions = ConcurrentHashMap<String, SseSession>()
    private val streamSessions = ConcurrentHashMap.newKeySet<String>()

    fun close() {
        sessions.values.forEach { it.closed = true }
        sessions.clear()
    }

    fun handle(request: HttpRequest): HttpResponse {
        guard(request)?.let { return it }
        val authed = authorized(request)
        return when {
            request.method == "GET" && request.path == "/" -> textResponse(
                200,
                "Shizuku MCP\nPOST /mcp\nGET /sse\n令牌通过 Authorization: Bearer 或 ?token= 传递。\n",
            )
            request.method == "GET" && request.path == "/health" -> jsonResponse(200, """{"status":"ok","name":"shizuku-mcp"}""")
            request.method == "POST" && request.path == "/mcp" -> {
                if (!authed) return unauthorized()
                postMcp(request)
            }
            request.method == "GET" && request.path == "/mcp" -> HttpResponse(
                status = 405,
                headers = listOf("Allow" to "POST, DELETE"),
                body = "method not allowed".toByteArray(),
            )
            request.method == "DELETE" && request.path == "/mcp" -> {
                if (!authed) return unauthorized()
                request.headers["mcp-session-id"]?.let { streamSessions.remove(it) }
                HttpResponse(status = 204, body = ByteArray(0))
            }
            request.method == "GET" && request.path == "/sse" -> {
                if (!authed) return unauthorized()
                openSse(request)
            }
            request.method == "POST" && request.path == "/message" -> {
                if (!authed) return unauthorized()
                postMessage(request)
            }
            else -> textResponse(404, "not found")
        }
    }

    private fun postMcp(request: HttpRequest): HttpResponse {
        val replied = engine.handle(request.body.toString(StandardCharsets.UTF_8))
        return when (replied) {
            HandleResult.Accepted -> HttpResponse(status = 202, body = ByteArray(0))
            is HandleResult.Json -> {
                val extra = mutableListOf<Pair<String, String>>()
                if (replied.freshSession) {
                    val id = UUID.randomUUID().toString()
                    streamSessions.add(id)
                    extra += "Mcp-Session-Id" to id
                }
                jsonResponse(200, replied.body, extra)
            }
        }
    }

    private fun openSse(request: HttpRequest): HttpResponse {
        if (sessions.size >= 16) return textResponse(503, "too many sessions")
        val id = UUID.randomUUID().toString().replace("-", "")
        val session = SseSession(id)
        sessions[id] = session
        val tokenInUrl = request.query["token"]?.let { constantTimeEquals(it, token) } == true
        val endpoint = buildString {
            append("http://127.0.0.1:")
            append(portOf())
            append("/message?sessionId=")
            append(id)
            if (tokenInUrl) {
                append("&token=")
                append(URLEncoder.encode(token, StandardCharsets.UTF_8))
            }
        }
        events("info", "sse session opened")
        return HttpResponse(
            status = 200,
            headers = listOf("Content-Type" to "text/event-stream; charset=utf-8"),
            stream = { writer ->
                try {
                    writer.write(sseFrame("endpoint", endpoint))
                    while (!session.closed) {
                        val frame = session.queue.poll(15, TimeUnit.SECONDS)
                        if (frame == null) writer.write(": ping\n\n".toByteArray(StandardCharsets.UTF_8))
                        else writer.write(frame)
                    }
                } catch (_: Exception) {
                    session.closed = true
                } finally {
                    sessions.remove(id, session)
                    events("info", "sse session closed")
                }
            },
        )
    }

    private fun postMessage(request: HttpRequest): HttpResponse {
        val id = request.query["sessionId"] ?: return textResponse(400, "sessionId is required")
        val session = sessions[id] ?: return textResponse(404, "unknown session")
        if (session.closed) return textResponse(404, "session closed")
        val replied = engine.handle(request.body.toString(StandardCharsets.UTF_8))
        val payload = when (replied) {
            HandleResult.Accepted -> null
            is HandleResult.Json -> sseFrame("message", replied.body)
        }
        if (payload != null && !session.queue.offer(payload, 2, TimeUnit.SECONDS)) {
            return textResponse(503, "session busy")
        }
        return HttpResponse(status = 202, body = ByteArray(0))
    }

    private fun guard(request: HttpRequest): HttpResponse? {
        val host = hostOnly(request.headers["host"] ?: return textResponse(400, "host is required"))
        if (host != "127.0.0.1" && host != "localhost") return textResponse(403, "forbidden host")
        val origin = request.headers["origin"] ?: return null
        val allowed = try {
            val uri = URI(origin)
            uri.scheme.equals("http", ignoreCase = true) &&
                (uri.host.equals("127.0.0.1", ignoreCase = true) || uri.host.equals("localhost", ignoreCase = true))
        } catch (_: Exception) {
            false
        }
        return if (allowed) null else textResponse(403, "forbidden origin")
    }

    private fun authorized(request: HttpRequest): Boolean {
        val header = request.headers["authorization"]
        if (header != null && header.startsWith("Bearer ", ignoreCase = true)) {
            if (constantTimeEquals(header.substring(7).trim(), token)) return true
        }
        val queryToken = request.query["token"]
        return queryToken != null && constantTimeEquals(queryToken, token)
    }

    private fun unauthorized(): HttpResponse = jsonResponse(
        401,
        """{"error":"unauthorized"}""",
        listOf("WWW-Authenticate" to "Bearer realm=\"shizuku-mcp\""),
    )

    private fun hostOnly(header: String): String = if (header.startsWith("[")) {
        header.substringAfter("[").substringBefore("]")
    } else {
        header.substringBefore(":")
    }.lowercase()

    private fun sseFrame(event: String, data: String): ByteArray = buildString {
        append("event: ").append(event).append('\n')
        data.lineSequence().forEach { line ->
            append("data: ").append(line).append('\n')
        }
        append('\n')
    }.toByteArray(StandardCharsets.UTF_8)

    private fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))
}
