package dev.shizukumcp.core.mcp

import dev.shizukumcp.core.shell.FileRead
import dev.shizukumcp.core.shell.ShellException
import dev.shizukumcp.core.shell.ShellExecutor
import dev.shizukumcp.core.shell.ShellQuote
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

data class ToolContent(
    val type: String,
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
)

data class ToolCallResult(
    val content: List<ToolContent>,
    val isError: Boolean,
)

class Tool(
    val name: String,
    val description: String,
    val summaryZh: String,
    val inputSchema: JsonObject,
    val call: (JsonObject) -> ToolCallResult,
)

fun adbToolSummaries(): List<Pair<String, String>> =
    createAdbTools(UnusedShell).map { it.name to it.summaryZh }

fun createAdbTools(shell: ShellExecutor): List<Tool> = listOf(
    tool(
        name = "adb_shell",
        summaryZh = "执行 shell 命令",
        description = "Run a shell command as the ADB shell user on this device. There is no adb host binary; pass the command that would follow `adb shell`.",
        schema = schema(
            "command" to strProp("Shell command to run"),
            "timeout_ms" to intProp("Timeout in milliseconds, 100 to 120000. Default 30000."),
            required = listOf("command"),
        ),
    ) { args ->
        val command = args.string("command")?.takeIf { it.isNotBlank() }
            ?: return@tool err("command is required")
        if (command.length > 100_000 || command.contains('\u0000')) return@tool err("command is invalid")
        if (args.containsKey("timeout_ms") && args.integer("timeout_ms") == null) return@tool err("timeout_ms must be an integer")
        val timeout = args.integer("timeout_ms") ?: 30_000
        if (timeout !in 100..120_000) return@tool err("timeout_ms must be between 100 and 120000")
        run(shell, command, timeout.toLong())
    },
    tool(
        name = "device_info",
        summaryZh = "读取机型、系统版本和分辨率",
        description = "Read manufacturer, model, Android version, ABI, serial, user id, screen size, and density.",
        schema = schema(),
    ) { _ ->
        run(shell, DEVICE_INFO_SCRIPT, 10_000)
    },
    tool(
        name = "get_prop",
        summaryZh = "读取系统属性",
        description = "Read one Android system property, or a short curated set when key is omitted.",
        schema = schema("key" to strProp("Property name, for example ro.build.version.sdk")),
    ) { args ->
        val key = args.string("key")
        if (key == null) {
            run(shell, DEVICE_INFO_SCRIPT, 10_000)
        } else {
            if (!key.matches(PROP_KEY)) return@tool err("invalid property key")
            run(shell, "getprop $key", 5_000)
        }
    },
    tool(
        name = "list_packages",
        summaryZh = "列出已安装应用",
        description = "List installed package names. user_only defaults to true (third-party apps).",
        schema = schema(
            "user_only" to boolProp("Only packages installed by the user. Default true."),
            "enabled_only" to boolProp("Only enabled packages."),
            "filter" to strProp("Optional package-name filter passed to pm. Letters, digits, dots, and underscores only."),
        ),
    ) { args ->
        val userOnly = args.boolean("user_only") ?: true
        val enabledOnly = args.boolean("enabled_only") ?: false
        val filter = args.string("filter").orEmpty()
        if (filter.isNotEmpty() && !filter.matches(PACKAGE_FILTER)) return@tool err("invalid filter")
        val parts = mutableListOf("pm", "list", "packages")
        if (userOnly) parts += "-3"
        if (enabledOnly) parts += "-e"
        if (filter.isNotEmpty()) parts += filter
        run(shell, parts.joinToString(" "), 20_000)
    },
    tool(
        name = "app_info",
        summaryZh = "查看应用版本和安装路径",
        description = "Show the APK path and version fields for one package.",
        schema = schema("package" to strProp("Application id"), required = listOf("package")),
    ) { args ->
        val pkg = args.packageName("package") ?: return@tool err("package must be an Android package name")
        val q = ShellQuote.quote(pkg)
        run(
            shell,
            "pm path $q; dumpsys package $q | grep -E 'versionName=|versionCode=|pkgFlags=|enabled=|installerPackageName=' | head -n 50",
            15_000,
        )
    },
    tool(
        name = "launch_app",
        summaryZh = "启动应用",
        description = "Launch an app by package name. Pass activity to start a specific component.",
        schema = schema(
            "package" to strProp("Application id"),
            "activity" to strProp("Optional activity class, such as .MainActivity or the fully qualified class name."),
            required = listOf("package"),
        ),
    ) { args ->
        val pkg = args.packageName("package") ?: return@tool err("package must be an Android package name")
        val activity = args.string("activity")
        val command = if (activity.isNullOrBlank()) {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p ${ShellQuote.quote(pkg)}"
        } else {
            val component = componentName(pkg, activity) ?: return@tool err("activity is invalid")
            "am start -n ${ShellQuote.quote(component)}"
        }
        run(shell, command, 15_000)
    },
    tool(
        name = "force_stop",
        summaryZh = "强制停止应用",
        description = "Force-stop an application.",
        schema = schema("package" to strProp("Application id"), required = listOf("package")),
    ) { args ->
        val pkg = args.packageName("package") ?: return@tool err("package must be an Android package name")
        run(shell, "am force-stop ${ShellQuote.quote(pkg)}", 10_000)
    },
    tool(
        name = "install_apk",
        summaryZh = "安装设备上的 APK",
        description = "Install an APK that is already stored on the device, for example /data/local/tmp/app.apk. This does not upload a file from the computer.",
        schema = schema("path" to strProp("Absolute path of the APK on the device"), required = listOf("path")),
    ) { args ->
        val path = args.absolutePath("path") ?: return@tool err("path must be an absolute device path")
        run(shell, "pm install -r -- ${ShellQuote.quote(path)}", 120_000)
    },
    tool(
        name = "uninstall_app",
        summaryZh = "卸载应用",
        description = "Uninstall an application. Set keep_data to pass pm uninstall -k.",
        schema = schema(
            "package" to strProp("Application id"),
            "keep_data" to boolProp("Keep data and cache. Default false."),
            required = listOf("package"),
        ),
    ) { args ->
        val pkg = args.packageName("package") ?: return@tool err("package must be an Android package name")
        val keep = if (args.boolean("keep_data") == true) "-k " else ""
        run(shell, "pm uninstall $keep${ShellQuote.quote(pkg)}", 30_000)
    },
    tool(
        name = "screencap",
        summaryZh = "截取当前屏幕",
        description = "Capture the current screen and return a PNG image.",
        schema = schema(),
    ) { _ ->
        val path = "/data/local/tmp/shizuku-mcp-shot.png"
        val q = ShellQuote.quote(path)
        try {
            val shot = shell.exec("screencap -p $q", 15_000)
            if (shot.timedOut || shot.exitCode != 0) {
                return@tool err(formatExec(shot).ifBlank { "screencap failed" })
            }
            val file = try {
                shell.readFile(path, MAX_IMAGE_BYTES)
            } catch (e: Exception) {
                return@tool err(e.message ?: "failed to read screenshot")
            }
            if (file.truncated) return@tool err("screenshot is larger than $MAX_IMAGE_BYTES bytes")
            if (!isPng(file.bytes)) return@tool err("screencap did not produce a PNG")
            ToolCallResult(
                listOf(ToolContent(type = "image", data = Base64.getEncoder().encodeToString(file.bytes), mimeType = "image/png")),
                isError = false,
            )
        } finally {
            ignore { shell.deleteFile(path) }
        }
    },
    tool(
        name = "input_tap",
        summaryZh = "点击坐标",
        description = "Tap a screen coordinate in pixels.",
        schema = schema(
            "x" to intProp("X coordinate"),
            "y" to intProp("Y coordinate"),
            required = listOf("x", "y"),
        ),
    ) { args ->
        val x = args.coord("x") ?: return@tool err("x must be an integer from 0 to 20000")
        val y = args.coord("y") ?: return@tool err("y must be an integer from 0 to 20000")
        run(shell, "input tap $x $y", 5_000)
    },
    tool(
        name = "input_swipe",
        summaryZh = "滑动",
        description = "Swipe from one coordinate to another.",
        schema = schema(
            "x1" to intProp("Start X"),
            "y1" to intProp("Start Y"),
            "x2" to intProp("End X"),
            "y2" to intProp("End Y"),
            "duration_ms" to intProp("Duration in milliseconds. Default 300."),
            required = listOf("x1", "y1", "x2", "y2"),
        ),
    ) { args ->
        val x1 = args.coord("x1") ?: return@tool err("x1 is invalid")
        val y1 = args.coord("y1") ?: return@tool err("y1 is invalid")
        val x2 = args.coord("x2") ?: return@tool err("x2 is invalid")
        val y2 = args.coord("y2") ?: return@tool err("y2 is invalid")
        val duration = args.integer("duration_ms") ?: 300
        if (duration !in 1..10_000) return@tool err("duration_ms must be between 1 and 10000")
        run(shell, "input swipe $x1 $y1 $x2 $y2 $duration", 15_000)
    },
    tool(
        name = "input_text",
        summaryZh = "输入 ASCII 文本",
        description = "Type printable ASCII text into the focused field. Spaces are supported. Non-ASCII characters and percent signs are rejected because `input text` cannot represent them reliably.",
        schema = schema("text" to strProp("Printable ASCII text, at most 300 characters"), required = listOf("text")),
    ) { args ->
        val text = args.string("text") ?: return@tool err("text is required")
        if (text.isEmpty() || text.length > 300) return@tool err("text must be 1 to 300 characters")
        if (text.any { it < ' ' || it > '~' || it == '%' }) {
            return@tool err("text must be printable ASCII without '%'")
        }
        val encoded = text.replace(" ", "%s")
        run(shell, "input text ${ShellQuote.quote(encoded)}", 10_000)
    },
    tool(
        name = "input_keyevent",
        summaryZh = "发送按键",
        description = "Send a key event. Use a name such as HOME, BACK, ENTER, or DPAD_CENTER, or a numeric key code from 0 to 400.",
        schema = schema("key" to strProp("Key name or numeric key code"), required = listOf("key")),
    ) { args ->
        val key = args.string("key") ?: return@tool err("key is required")
        val code = normalizeKey(key) ?: return@tool err("unsupported key")
        run(shell, "input keyevent $code", 5_000)
    },
    tool(
        name = "ui_dump",
        summaryZh = "导出界面结构",
        description = "Dump the current window hierarchy with uiautomator and return the XML.",
        schema = schema(),
    ) { _ ->
        val path = "/data/local/tmp/shizuku-mcp-ui.xml"
        val q = ShellQuote.quote(path)
        try {
            run(shell, "uiautomator dump $q >/dev/null && cat $q", 25_000)
        } finally {
            ignore { shell.deleteFile(path) }
        }
    },
    tool(
        name = "logcat",
        summaryZh = "读取日志",
        description = "Read a bounded logcat dump. This does not stream forever.",
        schema = schema(
            "lines" to intProp("Number of lines, 1 to 1000. Default 200."),
            "buffer" to strProp("One of main, system, crash, events. Default main."),
            "filter" to strProp("Optional logcat filter such as ActivityManager:I"),
        ),
    ) { args ->
        val lines = args.integer("lines") ?: 200
        if (lines !in 1..1_000) return@tool err("lines must be between 1 and 1000")
        val buffer = args.string("buffer") ?: "main"
        if (buffer !in LOG_BUFFERS) return@tool err("buffer must be main, system, crash, or events")
        val filter = args.string("filter")
        if (filter != null && !filter.matches(LOG_FILTER)) return@tool err("invalid logcat filter")
        val command = buildString {
            append("logcat -d -t $lines -b $buffer")
            if (filter != null) append(" -s ").append(ShellQuote.quote(filter))
        }
        run(shell, command, 15_000)
    },
    tool(
        name = "current_focus",
        summaryZh = "查看当前焦点窗口",
        description = "Show the currently focused window and activity.",
        schema = schema(),
    ) { _ ->
        run(shell, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 20", 10_000)
    },
    tool(
        name = "list_dir",
        summaryZh = "列出目录",
        description = "List a directory as the shell user.",
        schema = schema("path" to strProp("Absolute or relative directory path"), required = listOf("path")),
    ) { args ->
        val path = args.safePath("path") ?: return@tool err("path is invalid")
        run(shell, "ls -la -- ${ShellQuote.quote(path)}", 10_000)
    },
    tool(
        name = "read_file",
        summaryZh = "读取文本文件",
        description = "Read a file as the shell user, up to max_bytes (default 65536, max 1000000). Binary files are returned as base64.",
        schema = schema(
            "path" to strProp("File path"),
            "max_bytes" to intProp("Maximum bytes to read. Default 65536."),
            required = listOf("path"),
        ),
    ) { args ->
        val path = args.safePath("path") ?: return@tool err("path is invalid")
        val maxBytes = args.integer("max_bytes") ?: 65_536
        if (maxBytes !in 1..1_000_000) return@tool err("max_bytes must be between 1 and 1000000")
        val file = try {
            shell.readFile(path, maxBytes)
        } catch (e: Exception) {
            return@tool err(e.message ?: "read failed")
        }
        readResult(file)
    },
    tool(
        name = "write_file",
        summaryZh = "写入文本文件",
        description = "Write UTF-8 text to a path as the shell user. At most 131072 bytes. Parent directories are created when possible.",
        schema = schema(
            "path" to strProp("Destination path"),
            "text" to strProp("UTF-8 text"),
            required = listOf("path", "text"),
        ),
    ) { args ->
        val path = args.safePath("path") ?: return@tool err("path is invalid")
        val text = args.string("text") ?: return@tool err("text is required")
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 131_072) return@tool err("text is larger than 131072 bytes")
        try {
            shell.writeFile(path, bytes)
        } catch (e: Exception) {
            return@tool err(e.message ?: "write failed")
        }
        ok("wrote ${bytes.size} bytes to $path")
    },
    tool(
        name = "open_url",
        summaryZh = "打开链接",
        description = "Open a URI with ACTION_VIEW. Supports http(s), app schemes, and other URIs without control characters.",
        schema = schema("url" to strProp("URI to open"), required = listOf("url")),
    ) { args ->
        val url = args.string("url") ?: return@tool err("url is required")
        if (url.length !in 1..2_000 || url.any { it.isISOControl() || it.isWhitespace() }) {
            return@tool err("url is invalid")
        }
        if (!url.matches(URI_SCHEME)) return@tool err("url must include a scheme")
        run(shell, "am start -a android.intent.action.VIEW -d ${ShellQuote.quote(url)}", 10_000)
    },
)

