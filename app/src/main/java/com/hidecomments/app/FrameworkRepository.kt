package com.hidecomments.app

import android.content.SharedPreferences
import com.hidecomments.core.HideConfig
import com.hidecomments.core.TargetApp
import io.github.libxposed.service.XposedService
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 设置 App 侧的「框架连接」。
 *
 * 模块设置页自己也是一个普通 App，它通过 `XposedServiceHelper` 让框架把服务反向绑回来，
 * 拿到 [XposedService] 之后就能读写跨进程配置、看作用域授权情况。
 *
 * 如果 [service] 是 null，说明框架没连上 —— 通常是没在管理器里勾选作用域，
 * 或者用的框架版本低于 libxposed API 101。
 */
object FrameworkRepository {

    @Volatile
    var service: XposedService? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /**
     * 连接状态变化回调。
     *
     * 框架是**异步**绑上来的：Activity 起来时可能还没连上，
     * 这时读配置只能拿到默认值。所以界面得订阅这个回调，
     * 等真正连上时再重新读一次，否则会一直显示错误的默认状态。
     */
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun addConnectionListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeConnectionListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyConnection(connected: Boolean) {
        for (listener in listeners) {
            runCatching { listener(connected) }
        }
    }

    fun onBind(connected: XposedService) {
        service = connected
        lastError = null
        notifyConnection(true)
    }

    fun onDied() {
        service = null
        lastError = "框架连接已断开"
        notifyConnection(false)
    }

    val connected: Boolean get() = service != null

    fun frameworkName(): String = runCatching {
        "${service?.getFrameworkName()} ${service?.getFrameworkVersion()}"
    }.getOrElse { "未知" }

    fun apiVersion(): Int = runCatching { service?.getApiVersion() ?: -1 }.getOrDefault(-1)

    // ------------------------------------------------------------ 配置

    fun prefs(): SharedPreferences? = runCatching {
        service?.getRemotePreferences(HideConfig.PREFS_GROUP)
    }.getOrNull()

    /**
     * 读配置。
     *
     * **没连上框架时不能返回「默认配置」** —— 那会让界面显示一堆看似正常、
     * 实际并不存在的状态（用户会以为「打开了」但其实没存进去）。
     * 这里返回 `null` 表示「读不到」，调用方据此显示未连接状态。
     */
    fun loadOrNull(): HideConfig? {
        val store = prefs() ?: return null
        return runCatching {
            HideConfig.fromJson(store.getString(HideConfig.PREFS_KEY, null))
        }.getOrNull()
    }

    /** 兼容旧调用：读不到时退回默认值。 */
    fun load(): HideConfig = loadOrNull() ?: HideConfig()

    fun save(config: HideConfig): Boolean {
        val store = prefs() ?: return false
        return runCatching {
            store.edit().putString(HideConfig.PREFS_KEY, config.toJson()).commit()
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------ 诊断

    /** 已授权的目标 App 包名。 */
    fun grantedPackages(): List<String> = runCatching { service?.scope.orEmpty() }.getOrDefault(emptyList())

    fun missingScope(): List<TargetApp> {
        val granted = grantedPackages().toSet()
        return TargetApp.entries.filter { app ->
            app.packages.none { it in granted }
        }
    }
}
