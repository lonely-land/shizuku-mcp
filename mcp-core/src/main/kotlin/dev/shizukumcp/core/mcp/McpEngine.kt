package dev.shizukumcp.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed class HandleResult {
    data class Json(val body: String, val freshSession: Boolean = false) : HandleResult()
    data object Accepted : HandleResult()
}

class McpEngine(
    shell: dev.shizukumcp.core.shell.ShellExecutor,
    private val listener: (level: String, message: String) -> Unit = { _, _ -> },
) {
    private val tools = createAdbTools(shell)
    private val byName = tools.associateBy { it.name }
    private val json = Json { ignoreUnknownKeys = true }

    fun handle(message: String): HandleResult {
        val element = try {
            json.parseToJsonElement(message)
        } catch (_: Exception) {
            return HandleResult.Json(error(null, -32700, "Parse error"))
        }
        if (element is JsonArray) {
            return HandleResult.Json(error(null, -32600, "JSON-RPC batches are not supported"))
        }
        val request = element as? JsonObject
            ?: return HandleResult.Json(error(null, -32600, "Invalid request"))
        if (request.string("jsonrpc") != "2.0") {
            return HandleResult.Json(error(request["id"], -32600, "Invalid request"))
        }
        val method = request.string("method")
            ?: return HandleResult.Json(error(request["id"], -32600, "Invalid request"))
        val id = request["id"]
        if (id == null || id is JsonNull) {
            return HandleResult.Accepted
        }
        return try {
            dispatch(method, id, request.obj("params"))
        } catch (e: Exception) {
            listener("error", "$method failed")
            HandleResult.Json(error(id, -32603, e.message ?: "Internal error"))
        }
    }

    private fun dispatch(method: String, id: JsonElement, params: JsonObject?): HandleResult = when (method) {
        "initialize" -> initialize(id, params)
        "ping" -> HandleResult.Json(result(id, buildJsonObject { }))
        "tools/list" -> HandleResult.Json(result(id, toolsList()))
        "tools/call" -> callTool(id, params)
        "resources/list" -> HandleResult.Json(result(id, resourcesList()))
        "resources/templates/list" -> HandleResult.Json(result(id, buildJsonObject { put("resourceTemplates", JsonArray(emptyList())) }))
        "resources/read" -> readResource(id, params)
        "prompts/list" -> HandleResult.Json(result(id, promptsList()))
        "prompts/get" -> getPrompt(id, params)
        "logging/setLevel" -> HandleResult.Json(result(id, buildJsonObject { }))
        else -> HandleResult.Json(error(id, -32601, "Method not found"))
    }

    private fun initialize(id: JsonElement, params: JsonObject?): HandleResult {
        val requested = params?.string("protocolVersion")
        val version = if (requested != null && requested in SUPPORTED_VERSIONS) requested else DEFAULT_VERSION
        val client = params?.obj("clientInfo")?.string("name") ?: "client"
        listener("info", "initialize $client $version")
        val body = result(
            id,
            buildJsonObject {
                put("protocolVersion", version)
                put(
                    "capabilities",
                    buildJsonObject {
                        put("tools", buildJsonObject { put("listChanged", false) })
                        put("resources", buildJsonObject { put("subscribe", false); put("listChanged", false) })
                        put("prompts", buildJsonObject { put("listChanged", false) })
                    },
                )
                put(
                    "serverInfo",
                    buildJsonObject {
                        put("name", "shizuku-mcp")
                        put("version", "1.0.0")
                    },
                )
                put("instructions", INSTRUCTIONS)
            },
        )
        return HandleResult.Json(body, freshSession = true)
    }

    private fun toolsList(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        },
                    )
                }
            },
        )
    }

    private fun callTool(id: JsonElement, params: JsonObject?): HandleResult {
        if (params == null) return HandleResult.Json(error(id, -32602, "tool name is required"))
        val name = params.string("name") ?: return HandleResult.Json(error(id, -32602, "tool name is required"))
        if (params.containsKey("arguments") && params.obj("arguments") == null) {
            return HandleResult.Json(error(id, -32602, "arguments must be an object"))
        }
        val args = params.obj("arguments") ?: buildJsonObject { }
        val tool = byName[name] ?: return HandleResult.Json(error(id, -32602, "Unknown tool: $name"))
        val started = System.nanoTime()
        val call = try {
            tool.call(args)
        } catch (e: Exception) {
            ToolCallResult(listOf(ToolContent(type = "text", text = e.message ?: "tool failed")), isError = true)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        listener("info", "$name ${if (call.isError) "error" else "ok"} ${ms}ms")
        return HandleResult.Json(result(id, call.toJson()))
    }

    private fun resourcesList(): JsonObject = buildJsonObject {
        put(
            "resources",
            buildJsonArray {
                add(resource("device://info", "Device information", "Model, Android version, and display"))
                add(resource("device://packages", "Installed packages", "Third-party package names"))
            },
        )
    }

    private fun resource(uri: String, name: String, description: String) = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("description", description)
        put("mimeType", "text/plain")
    }

    private fun readResource(id: JsonElement, params: JsonObject?): HandleResult {
        val uri = params?.string("uri") ?: return HandleResult.Json(error(id, -32602, "uri is required"))
        val toolName = when (uri) {
            "device://info" -> "device_info"
            "device://packages" -> "list_packages"
            else -> return HandleResult.Json(error(id, -32602, "Unknown resource: $uri"))
        }
        val call = byName.getValue(toolName).call(buildJsonObject { })
        val text = call.content.firstOrNull()?.text ?: ""
        return HandleResult.Json(
            result(
                id,
                buildJsonObject {
                    put(
                        "contents",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("uri", uri)
                                    put("mimeType", "text/plain")
                                    put("text", text)
                                },
                            )
                        },
                    )
                },
            ),
        )
    }

    private fun promptsList(): JsonObject = buildJsonObject {
        put(
            "prompts",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", "inspect_screen")
                        put("description", "Capture the screen, dump the UI hierarchy, and describe what is on screen.")
                    },
                )
            },
        )
    }

    private fun getPrompt(id: JsonElement, params: JsonObject?): HandleResult {
        val name = params?.string("name") ?: return HandleResult.Json(error(id, -32602, "prompt name is required"))
        if (name != "inspect_screen") return HandleResult.Json(error(id, -32602, "Unknown prompt: $name"))
        return HandleResult.Json(
            result(
                id,
                buildJsonObject {
                    put("description", "Inspect the current Android screen.")
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put(
                                        "content",
                                        buildJsonObject {
                                            put("type", "text")
                                            put(
                                                "text",
                                                "Use screencap, ui_dump, and current_focus. Then describe the visible screen, the focused app, and the main actions a user could take.",
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            ),
        )
    }

    private fun ToolCallResult.toJson(): JsonObject = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                content.forEach { item ->
                    add(
                        buildJsonObject {
                            put("type", item.type)
                            if (item.text != null) put("text", item.text)
                            if (item.data != null) put("data", item.data)
                            if (item.mimeType != null) put("mimeType", item.mimeType)
                        },
                    )
                }
            },
        )
        put("isError", isError)
    }

    private fun result(id: JsonElement, value: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }.toString()

    private fun error(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }.toString()

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    companion object {
        const val DEFAULT_VERSION = "2025-03-26"
        val SUPPORTED_VERSIONS = setOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        val INSTRUCTIONS = """
            Shizuku MCP runs on an Android device. Every tool executes as the ADB shell user (uid 2000), or as root if Shizuku was started with root. This is the device itself, not a remote adb host: there is no adb binary, and commands such as "adb devices" will fail. Prefer a structured tool over adb_shell when one exists. input_text only supports printable ASCII. screencap and ui_dump reflect the current screen.
        """.trimIndent()
    }
}
