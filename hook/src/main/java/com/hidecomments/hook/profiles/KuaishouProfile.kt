package com.hidecomments.hook.profiles

import android.view.View
import com.hidecomments.hook.AppProfile
import com.hidecomments.hook.Host
import com.hidecomments.hook.Logger
import com.hidecomments.hook.engine.CoreHooks
import com.hidecomments.hook.engine.DexIndex
import com.hidecomments.hook.engine.DynamicHooks
import com.hidecomments.hook.engine.MatchKind
import com.hidecomments.hook.engine.ViewShield
import com.hidecomments.hook.util.Reflect
import com.hidecomments.hook.util.hookAfter
import java.lang.reflect.Method

/**
 * 快手 / 快手极速版。
 *
 * 快手有个绕不开的坑：**评论区不在主 dex 里**。
 * 它是 DVA 动态插件（APK 内的 `assets/dva_feature/comment_detail-master.zip`），
 * 由 `com.kwai.plugin.dva.feature.core.loader.FeaturePluginLoader` 在运行时装载。
 * 主 ClassLoader 里 `Class.forName("com.yxcorp.gifshow.comment.common.CommonCommentsFragment")`
 * 必然是 ClassNotFoundException。
 *
 * 而且那个加载器的方法名被混淆过（形如 `b(PluginInfo, Application, ClassLoader, ...)`），
 * 每个版本还可能换字母，所以不能写死方法名 —— 这里改成「把该类所有带 ClassLoader 参数的
 * 静态短名方法都 hook 一遍」，从参数里认出插件名是 comment_detail 时，
 * 把它的 ClassLoader 注册进 Host，后面所有类查找都会多试这一个 loader。
 */
object KuaishouProfile : AppProfile {

    override fun install(host: Host) {
        CoreHooks.install()
        hookDvaPluginLoader(host)
        hookKnownCommentClasses(host)
        DexIndex.ensureScanned(KEYWORDS) { names ->
            DynamicHooks.installFromCandidates(host, names)
        }
    }

    private fun hookDvaPluginLoader(host: Host) {
        val loaderClass = host.resolveClass("com.kwai.plugin.dva.feature.core.loader.FeaturePluginLoader")
        if (loaderClass == null) {
            Logger.d("快手: 没找到 DVA 插件加载器，评论区动态发现会失效（退回通用 View 规则）")
            return
        }

        val candidates = Reflect.findPluginLoaderMethod(loaderClass)
        if (candidates.isEmpty()) {
            Logger.d("快手: DVA 加载器里没有符合特征的静态方法")
            return
        }

        for (method in candidates) {
            host.module.hookAfter(method, "hc.dvaLoader") { chain, _ ->
                val args = chain.args
                val looksLikeCommentPlugin = args.any { arg ->
                    arg is String && arg.contains("comment", ignoreCase = true)
                }
                val pluginLoader = args.filterIsInstance<ClassLoader>().firstOrNull()
                if (looksLikeCommentPlugin && pluginLoader != null) {
                    Logger.i("快手: 发现评论区插件 ClassLoader=$pluginLoader")
                    Host.addClassLoader(pluginLoader)
                    hookKnownCommentClasses(host)
                    runCatching { scanPluginApk(host, pluginLoader) }
                        .onFailure { Logger.w("插件 dex 扫描失败", it) }
                }
            }
        }
    }

    /**
     * 插件是 zip 包，可以直接从 ClassLoader 的 dexElements 里拿到路径再枚举类名。
     * 拿不到就算了，不影响其他三层。
     */
    private fun scanPluginApk(host: Host, pluginLoader: ClassLoader) {
        DexIndex.registerExtraSource(pluginLoader)
    }

    private fun hookKnownCommentClasses(host: Host) {
        // 插件加载器每加载一个插件都会回调一次，不加锁会把同一批方法重复 hook 好几遍
        if (knownClassesHooked) return
        knownClassesHooked = true

        hookHideRootOfFragment(host, "com.yxcorp.gifshow.comment.common.CommonCommentsFragment")
        hookHideItemsOfAdapter(host, "com.yxcorp.gifshow.comment.adapter.CommentAdapterImpl2")
        hookHideOnConstruct(host, "com.yxcorp.gifshow.comment.adapter.CommentAdapterImpl2")
    }

    private fun hookHideRootOfFragment(host: Host, className: String) {
        val clazz = host.resolveClass(className) ?: run {
            Logger.d("快手: $className 未加载（插件还没起来或已改名）")
            return
        }
        val bundle = android.os.Bundle::class.java
        val onViewCreated = runCatching {
            clazz.getDeclaredMethod("onViewCreated", View::class.java, bundle)
        }.getOrNull()
        if (onViewCreated != null) {
            host.module.hookAfter(onViewCreated, "hc.ksFrag") { chain, _ ->
                hideIfActive(chain.getArg(0) as? View)
            }
        }
        val onCreateView = runCatching {
            clazz.getDeclaredMethod(
                "onCreateView", android.view.ViewGroup::class.java, bundle,
            )
        }.getOrNull()
        if (onCreateView != null) {
            host.module.hookAfter(onCreateView, "hc.ksFrag") { _, result ->
                hideIfActive(result as? View)
            }
        }
    }

    private fun hookHideItemsOfAdapter(host: Host, className: String) {
        val clazz = host.resolveClass(className) ?: return
        for (method in Reflect.findMethodsByName(clazz, "onBindViewHolder")) {
            if (method.parameterCount < 2) continue
            host.module.hookAfter(method, "hc.ksVH") { chain, _ ->
                hideIfActive(chain.getArg(1) as? View)
            }
        }
    }

    private fun hookHideOnConstruct(host: Host, className: String) {
        val clazz = host.resolveClass(className) ?: return
        for (constructor in runCatching { clazz.declaredConstructors }.getOrNull() ?: return) {
            host.module.hookAfter(constructor, "hc.ksCtor") { chain, _ ->
                hideIfActive(chain.thisObject as? View)
            }
        }
    }

    private fun hideIfActive(view: View?) {
        val view = view ?: return
        val config = Host.config()
        if (!config.isActiveFor(Host.app.id) || !config.hidePanel) return
        ViewShield.hide(view, MatchKind.PANEL)
    }

    private val KEYWORDS = listOf("comment", "cmt", "reply")

    @Volatile
    private var knownClassesHooked = false
}
