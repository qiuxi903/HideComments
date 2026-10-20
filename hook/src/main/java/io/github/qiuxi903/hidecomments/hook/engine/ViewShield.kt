package io.github.qiuxi903.hidecomments.hook.engine

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.qiuxi903.hidecomments.core.HideConfig
import io.github.qiuxi903.hidecomments.hook.Logger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 屏蔽引擎的 View 层：负责扫描、隐藏、兜底重申和恢复。
 *
 * 三条防线：
 *  - **周期扫描**：Activity 恢复后启动，每秒遍历一次当前 View 树。
 *  - **绘制前重申**：App 经常会自己把 View 重新设为可见（切 Fragment、复用容器），
 *    所以在每帧绘制前把已隐藏的 View 再压回 GONE。这一步只读不遍历，代价可以忽略。
 *  - **插入即隐藏**：View 刚被 addView 进来就判定，避免一帧闪现。
 *
 * 复查时会把「已经不再匹配」的 View 恢复原状，所以用户在面板里改完设置立刻生效，不用重启 App。
 */
object ViewShield {

    private data class HiddenState(
        val originalVisibility: Int,
        val reason: String,
        /** 不可恢复：TabView 这种控件被 App 重新显示时，它内部的兄弟节点（评论数）没被藏过，
         *  放出来就会看到「126」这种评论数。宁可一直藏着。 */
        val sticky: Boolean = false,
    )

    /**
     * 弱引用 + 同步，避免 View 树被我们拖住导致 Activity 泄漏。
     *
     * 必须同步：这张表会被三条不同线程的路径同时碰到 ——
     *  1. 主线程的周期扫描与绘制前重申
     *  2. `View.setVisibility` hook（宿主随时可能在子线程调它）
     *  3. dex 动态发现的构造方法 hook（取决于谁在 new 这个 View）
     *
     * `WeakHashMap` 既不是线程安全的，内部又是链表结构，一边 `get` 一边结构性修改
     * 会直接死循环或者抛 `ConcurrentModificationException`。
     */
    private val hidden: MutableMap<View, HiddenState> =
        java.util.Collections.synchronizedMap(WeakHashMap<View, HiddenState>())

    /** 防止「我们设置 GONE → 触发 hook → hook 又设置 GONE」的无限递归。 */
    private val reentry = ThreadLocal<Boolean>()

    private val handler = Handler(Looper.getMainLooper())

    /** 当前界面的根 View（decorView）。 */
    private var rootRef: WeakReference<View>? = null

    private var configProvider: () -> HideConfig = { HideConfig() }

    /** 配置指纹。设置变化时先全部恢复再重扫，避免旧规则留下的隐藏无法撤销。 */
    private var configFingerprint: String? = null

    /** 每次扫描最多访问的节点数，防止在超深的 View 树上卡住主线程。 */
    private const val MAX_NODES = 20000

    /** 稳定期扫描间隔。 */
    private const val SCAN_INTERVAL_MS = 1000L

    /**
     * 界面刚起来时的密集扫描间隔。
     *
     * 详情页从打开到评论入口完成加载，中间有一个空窗期。这段时间里控件往往已经插进
     * View 树、但文字还没设上（宿主是先 addView 后 setText），只有密集扫描才能盖住它 ——
     * 否则就是「按钮闪一下，一秒后才消失」。
     */
    private const val BURST_INTERVAL_MS = 120L

    /** 密集扫描持续多久，之后回到稳定间隔。 */
    private const val BURST_DURATION_MS = 5000L

    fun bindConfigProvider(provider: () -> HideConfig) {
        configProvider = provider
    }

    fun setRoot(root: View?) {
        rootRef = root?.let { WeakReference(it) }
    }

    fun currentRoot(): View? = rootRef?.get()

    fun applyGone(view: View) {
        if (reentry.get() == true) return
        reentry.set(true)
        try {
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        } catch (throwable: Throwable) {
            Logger.w("setVisibility(GONE) failed", throwable)
        } finally {
            reentry.set(false)
        }
    }

    fun isHidden(view: View): Boolean = hidden.containsKey(view)

    fun isReentrant(): Boolean = reentry.get() == true

