package io.github.qiuxi903.hidecomments.hook

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.util.ArrayDeque

/**
 * 日志出口：同时写 logcat、Xposed 日志，并留一份内存副本。
 *
 * 内存副本是为了落盘成文件 —— 框架的日志面板在某些版本/管理器里并不好找，
 * 而「写进目标 App 的外部目录、用文件管理器直接打开」是永远可用的通道。
 */
object Logger {

    const val TAG = "HideComments"

    @Volatile
    private var module: XposedInterface? = null

    /** 详细日志开关，由设置面板控制，避免平时刷屏。 */
    @Volatile
    var verbose: Boolean = false

    /** 最近日志的内存副本。 */
    private val buffer = ArrayDeque<String>()

    private const val BUFFER_LIMIT = 500

    fun attach(target: XposedInterface) {
        module = target
    }

    fun i(message: String) {
        Log.i(TAG, message)
        module?.log(Log.INFO, TAG, message)
        record("I", message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
        module?.log(Log.WARN, TAG, message, throwable)
        record("W", message + (throwable?.let { " | ${it.message}" } ?: ""))
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        module?.log(Log.ERROR, TAG, message, throwable)
        record("E", message + (throwable?.let { " | ${it.message}" } ?: ""))
    }

    /** 只在设置里打开「详细日志」时才输出。 */
    fun d(message: String) {
        if (!verbose) return
        Log.d(TAG, message)
        module?.log(Log.DEBUG, TAG, message)
        record("D", message)
    }

    private fun record(level: String, message: String) {
        synchronized(buffer) {
            buffer.addLast("${System.currentTimeMillis()} $level $message")
            while (buffer.size > BUFFER_LIMIT) buffer.removeFirst()
        }
    }

    /** 取最近日志的副本，供落盘用。 */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /** 当前已经累积的日志条数，用来判断有没有新内容。 */
    fun size(): Int = synchronized(buffer) { buffer.size }
}
