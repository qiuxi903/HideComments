package io.github.qiuxi903.hidecomments.hook.engine

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.qiuxi903.hidecomments.core.Strength
import java.util.WeakHashMap

/** 一个 View 被判定成什么。 */
enum class MatchKind {
    NONE,

    /** 打开评论区的入口：Tab、按钮、图标。 */
    ENTRY,

    /** 评论区容器本身。 */
    PANEL,
}

/**
 * 识别「哪个 View 是评论区」。
 *
 * 这里是整个模块技术风险最集中的地方。三家 App 的布局资源名都被混淆过，没有一份能长期有效的
 * 选择器表，所以判定分成三档可靠度，从最可靠的往下退：
 *
 *  1. **资源 ID 名字**（`R.id.xxx` 的字符串形式）—— 最可靠。整数值每次发版都会变，名字相对稳定，
 *     而且 `comment` / `reply` / `cmt` 这类英文单词混淆器不会去改（改了 App 自己也得跟着改）。
 *  2. **控件类名** —— 被 XML inflate 的自定义 View 通常不会被 R8 混淆，因为 App 自己要反射用到。
 *  3. **控件文字** —— 最不可靠，文案随语言和 AB 实验变。只在「标准」和「激进」档启用。
 *
 * 三档全部落空时才轮到「激进」档的几何猜测，而那一档可以在设置里关掉。
 */
object ViewClassifier {

