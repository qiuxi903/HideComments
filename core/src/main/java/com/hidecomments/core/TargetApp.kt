package com.hidecomments.core

/**
 * 模块支持的目标 App。
 *
 * 同一产品的多个变体（极速版 / HD / 概念版）共用一份 hook 逻辑，只是包名不同，
 * 因此这里按「产品」而不是按「包名」建模。
 */
enum class TargetApp(
    val id: String,
    val displayName: String,
    val packages: List<String>,
) {
    DOUYIN(
        id = "douyin",
        displayName = "抖音",
        packages = listOf(
            "com.ss.android.ugc.aweme",
            "com.ss.android.ugc.aweme.lite",
        ),
    ),

    KUAISHOU(
        id = "kuaishou",
        displayName = "快手",
        packages = listOf(
            "com.smile.gifmaker",
            "com.kuaishou.nebula",
        ),
    ),

    BILIBILI(
        id = "bilibili",
        displayName = "哔哩哔哩",
        packages = listOf(
            "tv.danmaku.bili",
            "tv.danmaku.bilibilihd",
            "tv.danmaku.biliplayerv2",
            "com.bilibili.app.blue",
        ),
    ),

    BILIBILI_INTL(
        id = "bilibili_intl",
        displayName = "哔哩哔哩国际版",
        packages = listOf(
            "com.bilibili.app.in",
        ),
    );

    companion object {
        private val byPackage: Map<String, TargetApp> = buildMap {
            // 这里必须写全 TargetApp.entries：buildMap 的接收者是 Map，
            // 裸写 entries 会被解析成 Map 的 entries
            for (app in TargetApp.entries) {
                for (pkg in app.packages) put(pkg, app)
            }
        }

        fun byPackage(packageName: String?): TargetApp? =
            packageName?.let { byPackage[it] }

        fun byId(id: String?): TargetApp? =
            entries.firstOrNull { it.id == id }

        val allPackages: List<String> = entries.flatMap { it.packages }
    }
}