    fun hide(view: View, kind: MatchKind) {
        val reason = if (kind == MatchKind.ENTRY) "entry" else "panel"

        // 顺手记一下「这块面板挂在 pager 的第几页」，
        // 这样详情页往左滑就不会滑进评论页（内容已经藏了，再挡掉入口）
        //
        // **必须放在「已经藏过了就返回」的前面。**
        // 评论区容器常常是刚被 new 出来、还没挂到树上时就被我们藏掉的，
        // 那一刻它的 parent 是 null，往上爬的第一步就断了 —— 页码学不到。
        // 等它后来真的挂进 View 树，这一行又会再跑一次，那时 parent 齐全，学得到。
        if (kind == MatchKind.PANEL) {
            runCatching { PagerGuard.noteCommentPage(view) }
                .onFailure { Logger.i("noteCommentPage 出错: ${it.message}") }
        }

        // 藏的是标签栏里的那个 Tab 项时，顺手把「它是第几页」学下来。
        //
        // 放在这里而不是 addView 分支里，是因为 TabView 未必是被「插入即隐藏」那条路藏的 ——
        // 很多时候是「文字路径往上爬」爬到了它，那样 addView 分支根本不会触发。
        if (view.javaClass.name.contains("TabLayout\$TabView")) {
            runCatching { PagerGuard.noteCommentTab(view) }
                .onFailure { Logger.i("noteCommentTab 出错: ${it.message}") }
        }

        if (hidden.containsKey(view)) {
            applyGone(view)
            return
        }
        val isTabItem = view.javaClass.name.contains("TabLayout\$TabView")
        hidden[view] = HiddenState(view.visibility, reason, sticky = isTabItem)
        applyGone(view)
        Logger.i("hidden[$reason] ${describe(view)}")
    }

    /**
     * 每帧绘制前重申。
     *
     * 只遍历我们已隐藏的那几十个 View，不碰整棵树。
     */
    fun reassert() {
        // 必须在锁内取快照：WeakHashMap 的 keys() 迭代期间如果有别的线程结构性修改，
        // 会抛 ConcurrentModificationException；而这里是每帧都跑的，风险很高。
        val snapshot = synchronized(hidden) { hidden.keys.toList() }
        if (snapshot.isEmpty()) return
        for (view in snapshot) {
            if (view.visibility != View.GONE) {
                Logger.d("app restored visibility, re-hiding ${describe(view)}")
                applyGone(view)
            }
        }
    }

    /** 全量扫描当前根 View。 */
    fun scanCurrent() {
        val root = currentRoot() ?: return
        runCatching { scan(root, configProvider()) }
            .onFailure { Logger.w("scan failed", it) }
    }

    /**
     * 请求立刻补一次扫描。
     *
     * `addView` 触发时根容器可能还没就绪（Activity 刚 onCreate），那时无法判定，
     * 只能等下一秒的周期扫描。视频详情页加载快的时候这一秒是能被肉眼看到的，
     * 所以在这种情况下主动补一次。
     */
    fun requestImmediateScan() {
        handler.removeCallbacks(immediateScan)
        handler.post(immediateScan)
    }

    private val immediateScan = Runnable { scanCurrent() }

    fun scan(root: View, config: HideConfig) {
        val fingerprint = fingerprintOf(config)
        val configChanged = configFingerprint != null && configFingerprint != fingerprint
        configFingerprint = fingerprint
        if (configChanged) {
            Logger.d("config changed -> restore all, then rescan")
            restoreAll()
        }

        val matched = LinkedHashMap<View, MatchKind>()
        val visited = java.util.IdentityHashMap<View, Boolean>()
        val stack = ArrayDeque<View>()
        stack.addLast(root)

        var count = 0
        while (stack.isNotEmpty()) {
            if (++count > MAX_NODES) {
                Logger.d("scan hit node cap ($MAX_NODES), remaining subtree skipped")
                break
            }
            val view = stack.removeLast()
            if (visited.put(view, true) != null) continue

            val kind = ViewClassifier.classify(view, config.strength, root)
            if (kind != MatchKind.NONE && isKindEnabled(config, kind)) {
                if (kind == MatchKind.PANEL && view is android.widget.TextView) {
                    // 命中的是面板内部的文案种子（"热门评论"之类），
                    // 真正要藏的是它所在的容器，而不是这一行字
                    val container = ViewClassifier.panelContainer(view, root)
                    matched[container] = MatchKind.PANEL
                } else {
                    matched[view] = kind
                }
                if (kind == MatchKind.ENTRY) {
                    for (container in ViewClassifier.entryContainers(view, root)) {
                        matched[container] = MatchKind.ENTRY
                    }
                }
            }

            // 命中「大容器」就到此为止不再下钻；命中行级控件则继续往下找真正的容器。
            // 已经不可见的节点也不用再往下钻了：它的子树没有再扫的价值。
            val isBigPanel = kind == MatchKind.PANEL &&
                (view.visibility != View.VISIBLE ||
                    (root.height > 0 && view.height >= root.height / 4))
            if (view is ViewGroup && !isBigPanel) {
                for (i in 0 until view.childCount) {
                    stack.addLast(view.getChildAt(i))
                }
            }
        }

        // 本轮不再匹配的，恢复原状。
        //
        // 但有一个必须排除的情况：**视图还处于 GONE**。
        // 我们隐藏它之后宽高就归零了，基于尺寸的判定规则会立刻失效，
        // 如果照单恢复就会形成「隐藏 → 复查判定不通过 → 恢复 → 绘制前守卫又压回去 →
        // 下一轮再恢复」的抖动，表现为评论区闪一下之后就一直留在屏幕上。
        //
        // 只在 App 主动把它显示出来、且重新判定确实不是评论区时才恢复，
        // 这样既保住了隐藏状态，又能正确处理 RecyclerView 复用（复用的 item 会被 App 设为可见）。
        val stale = synchronized(hidden) {
            val result = ArrayList<Pair<View, HiddenState>>()
            val iterator = hidden.entries.iterator()
            while (iterator.hasNext()) {
                val (view, state) = iterator.next()
                if (state.sticky) continue
                if (matched.containsKey(view)) continue
                if (view.visibility == View.GONE) continue
                result.add(view to state)
                iterator.remove()
            }
            result
        }
        for ((view, state) in stale) {
            restore(view, state.originalVisibility)
            Logger.i("restored ${describe(view)}")
        }

        // 本轮新匹配的，隐藏
        for ((view, kind) in matched) {
            hide(view, kind)
        }
    }

