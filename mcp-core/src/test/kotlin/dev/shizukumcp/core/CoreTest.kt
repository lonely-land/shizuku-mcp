package dev.shizukumcp.core

import dev.shizukumcp.core.http.McpHttpServer
import dev.shizukumcp.core.mcp.HandleResult
import dev.shizukumcp.core.mcp.McpEngine
import dev.shizukumcp.core.shell.ExecCodec
import dev.shizukumcp.core.shell.ExecResult
import dev.shizukumcp.core.shell.FileRead
import dev.shizukumcp.core.shell.JdkShellExecutor
import dev.shizukumcp.core.shell.ShellException
import dev.shizukumcp.core.shell.ShellExecutor
import dev.shizukumcp.core.shell.ShellQuote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Timeout(30)
class ShellQuoteTest {
    @Test
    fun quotesMetacharactersForRealShell() {
        val raw = "com.foo'; echo PWNED"
        val script = "printf %s ${ShellQuote.quote(raw)}"
        val process = ProcessBuilder("sh", "-c", script).start()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertEquals(raw, process.inputStream.readBytes().toString(StandardCharsets.UTF_8))
        assertEquals(0, process.exitValue())
    }
}

@Timeout(30)
class JdkShellExecutorTest {
    private val shell = JdkShellExecutor(maxStdoutBytes = 1024, maxStderrBytes = 256)

    @Test
    fun capturesStdoutStderrAndExitCode() {
        val result = shell.exec("echo out; echo err >&2; exit 3", 5_000)
        assertEquals(3, result.exitCode)
        assertEquals("out", result.stdout.trim())
        assertEquals("err", result.stderr.trim())
        assertFalse(result.timedOut)
    }