private const val MAX_IMAGE_BYTES = 6_000_000

private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val PACKAGE_FILTER = Regex("^[A-Za-z0-9._]{1,200}$")
private val PROP_KEY = Regex("^[A-Za-z0-9._:-]{1,128}$")
private val ACTIVITY_CLASS = Regex("^[A-Za-z0-9_.$]+$")
private val LOG_FILTER = Regex("^[A-Za-z0-9_.$/]+(?::[VDIWEFS])?$")
private val LOG_BUFFERS = setOf("main", "system", "crash", "events")
private val URI_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.+")

private val DEVICE_INFO_SCRIPT = """
set +e
echo "manufacturer=${"$"}(getprop ro.product.manufacturer)"
echo "model=${"$"}(getprop ro.product.model)"
echo "device=${"$"}(getprop ro.product.device)"
echo "android=${"$"}(getprop ro.build.version.release)"
echo "sdk=${"$"}(getprop ro.build.version.sdk)"
echo "abi=${"$"}(getprop ro.product.cpu.abi)"
echo "serial=${"$"}(getprop ro.serialno)"
echo "security_patch=${"$"}(getprop ro.build.version.security_patch)"
id
wm size
wm density
""".trimIndent()

private val KEYS = mapOf(
    "HOME" to "KEYCODE_HOME",
    "BACK" to "KEYCODE_BACK",
    "ENTER" to "KEYCODE_ENTER",
    "MENU" to "KEYCODE_MENU",
    "APP_SWITCH" to "KEYCODE_APP_SWITCH",
    "POWER" to "KEYCODE_POWER",
    "WAKEUP" to "KEYCODE_WAKEUP",
    "SLEEP" to "KEYCODE_SLEEP",
    "VOLUME_UP" to "KEYCODE_VOLUME_UP",
    "VOLUME_DOWN" to "KEYCODE_VOLUME_DOWN",
    "VOLUME_MUTE" to "KEYCODE_VOLUME_MUTE",
    "DEL" to "KEYCODE_DEL",
    "FORWARD_DEL" to "KEYCODE_FORWARD_DEL",
    "TAB" to "KEYCODE_TAB",
    "ESCAPE" to "KEYCODE_ESCAPE",
    "DPAD_UP" to "KEYCODE_DPAD_UP",
    "DPAD_DOWN" to "KEYCODE_DPAD_DOWN",
    "DPAD_LEFT" to "KEYCODE_DPAD_LEFT",
    "DPAD_RIGHT" to "KEYCODE_DPAD_RIGHT",
    "DPAD_CENTER" to "KEYCODE_DPAD_CENTER",
    "SEARCH" to "KEYCODE_SEARCH",
    "MEDIA_PLAY_PAUSE" to "KEYCODE_MEDIA_PLAY_PAUSE",
    "CAMERA" to "KEYCODE_CAMERA",
)

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private object UnusedShell : ShellExecutor {
    override fun exec(command: String, timeoutMs: Long) = error("unused")
    override fun readFile(path: String, maxBytes: Int) = error("unused")
    override fun writeFile(path: String, bytes: ByteArray) = error("unused")
    override fun deleteFile(path: String) = error("unused")
}

