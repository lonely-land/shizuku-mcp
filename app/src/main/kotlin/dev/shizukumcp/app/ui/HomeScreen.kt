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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.shizukumcp.app.AppRuntime
import dev.shizukumcp.app.MainViewModel
import dev.shizukumcp.app.ShizukuStatus
import dev.shizukumcp.core.mcp.adbToolSummaries
import kotlinx.coroutines.launch
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
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            StepRow(shizuku, phase)
            if (!error.isNullOrBlank()) {
                Banner(error!!)
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
            TextButton(onClick = {
                try {
                    openBatterySettings(context)
                } catch (_: Exception) {
                    toast("无法打开电池设置")
                }
            }) {
                Text("电池优化设置", color = Mist)
            }
            ToolsCard(tools)
            LogsCard(logs)
            Text(
                "命令以 ADB shell 身份执行。服务只监听 127.0.0.1，并校验令牌。",
                color = Mist,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Citron),
            contentAlignment = Alignment.Center,
        ) {
            Text(">_ ", color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Shizuku MCP", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("本机 ADB 的 MCP 服务", color = Mist, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StepRow(status: ShizukuStatus, phase: AppRuntime.Phase) {
    val installed = status != ShizukuStatus.NOT_INSTALLED
    val granted = status == ShizukuStatus.AUTHORIZED
    val running = phase == AppRuntime.Phase.RUNNING
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Step("01", "Shizuku", installed)
        Step("02", "授权", granted)
        Step("03", "服务", running)
    }
}

@Composable
private fun Step(index: String, label: String, done: Boolean) {
    Column {
        Text(index, color = if (done) Citron else Mist, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(label, color = if (done) Paper else Mist, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Banner(text: String) {
    Text(
        text,
        color = Paper,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Coral.copy(alpha = 0.18f))
            .padding(14.dp),
        fontSize = 14.sp,
    )
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
    Button(
        onClick = onClick,
        enabled = phase != AppRuntime.Phase.STARTING,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (running) Coral else Citron,
            contentColor = if (running) Paper else Ink,
            disabledContainerColor = InkRaised,
            disabledContentColor = Mist,
        ),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HelpCard(status: ShizukuStatus) {
    val text = when (status) {
        ShizukuStatus.NOT_INSTALLED -> "先安装 Shizuku。它是这台手机把 ADB 权限交给本应用的通道。"
        ShizukuStatus.NOT_RUNNING -> "在 Shizuku 里用无线调试或电脑 adb 启动服务，然后回到这里授权。"
        ShizukuStatus.DENIED -> "授权后，本应用才能以 ADB shell 的身份执行命令。"
        ShizukuStatus.AUTHORIZED -> ""
    }
    SurfaceCard { Text(text, color = Paper, fontSize = 15.sp) }
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
    SurfaceCard {
        Text("连接", color = Mist, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text(
            if (running) "服务正在监听" else "启动后才会接受连接",
            color = Paper,
            fontWeight = FontWeight.Medium,
        )
        Text(
            "在电脑上执行 adb forward，把电脑的本地端口转到手机。",
            color = Mist,
            fontSize = 13.sp,
        )
        CodeBlock("adb forward tcp:$port tcp:$port")
        TextButton(onClick = { onCopy("转发命令", "adb forward tcp:$port tcp:$port") }) {
            Text("复制转发命令", color = Citron)
        }
        OutlinedTextField(
            value = portText,
            onValueChange = { value ->
                portText = value.filter { it.isDigit() }.take(5)
                val parsed = portText.toIntOrNull()
                if (parsed != null && parsed in 1024..65535) onPort(parsed)
            },
            enabled = !running,
            label = { Text("端口") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("令牌", color = Mist, fontSize = 12.sp)
        SelectionContainer {
            Text(
                if (reveal) token else "•".repeat(24),
                color = Paper,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            )
        }
        Row {
            TextButton(onClick = { reveal = !reveal }) {
                Text(if (reveal) "隐藏令牌" else "显示令牌", color = Citron)
            }
            TextButton(onClick = onRegenerate, enabled = !running) {
                Text("重新生成", color = if (running) Mist else Citron)
            }
        }
        Text("Cursor / Claude 配置", color = Mist, fontSize = 12.sp)
        CodeBlock(streamableConfig(port, token))
        Row {
            TextButton(onClick = { onCopy("MCP 配置", streamableConfig(port, token)) }) {
                Text("复制配置", color = Citron)
            }
            TextButton(onClick = { onCopy("SSE 配置", sseConfig(port, token)) }) {
                Text("复制 SSE", color = Citron)
            }
        }
    }
}

@Composable
private fun ToolsCard(tools: List<Pair<String, String>>) {
    SurfaceCard {
        Text("工具", color = Mist, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        tools.forEach { (name, summary) ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(name, color = Citron, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(summary, color = Paper, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun LogsCard(logs: List<AppRuntime.LogLine>) {
    SurfaceCard {
        Text("日志", color = Mist, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        if (logs.isEmpty()) {
            Text("还没有请求。", color = Mist, fontSize = 14.sp)
        } else {
            logs.take(40).forEach { line ->
                Text(
                    "${formatTime(line.atMs)}  ${line.message}",
                    color = if (line.level == "error") Coral else Paper,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SurfaceCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(InkRaised)
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun CodeBlock(text: String) {
    SelectionContainer {
        Text(
            text,
            color = Citron,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Ink)
                .padding(12.dp),
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