    @Test
    fun reportsTimeout() {
        val result = shell.exec("sleep 5", 200)
        assertTrue(result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun truncatesLargeOutputAndStillExits() {
        val result = shell.exec("i=0; while [ \"\$i\" -lt 5000 ]; do echo hello; i=\$((i+1)); done", 10_000)
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertTrue(result.stdout.length <= 1024)
    }

    @Test
    fun roundTripsFileAndCodec() {
        val dir = Files.createTempDirectory("shizuku-mcp")
        val path = dir.resolve("note.txt").toString()
        shell.writeFile(path, "你好".toByteArray())
        val read = shell.readFile(path, 100)
        assertEquals("你好", read.bytes.toString(StandardCharsets.UTF_8))
        assertFalse(read.truncated)
        val encoded = ExecCodec.toJson(ExecResult(0, "a\"b", "c", timedOut = false, truncated = true))
        val decoded = ExecCodec.fromJson(encoded)
        assertEquals("a\"b", decoded.stdout)
        assertTrue(decoded.truncated)
        assertTrue(shell.deleteFile(path))
    }
}

class FakeShell : ShellExecutor {
    val commands = mutableListOf<String>()
    val files = linkedMapOf<String, ByteArray>()
    var handler: (String) -> ExecResult = { ExecResult(0, "ok", "", timedOut = false, truncated = false) }

    override fun exec(command: String, timeoutMs: Long): ExecResult {
        commands += command
        return handler(command)
    }

    override fun readFile(path: String, maxBytes: Int): FileRead {
        val bytes = files[path] ?: throw ShellException("file not found: $path")
        val slice = if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
        return FileRead(slice, bytes.size.toLong(), bytes.size > maxBytes)
    }

    override fun writeFile(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
    }

    override fun deleteFile(path: String): Boolean = files.remove(path) != null
}

@Timeout(20)
class McpEngineTest {
    private val json = Json
    private val shell = FakeShell()
    private val engine = McpEngine(shell)

    @Test
    fun negotiatesInitializeAndListsTools() {
        val init = rpc("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""")
        val body = init.jsonObject
        assertEquals("2024-11-05", body.result().string("protocolVersion"))
        assertEquals("shizuku-mcp", body.result().obj("serverInfo").string("name"))
        val listed = rpc("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").jsonObject
        val names = listed.result().jsonArray("tools").map { it.jsonObject.string("name") }
        assertTrue("adb_shell" in names)
        assertTrue("screencap" in names)
        assertTrue("input_text" in names)
        assertEquals(21, names.size)
    }

    @Test
    fun runsShellAndRejectsBadCalls() {
        shell.handler = { ExecResult(0, "Pixel\n", "", timedOut = false, truncated = false) }
        val ok = call("adb_shell", buildJsonObject { put("command", "getprop ro.product.model") })
        assertEquals("getprop ro.product.model", shell.commands.single())
        assertEquals(false, ok.result().boolean("isError"))
        assertTrue(ok.result().jsonArray("content")[0].jsonObject.string("text").contains("Pixel"))

        val missing = call("adb_shell", buildJsonObject { })
        assertEquals(true, missing.result().boolean("isError"))

        val unknown = rpc("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"nope","arguments":{}}}""").jsonObject
        assertEquals(-32602, unknown.obj("error").int("code"))
        val parse = engine.handle("{")
        assertIs<HandleResult.Json>(parse)
        assertTrue(parse.body.contains("-32700"))
        assertIs<HandleResult.Accepted>(engine.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        val ping = rpc("""{"jsonrpc":"2.0","id":"p","method":"ping"}""").jsonObject
        assertEquals("p", ping.string("id"))
    }

    @Test
    fun quotesPathsAndBuildsInputCommands() {
        call("list_dir", buildJsonObject { put("path", "/sdcard/a';touch pwned") })
        assertEquals(
            "ls -la -- ${ShellQuote.quote("/sdcard/a';touch pwned")}",
            shell.commands.last(),
        )
        call("input_text", buildJsonObject { put("text", "hello world") })
        assertEquals("input text ${ShellQuote.quote("hello%sworld")}", shell.commands.last())
        val badKey = call("input_keyevent", buildJsonObject { put("key", "RM_RF") })
        assertEquals(true, badKey.result().boolean("isError"))
        call("input_keyevent", buildJsonObject { put("key", "HOME") })
        assertEquals("input keyevent KEYCODE_HOME", shell.commands.last())
        val badPkg = call("force_stop", buildJsonObject { put("package", "not a package") })
        assertEquals(true, badPkg.result().boolean("isError"))
        shell.commands.clear()
        call("device_info", buildJsonObject { })
        assertTrue(shell.commands.single().contains("\$(getprop ro.product.model)"))
    }

    @Test
    fun returnsScreenshotAsImage() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        shell.handler = { command ->
            if (command.startsWith("screencap")) shell.files["/data/local/tmp/shizuku-mcp-shot.png"] = png
            ExecResult(0, "", "", timedOut = false, truncated = false)
        }
        val result = call("screencap", buildJsonObject { })
        val image = result.result().jsonArray("content")[0].jsonObject
        assertEquals("image", image.string("type"))
        assertEquals("image/png", image.string("mimeType"))
        assertTrue(image.string("data").isNotEmpty())
        assertFalse(shell.files.containsKey("/data/local/tmp/shizuku-mcp-shot.png"))
    }

    @Test
    fun readsResourcesAndPrompts() {
        shell.handler = { ExecResult(0, "pkg\n", "", timedOut = false, truncated = false) }
        val resource = rpc("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"device://info"}}""").jsonObject
        assertTrue(resource.result().jsonArray("contents")[0].jsonObject.string("text").contains("pkg"))
        val prompt = rpc("""{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"inspect_screen"}}""").jsonObject
        assertEquals("user", prompt.result().jsonArray("messages")[0].jsonObject.string("role"))
    }

    private fun call(name: String, args: JsonObject): JsonObject {
        val message = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            put("params", buildJsonObject {
                put("name", name)
                put("arguments", args)
            })
        }.toString()
        return rpc(message).jsonObject
    }

    private fun rpc(message: String): kotlinx.serialization.json.JsonElement {
        val result = engine.handle(message)
        val jsonResult = assertIs<HandleResult.Json>(result)
        return json.parseToJsonElement(jsonResult.body)
    }
}

@Timeout(30)
class McpHttpTest {
    private val json = Json

    @Test
    fun requiresTokenBindsLoopbackAndServesTools() {
        val shell = FakeShell()
        shell.handler = { ExecResult(0, "hello", "", timedOut = false, truncated = false) }
        val server = McpHttpServer(port = 0, token = "test-token", engine = McpEngine(shell))
        server.start()
        try {
            val port = server.boundPort
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val health = client.send(request(port, "GET", "/health", null, null), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, health.statusCode())

            val denied = client.send(request(port, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", null), HttpResponse.BodyHandlers.ofString())
            assertEquals(401, denied.statusCode())

            val ping = client.send(
                request(port, "POST", "/mcp", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "test-token"),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, ping.statusCode())
            assertTrue(ping.headers().firstValue("Mcp-Session-Id").isEmpty)

            val init = client.send(
                request(
                    port,
                    "POST",
                    "/mcp",
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{}}}""",
                    "test-token",
                ),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, init.statusCode())
            assertTrue(init.headers().firstValue("Mcp-Session-Id").isPresent)
            assertTrue(init.body().contains("shizuku-mcp"))

            val call = client.send(
                request(
                    port,
                    "POST",
                    "/mcp",
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"adb_shell","arguments":{"command":"echo hi"}}}""",
                    "test-token",
                ),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, call.statusCode())
            val body = json.parseToJsonElement(call.body()).jsonObject
            assertEquals(false, body.result().boolean("isError"))
            assertEquals("echo hi", shell.commands.single())

            val evil = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/mcp"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer test-token")
                    .header("Origin", "https://evil.example")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(403, evil.statusCode())
            assertLoopbackOnly(port)
        } finally {
            server.stop()
        }
    }

    @Test
    fun servesLegacySseAndChunkedBodies() {
        val shell = FakeShell()
        val server = McpHttpServer(port = 0, token = "test-token", engine = McpEngine(shell))
        server.start()
        try {
            val port = server.boundPort
            val socket = Socket("127.0.0.1", port)
            socket.soTimeout = 5_000
            val writer = socket.getOutputStream()
            writer.write(
                "GET /sse?token=test-token HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray(),
            )
            writer.flush()
            val headerAndEvent = readUntil(socket.getInputStream(), "\n\n")
            assertTrue(headerAndEvent.contains("text/event-stream"))
            val session = Regex("sessionId=([a-f0-9]+)").find(headerAndEvent)?.groupValues?.get(1)
            assertTrue(!session.isNullOrBlank())

            val client = HttpClient.newBuilder().build()
            val posted = client.send(
                request(
                    port,
                    "POST",
                    "/message?sessionId=$session&token=test-token",
                    """{"jsonrpc":"2.0","id":7,"method":"ping"}""",
                    null,
                ),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(202, posted.statusCode())
            val message = readUntil(socket.getInputStream(), "\n\n")
            assertTrue(message.contains("\"id\":7") || message.contains("\"id\": 7"))
            socket.close()

            val chunked = Socket("127.0.0.1", port)
            chunked.use {
                val payload = """{"jsonrpc":"2.0","id":3,"method":"ping"}"""
                val size = payload.toByteArray().size.toString(16)
                val raw = "POST /mcp?token=test-token HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n$size\r\n$payload\r\n0\r\n\r\n"
                it.getOutputStream().write(raw.toByteArray())
                it.getOutputStream().flush()
                val response = readUntil(it.getInputStream(), "\r\n\r\n") + String(it.getInputStream().readNBytes(256))
                assertTrue(response.contains("200"))
                assertTrue(response.contains("\"id\":3") || response.contains("\"id\": 3"))
            }
        } finally {
            server.stop()
        }
    }

    private fun assertLoopbackOnly(port: Int) {
        val lan = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { nic -> nic.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?: return
        assertFails {
            Socket().use { it.connect(InetSocketAddress(lan, port), 400) }
        }
    }

    private fun request(port: Int, method: String, path: String, body: String?, token: String?): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(5))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return builder.build()
    }

    private fun readUntil(input: java.io.InputStream, marker: String): String {
        val out = StringBuilder()
        while (!out.contains(marker)) {
            val b = input.read()
            if (b < 0) break
            out.append(b.toChar())
        }
        return out.toString()
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun kotlinx.serialization.json.JsonElement.get(name: String) = jsonObject.getValue(name)

private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

private fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBoolean()

private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.content.toInt()

private fun JsonObject.jsonArray(name: String) = getValue(name).jsonArray
