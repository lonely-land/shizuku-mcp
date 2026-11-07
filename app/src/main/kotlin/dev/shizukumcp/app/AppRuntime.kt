package dev.shizukumcp.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object AppRuntime {
    enum class Phase { STOPPED, STARTING, RUNNING }

    data class LogLine(val atMs: Long, val level: String, val message: String)

    val logs = MutableStateFlow<List<LogLine>>(emptyList())
    val phase = MutableStateFlow(Phase.STOPPED)
    val lastError = MutableStateFlow<String?>(null)
    val boundPort = MutableStateFlow<Int?>(null)

    fun log(message: String, level: String = "info") {
        val line = LogLine(System.currentTimeMillis(), level, message)
        logs.update { (listOf(line) + it).take(80) }
    }

    fun fail(message: String) {
        lastError.value = message
        log(message, "error")
        phase.value = Phase.STOPPED
        boundPort.value = null
    }

    fun clearError() {
        lastError.value = null
    }
}
