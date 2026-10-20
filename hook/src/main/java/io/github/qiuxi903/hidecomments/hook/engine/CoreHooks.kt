package io.github.qiuxi903.hidecomments.hook.engine

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import io.github.qiuxi903.hidecomments.hook.Host
import io.github.qiuxi903.hidecomments.hook.Logger
import io.github.qiuxi903.hidecomments.hook.util.Reflect
import io.github.qiuxi903.hidecomments.hook.util.hookAfter
import io.github.qiuxi903.hidecomments.hook.util.hookBefore
import io.github.qiuxi903.hidecomments.hook.util.hookExec
import java.lang.ref.WeakReference

/**
 * 与具体 App 无关的通用 hook。
 *
 * Profile 里写的是「这个 App 的评论区叫什么」，而这里写的是「不管叫什么都要做的事」：
 * 拿到界面、盯住 View 树、拦住评论入口的点击和跳转。
 */
object CoreHooks {

    private const val TAB_LAYOUT_CLASS = "com.google.android.material.tabs.TabLayout"

    /**
     * 已经挂过守卫的根 View。
     *
     * 必须记「挂在哪个 root 上」而不是一个布尔值：每次换 Activity 都会换一棵新树，
     * 用布尔值的话只有第一个界面有守卫，之后所有界面都会漏掉「绘制前重申」这道防线。
     */
    private var guardedRoot: WeakReference<View>? = null

    fun install() {
        hookLifecycle()
        hookVisibility()
        hookAttach()
        hookTextChange()
        hookCommentTabs()
        hookClicks()
        hookNavigation()
        PagerGuard.install()
        ViewShield.bindConfigProvider { Host.config() }
    }

    // ---------------------------------------------------------------- 标签页

    /**
     * 拦下评论 Tab 加入标签栏。
     *
     * 这是消除闪现最干净的一招。B站详情页的标签栏是 `UnitedTabLayout`（Material TabLayout 的子类），
     * 从 View 树可以看到 `#tabs` 一开始只有 `TabLayout$SlidingTabIndicator` 一个孩子，
     * **「评论」这个 Tab 是之后才 addTab 进来的**。
     *
     * 既然是后加的，那就在它加入的那一刻拦下来 —— 标签栏里从一开始就没有「评论」，
     * 不会有任何一帧闪现。这比「加进来再藏起来」干净得多，布局也不会留下空位。
     *
     * 两条路径都覆盖：
     *  1. `addTab(tab)` —— 先设文字再加（`addTab(newTab().setText("评论"))`），直接拦在门口。
     *  2. `Tab.setText(...)` —— 先加后设文字，这时把那个 Tab 从标签栏里摘掉。
     */
    private fun hookCommentTabs() {
        val tabLayoutClass = Host.resolveClass(TAB_LAYOUT_CLASS)
        if (tabLayoutClass == null) {
            Logger.d("TabLayout 不存在，跳过标签页拦截")
            return
        }
        val tabClassName = "$TAB_LAYOUT_CLASS\$Tab"

        // ---- 路径 1：加入之前拦 ----
        for (method in Reflect.findMethodsByName(tabLayoutClass, "addTab")) {
            val first = method.parameterTypes.firstOrNull() ?: continue
            if (first.name != tabClassName) continue
            Host.module.hookBefore(method, "hc.addTab") { chain ->
                if (!Host.isActive()) return@hookBefore false
                val tab = chain.getArg(0) ?: return@hookBefore false
                val text = readTabText(tab) ?: return@hookBefore false
                if (!ViewClassifier.isCommentLabel(text)) return@hookBefore false
                Logger.i("拦下评论 Tab「$text」：不让它进入标签栏")
                true
            }

            // 记下 Tab 属于哪个标签栏，「先加后设文字」时才知道去哪儿摘它
            Host.module.hookAfter(method, "hc.addTab.after") { chain, _ ->
                val tab = chain.getArg(0)
                val owner = chain.thisObject as? View
                if (tab != null && owner != null) tabOwners[tab] = owner
            }
        }

        // ---- 路径 2：先加后设文字，事后摘掉 ----
        val tabClass = Host.resolveClass(tabClassName)
        if (tabClass != null) {
            for (method in Reflect.findMethodsByName(tabClass, "setText")) {
                if (method.parameterCount != 1) continue
                Host.module.hookAfter(method, "hc.tabSetText") { chain, _ ->
                    if (!Host.isActive()) return@hookAfter
                    val tab = chain.thisObject ?: return@hookAfter
                    val text = readTabText(tab) ?: return@hookAfter
                    if (!ViewClassifier.isCommentLabel(text)) return@hookAfter
                    val owner = tabOwners[tab]
                    if (owner == null) return@hookAfter
                    val index = indexOfTab(owner, tab)
                    if (index < 0) return@hookAfter
                    Logger.i("摘掉后设文字的评论 Tab「$text」（第 $index 个）")
                    removeTabAt(owner, index)
                }
            }
        }
    }

