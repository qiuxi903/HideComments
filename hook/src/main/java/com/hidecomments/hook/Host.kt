package com.hidecomments.hook

import android.app.Application
import android.content.Context
import com.hidecomments.core.HideConfig
import com.hidecomments.core.TargetApp
import com.hidecomments.hook.engine.ViewShield
import com.hidecomments.hook.profiles.BilibiliProfile
import com.hidecomments.hook.profiles.DouyinProfile
import com.hidecomments.hook.profiles.KuaishouProfile
import com.hidecomments.hook.util.hookAfter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程级宿主：持有模块入口、目标 App、类加载器和当前配置，并负责把各个 Profile 装上。
 */
object Host {

    lateinit var module: HideCommentsModule
        private set

    lateinit var app: TargetApp
        private set

    lateinit var packageName: String
        private set

    /**
     * 参与类查找的 ClassLoader 列表。
     *
     * 第一个是宿主主 dex 的 loader；后面陆续追加的是插件 loader（快手的评论区就在
     * DVA 插件里，光有主 loader 是找不到 `com.yxcorp.gifshow.comment.*` 的）。
     */
    private val classLoaders = CopyOnWriteArrayList<ClassLoader>()

    @Volatile
    var appContext: Context? = null
        private set

    @Volatile
    var installed: Boolean = false
        private set

    fun install(entry: HideCommentsModule, target: TargetApp, pkg: String, loader: ClassLoader) {
        module = entry
        app = target
        packageName = pkg
        addClassLoader(loader)
        ViewShield.setApp(target.id)

        Logger.i("installing for ${target.displayName} ($pkg)")

        ConfigProvider.init(entry)
        val config = ConfigProvider.get()
        Logger.verbose = config.verboseLog
        // 这一条很关键：它是「设置到底有没有传进宿主进程」的唯一证据。
        // 手机和电脑不在一起时，只能在 Vector 的日志面板里看到它。
        Logger.i(
            "==== HideComments 配置已生效 ====\n" +
                "  目标 App      : ${target.displayName} ($pkg)\n" +
                "  总开关        : ${if (config.masterEnabled) "开" else "关"}\n" +
                "  本 App 是否生效: ${if (config.isActiveFor(target.id)) "是" else "否（被关闭或已放行）"}\n" +
                "  识别强度      : ${config.strength.label}\n" +
                "  隐藏入口/面板 : ${config.hideEntry} / ${config.hidePanel}\n" +
                "  导出 View 树  : ${if (config.dumpViewTree) "已开启" else "未开启"}",
        )

        // 拿 Application 上下文，既用于 dump 诊断文件，也用于配置变化后主动恢复
        hookApplication()

        val profile = profileFor(target)
        runCatching { profile.install(this) }
            .onFailure { Logger.e("profile install failed", it) }

        installed = true
    }

    private fun profileFor(target: TargetApp): AppProfile = when (target) {
        TargetApp.DOUYIN -> DouyinProfile
        TargetApp.KUAISHOU -> KuaishouProfile
        TargetApp.BILIBILI, TargetApp.BILIBILI_INTL -> BilibiliProfile
    }

    private fun hookApplication() {
        // hook Application.attach，这样后面能拿到真实的 appContext 去做 dump 落盘
        val attach = runCatching {
            Application::class.java.getDeclaredMethod("attach", Context::class.java)
        }.getOrNull()
        module.hookAfter(attach, "hc.attach") { chain, _ ->
            val context = chain.getArg(0) as? Context
            if (context != null && appContext == null) {
                // attach 那一刻 getApplicationContext() 可能还是 null，
                // 直接 .packageName 会抛 NPE（框架的 PROTECTIVE 模式会兜住，
                // 但 appContext 就成 null 了）。这里退回到 context 本身。
                val app = context.applicationContext ?: context
                appContext = app
                Logger.i("app context ready: ${app.packageName}")
            }
        }
    }

    fun addClassLoader(loader: ClassLoader?) {
        if (loader == null || classLoaders.contains(loader)) return
        classLoaders.add(loader)
        Logger.d("classloader registered: $loader (total=${classLoaders.size})")
    }

    fun loaders(): List<ClassLoader> = classLoaders.toList()

    fun resolveClass(names: List<String>): Class<*>? =
        com.hidecomments.hook.util.Reflect.findClassAny(names, classLoaders.toList())

    fun resolveClass(name: String): Class<*>? =
        com.hidecomments.hook.util.Reflect.findClass(name, classLoaders.toList())

    fun config(): HideConfig = ConfigProvider.get()

    fun isActive(): Boolean = runCatching { config().isActiveFor(app.id) }.getOrDefault(false)
}

/** 每个 App 一套 hook 策略。 */
interface AppProfile {
    fun install(host: Host)
}
