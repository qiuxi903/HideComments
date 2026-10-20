package io.github.qiuxi903.hidecomments.hook.engine

import android.view.View
import android.view.ViewGroup
import io.github.qiuxi903.hidecomments.hook.Host
import io.github.libxposed.api.XposedInterface
import io.github.qiuxi903.hidecomments.hook.Logger
import io.github.qiuxi903.hidecomments.hook.util.Reflect
import io.github.qiuxi903.hidecomments.hook.util.hookAfter
import io.github.qiuxi903.hidecomments.hook.util.hookBefore
import io.github.qiuxi903.hidecomments.hook.util.hookExec
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 页面切换守卫。
 *
 * 解决的是「内容挡住了，但还是能滑进评论页」这个尴尬情况。
 *
 * **认 pager 不靠类名，靠结构。** B站的 `UnitedViewPager` 是个自定义类，
 * 光看 View 树只能知道它的名字，证明不了它继承自谁；不同版本还可能换基类。
 * 所以这里用「有没有 `getCurrentItem()` 和 `setCurrentItem()`」这种结构特征来判断，
 * 认出来之后**再对它的实际类挂 hook** —— 这样连子类自己覆写了方法的情况也能抓到。
 *
 * 做法是**先认后拦**：
 *  1. 当我们判定并隐藏了一块评论面板时，顺着它的父链往上找，
 *     如果它正好挂在某个 pager 的某一页里，就把「这个 pager 的这一页」记下来。
 *  2. 之后 pager 要切到被记下的页时，切过去之后立刻切回原来那一页。
 *
 * 用「切过去再切回来」而不是「直接拦截」，是因为 ViewPager 处理滑动时走的是内部的
 * `setCurrentItemInternal`，在那一层硬拦会让内部状态和 RecyclerView 的滚动位置对不上，
 * 容易出现更糟的错位。宁可闪一下，也不要错乱。
 *
 * 本模块不依赖 androidx，全部走反射。
 */
object PagerGuard {

    private const val VIEWPAGER2 = "androidx.viewpager2.widget.ViewPager2"

    /** pager 实例 -> 被标记为评论页的页码。 */
    private val commentPages: MutableMap<View, MutableSet<Int>> =
        java.util.Collections.synchronizedMap(WeakHashMap<View, MutableSet<Int>>())

    /** 防止我们自己的回切触发自己的 hook。 */
    private val reverting = ThreadLocal<Boolean>()


    /** 已经报告过的「pager#页码」，避免每秒刷屏。 */
    private val learnedKeys: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 标签栏结构诊断只打一次。 */
    private val tabDiagShown = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 准备弹回的页码。 */
    private val pendingRevert = ThreadLocal<Int>()

    /** 诊断计数器：翻页器方法被调用的实况。 */
    private val diagCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 已经挂过 hook 的类，避免重复挂。 */
    private val hookedClasses: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 这些方法第一个参数也是 int，但和页码无关，排除掉。
     *
     * 留着它们也不会误伤（hook 体里会拿页码范围和评论页集合二次确认），
     * 但少挂几个 hook 总是好的。
     */
    private val IGNORED_PAGER_METHODS = setOf(
        "setOffscreenPageLimit", "setPageMargin", "setPageMarginDrawable",
        "setScrollState", "setScrollingCacheEnabled", "setAdapter",
        "setOnPageChangeListener",
    )