    /** Tab -> 所属的 TabLayout，用来在「先加后设文字」时找到宿主。 */
    private val tabOwners: MutableMap<Any, View> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, View>())

    private fun readTabText(tab: Any): CharSequence? = runCatching {
        val method = tab.javaClass.methods.firstOrNull {
            it.name == "getText" && it.parameterCount == 0
        } ?: return@runCatching null
        method.invoke(tab) as? CharSequence
    }.getOrNull()

    private fun indexOfTab(tabLayout: View, tab: Any): Int {
        val getTabAt = tabLayout.javaClass.methods.firstOrNull {
            it.name == "getTabAt" && it.parameterCount == 1
        } ?: return -1
        for (i in 0 until 64) {
            val candidate = runCatching { getTabAt.invoke(tabLayout, i) }.getOrNull() ?: return -1
            if (candidate === tab) return i
        }
        return -1
    }

    private fun removeTabAt(tabLayout: View, index: Int) {
        runCatching {
            val method = tabLayout.javaClass.methods.firstOrNull {
                it.name == "removeTabAt" && it.parameterCount == 1
            } ?: return
            method.invoke(tabLayout, index)
        }.onFailure { Logger.w("removeTabAt failed", it) }
    }

    // ---------------------------------------------------------------- 生命周期

    private fun hookLifecycle() {
        // onPostResume 而不是 onCreate：此时 View 树已经 inflate 完并测量过了。
        val postResume = Reflect.findMethod(Activity::class.java, "onPostResume")
        Host.module.hookAfter(postResume, "hc.onPostResume") { chain, _ ->
            val activity = chain.thisObject as? Activity ?: return@hookAfter
            if (activity.packageName != Host.packageName) return@hookAfter
            onActivityReady(activity)
        }

        // setContentView 之后再扫一次，覆盖那些 onPostResume 之后才 setContentView 的界面
        val setContentView = Reflect.findMethod(
            Activity::class.java, "setContentView", View::class.java,
        )
        Host.module.hookAfter(setContentView, "hc.setContentView") { chain, _ ->
            val activity = chain.thisObject as? Activity ?: return@hookAfter
            if (activity.packageName != Host.packageName) return@hookAfter
            val content = chain.getArg(0) as? View ?: return@hookAfter
            onActivityReady(activity, content)
        }

        // 界面销毁时把隐藏记录清掉，避免弱引用表里堆一堆死 View
        val destroy = Reflect.findMethod(Activity::class.java, "onDestroy")
        Host.module.hookAfter(destroy, "hc.onDestroy") { chain, _ ->
            val activity = chain.thisObject as? Activity ?: return@hookAfter
            if (activity.packageName != Host.packageName) return@hookAfter
            if (activity.isChangingConfigurations) return@hookAfter
            ViewShield.stop()
        }
    }

    private fun onActivityReady(activity: Activity, content: View? = null) {
        val root = content ?: runCatching { activity.window?.decorView }.getOrNull()
        if (root == null) return
        ViewShield.setRoot(root)
        attachPreDrawGuard(root)
        ViewShield.scanCurrent()
        ViewShield.startPeriodicScan()
        ViewDumper.maybeDump(activity, root)
        LogFile.attach(activity)
        if (dumpAnnounced.compareAndSet(false, true)) {
            val on = runCatching { Host.config().dumpViewTree }.getOrDefault(false)
            Logger.i("界面就绪：${activity.javaClass.simpleName}；导出 View 树 = $on")
        }
    }

    private val dumpAnnounced = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 绘制前重申。
     *
     * App 把 View 重新设为可见这件事很常见（切 Fragment、列表复用、重新 setContentView），
     * 靠每秒一次的扫描会有最多一秒的闪现。每帧重申一次就彻底没有了。
     */
    private fun attachPreDrawGuard(root: View) {
        // 同一个 root 上只挂一次；换了 Activity（新 root）则重新挂
        if (guardedRoot?.get() === root) return
        val observer = runCatching { root.viewTreeObserver }.getOrNull() ?: return
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // 这个回调跑在绘制流程里，不在框架的 hook 保护范围内 ——
                // 里面抛出的异常会一路冒到 Choreographer，直接把宿主 App 搞崩。
                // 所以必须整段兜住，任何情况下都只放行绘制。
                runCatching {
                    if (Host.isActive()) ViewShield.reassert()
                }.onFailure {
                    Logger.w("pre-draw guard failed", it)
                }
                return true
            }
        })
        guardedRoot = WeakReference(root)
    }

    // ---------------------------------------------------------------- 可见性

    /**
     * 拦 `View.setVisibility`。
     *
     * App 之后把被隐藏的 View 改回可见时，这里把它按回 GONE。
     *
     * 这里刻意用 [io.github.qiuxi903.hidecomments.hook.util.hookExec] 手写而不用 `hookBefore`：
     * 我们的语义是「改参数后照常执行」而不是「拦截」，两条分支各自 proceed 一次，
     * 绝不会出现原方法被执行两遍。
     */
    private fun hookVisibility() {
        val setVisibility = Reflect.findMethod(View::class.java, "setVisibility", Int::class.java)
        Host.module.hookExec(setVisibility, "hc.setVisibility") { chain ->
            if (ViewShield.isReentrant() || !Host.isActive()) return@hookExec chain.proceed()

            val view = chain.thisObject as? View
            val requested = chain.getArg(0) as? Int
            if (view == null || requested == null || requested == View.GONE) {
                return@hookExec chain.proceed()
            }
            if (!ViewShield.isHidden(view)) return@hookExec chain.proceed()

            Logger.d("app tried to show ${view.javaClass.simpleName}, forcing GONE")
            chain.proceed(arrayOf<Any>(View.GONE))
        }
    }

    /**
     * 拦 `TextView.setText`。
     *
     * 这是消除「评论按钮闪一下」的关键。宿主 App 的典型时序是
     * 「先 inflate / addView，后面才 setText 设文字」——
     * 也就是说在 `addView` 那一刻控件的文字还是空的，判定必然落空。
     *
     * 而且**只藏不改是没用的**：文字藏掉之后，那个 364px 宽的 Tab 框架
     * 和旁边的「2416」评论数还在，标签栏照样会抖一下，看起来还是闪。
     *
     * 所以这里对「文字恰好等于评论/回复」的情况**直接不让他设进去** ——
     * 字符从一开始就没存在过，闪现在物理上就不可能发生。
     *
     * `setText` 是全 App 最热的方法，所以先过一道便宜的预筛：
     * 只有长度 1~24 且含评论相关字词的才继续判断。
     */
    private fun hookTextChange() {
        val methods = Reflect.findMethodsByName(TextView::class.java, "setText")
        for (method in methods) {
            if (method.parameterCount != 1) continue
            val argType = method.parameterTypes[0]
            if (argType != CharSequence::class.java) continue

            // before：拦住原方法，文字根本不设进去
            Host.module.hookBefore(method, "hc.setText.block") { chain ->
                if (!Host.isActive()) return@hookBefore false
                val raw = chain.getArg(0) as? CharSequence ?: return@hookBefore false
                if (raw.length !in 1..24) return@hookBefore false
                if (!maybeCommentLabel(raw.toString())) return@hookBefore false
                // 只有「整体就等于」评论词才拦，避免把正文里含「评论」的长文本也挡掉
                if (!ViewClassifier.isCommentLabel(raw)) return@hookBefore false
                Logger.i("拦下评论文字「$raw」：不让它显示出来")
                // 记一笔：等它挂到树上的时候，连同它所属的 Tab 项一起藏掉
                val view = chain.thisObject as? View
                if (view != null) pendingCommentTexts.add(view)
                hideEntryAndTabItem(view)
                true
            }

            // after：万一没拦住（比如宿主自己覆写了 setText），退而求其次把它藏掉
            Host.module.hookAfter(method, "hc.setText") { chain, _ ->
                if (!Host.isActive()) return@hookAfter
                val view = chain.thisObject as? TextView ?: return@hookAfter
                val raw = runCatching { view.text }.getOrNull() ?: return@hookAfter
                if (raw.length !in 1..24) return@hookAfter
                if (!maybeCommentLabel(raw.toString())) return@hookAfter
                val before = ViewShield.isHidden(view)
                pendingCommentTexts.add(view)
                hideIfCandidate(view)
                if (!before && ViewShield.isHidden(view)) {
                    Logger.i("fast-path hide on setText: \"$raw\"")
                }
            }
        }
    }

    /**
     * 被判定为评论文案、但当时可能还没挂到父容器上的控件。
     *
     * 存在的理由是**添加顺序不确定**：
     *  B站给 Tab 塞自定义布局，控件可能是「先 setText 文字、之后才 addView 到树上」，
     *  也可能反过来。文字被设好的那一刻，它可能还没有父容器，
     *  这时候往上找 Tab 项是找不到的。
     *
     * 所以先把「这个控件是评论文案」记下来，等它（或它的父容器）真正挂到树上时，
     * 再连同它所属的 Tab 项一起藏掉 —— 这样跟添加顺序无关。
     */
    private val pendingCommentTexts: MutableSet<View> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** 子树里有没有被标记过的评论文案控件。 */
    private fun containsPendingCommentText(root: View, depth: Int = 4): Boolean {
        if (pendingCommentTexts.contains(root)) return true
        if (depth <= 0 || root !is android.view.ViewGroup) return false
        for (i in 0 until root.childCount) {
            if (containsPendingCommentText(root.getChildAt(i), depth - 1)) return true
        }
        return false
    }

    /** 便宜的关键词预筛，避免对全 App 的 setText 做完整判定。 */
    private fun maybeCommentLabel(raw: String): Boolean =
        raw.contains("评论") || raw.contains("回复") || raw.contains("热门") ||
            raw.contains("热度") || raw.contains("精选") || raw.contains("时间") ||
            raw.contains("comment", ignoreCase = true) ||
            raw.contains("reply", ignoreCase = true)

    /**
     * 把一个入口控件连带它所属的 Tab 项一起藏掉。
     *
     * 根容器不可用时至少保证 Tab 项被藏掉 —— 标签栏里不能留一个 364px 的空位。
     */
    private fun hideEntryAndTabItem(view: View?) {
        val view = view ?: return
        val root = ViewShield.currentRoot()
        if (root != null) {
            val config = Host.config()
            if (config.hideEntry && ViewClassifier.classify(view, config.strength, root) == MatchKind.ENTRY) {
                for (container in ViewClassifier.entryContainers(view, root)) {
                    ViewShield.hide(container, MatchKind.ENTRY)
                }
                return
            }
        }
        ViewClassifier.findTabItemAncestor(view)?.let {
            ViewShield.hide(it, MatchKind.ENTRY)
        }
    }

    /**
     * 拦 `ViewGroup.addView`：刚插进来的 View 立刻判定并隐藏，不等下一秒的扫描。
     *
     * 这是「评论区闪现」的主要来源：RecyclerView 复用 item、Fragment 切换容器都是先插进来再设内容。
     */
    private fun hookAttach() {
        val layoutParams = ViewGroup.LayoutParams::class.java
        val variants = listOf(
            arrayOf(View::class.java),
            arrayOf(View::class.java, Int::class.java),
            arrayOf(View::class.java, Int::class.java, layoutParams),
        )
        for (params in variants) {
            val method = Reflect.findMethod(ViewGroup::class.java, "addView", *params)
            Host.module.hookAfter(method, "hc.addView") { chain, _ ->
                val child = chain.getArg(0) as? View ?: return@hookAfter
                hideIfCandidate(child)
            }
        }
    }

