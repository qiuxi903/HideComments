package com.hidecomments.hook.engine

import android.view.View
import android.view.ViewGroup
import com.hidecomments.hook.Host
import com.hidecomments.hook.Logger
import com.hidecomments.hook.util.hookAfter

/**
 * 把「dex 里扫出来的候选类」变成实际的 hook。
 *
 * 判定分两类：
 *  - **ViewGroup 子类** → hook 它的构造方法，一实例化就隐藏。这样不管宿主怎么摆布局、
 *    怎么复用、什么时候设可见，都逃不掉。
 *  - **Fragment 子类** → hook `onCreateView` / `onViewCreated`，把它的根 View 隐藏。
 */
object DynamicHooks {

    /** 单个 App 最多挂多少个动态 hook，防止把方法内联打爆、拖垮宿主性能。 */
    private const val MAX_HOOKS = 80

    /**
     * 名字里还得带上这些词，才认为它是个「界面」而不是「数据模型 / 工具类」。
     *
     * 抖音里 `Comment` 开头的类一大半是 protobuf model 和序列化类，挂上去纯属浪费。
     */
    private val UI_HINTS = arrayOf(
        "layout", "panel", "container", "fragment", "view", "list", "widget",
        "item", "recycler", "holder", "dialog", "popup", "sheet",
    )

    private const val FRAGMENT_CLASS = "androidx.fragment.app.Fragment"

    fun installFromCandidates(host: Host, candidates: List<String>) {
        val loaders = host.loaders()
        if (loaders.isEmpty()) return

        val fragmentClass = loaders.firstNotNullOfOrNull { loader ->
            runCatching { Class.forName(FRAGMENT_CLASS, false, loader) }.getOrNull()
        }
        val fragmentSupers = fragmentClass?.let { base ->
            buildList {
                var current: Class<*>? = base
                var depth = 0
                while (current != null && current != Any::class.java && depth < 4) {
                    add(current)
                    current = current.superclass
                    depth++
                }
            }.toSet()
        } ?: emptySet()

        var hooked = 0
        var considered = 0

        for (name in candidates) {
            if (hooked >= MAX_HOOKS) {
                Logger.i("dynamic hook cap ($MAX_HOOKS) reached, stopped")
                break
            }
            val lower = name.lowercase()
            if (UI_HINTS.none { lower.contains(it) }) continue
            considered++

            val clazz = runCatching { Class.forName(name, false, loaders.first()) }.getOrNull() ?: continue

            when {
                ViewGroup::class.java.isAssignableFrom(clazz) -> {
                    hooked += hookViewConstructors(host, clazz)
                }

                fragmentSupers.any { it.isAssignableFrom(clazz) } -> {
                    hooked += hookFragment(host, clazz)
                }
            }
        }

        Logger.i("dynamic hooks installed: $hooked (considered $considered candidates)")
    }

    private fun hookViewConstructors(host: Host, clazz: Class<*>): Int {
        val constructors = runCatching { clazz.declaredConstructors }.getOrNull() ?: return 0
        var count = 0
        for (constructor in constructors) {
            val ok = host.module.hookAfter(constructor, "hc.viewCtor") { chain, _ ->
                val instance = chain.thisObject as? View ?: return@hookAfter
                val config = Host.config()
                if (!config.isActiveFor(Host.app.id)) return@hookAfter
                if (!config.hidePanel && !config.hideEntry) return@hookAfter
                ViewShield.hide(instance, MatchKind.PANEL)
            }
            if (ok) count++
        }
        return count
    }

    private fun hookFragment(host: Host, clazz: Class<*>): Int {
        val bundleClass = android.os.Bundle::class.java
        val createView = runCatching {
            clazz.getDeclaredMethod("onCreateView", ViewGroup::class.java, bundleClass)
        }.getOrNull()
        val viewCreated = runCatching {
            clazz.getDeclaredMethod("onViewCreated", View::class.java, bundleClass)
        }.getOrNull()

        if (createView == null && viewCreated == null) return 0

        var count = 0
        if (createView != null) {
            // after hook 已经 proceed 过一次了，返回值就是第二个参数，不能再调 chain.proceed()
            val ok = host.module.hookAfter(createView, "hc.fragOnCreateView") { _, result ->
                val root = result as? View ?: return@hookAfter
                val config = Host.config()
                if (!config.isActiveFor(Host.app.id) || !config.hidePanel) return@hookAfter
                ViewShield.hide(root, MatchKind.PANEL)
            }
            if (ok) count++
        }
        if (viewCreated != null) {
            val ok = host.module.hookAfter(viewCreated, "hc.fragOnViewCreated") { chain, _ ->
                val root = chain.getArg(0) as? View ?: return@hookAfter
                val config = Host.config()
                if (!config.isActiveFor(Host.app.id) || !config.hidePanel) return@hookAfter
                ViewShield.hide(root, MatchKind.PANEL)
            }
            if (ok) count++
        }
        return count
    }
}