    /**
     * 这个类**自己声明**的方法（不含 View/ViewGroup 那些）。
     *
     * View 的 onMeasure/onSizeChanged 等也是「第一个参数是 int」，
     * 但它们声明在 View 里而不是翻页器里，按声明类过滤就天然排除了。
     */
    private fun ownMethods(clazz: Class<*>): List<Method> {
        val result = LinkedHashSet<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val name = current.name
            if (name == "android.view.ViewGroup" || name == "android.view.View") break
            runCatching { current.declaredMethods }.getOrNull()?.forEach { result.add(it) }
            current = current.superclass
        }
        return result.toList()
    }

    fun install() {
        hookPagerClass(Host.resolveClass(VIEWPAGER2), VIEWPAGER2)
    }

    /**
     * 对一个具体的 pager 类挂 hook。
     *
     * 既可以按基类名提前挂（覆盖大部分情况），
     * 也可以在认出实际实例后按它的真实类挂（覆盖子类覆写方法的情况）。
     */
    private fun hookPagerClass(clazz: Class<*>?, label: String) {
        if (clazz == null) {
            Logger.d("pager guard: $label 不存在")
            return
        }
        if (!hookedClasses.add(clazz.name)) return

        var hooked = 0

        // **不能按方法名找。**
        //
        // B站把 androidx ViewPager 也一起混淆了：73 个方法里页面切换那几条被改名成
        // `A (IZ)V` / `B (IIZZ)V` / `j (I)V` 之类，`setCurrentItemInternal` 这种名字
        // 一个都不存在。按名字找最多只能捞到 `setCurrentItem(I)V` 这一个重载。
        //
        // 所以改成**按签名找**：只认「这个类自己声明的、第一个参数是 int、返回 void」的方法。
        //  - 「自己声明的」把 View/ViewGroup 那些 onMeasure/onDraw 排除掉
        //  - 「第一个参数是 int」筛出所有可能带页码的方法
        //  - hook 体里再用 0~32 的范围和「这个页码是不是评论页」二次确认，误伤不了
        val candidates = ownMethods(clazz).filter { method ->
            method.parameterCount in 1..4 &&
                method.returnType == Void.TYPE &&
                method.parameterTypes.firstOrNull() == Int::class.javaPrimitiveType &&
                method.name !in IGNORED_PAGER_METHODS
        }
        Logger.i(
            "$label 待挂钩方法：" +
                candidates.joinToString { "${it.name}(${it.parameterTypes.size})" }.ifEmpty { "无" },
        )

        for (method in candidates) {
            val isPublicEntry = method.name == "setCurrentItem"
            val id = "hc.pager.${clazz.simpleName}.${method.name}(${method.parameterCount})"

            // 公开入口（点击、深链）：直接拦掉，根本不给切过去的机会
            if (isPublicEntry) {
                hooked += if (Host.module.hookBefore(method, id) { chain ->
                    if (reverting.get() == true) return@hookBefore false
                    val pager = chain.thisObject as? View ?: return@hookBefore false
                    val target = chain.getArg(0) as? Int ?: return@hookBefore false
                    if (!isCommentPage(pager, target)) return@hookBefore false
                    Logger.i("拦下翻页到评论页 #$target")
                    true
                }) 1 else 0
                continue
            }

            // 内部方法（手势滑动走的就是它）。
            //
            // 做法是「拦下 + 立刻回退」：
            //  - 拦下：不让原方法执行，pager 的当前页就不会变成评论页
            //  - 回退：用 invoker 以 ORIGIN 模式调 setCurrentItem(上一页)，
            //    绕开我们自己挂的 hook，让 RecyclerView 的滚动位置也一并回到上一页
            //
            // 之前只用 setCurrentItem(上一页) 回退是不行的 ——
            // 它内部走平滑滚动，而此时 RecyclerView 已经停在第 1 页，
            // 两边状态对不上，结果页面卡在评论页上（内容是空的）。
            hooked += if (Host.module.hookExec(method, id) { chain ->
                val pager = chain.thisObject as? View ?: return@hookExec chain.proceed()
                val target = chain.getArg(0) as? Int ?: return@hookExec chain.proceed()
                // 只记录「像页码」的调用，onMeasure 之类的噪音不记
                if (Logger.verbose && target in 0..32 && diagCount.get() < 40 &&
                    method.name !in setOf("onMeasure", "onSizeChanged", "w", "p", "B", "z")) {
                    diagCount.incrementAndGet()
                    Logger.d("翻页器调用 ${method.name}($target) 当前页=${callInt(pager, "getCurrentItem")} 是评论页=${isCommentPage(pager, target)}")
                }
                if (reverting.get() == true) return@hookExec chain.proceed()
                if (!isCommentPage(pager, target)) return@hookExec chain.proceed()

                val prev = callInt(pager, "getCurrentItem") ?: -1
                if (prev < 0 || prev == target) return@hookExec chain.proceed()

                // 拦下这一页切换。
                // 真正让手势失效的是下面的滚动守卫（scrollHorizontallyBy）——
                // RecyclerView 根本没被拖过去，这里就不会出现「状态和画面对不上」的问题。
                Logger.i("拦下翻页到评论页 #$target")
                true // 已消费，不让原方法执行
            }) 1 else 0
        }
        Logger.i("pager guard: $label 挂了 $hooked 个 hook")
    }

    /**
     * 强行把翻页器切回指定页。
     *
     * 用 invoker 的 ORIGIN 模式调用，**绕开我们自己挂的 hook** ——
     * 否则回退动作会再次被自己的守卫拦下来，变成死循环。
     */
    private fun restorePage(pager: View, target: Int) {
        reverting.set(true)
        try {
            val method = pager.javaClass.methods.firstOrNull {
                it.name == "setCurrentItem" && it.parameterCount == 1
            }
            if (method == null) {
                Logger.i("回退失败：找不到 setCurrentItem(int)")
                return
            }
            // 用 invoker 的 ORIGIN 模式调用，绕开我们自己挂的 hook
            val invoker = Host.module.getInvoker(method)
                .setType(XposedInterface.Invoker.Type.ORIGIN)
            invoker.invoke(pager, target)
            Logger.i("已回退到第 $target 页，当前=${callInt(pager, "getCurrentItem")}")
        } catch (throwable: Throwable) {
            Logger.w("回退失败", throwable)
        } finally {
            reverting.set(false)
        }
    }


    /**
     * 判断这个 View 是不是「pager 的一页」。
     *
     * ViewPager2 的页是它内部 RecyclerView 的 child；老版 ViewPager 的页是它的直接 child。
     * 一旦越过这条边界继续往上爬，就会爬到整个 pager，把**兄弟页**（比如简介页）一起藏掉 ——
     * 这正是之前「简介页内容变空白」的原因。
     */
    fun isPageBoundary(view: View): Boolean {
        val parent = view.parent as? View ?: return false
        if (parent.javaClass.name == VIEWPAGER2) {
            val group = parent as? ViewGroup ?: return false
            val recycler = runCatching { group.getChildAt(0) }.getOrNull() ?: return false
            return recycler.parent === view
        }
        if (!isPagerLike(parent)) return false
        val group = parent as? ViewGroup ?: return false
        return group.indexOfChild(view) >= 0
    }

    /**
     * 手势守卫：不让手指把翻页器拖向评论页。
     *
     * 拦的是 `onInterceptTouchEvent`（是否接管这次触摸）而不是 `scrollHorizontallyBy`。
     *
     * 原因：androidx ViewPager 的拖拽是**它自己**处理的 ——
     * onTouchEvent 里直接 `mRecyclerView.scrollTo(...)` 把内容挪过去，
     * 压根不经过 LayoutManager 的 scrollHorizontallyBy，所以拦那里没用。
     *
     * 而 onInterceptTouchEvent 决定「这次手势归我管还是归子 View 管」。
     * 滑向评论页时我们返回 false，翻页器就不接管，
     * 手势落回下面那个竖向列表（也就是什么都不发生），画面纹丝不动。
     */
    private fun installTouchGuard(pager: View) {
        val method = pager.javaClass.methods.firstOrNull {
            it.name == "onInterceptTouchEvent" && it.parameterCount == 1
        } ?: run {
            Logger.i("手势守卫：找不到 onInterceptTouchEvent")
            return
        }

        Host.module.hookExec(method, "hc.pagerTouchGuard") { chain ->
            val event = chain.getArg(0) as? android.view.MotionEvent
                ?: return@hookExec chain.proceed()
            val action = event.actionMasked

            if (action == android.view.MotionEvent.ACTION_DOWN) {
                downX.set(event.x)
                touchWarned.set(false)
                return@hookExec chain.proceed()
            }

            if (action == android.view.MotionEvent.ACTION_MOVE) {
                val pages = synchronized(commentPages) { commentPages[pager]?.toSet() } ?: emptySet()
                if (pages.isEmpty()) return@hookExec chain.proceed()
                val start = downX.get()
                if (start == null) return@hookExec chain.proceed()
                val delta = event.x - start
                val position = callInt(pager, "getCurrentItem") ?: 0
                // 横向布局里往左拖（delta < 0）是往下一页去
                val towardCommentPage = delta < 0 && (position + 1) in pages
                if (towardCommentPage) {
                    if (touchWarned.compareAndSet(false, true)) {
                        Logger.i("不让手势滑向评论页（第 $position 页）")
                    }
                    return@hookExec false // 不接管这次手势
                }
            }
            chain.proceed()
        }
    }

    /** 手势起点的横坐标。 */
    private val downX = ThreadLocal<Float>()

    /** 本次手势是否已经提示过，避免每个 MOVE 都刷一行。 */
    private val touchWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 滚动守卫：让手势根本滑不到评论页。
     *
     * 这是整套机制里最关键的一环。
     *
     * 翻页器（ViewPager）内部是一个 RecyclerView，**手势是直接拖这个 RecyclerView 的**，
     * 拖到一定程度它才通知翻页器「该切页了」。所以：
     *  - 只拦「切页」→ RecyclerView 已经被拖到半路，画面歪着，回滚也难还原
     *  - 只放行再弹回 → 翻过去再翻回来，宿主的简介页被回收重建，内容没了
     *
     * 正确做法是**在滚动层面就不让它动**：把内部 RecyclerView 的 LayoutManager 的
     * `scrollHorizontallyBy` 拦下来，滑向评论页时直接返回 0（一个像素都不滚）。
     * RecyclerView 没动，翻页器自然也不会切页，两边状态始终一致。
     */
    private fun installScrollGuard(pager: View) {
        val recycler = (pager as? ViewGroup)?.getChildAt(0) ?: return
        val layoutManager = runCatching {
            recycler.javaClass.getMethod("getLayoutManager").invoke(recycler)
        }.getOrNull() ?: return

        val method = layoutManager.javaClass.methods.firstOrNull {
            it.name == "scrollHorizontallyBy" && it.parameterCount == 3
        } ?: run {
            Logger.d("滚动守卫：找不到 scrollHorizontallyBy")
            return
        }

        Host.module.hookExec(method, "hc.pagerScrollGuard") { chain ->
            val dx = chain.getArg(0) as? Int ?: 0
            val pages = synchronized(commentPages) { commentPages[pager]?.toSet() } ?: emptySet()
            if (pages.isEmpty() || dx == 0) return@hookExec chain.proceed()

            val position = callInt(chain.thisObject, "findFirstVisibleItemPosition") ?: 0
            // 横向布局里 dx < 0 表示往下一页滑，dx > 0 表示往上一页滑
            val blocked = (dx < 0 && (position + 1) in pages) || (dx > 0 && position in pages)
            if (!blocked) return@hookExec chain.proceed()

            Logger.i("挡住滑向评论页的手势（第 $position 页，dx=$dx）")
            0 // 一个像素都不滚
        }
    }

    /**
     * 从「评论 Tab」反推它是第几页。
     *
     * 标签栏和翻页器是兄弟节点，TabLayout 的第 N 个标签对应翻页器的第 N 页 ——
     * 所以**标签的下标就是页码**。
     *
     * 这条路径比「顺着评论面板往上爬」可靠得多：
     *  - 评论面板是自底向上构造的，刚被创建时还没挂到树上，往上爬会断
     *  - 标签栏里的那个 Tab 在被加进标签栏时就已经挂好了，下标直接能算
     *  - 而且它比翻页器本身先出现 —— 用户还没来得及滑，页码就已经知道了
     */
    fun noteCommentTab(tabView: View) {
        val indicator = tabView.parent as? View ?: return
        val tabLayout = indicator.parent as? ViewGroup ?: return
        val index = indexOfTabView(tabLayout, tabView)
        if (index < 0) {
            Logger.i("评论 Tab：算不出下标（标签栏 ${tabLayout.javaClass.simpleName} 的结构不认得）")
            return
        }

        // 标签栏和翻页器通常挂在同一个父容器下
        val parent = tabLayout.parent as? ViewGroup ?: run {
            Logger.i("评论 Tab：标签栏没有父容器，放弃")
            return
        }
        val children = (0 until parent.childCount).map { parent.getChildAt(it) }
        if (tabDiagShown.compareAndSet(false, true)) {
            Logger.i(
                "评论 Tab：第 $index 个标签，标签栏父容器的孩子 = " +
                    children.joinToString { it.javaClass.simpleName },
            )
        }
        for (child in children) {
            if (!isPagerLike(child)) continue
            synchronized(commentPages) {
                commentPages.getOrPut(child) { LinkedHashSet() }.add(index)
            }
            if (learnedKeys.add("${child.javaClass.name}#$index")) {
                Logger.i("学到：评论 Tab 在 ${child.javaClass.simpleName} 的第 $index 页")
            }
            hookPagerClass(child.javaClass, child.javaClass.simpleName)
            installScrollGuard(child)
            installTouchGuard(child)
            return
        }
    }

    /**
     * 这个 TabView 在标签栏里的序号（不含那个 SlidingTabIndicator）。
     *
     * Material 的 TabLayout 把标签项放在 SlidingTabIndicator 里面，
     * 所以要往下一层翻 —— 只看标签栏的直接孩子是找不到的。
     */
    private fun indexOfTabView(tabLayout: ViewGroup, tabView: View): Int {
        var index = 0
        for (i in 0 until tabLayout.childCount) {
            val child = tabLayout.getChildAt(i)
            if (child === tabView) return index
            if (isTabItem(child)) {
                index++
                continue
            }
            if (child is ViewGroup) {
                for (j in 0 until child.childCount) {
                    val sub = child.getChildAt(j)
                    if (sub === tabView) return index
                    if (isTabItem(sub)) index++
                }
            }
        }
        return -1
    }

    private fun isTabItem(view: View): Boolean =
        view.javaClass.name.contains("TabLayout\$TabView")

    /**
     * 记录「这块评论区面板挂在 pager 的第几页」。
     *
     * 从被隐藏的视图往上走父链，逐层判断「我的父容器是不是某个 pager」，
     * 是的话就把页码记下来。
     */
    fun noteCommentPage(view: View) {
        // 诊断：把上溯链路原样打出来，认不出翻页器时这是唯一线索
        if (loggedChains.add(view.javaClass.name)) {
            val chain = StringBuilder()
            var c: View? = view
            var d = 0
            while (c != null && d < 12) {
                chain.append(c.javaClass.simpleName)
                ViewClassifier.idName(c)?.let { chain.append('#').append(it) }
                chain.append(" -> ")
                c = c.parent as? View
                d++
            }
            Logger.d("noteCommentPage 上溯链路：$chain")
        }

        var current: View? = view
        var depth = 0
        while (current != null && depth < 40) {
            val parent = current.parent as? View ?: return
            val parentName = parent.javaClass.name
            // 只要名字里带 Pager 就打一次继承链 —— 认不出翻页器时，这是唯一能定位问题的线索
            if (parentName.contains("Pager") && loggedPagers.add(parentName)) {
                Logger.d("发现候选翻页器 ${parent.javaClass.simpleName}，继承链：${superChain(parent.javaClass)}")
            }
            // **这里只做诊断，绝不据此认定「这是评论页」。**
            //
            // 「评论面板恰好在某个翻页器的某一页里」这个推断是不可靠的：
            // 抖音的主界面（首页/朋友/消息/我的）本身就是 ViewPager2，
            // 而评论面板就挂在首页那一页里 —— 照着这个推断会把第 0 页当成评论页，
            // 结果整个主界面的切换都被我们拦死。
            //
            // 唯一可信的页码来源是**标签栏里明确写着「评论」的那个 Tab**（见 noteCommentTab）。
            current = parent
            depth++
        }
    }

    /** 已经打过继承链日志的类，避免刷屏。 */
    private val loggedPagers: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 已经打过上溯链路日志的类，避免刷屏。 */
    private val loggedChains: MutableSet<String> =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun superChain(clazz: Class<*>): String {
        val names = LinkedHashSet<String>()
        var current: Class<*>? = clazz
        var depth = 0
        while (current != null && depth < 12) {
            names.add(current.simpleName)
            current = current.superclass
            depth++
        }
        return names.joinToString(" < ")
    }

    /**
     * 结构化判断「这是不是翻页器」。
     *
     * 看有没有 `getCurrentItem()` 和 `setCurrentItem()` —— 这两个方法只有翻页器才有。
     * 比按类名匹配可靠：宿主可以随便改类名、换基类，方法签名却改不掉。
     */
    private fun isPagerLike(view: View): Boolean {
        if (view.javaClass.name == VIEWPAGER2) return true
        val hasCurrent = view.javaClass.methods.any {
            it.name == "getCurrentItem" && it.parameterCount == 0
        }
        val hasSet = view.javaClass.methods.any {
            it.name == "setCurrentItem" && it.parameterCount in 1..2
        }
        return hasCurrent && hasSet
    }

    private fun indexIn(parent: View, child: View): Int {
        if (parent.javaClass.name == VIEWPAGER2) {
            // ViewPager2 内部是一个 RecyclerView，页就是它的 child
            val group = parent as? ViewGroup ?: return -1
            val recycler: View = runCatching { group.getChildAt(0) }.getOrNull() ?: return -1
            if (recycler.parent !== child) return -1
            val method = recycler.javaClass.methods.firstOrNull {
                it.name == "getChildAdapterPosition" && it.parameterCount == 1
            } ?: return -1
            return runCatching { method.invoke(recycler, child) as? Int }.getOrNull() ?: -1
        }
        // 老版 ViewPager（含 androidx 那个）：页是它的直接 child
        return callInt(parent, "indexOfChild", child) ?: -1
    }

    private fun isCommentPage(pager: View, index: Int): Boolean =
        synchronized(commentPages) { commentPages[pager]?.contains(index) == true }

    private fun callInt(target: Any, methodName: String, vararg args: Any?): Int? {
        val method = target.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterCount == args.size
        } ?: return null
        return runCatching { method.invoke(target, *args) as? Int }.getOrNull()
    }

    /** pager 不在了就清理，避免弱引用表残留。 */
    fun clear() {
        synchronized(commentPages) { commentPages.clear() }
    }
}
