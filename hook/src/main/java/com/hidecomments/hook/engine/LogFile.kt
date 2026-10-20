package com.hidecomments.hook.engine

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.hidecomments.hook.Host
import com.hidecomments.hook.Logger
import java.io.File

/**
 * 把模块日志落成文件。
 *
 * 为什么不用框架的日志面板：不同版本的管理器里那个入口位置不一样，有的干脆没有，
 * 而「在手机上打开一个 .txt」这件事谁都会。
 *
 * 文件落在目标 App 的**外部私有目录**：
 * `/sdcard/Android/data/<包名>/files/hidecomments/module.log`
 * 这个位置不需要 root 就能读（系统自带的文件管理器可以直接浏览 Android/data）。
 */
object LogFile {

    private const val DIR_NAME = "hidecomments"
    private const val FILE_NAME = "module.log"
    private const val INTERVAL_MS = 1500L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var targetDir: File? = null

    @Volatile
    private var lastWrittenCount: Int = -1

    /** 记录本次实际写到哪儿了，设置界面要把这个路径显示出来。 */
    @Volatile
    var lastPath: String? = null
        private set

    fun attach(activity: Activity) {
        val dir = resolveDir(activity) ?: return
        if (targetDir?.absolutePath == dir.absolutePath) return
        targetDir = dir
        lastPath = File(dir, FILE_NAME).absolutePath
        Logger.i("日志文件 -> $lastPath")
        start()
    }

    private fun resolveDir(activity: Activity): File? {
        // 优先外部目录：不用 root 就能读
        val external = runCatching { activity.getExternalFilesDir(null) }.getOrNull()
        val base = external ?: runCatching { activity.filesDir }.getOrNull() ?: return null
        return File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    private fun start() {
        handler.removeCallbacksAndMessages(null)
        handler.post(object : Runnable {
            override fun run() {
                runCatching { flush() }.onFailure { Logger.d("log flush failed: ${it.message}") }
                handler.postDelayed(this, INTERVAL_MS)
            }
        })
    }

    private fun flush() {
        val dir = targetDir ?: return
        val lines = Logger.snapshot()
        if (lines.size == lastWrittenCount) return
        lastWrittenCount = lines.size
        val file = File(dir, FILE_NAME)
        runCatching {
            file.writeText(
                buildString {
                    append("# HideComments 运行日志\n")
                    append("# 目标 App : ${Host.packageName}\n")
                    append("# 更新时间 : ${java.util.Date()}\n")
                    append("# 共 ${lines.size} 行\n\n")
                    lines.forEach { append(it).append('\n') }
                },
            )
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
    }
}