private fun tool(
    name: String,
    summaryZh: String,
    description: String,
    schema: JsonObject,
    call: (JsonObject) -> ToolCallResult,
) = Tool(name, description, summaryZh, schema, call)

private fun schema(vararg props: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { props.forEach { (key, value) -> put(key, value) } })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }

private fun strProp(description: String) = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun intProp(description: String) = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun boolProp(description: String) = buildJsonObject {
    put("type", "boolean")
    put("description", description)
}

private fun JsonObject.string(name: String): String? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

private fun JsonObject.integer(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    primitive.content.toIntOrNull()?.let { return it }
    val asDouble = primitive.content.toDoubleOrNull() ?: return null
    if (asDouble % 1.0 != 0.0) return null
    if (asDouble > Int.MAX_VALUE || asDouble < Int.MIN_VALUE) return null
    return asDouble.toInt()
}

private fun JsonObject.boolean(name: String): Boolean? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonObject.packageName(name: String): String? =
    string(name)?.takeIf { it.matches(PACKAGE_NAME) }

private fun JsonObject.coord(name: String): Int? = integer(name)?.takeIf { it in 0..20_000 }

private fun JsonObject.safePath(name: String): String? {
    val value = string(name) ?: return null
    if (value.isBlank() || value.length > 1024 || value.any { it.isISOControl() }) return null
    return value
}