    /** id 的字符串形式拿一次要跨 Resources，缓存起来避免每帧都算。 */
    private val idNameCache: MutableMap<View, String> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, String>())

    /** 评论区容器的资源 ID 关键字。 */
    private val PANEL_ID_TOKENS = arrayOf("comment", "reply", "cmt")

    /** 评论区容器的类名特征。 */
    private val PANEL_CLASS_TOKENS = arrayOf(
        ".comment.",
        ".comments.",
        "commentpanel",
        "commentlayout",
        "commentlist",
        "commentcontainer",
        "commentview",
        "commentfragment",
        "replypanel",
        "replylayout",
        "cmtpanel",
        "cmtlist",
    )

    /** 入口按钮的类名特征。 */
    private val ENTRY_CLASS_TOKENS = arrayOf(
        "commententry",
        "commentbutton",
        "commentitem",
        "commenttab",
        "replybutton",
        "replyentry",
    )

    /**
     * 入口按钮的文字白名单。
     *
     * 归一化之后做「完全相等」或「前缀匹配」，并且要求控件本身足够小 ——
     * 一个整屏宽的 TextView 上写着「评论」，八成是视频描述而不是入口按钮。
     */
    private val ENTRY_TEXTS = setOf(
        "评论", "评论区", "回复", "条评论", "全部评论", "写评论", "说点什么",
        "评论一下", "查看评论", "热门评论", "最新评论",
        "comment", "comments", "reply", "replies", "commentreply",
    )

    private val ENTRY_TEXT_PREFIXES = arrayOf("评论", "回复")

    /**
     * 评论区**面板内部**的固定文案。
     *
     * 这些是纯标签（"热门评论"、"按热度"），不是按钮，也不带可点击祖先 ——
     * 所以走入口按钮那条规则会被跳过。但它们是评论区面板最稳定的特征：
     * 面板容器本身的名字经常被混淆成看不出含义，但这几行文案改不掉。
     *
     * 用它们当「种子」：一旦命中，就从这里往上找真正的面板容器。
     *
     * **只收「评论」二字开头的。** 像「按热度」「按时间」这种排序词到处都有 ——
     * 搜索页的排序栏里就是「按热度」，把它当种子会导致整个搜索结果列表被藏掉。
     */
    private val PANEL_SEED_TEXTS = setOf(
        "热门评论", "最新评论", "全部评论", "精选评论", "精彩评论", "相关评论",
        "置顶评论", "评论一下", "说点什么", "写下你的评论", "查看全部评论", "大家都在说",
    )

    /**
     * 往上找容器时的上限（占根容器高度的比例）。
     *
     * 用来防止一路爬到 Activity 根布局把整个界面连视频区一起干掉。
     * 0.6 这个值对应实机观感：评论区面板大概占屏幕下半部分，
     * 而「视频 + Tab 行 + 内容区」那个更大的公共父容器通常超过 60%，正好在此停下。
     */
    private const val CONTAINER_HEIGHT_CEILING = 0.6f


    private const val MASK_PANEL = 1
    private const val MASK_ENTRY = 2

    /**
     * 类名特征按 Class 缓存。
     *
     * `addView` 是全 App 最热的方法之一，每次都 `toLowerCase()` 再扫十几条子串会明显掉帧。
     * 类名对一个进程来说是稳定的，缓存一次就够了。
     */
    private val classMaskCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Int>()

    private fun classMask(clazz: Class<*>): Int = classMaskCache.getOrPut(clazz) {
        val name = clazz.name.lowercase()
        var mask = 0
        if (PANEL_CLASS_TOKENS.any { name.contains(it) }) mask = mask or MASK_PANEL
        if (ENTRY_CLASS_TOKENS.any { name.contains(it) }) mask = mask or MASK_ENTRY
        mask
    }

    /**
     * 便宜的预筛：只有可能命中的 View 才值得跑完整判定。
     *
     * 用来保护 `ViewGroup.addView` 这类高频路径 —— 大部分控件在这里就被排除掉了。
     */
    fun quickCandidate(view: View): Boolean {
        if (view is TextView) return true
        val mask = classMask(view.javaClass)
        if (mask != 0) return true
        if (view.id != View.NO_ID) {
            val name = idName(view)?.lowercase() ?: return false
            return PANEL_ID_TOKENS.any { name.contains(it) }
        }
        return false
    }

    fun idName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        idNameCache[view]?.let { return it.ifEmpty { null } }
        val name = runCatching {
            view.resources?.getResourceEntryName(id)
        }.getOrNull().orEmpty()
        idNameCache[view] = name
        return name.ifEmpty { null }
    }

    /**
     * 归一化控件文字：去掉括号里的评论数（"评论(128)"）、去掉数字与空白、转小写。
     */
    fun normalizedText(view: View): String? {
        if (view !is TextView) return null
        val raw = runCatching { view.text?.toString() }.getOrNull() ?: return null
        if (raw.isBlank()) return null
        return normalizeText(raw)
    }

    fun normalizeText(raw: String): String {
        var text = raw
        // "评论(128)" / "评论 128 条" 里的括号部分
        text = text.replace(Regex("[（(【\\[][^）)】\\]]{0,12}[）)】\\]]"), "")
        text = text.replace(Regex("[0-9]"), "")
        text = text.replace(Regex("\\s+"), "")
        return text.lowercase()
    }

    /**
     * 主判定。
     *
     * @param root 当前界面的根 View，用来给「激进」档做几何判断
     */
    fun classify(view: View, strength: Strength, root: View): MatchKind {
if (view.visibility != View.VISIBLE) {
            val idName = idName(view)?.lowercase()
            if (idName != null && PANEL_ID_TOKENS.any { idName.contains(it) }) {
                // 不可见的控件没法用尺寸判断 —— 它的高度是 0，
                // 而「高度小于根容器四分之一」会把 0 判成「行级控件（入口按钮）」，
                // 于是评论区容器被误判成入口按钮，
                // 「记录它在第几页」的逻辑只对容器生效，页码就永远学不到，
                // 翻页守卫自然不会触发。所以这里一律按容器处理。
                return MatchKind.PANEL
            }
        }

        val idName = idName(view)?.lowercase()

        // ---- 第 1 档：资源 ID 名字 ----
        if (idName != null && PANEL_ID_TOKENS.any { idName.contains(it) }) {
            // 小控件（单条评论、图标）更可能是入口或行，大容器才是评论区
            return if (isRowLike(view, root)) MatchKind.ENTRY else MatchKind.PANEL
        }

        // ---- 第 2 档：类名（按 Class 缓存，避免每次 addView 都做字符串扫描） ----
        val mask = classMask(view.javaClass)
        if (mask and MASK_PANEL != 0) {
            return if (isRowLike(view, root)) MatchKind.ENTRY else MatchKind.PANEL
        }
        if (mask and MASK_ENTRY != 0) {
            return MatchKind.ENTRY
        }

        // ---- 第 3 档：文字（需要用户主动开启） ----
        if (strength != Strength.GENTLE) {
            val text = normalizedText(view)
            if (text != null) {
                // 面板内部的固定文案：它是容器种子，自己不该被当成按钮
                if (text in PANEL_SEED_TEXTS) {
                    return MatchKind.PANEL
                }
                if (looksLikeEntryText(text) && isSmallEnough(view, root)) {
                    // 文字**恰好等于**「评论」「回复」这种词，本身就足以判定是评论入口。
                    //
                    // 之前这里额外要求「必须有可点击祖先」，结果把 B站的评论 Tab 漏掉了：
                    // 那个 Tab 是个纯文本标签，宿主未必给它挂点击监听，
                    // 于是只能等周期扫描才被藏起来 —— 表现就是打开视频时「评论」闪一下。
                    //
                    // 精确匹配不会误伤：正文里的「评论」是标题/段落的一部分，
                    // 那个 TextView 的 text 是整段文字，不等于「评论」两个字。
                    if (text in ENTRY_TEXTS) {
                        return MatchKind.ENTRY
                    }
                    // 前缀匹配（评论xxx）仍然要求可点击，保守一点
                    if (hasClickableAncestor(view)) {
                        return MatchKind.ENTRY
                    }
                }
            }
        }

        // ---- 第 4 档：几何猜测，只有激进档才启用 ----
        if (strength == Strength.AGGRESSIVE && looksLikeCommentPanel(view, root)) {
            return MatchKind.PANEL
        }

        return MatchKind.NONE
    }

    private fun looksLikeEntryText(normalized: String): Boolean {
        if (normalized in ENTRY_TEXTS) return true
        return ENTRY_TEXT_PREFIXES.any { normalized.startsWith(it) && normalized.length <= 6 }
    }

    /** 「行级」控件：高度不到根容器四分之一，典型的一条评论或一个图标按钮。 */
    private fun isRowLike(view: View, root: View): Boolean {
        val rootHeight = root.height
        // 高度为 0 说明还没测量，没法判断大小 —— 按容器处理，
        // 否则「0 比任何正数都小」会把还没布局的容器误判成行级控件
        if (view.height == 0) return false
        if (rootHeight <= 0) return view.height in 1..dp(240)
        return view.height < rootHeight / 4
    }

    /** 尺寸上够小（是一行标签而不是一段正文）。 */
    private fun isSmallEnough(view: View, root: View): Boolean {
        if (view.height > dp(160)) return false
        if (root.width > 0 && view.width > root.width * 0.6f) return false
        return true
    }

    /** 入口按钮的完整特征：体积小，且自身或祖先可点击。 */
    private fun isSmallClickable(view: View, root: View): Boolean =
        isSmallEnough(view, root) && hasClickableAncestor(view)

    private fun hasClickableAncestor(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable || current.isLongClickable) return true
            current = current.parent as? View
            depth++
        }
        return false
    }

    /**
     * 激进档的几何猜测：一个占了大半屏、位于下半部分、子节点又不少的大容器。
     *
     * 这个判定会误伤（比如「更多推荐」列表），所以只在激进档启用，且要求已经排除了视频播放区。
     */
    private fun looksLikeCommentPanel(view: View, root: View): Boolean {
        if (view !is ViewGroup) return false
        if (root.width <= 0 || root.height <= 0) return false
        if (view.width < root.width * 0.75f) return false
        if (view.height < root.height * 0.22f) return false
        if (view.height > root.height * 0.8f) return false
        if (view.top < root.height * 0.15f) return false
        return view.childCount >= 4
    }

    /**
     * 入口按钮往上找到真正该隐藏的容器。
     *
     * 视频页里的 Tab 通常是「图标 + 文字」两层结构，只隐藏文字会留下一个孤儿图标，
     * 所以沿着父链往上走，把「自己没几个孩子」的祖先一并收掉，最多上溯 3 层。
     */
    fun entryContainers(view: View, root: View): List<View> {
        val result = LinkedHashSet<View>()
        result.add(view)
        // 控件本身就是列表项的话，最多只藏它自己。
        //
        // 列表项 = RecyclerView 的直接子节点，代表的是「一条内容」——
        // 搜索结果里的一个视频、评论列表里的一条评论。
        // 把它藏掉就等于「把这条内容藏了」，搜索页会变成一个视频都搜不到。
        if (isListItem(view)) return result.toList()

        var current = view.parent as? View
        var depth = 0
        while (current != null && depth < 5) {
            if (isListItem(current)) break
            if (current is ViewGroup) {
                val visibleChildren = (0 until current.childCount)
                    .count { current.getChildAt(it).visibility == View.VISIBLE }
                val tooTall = root.height > 0 && current.height > root.height / 3
                // TabLayout 的标签项要往上吃到 TabView 那一层。
                //
                // B站给每个 Tab 塞了自定义布局：
                //   TabView > #container > #tab_layout > #tab_title(文字) + #tab_sub_title(条数)
                // 只藏文字的话，标签栏里会留下一个 364px 宽的空位，所以要把 TabView 一起藏。
                //
                // 但**只能停在 TabView**。再往上就是标签栏本身（UnitedTabLayout），
                // 把它一起藏掉的话「简介」这个 Tab 也没了，整条顶边栏直接消失 ——
                // 这个坑我踩过一次。
                val isTabItem = current.javaClass.name.contains("TabLayout\$TabView")
                if (isTabItem) {
                    result.add(current)
                    break
                }
                if (visibleChildren > 2 || tooTall) break
            }
            result.add(current)
            current = current.parent as? View
            depth++
        }
        return result.toList()
    }

    /** 往上找到「这个控件属于哪个 Tab 项」，找不到返回 null。 */
    fun findTabItemAncestor(view: View): View? {
        var current = view.parent as? View
        var depth = 0
        while (current != null && depth < 8) {
            if (current.javaClass.name.contains("TabLayout\$TabView")) return current
            current = current.parent as? View
            depth++
        }
        return null
    }

    /**
     * 这个控件是不是「列表里的一条内容」。
     *
     * 列表项 = RecyclerView 的直接子节点，代表的是一条独立内容
     * （搜索结果里的一个视频、评论列表里的一条评论）。
     *
     * 把它藏掉就等于把这条内容藏了 —— 搜索页会变成一个视频都搜不到，
     * 所以凡是「往上找容器」的逻辑，都必须在列表项这里刹住。
     */
    private fun isListItem(view: View): Boolean {
        val parent = view.parent as? View ?: return false
        return parent.javaClass.name.contains("RecyclerView")
    }

    /**
     * 在一棵子树里找有没有评论相关的文字。
     *
     * 用来判断「这个 Tab 是不是评论 Tab」——
     * B站给每个 Tab 塞自定义布局，标题文字藏在 `#tab_layout` 里面，
     * 从 TabView 这一层是看不到文字的，得往里翻一层。
     */
    fun containsCommentLabel(root: View, maxDepth: Int = 4): Boolean {
        if (maxDepth < 0) return false
        if (root is TextView) {
            val text = normalizedText(root)
            if (text != null && isCommentLabel(text)) return true
        }
        if (root !is ViewGroup) return false
        for (i in 0 until root.childCount) {
            if (containsCommentLabel(root.getChildAt(i), maxDepth - 1)) return true
        }
        return false
    }

    /**
     * 从面板内部的种子控件往上找到真正的面板容器。
     *
     * 沿父链一直走到「再往上就要把视频区也吃掉」为止，返回那个最高的、仍然合理的祖先。
     * 实机上「热门评论」的上溯链路大概是：
     * 文本 → 标题行 → 列表容器(约占屏幕 45%) → 面板根(约占 72%，到顶停手)，
     * 于是隐藏的是列表容器，视频和 Tab 行都保住。
     */
    fun panelContainer(seed: View, root: View): View {
        // 这个文案种子只有在「它所在的区域确实是个评论区」时才配当容器种子。
        //
        // 没有这道校验的话，搜索页排序栏里的「按热度」也会命中，
        // 然后一路往上爬到整个结果列表，把搜索结果全藏掉 —— 那是严重误伤。
        //
        // 判据是：这片区域里确实存在评论相关的控件（id 或类名带 comment/cmt/reply）。
        // 认不出来就只藏种子文字本身，宁可少藏也不误伤。
        var current = seed.parent as? View
        var depth = 0
        while (current != null && depth < 8) {
            // 列表项是红线。
            //
            // 搜索结果的卡片里有个 `#tv_right_comment_text`（评论数标签），
            // id 带 comment 会被认成「评论区味道」，于是整张卡片被当评论区藏掉 ——
            // 表现就是「搜索页一个视频都搜不到」。
            // 列表项代表的是一条独立内容，藏它就等于藏了一条内容，绝不能碰。
            if (isListItem(current)) return seed

            if (hasCommentishDescendant(current)) {
                if (root.height > 0 && current.height > root.height * CONTAINER_HEIGHT_CEILING) break
                if (root.height > 0 && current.top < root.height * 0.15f) break
                if (PagerGuard.isPageBoundary(current)) break
                return current
            }
            // 别爬到屏幕顶部去，那已经是全局容器而不是评论区面板了
            if (root.height > 0 && current.top < root.height * 0.15f) break
            current = current.parent as? View
            depth++
        }
        return seed
    }

    /**
     * 这片区域里有没有「评论味道」的控件。
     *
     * 认 id 名或类名里带 comment / cmt / reply 的控件。
     * B站的评论区里就有 `#cmt3_recycler`、`#comment_main` 这类 id，认得出来。
     */
    private fun hasCommentishDescendant(root: View, depth: Int = 6, budget: IntArray = intArrayOf(200)): Boolean {
        if (depth < 0 || budget[0] <= 0) return false
        budget[0]--
        val id = idName(root)?.lowercase()
        if (id != null && PANEL_ID_TOKENS.any { id.contains(it) }) return true
        val className = root.javaClass.name.lowercase()
        if (PANEL_CLASS_TOKENS.any { className.contains(it) }) return true
        if (root !is ViewGroup) return false
        for (i in 0 until root.childCount) {
            if (hasCommentishDescendant(root.getChildAt(i), depth - 1, budget)) return true
        }
        return false
    }

    /**
 * 不依赖根容器的判定。
 *
 * 只在根容器还没就绪时用（Activity 刚起来，decorView 未知）。
 * 此时没法做尺寸判断，所以只认「文字恰好等于」这种最强信号。
 */
    fun classifyTextOnly(view: View, strength: Strength): MatchKind {
        if (strength == Strength.GENTLE) return MatchKind.NONE
        val text = normalizedText(view) ?: return MatchKind.NONE
        if (text in ENTRY_TEXTS) return MatchKind.ENTRY
        if (text in PANEL_SEED_TEXTS) return MatchKind.PANEL
        return MatchKind.NONE
    }

    /**
 * 文字是否就是评论入口的文案。
 *
 * 「恰好等于评论/回复」是最强的信号，直接判定；「评论xxx」这种前缀匹配弱一些。
 */
    fun isCommentLabel(raw: CharSequence): Boolean {
        val normalized = normalizeText(raw.toString())
        if (normalized.isEmpty()) return false
        if (normalized in ENTRY_TEXTS) return true
        return ENTRY_TEXT_PREFIXES.any { normalized.startsWith(it) && normalized.length <= 6 }
    }

    fun dp(value: Int): Int = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

    /** 清掉缓存，避免 Activity 销毁后 View 泄漏。 */
    fun clearCache() {
        idNameCache.clear()
    }
}
