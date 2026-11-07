# Shizuku MCP

在 Android 手机上通过 [Shizuku](https://shizuku.rikka.app/) 取得 ADB shell 权限，并在本机启动一个 MCP 服务器，把这台设备的 shell 能力提供给 Cursor、Claude 或其他 MCP 客户端。

服务只绑定 `127.0.0.1`。每次请求都要带启动时生成的令牌。服务运行时会显示前台通知。

## 使用

1. 安装 Shizuku，并在 Shizuku 里用无线调试或电脑 adb 把它启动起来。
2. 打开 **Shizuku MCP**，授予 ADB 权限，然后启动服务。
3. 在电脑上把端口转进来。默认端口是 `27183`：

```bash
adb forward tcp:27183 tcp:27183
```

4. 把应用里复制的配置贴进 Cursor 的 `mcp.json`。内容类似：

```json
{
  "mcpServers": {
    "shizuku-adb": {
      "url": "http://127.0.0.1:27183/mcp",
      "headers": {
        "Authorization": "Bearer <令牌>"
      }
    }
  }
}
```

只支持旧版 SSE 的客户端可以使用 `http://127.0.0.1:27183/sse?token=<令牌>`。

从电脑安装 APK 时，先推到手机再调用 `install_apk`：

```bash
adb push app.apk /data/local/tmp/app.apk
```

## 工具

`adb_shell`、`device_info`、`get_prop`、`list_packages`、`app_info`、`launch_app`、`force_stop`、`install_apk`、`uninstall_app`、`screencap`、`input_tap`、`input_swipe`、`input_text`、`input_keyevent`、`ui_dump`、`logcat`、`current_focus`、`list_dir`、`read_file`、`write_file`、`open_url`。

这些调用跑在设备上的 shell 用户（Shizuku 以 root 启动时则是 root），不是电脑上的 `adb` 客户端。`input_text` 只接受可打印 ASCII。

## 开发

```bash
./gradlew :mcp-core:test
./gradlew :app:assembleDebug
```

协议、工具参数和 HTTP 传输在 `mcp-core` 里，不依赖 Android，可以用 JVM 测试。Shizuku 用户服务在单独进程里执行命令，应用进程只负责界面、前台服务和回环端口。

需要 Android SDK 37 和 JDK 21。
