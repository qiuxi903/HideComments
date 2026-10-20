package com.hidecomments.hook.engine

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hidecomments.core.Strength
import com.hidecomments.hook.Host
import com.hidecomments.hook.Logger
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * View 树导出，用于「填类名」这件事。
 *
 * 目标 App 的控件 id 和类名被混淆过，通用规则未必覆盖得到当前版本。打开设置里的
 * 「导出 View 树」之后进入视频页，模块会把当前界面的完整 View 树写到
 * `Android/data/<包名>/files/hidecomments/viewtree-*.txt`，root 环境下直接就能取。
 *
 * 拿到这份文件就能看出评论区到底叫什么，据此收窄 `ViewClassifier` 的关键字，
 * 或者把类名填进对应 Profile 的候选列表里。
 */
object ViewDumper {

    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 60

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** 用 root 容器基准做判定，和实际扫描时保持一致。 */
    private class RootHolder(val root: View)

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 已经抓过的 Activity，同一个界面不重复抓。 */
    private var lastDumped: WeakReference<Activity>? = null

    fun maybeDump(activity: Activity, root: View) {
        val config = runCatching { Host.config() }.getOrNull() ?: return
        if (!config.dumpViewTree) return
        if (activity.packageName != Host.packageName) return

        // onPostResume / setContentView 每次都会走到这里，
        // 不去重的话一分钟能刷出几十个文件，把目录塞满。
        if (lastDumped?.get() === activity) return
        lastDumped = WeakReference(activity)

        // 必须等布局完成再抓。
        // 界面刚建立时 View 还没测量，全部是 0x0，尺寸信息一点用都没有 ——
        // 而尺寸恰恰是判断「这是不是评论区面板」的关键依据之一。
        // 所以在 1 秒和 3 秒各抓一次：一次是稳定态，一次是内容加载后的状态。
        scheduleDump(activity, root, 1000L)
        scheduleDump(activity, root, 3000L)
    }

    private fun scheduleDump(activity: Activity, root: View, delayMs: Long) {
        handler.postDelayed({
            runCatching { dump(activity, root) }
                .onFailure { Logger.w("view dump failed", it) }
        }, delayMs)
    }

    private fun dump(activity: Activity, root: View) {
        // 这里刻意用 activity 自己的上下文，而不是 Host.appContext：
        // 后者要等 Application.attach 之后才拿得到，拿不到时原来会静默 return，
        // 表现就是「开关开了但怎么都等不到文件」，完全没有线索。
        val dir = resolveDir(activity)
        if (dir == null || (!dir.exists() && !dir.mkdirs())) {
            Logger.w("view dump: cannot create output dir for ${activity.packageName}")
            return
        }
        val file = File(dir, "viewtree-${stamp.format(Date())}.txt")

        val out = StringBuilder()
        out.append("# package : ").append(activity.packageName).append('\n')
        out.append("# activity: ").append(activity.javaClass.name).append('\n')
        out.append("# device  : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        out.append("# android : ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        out.append("# root    : ").append(root.width).append('x').append(root.height).append('\n')
        out.append("# MATCH 是模块的通用规则命中处，类名/id 可直接抄进 ViewClassifier\n\n")

        var budget = MAX_NODES
        walk(root, 0, out, RootHolder(root)) { budget--; budget > 0 }

        file.writeText(out.toString())
        Logger.i("view tree dumped -> ${file.absolutePath}")
        pruneOldDumps(dir)
    }

    /** 只留最近 3 份 dump，别把用户目录塞满。 */
    private fun pruneOldDumps(dir: File) {
        val files = runCatching {
            dir.listFiles { f -> f.name.startsWith("viewtree-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
        }.getOrNull() ?: return
        files.drop(3).forEach { old ->
            runCatching { old.delete() }
        }
    }

    /**
     * 选一个「不用 root 就能取到」的输出目录。
     *
     * 外部私有目录 `/sdcard/Android/data/<包名>/files/` 普通文件管理器和 adb 都能直接读，
     * 而内部私有目录必须 root —— 手机和电脑不在一起时，后者基本等于取不到。
     */
    private fun resolveDir(activity: Activity): File? {
        val external = runCatching { activity.getExternalFilesDir(null) }.getOrNull()
        val base = external ?: runCatching { activity.filesDir }.getOrNull()
        if (base == null) {
            Logger.w("view dump: no writable dir for ${activity.packageName}")
            return null
        }
        return File(base, "hidecomments")
    }

    private fun walk(
        view: View,
        depth: Int,
        out: StringBuilder,
        holder: RootHolder,
        tick: () -> Boolean,
    ) {
        if (depth > MAX_DEPTH) return
        if (!tick()) return

        out.append("  ".repeat(depth))
        out.append(view.javaClass.name)
        ViewClassifier.idName(view)?.let { out.append("  #").append(it) }
        out.append("  [").append(view.width).append('x').append(view.height).append(']')
        if (view.visibility != View.VISIBLE) out.append("  vis=").append(visName(view.visibility))
        if (view.isClickable) out.append("  clickable")

        if (view is TextView) {
            val text = runCatching { view.text?.toString() }.getOrNull()
            if (!text.isNullOrBlank()) out.append("  text=\"").append(text.take(40)).append('"')
        }

        val kind = ViewClassifier.classify(view, Strength.BALANCED, holder.root)
        if (kind != MatchKind.NONE) out.append("   <== MATCH ").append(kind.name)

        out.append('\n')

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), depth + 1, out, holder, tick)
            }
        }
    }

    private fun visName(visibility: Int): String = when (visibility) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> visibility.toString()
    }
}