private fun hideIfCandidate(child: View) {
        if (ViewShield.isReentrant()) return
        if (!Host.isActive()) return
        if (ViewShield.isHidden(child)) return

        // 标签栏里的标签项：整块处理。
        //
        // B站给每个 Tab 塞了自定义布局，标题文字藏在 #tab_layout 里面，
        // 从 TabView 这一层看不到文字。所以这里往里翻一层找评论文案，
        // 找到就把整个 TabView 藏掉 —— 只藏文字的话，标签栏里会剩下
        // 一个 364px 宽的空位和「2416」那个评论数，看起来就是闪了一下。
        if (child.javaClass.name.contains("TabLayout\$TabView")) {
            if (ViewClassifier.containsCommentLabel(child) || containsPendingCommentText(child)) {
                ViewShield.hide(child, MatchKind.ENTRY)
                // 标签的下标就是页码，趁它还挂在标签栏上把翻页守卫教会
                PagerGuard.noteCommentTab(child)
                Logger.i("隐藏评论 Tab：${child.javaClass.name}")
            }
            return
        }

        // 控件刚挂到树上，而它自己（或它的子树）是被判定过的评论文案：
        // 连同它所属的 Tab 项一起藏掉。
        if (containsPendingCommentText(child)) {
            val tabItem = ViewClassifier.findTabItemAncestor(child)
            ViewShield.hide(tabItem ?: child, MatchKind.ENTRY)
            Logger.i("隐藏评论文案所在控件：${child.javaClass.name}")
            return
        }

        if (!ViewClassifier.quickCandidate(child)) return

        val config = Host.config()
        val root = ViewShield.currentRoot()
        if (root == null) {
            // Activity 还没走到 onPostResume，根容器未知，做不了尺寸判断。
            // 但这恰恰是「评论」两个字闪一下最容易发生的时刻，
            // 所以用不依赖根容器的纯文字判定顶一下，总比等下一秒的扫描强。
            val kind = ViewClassifier.classifyTextOnly(child, config.strength)
            if (kind == MatchKind.NONE) {
                ViewShield.requestImmediateScan()
                return
            }
            ViewShield.hide(child, kind)
            Logger.i("fast-path hide (no root): ${child.javaClass.name}")
            return
        }

        val kind = ViewClassifier.classify(child, config.strength, root)
        if (kind == MatchKind.NONE) return
        val enabled = when (kind) {
            MatchKind.ENTRY -> config.hideEntry
            MatchKind.PANEL -> config.hidePanel
            MatchKind.NONE -> false
        }
        if (!enabled) return
        val targets = when {
            kind == MatchKind.ENTRY -> ViewClassifier.entryContainers(child, root)
            // 面板内部文案（"热门评论" 之类）只是种子，要藏的是它所在的容器。
            // 非 TextView 的命中本身就是容器，别再往上爬，否则会一路爬到 Activity 根布局。
            kind == MatchKind.PANEL && child is TextView ->
                listOf(ViewClassifier.panelContainer(child, root))

            kind == MatchKind.PANEL -> listOf(child)
            else -> emptyList()
        }
        for (target in targets) ViewShield.hide(target, kind)
    }

    // ---------------------------------------------------------------- 点击

    /**
     * 拦 `View.performClick`。
     *
     * 隐藏只是让按钮看不见，但点击分发逻辑还在（App 往往在更外层监听整块区域的触摸），
     * 所以这里再堵一次，保证「看不见也点不开」。
     */
    private fun hookClicks() {
        val performClick = Reflect.findMethod(View::class.java, "performClick")
        Host.module.hookBefore(performClick, "hc.performClick") { chain ->
            if (!Host.isActive()) return@hookBefore false
            val config = Host.config()
            if (!config.hideEntry) return@hookBefore false
            val view = chain.thisObject as? View ?: return@hookBefore false
            if (!ViewClassifier.quickCandidate(view)) return@hookBefore false

            val root = ViewShield.currentRoot()
            val kind = if (root != null) {
                ViewClassifier.classify(view, config.strength, root)
            } else {
                MatchKind.NONE
            }
            if (kind != MatchKind.ENTRY) return@hookBefore false

            Logger.d("blocked click on comment entry ${view.javaClass.simpleName}")
            true // 已消费，不再执行原点击逻辑
        }
    }

    // ---------------------------------------------------------------- 跳转

    /**
     * 拦 `startActivity`：评论区如果是独立 Activity（或国际版那种把评论区做成页面的实现），
     * 光隐藏 View 树拦不住分享链接、搜索结果等入口带过去的跳转。
     */
    private fun hookNavigation() {
        val variants = listOf(
            arrayOf(Intent::class.java),
            arrayOf(Intent::class.java, Int::class.java),
            arrayOf(Intent::class.java, Int::class.java, Bundle::class.java),
        )
        for (params in variants) {
            val method = Reflect.findMethod(ContextWrapper::class.java, "startActivity", *params)
            Host.module.hookBefore(method, "hc.startActivity") { chain ->
                if (!Host.isActive()) return@hookBefore false
                val config = Host.config()
                if (!config.blockNavigation) return@hookBefore false
                val intent = chain.getArg(0) as? Intent ?: return@hookBefore false
                val reason = NavigationGuard.commentRelatedReason(intent)
                if (reason == null) {
                    false
                } else {
                    Logger.d("blocked startActivity -> ${intent.component?.className} ($reason)")
                    true
                }
            }
        }
    }
}
