package com.hidecomments.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 屏蔽强度。强度越高，匹配越激进，误伤其他控件的概率也越高。
 */
enum class Strength(val id: String, val label: String, val description: String) {
    GENTLE(
        id = "gentle",
        label = "保守",
        description = "只认明确带 comment/reply 字样的控件，绝不按几何特征猜测",
    ),
    BALANCED(
        id = "balanced",
        label = "标准",
        description = "在保守基础上，允许按控件文字（评论/回复/Comments）识别入口按钮",
    ),
    AGGRESSIVE(
        id = "aggressive",
        label = "激进",
        description = "再加上按尺寸与位置猜测评论区容器，覆盖更彻底但可能误伤",
    );

    companion object {
        fun fromId(id: String?): Strength = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/**
 * 全部配置。序列化成一段 JSON，作为单个字符串存进 Xposed Remote Preferences，
 * 这样跨进程读写只需要一个 key，App 侧写入、Hook 侧读取都不会出现半更新状态。
 */
data class HideConfig(
    val masterEnabled: Boolean = true,
    val enabledApps: Set<String> = TargetApp.entries.map { it.id }.toSet(),
    /** 会话内临时放行的 App：切到后台再回来就失效，用于「我就看一眼评论区」。 */
    val sessionAllow: Set<String> = emptySet(),
    val hideEntry: Boolean = true,
    val hidePanel: Boolean = true,
    val blockNavigation: Boolean = true,
    val strength: Strength = Strength.BALANCED,
    val verboseLog: Boolean = false,
    val dumpViewTree: Boolean = false,
) {

    fun isActiveFor(appId: String): Boolean =
        masterEnabled && appId in enabledApps && appId !in sessionAllow

    fun isAppEnabled(appId: String): Boolean = appId in enabledApps

    /**
     * 按**明确的值**设置某个 App 的启用状态。
     *
     * 不用「翻转」语义：界面上的开关已经告诉我们要什么了，
     * 再翻一次反而会在状态不同步时得到错误结果。
     */
    fun withAppEnabled(appId: String, enabled: Boolean): HideConfig =
        if (enabled) {
            if (appId in enabledApps) this else copy(enabledApps = enabledApps + appId)
        } else {
            if (appId !in enabledApps) this else copy(enabledApps = enabledApps - appId)
        }

    fun toggledSessionAllow(appId: String): HideConfig =
        if (appId in sessionAllow) {
            copy(sessionAllow = sessionAllow - appId)
        } else {
            copy(sessionAllow = sessionAllow + appId)
        }

    fun toggledApp(appId: String): HideConfig =
        if (appId in enabledApps) {
            copy(enabledApps = enabledApps - appId)
        } else {
            copy(enabledApps = enabledApps + appId)
        }

    fun toJson(): String {
        val root = JSONObject()
        root.put(KEY_MASTER, masterEnabled)
        root.put(KEY_APPS, JSONArray(enabledApps.toList()))
        root.put(KEY_ALLOW, JSONArray(sessionAllow.toList()))
        root.put(KEY_HIDE_ENTRY, hideEntry)
        root.put(KEY_HIDE_PANEL, hidePanel)
        root.put(KEY_BLOCK_NAV, blockNavigation)
        root.put(KEY_STRENGTH, strength.id)
        root.put(KEY_VERBOSE, verboseLog)
        root.put(KEY_DUMP, dumpViewTree)
        return root.toString()
    }

    companion object {
        const val PREFS_GROUP = "hide_comments_config"
        const val PREFS_KEY = "config"

        private const val KEY_MASTER = "masterEnabled"
        private const val KEY_APPS = "enabledApps"
        private const val KEY_ALLOW = "sessionAllow"
        private const val KEY_HIDE_ENTRY = "hideEntry"
        private const val KEY_HIDE_PANEL = "hidePanel"
        private const val KEY_BLOCK_NAV = "blockNavigation"
        private const val KEY_STRENGTH = "strength"
        private const val KEY_VERBOSE = "verboseLog"
        private const val KEY_DUMP = "dumpViewTree"

        fun fromJson(raw: String?): HideConfig {
            if (raw.isNullOrBlank()) return HideConfig()
            return runCatching {
                val root = JSONObject(raw)
                HideConfig(
                    masterEnabled = root.optBoolean(KEY_MASTER, true),
                    enabledApps = root.optStringSet(KEY_APPS)
                        ?: TargetApp.entries.map { it.id }.toSet(),
                    sessionAllow = root.optStringSet(KEY_ALLOW) ?: emptySet(),
                    hideEntry = root.optBoolean(KEY_HIDE_ENTRY, true),
                    hidePanel = root.optBoolean(KEY_HIDE_PANEL, true),
                    blockNavigation = root.optBoolean(KEY_BLOCK_NAV, true),
                    strength = Strength.fromId(root.optString(KEY_STRENGTH)),
                    verboseLog = root.optBoolean(KEY_VERBOSE, false),
                    dumpViewTree = root.optBoolean(KEY_DUMP, false),
                )
            }.getOrElse { HideConfig() }
        }

        private fun JSONObject.optStringSet(key: String): Set<String>? {
            val array = optJSONArray(key) ?: return null
            val result = LinkedHashSet<String>(array.length())
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let(result::add)
            }
            return result
        }
    }
}