private fun JsonObject.absolutePath(name: String): String? {
    val value = safePath(name) ?: return null
    if (!value.startsWith("/")) return null
    return value
}

private fun componentName(pkg: String, activity: String): String? {
    val cls = when {
        activity.startsWith(".") -> activity
        activity.contains('.') -> activity
        else -> ".$activity"
    }
    if (!cls.matches(ACTIVITY_CLASS)) return null
    return "$pkg/$cls"
}

private fun normalizeKey(raw: String): String? {
    val name = raw.trim().removePrefix("KEYCODE_").uppercase()
    if (name.all { it.isDigit() }) {
        val code = name.toIntOrNull() ?: return null
        if (code in 0..400) return code.toString()
        return null
    }
    return KEYS[name]
}

private fun run(shell: ShellExecutor, command: String, timeoutMs: Long): ToolCallResult {
    val result = try {
        shell.exec(command, timeoutMs)
    } catch (e: Exception) {
        return err(e.message ?: e.toString())
    }
    return ToolCallResult(listOf(ToolContent(type = "text", text = formatExec(result))), isError = result.timedOut || result.exitCode != 0)
}

private fun formatExec(result: dev.shizukumcp.core.shell.ExecResult): String = buildString {
    if (result.stdout.isNotEmpty()) append(result.stdout.trimEnd())
    if (result.stderr.isNotEmpty()) {
        if (isNotEmpty()) append('\n')
        append(result.stderr.trimEnd())
    }
    if (result.timedOut) {
        if (isNotEmpty()) append('\n')
        append("[timed out]")
    }
    if (result.truncated) {
        if (isNotEmpty()) append('\n')
        append("[truncated]")
    }
    if (isNotEmpty()) append('\n')
    append("[exit ${result.exitCode}]")
}

private fun readResult(file: FileRead): ToolCallResult {
    val binary = file.bytes.any { it == 0.toByte() }
    val body = if (binary) {
        "binary file, ${file.size} bytes\n" + Base64.getEncoder().encodeToString(file.bytes)
    } else {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(file.bytes))
            .toString()
        if (file.truncated) "$text\n[truncated, ${file.size} bytes total]" else text
    }
    return ok(body)
}

private fun ok(text: String) = ToolCallResult(listOf(ToolContent(type = "text", text = text)), isError = false)

private fun err(text: String) = ToolCallResult(listOf(ToolContent(type = "text", text = text)), isError = true)

private fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_MAGIC.size && bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)

private fun ignore(block: () -> Unit) {
    try {
        block()
    } catch (_: ShellException) {
    } catch (_: Exception) {
    }
}
