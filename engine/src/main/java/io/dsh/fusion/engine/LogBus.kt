package io.dsh.fusion.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { TRACE, INFO, WARN, ERROR }

data class LogLine(val at: Long, val tag: String, val level: LogLevel, val message: String) {
    fun format(): String = "${TIME.format(Date(at))} ${level.name.padEnd(5)} [$tag] $message"

    private companion object {
        val TIME: SimpleDateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * 全局日志总线。引擎的 stdout/stderr、设备工具调用、语音唤醒全都汇到这里，
 * 控制台页面与「流体云」回复卡都从这一条流上取数。
 */
object LogBus {

    private const val CAPACITY = 1500

    private val _snapshot = MutableStateFlow<List<LogLine>>(emptyList())
    val snapshot: StateFlow<List<LogLine>> = _snapshot

    private val _stream = MutableSharedFlow<LogLine>(extraBufferCapacity = 512)
    val stream: SharedFlow<LogLine> = _stream

    fun log(tag: String, level: LogLevel, message: String) {
        val line = LogLine(System.currentTimeMillis(), tag, level, message)
        val cur = _snapshot.value
        _snapshot.value =
            if (cur.size >= CAPACITY) cur.subList(cur.size - CAPACITY + 1, cur.size) + line
            else cur + line
        _stream.tryEmit(line)
        runCatching { Log.println(priorityOf(level), "DSH/$tag", message) }
    }

    private fun priorityOf(level: LogLevel): Int = when (level) {
        LogLevel.TRACE -> Log.VERBOSE
        LogLevel.INFO -> Log.INFO
        LogLevel.WARN -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    fun t(tag: String, m: String) = log(tag, LogLevel.TRACE, m)
    fun i(tag: String, m: String) = log(tag, LogLevel.INFO, m)
    fun w(tag: String, m: String) = log(tag, LogLevel.WARN, m)
    fun e(tag: String, m: String) = log(tag, LogLevel.ERROR, m)

    fun clear() { _snapshot.value = emptyList() }

    fun dump(): String = _snapshot.value.joinToString("\n") { it.format() }
}