    fun restoreAll() {
        val entries = synchronized(hidden) {
            val snapshot = hidden.entries.toList()
            hidden.clear()
            snapshot
        }
        for ((view, state) in entries) {
            restore(view, state.originalVisibility)
        }
    }

    /** 启动周期性扫描，root 消失时自动停。 */
    fun startPeriodicScan() {
        handler.removeCallbacksAndMessages(null)
        val startedAt = android.os.SystemClock.uptimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                try {
                    val root = currentRoot()
                    if (root == null || !root.isAttachedToWindow) return
                    val config = runCatching { configProvider() }.getOrDefault(HideConfig())
                    if (config.isActiveFor(currentAppId())) {
                        runCatching { scan(root, config) }
                            .onFailure { Logger.w("periodic scan failed", it) }
                    } else {
                        restoreAll()
                    }
                } catch (throwable: Throwable) {
                    // 这个 Runnable 不在框架的 PROTECTIVE 保护范围内：
                    // 异常一旦逃出去就是主线程未捕获异常，直接把宿主 App 搞崩。
                    Logger.e("periodic scan crashed, stopping loop", throwable)
                } finally {
                    // 放在 finally：即使这一轮出问题也要继续下一轮，否则扫描就此停摆，
                    // 表现就是「刚打开能挡、翻几屏之后评论区就冒出来了」
                    if (currentRoot()?.isAttachedToWindow == true) {
                        val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                        val interval =
                            if (elapsed < BURST_DURATION_MS) BURST_INTERVAL_MS else SCAN_INTERVAL_MS
                        handler.postDelayed(this, interval)
                    }
                }
            }
        })
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        restoreAll()
        ViewClassifier.clearCache()
        configFingerprint = null
        rootRef = null
    }

    private fun restore(view: View, visibility: Int) {
        if (reentry.get() == true) return
        reentry.set(true)
        try {
            val target = if (visibility == View.GONE) View.VISIBLE else visibility
            if (view.visibility == View.GONE && target != View.GONE) {
                view.visibility = target
            }
        } catch (throwable: Throwable) {
            Logger.w("restore failed", throwable)
        } finally {
            reentry.set(false)
        }
    }

    private fun isKindEnabled(config: HideConfig, kind: MatchKind): Boolean = when (kind) {
        MatchKind.ENTRY -> config.hideEntry
        MatchKind.PANEL -> config.hidePanel
        MatchKind.NONE -> false
    }

    private fun fingerprintOf(config: HideConfig): String =
        "${config.hideEntry}|${config.hidePanel}|${config.strength.id}"

    private fun describe(view: View): String = buildString {
        append(view.javaClass.name)
        ViewClassifier.idName(view)?.let { append(" #").append(it) }
        append(" [").append(view.width).append('x').append(view.height).append(']')
        if (view is ViewGroup) append(" children=").append(view.childCount)
    }

    private var appId: String = ""

    fun setApp(id: String) {
        appId = id
    }

    private fun currentAppId(): String = appId
}
