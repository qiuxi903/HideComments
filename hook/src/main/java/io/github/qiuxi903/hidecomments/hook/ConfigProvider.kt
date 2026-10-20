package io.github.qiuxi903.hidecomments.hook

import android.content.SharedPreferences
import android.os.SystemClock
import io.github.qiuxi903.hidecomments.core.HideConfig
import io.github.libxposed.api.XposedInterface

/**
 * 配置读取。
 *
 * Hook 侧拿到的 Remote Preferences 是**只读**的（这是框架有意为之：被注入的 App 只能看，
 * 不能改设置 App 的数据），每两秒重新读一次，避免用户改完设置还得杀进程重启。
 */
object ConfigProvider {

    private const val RELOAD_INTERVAL_MS = 2000L

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var cached: HideConfig = HideConfig()

    private var lastReadAt: Long = 0L

    fun init(module: XposedInterface) {
        prefs = runCatching { module.getRemotePreferences(HideConfig.PREFS_GROUP) }
            .onFailure { Logger.w("remote preferences unavailable", it) }
            .getOrNull()
        lastReadAt = 0L
    }

    fun get(): HideConfig {
        val now = SystemClock.uptimeMillis()
        if (now - lastReadAt < RELOAD_INTERVAL_MS) return cached
        lastReadAt = now
        val raw = runCatching { prefs?.getString(HideConfig.PREFS_KEY, null) }
            .getOrNull()
        val parsed = HideConfig.fromJson(raw)
        if (parsed != cached) {
            Logger.d("config reloaded: $parsed")
        }
        cached = parsed
        Logger.verbose = parsed.verboseLog
        return cached
    }

    /** 立即失效缓存，下一次 get() 就重新读。 */
    fun invalidate() {
        lastReadAt = 0L
    }
}
