package dev.shizukumcp.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.shizukumcp.app.AppRuntime
import dev.shizukumcp.app.MainViewModel
import dev.shizukumcp.app.ShizukuStatus
import dev.shizukumcp.core.mcp.adbToolSummaries
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeRoute(model: MainViewModel) {
    val shizuku by model.shizuku.collectAsState()
    val phase by model.phase.collectAsState()
    val error by model.lastError.collectAsState()
    val port by model.port.collectAsState()
    val token by model.token.collectAsState()
    val logs by model.logs.collectAsState()
    val boundPort by model.boundPort.collectAsState()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tools = remember { adbToolSummaries() }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.start()
    }

    fun toast(text: String) {
        scope.launch { snackbars.showSnackbar(text) }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "Shizuku MCP",
                subtitle = "本机 ADB",
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(padding).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(shizuku, phase)
                if (!error.isNullOrBlank()) {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        Text(
                            text = error!!,
                            color = MiuixTheme.colorScheme.error,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
                PrimaryAction(shizuku, phase) {
                    when (shizuku) {
                        ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING -> model.openShizuku()
                        ShizukuStatus.DENIED -> model.requestPermission()
                        ShizukuStatus.AUTHORIZED -> when (phase) {
                            AppRuntime.Phase.RUNNING -> model.stop()
                            AppRuntime.Phase.STARTING -> Unit
                            AppRuntime.Phase.STOPPED -> {
                                if (needsNotificationPermission(context)) {
                                    notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    model.start()
                                }
                            }
                        }
                    }
                }
                if (shizuku == ShizukuStatus.AUTHORIZED) {
                    ConnectionCard(
                        port = boundPort ?: port,
                        editablePort = port,
                        token = token,
                        running = phase != AppRuntime.Phase.STOPPED,
                        onPort = { model.savePort(it) },
                        onCopy = { label, text ->
                            copyText(context, label, text)
                            toast("已复制 $label")
                        },
                        onRegenerate = {
                            model.regenerateToken()
                            toast("已生成新令牌")
                        },
                    )
                } else {
                    HelpCard(shizuku)
                }
                TextButton(
                    text = "电池优化设置",
                    onClick = {
                        try {
                            openBatterySettings(context)
                        } catch (_: Exception) {
                            toast("无法打开电池设置")
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxWidth(),
                )
                ToolsCard(tools)
                LogsCard(logs)
                Text(
                    text = "命令以 ADB shell 身份执行。服务只监听 127.0.0.1，并校验令牌。",
                    modifier = Modifier.padding(horizontal = 28.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote2,
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: ShizukuStatus, phase: AppRuntime.Phase) {
    val installed = status != ShizukuStatus.NOT_INSTALLED
    val granted = status == ShizukuStatus.AUTHORIZED
    val running = phase == AppRuntime.Phase.RUNNING
    SmallTitle(text = "状态")
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Step("01", "Shizuku", installed)
            Step("02", "授权", granted)
            Step("03", "服务", running)
        }
    }
}

@Composable
private fun Step(index: String, label: String, done: Boolean) {
    val colors = MiuixTheme.colorScheme
    Column {
        Text(
            text = index,
            color = if (done) colors.primary else colors.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = label,
            color = if (done) colors.onSurface else colors.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body1,
        )
    }
}

@Composable
private fun PrimaryAction(status: ShizukuStatus, phase: AppRuntime.Phase, onClick: () -> Unit) {
    val running = phase == AppRuntime.Phase.RUNNING
    val label = when {
        status == ShizukuStatus.NOT_INSTALLED -> "安装 Shizuku"
        status == ShizukuStatus.NOT_RUNNING -> "打开 Shizuku"
        status == ShizukuStatus.DENIED -> "授予 ADB 权限"
        phase == AppRuntime.Phase.STARTING -> "正在启动…"
        running -> "停止服务"
        else -> "启动 MCP 服务"
    }
    TextButton(
        text = label,
        onClick = onClick,
        enabled = phase != AppRuntime.Phase.STARTING,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
        colors = if (running) {
            ButtonDefaults.textButtonColors()
        } else {
            ButtonDefaults.textButtonColorsPrimary()
        },
    )
}

@Composable
private fun HelpCard(status: ShizukuStatus) {
    val text = when (status) {
        ShizukuStatus.NOT_INSTALLED -> "先安装 Shizuku。它是这台手机把 ADB 权限交给本应用的通道。"
        ShizukuStatus.NOT_RUNNING -> "在 Shizuku 里用无线调试或电脑 adb 启动服务，然后回到这里授权。"
        ShizukuStatus.DENIED -> "授权后，本应用才能以 ADB shell 的身份执行命令。"
        ShizukuStatus.AUTHORIZED -> ""
    }
    SmallTitle(text = "准备")
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(text = text, style = MiuixTheme.textStyles.body1)
    }
}

@Composable
private fun ConnectionCard(
    port: Int,
    editablePort: Int,
    token: String,
    running: Boolean,
    onPort: (Int) -> Unit,
    onCopy: (String, String) -> Unit,
    onRegenerate: () -> Unit,
) {
    var portText by remember(editablePort) { mutableStateOf(editablePort.toString()) }
    var reveal by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    SmallTitle(text = "连接")
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (running) "服务正在监听" else "启动后才会接受连接",
                style = MiuixTheme.textStyles.body1,
            )
            Text(
                text = "在电脑上执行 adb forward，把电脑的本地端口转到手机。",
                color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
            CodeLine("adb forward tcp:$port tcp:$port")
            TextField(
                value = portText,
                onValueChange = { value ->
                    portText = value.filter { it.isDigit() }.take(5)
                    val parsed = portText.toIntOrNull()
                    if (parsed != null && parsed in 1024..65535) onPort(parsed)
                },
                label = "端口",
                enabled = !running,
                singleLine = true,
            )
            Text(text = "令牌", color = colors.onSurfaceVariantSummary, style = MiuixTheme.textStyles.footnote1)
            SelectionContainer {
                Text(
                    text = if (reveal) token else "•".repeat(24),
                    fontFamily = FontFamily.Monospace,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Text(
                text = "Cursor / Claude 配置",
                color = colors.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
            CodeLine(streamableConfig(port, token))
        }
    }
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            text = "复制转发命令",
            onClick = { onCopy("转发命令", "adb forward tcp:$port tcp:$port") },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = if (reveal) "隐藏令牌" else "显示令牌",
            onClick = { reveal = !reveal },
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            text = "重新生成",
            onClick = onRegenerate,
            enabled = !running,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = "复制配置",
            onClick = { onCopy("MCP 配置", streamableConfig(port, token)) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
    TextButton(
        text = "复制 SSE 配置",
        onClick = { onCopy("SSE 配置", sseConfig(port, token)) },
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
    )
}

@Composable
private fun ToolsCard(tools: List<Pair<String, String>>) {
    SmallTitle(text = "工具")
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tools.forEach { (name, summary) ->
                Column {
                    Text(
                        text = name,
                        color = MiuixTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(text = summary, style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<AppRuntime.LogLine>) {
    SmallTitle(text = "日志")
    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        if (logs.isEmpty()) {
            Text(
                text = "还没有请求。",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                logs.take(40).forEach { line ->
                    Text(
                        text = "${formatTime(line.atMs)}  ${line.message}",
                        color = if (line.level == "error") {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        fontFamily = FontFamily.Monospace,
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeLine(text: String) {
    SelectionContainer {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            style = MiuixTheme.textStyles.footnote1,
        )
    }
}

private fun streamableConfig(port: Int, token: String) = """
{
  "mcpServers": {
    "shizuku-adb": {
      "url": "http://127.0.0.1:$port/mcp",
      "headers": {
        "Authorization": "Bearer $token"
      }
    }
  }
}
""".trimIndent()

private fun sseConfig(port: Int, token: String): String {
    val encoded = URLEncoder.encode(token, Charsets.UTF_8.name())
    return """
{
  "mcpServers": {
    "shizuku-adb": {
      "url": "http://127.0.0.1:$port/sse?token=$encoded"
    }
  }
}
    """.trimIndent()
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun needsNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return false
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

private fun openBatterySettings(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
        Uri.parse("package:${context.packageName}"),
    )
    context.startActivity(intent)
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun formatTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(clock)
