package io.github.qiuxi903.hidecomments.app

import android.app.Activity
import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView

/**
 * 崩溃兜底显示。
 *
 * 设置界面崩掉是最难排查的一类问题：用户在手机上既看不到 logcat，也没法把堆栈贴出来。
 * 所以初始化失败时不静默退出，而是把完整堆栈直接渲染成一个纯文本页面 ——
 * 截图发给开发者就能看到根因。
 */
object CrashDisplay {

    private const val TAG = "HideComments"

    fun show(activity: Activity, throwable: Throwable) {
        Log.e(TAG, "settings UI failed", throwable)
        val text = buildString {
            append("干掉评论区 · 设置界面初始化失败\n\n")
            append(throwable.stackTraceToString())
            append("\n\n详细日志请执行：\nadb logcat -s HideComments AndroidRuntime\n")
        }
        val view = TextView(activity).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.parseColor("#E6E6E6"))
            setBackgroundColor(Color.parseColor("#101014"))
            setPadding(32, 32, 32, 32)
        }
        activity.setContentView(view)
    }
}
