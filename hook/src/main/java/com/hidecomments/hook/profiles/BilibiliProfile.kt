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

/**
 * 哔哩哔哩（国内版 / HD / 概念版 / 播放版 / 国际版共用）。
 *
 * B站详情页用的是自研的 Theseus「United」框架（国际版实测）：
 *
 * ```
 * TheseusAncestorLayout
 *   FrameLayout  #video_area                     ← 播放器
 *   RoundFrameLayout  #round_container
 *     TouchAwareConstraintLayout  #content_container
 *       UnitedViewPager  #pager                 ← 老版 androidx ViewPager
 *         ConstraintLayout                       ← 第 1 页：简介
 *           UnitedRecyclerView  #recycler
 *         ConstraintLayout                       ← 第 2 页：评论
 *           FrameLayout  #comment_container      ← 评论区本体
 *       UnitedTabLayout  #tabs                  ← 标签栏
 *         TabLayout$TabView > #tab_layout > #tab_title("评论") + #tab_sub_title("2416")
 * ```
 *
 * **下面这几个类名是从用户实机的 View 树导出里读出来的**，不是猜的，
 * 而且国际版和国内版共用同一套 comment3 SDK：
 *  - `com.bilibili.app.comment.ext.widgets.CmtRecycleView`         （#cmt3_recycler 评论列表）
 *  - `com.bilibili.app.comment3.ui.widget.CommentErrorAndEmptyView`（#cmt3_placeholder 空态）
 *  - `com.bilibili.app.comment3.ui.widget.CommentNewNoticeView`    （#new_notice 新评论提示）
 *
 * 它们都以「一实例化就隐藏」的方式挂上，比按 id 匹配更稳，App 改 id 也不受影响。
 */
object BilibiliProfile : AppProfile {

    /** 实机 View 树里确认过的评论相关控件 */
    private val COMMENT_VIEW_CLASSES = listOf(
        "com.bilibili.app.comment.ext.widgets.CmtRecycleView",
        "com.bilibili.app.comment3.ui.widget.CommentErrorAndEmptyView",
        "com.bilibili.app.comment3.ui.widget.CommentNewNoticeView",
    )

    override fun install(host: Host) {
        CoreHooks.install()
        hookGeminiTabHolder(host)
        hookCommentAdapter(host)
        hookCommentWidgets(host)
        DexIndex.ensureScanned(KEYWORDS) { names ->
            DynamicHooks.installFromCandidates(host, names)
        }
    }

    /** Gemini 页面的通用 ViewHolder，评论 Tab / 推荐 Tab / 相关 Tab 都从这里出来。 */
    private fun hookGeminiTabHolder(host: Host) {
        val clazz = host.resolveClass("com.bilibili.app.gemini.ui.UIComponentViewHolder")
        if (clazz == null) {
            Logger.d("B站: Gemini UIComponentViewHolder 不存在")
            return
        }
        for (method in Reflect.findMethodsByName(clazz, "bindViewHolder")) {
            if (method.parameterCount < 2) continue
            host.module.hookAfter(method, "hc.biliBindVH") { chain, _ ->
                val itemView = chain.getArg(1) as? View ?: return@hookAfter
                val config = Host.config()
                if (!config.isActiveFor(Host.app.id) || !config.hidePanel) return@hookAfter
                ViewShield.hide(itemView, MatchKind.PANEL)
            }
        }
    }

    /** comment3 的评论列表 Adapter，逐条隐藏。 */
    private fun hookCommentAdapter(host: Host) {
        val clazz = host.resolveClass("com.bilibili.app.comment3.ui.adapter.CommentListAdapter")
        if (clazz == null) {
            Logger.d("B站: comment3 CommentListAdapter 不存在")
            return
        }
        for (method in Reflect.findMethodsByName(clazz, "onBindViewHolder")) {
            if (method.parameterCount < 2) continue
            host.module.hookAfter(method, "hc.biliCmtVH") { chain, _ ->
                val itemView = chain.getArg(1) as? View ?: return@hookAfter
                val config = Host.config()
                if (!config.isActiveFor(Host.app.id) || !config.hidePanel) return@hookAfter
                ViewShield.hide(itemView, MatchKind.PANEL)
            }
        }
    }

    /**
     * 直接把实机确认过的评论控件在构造时就藏掉。
     *
     * 这些控件是评论区的核心内容，一实例化就 GONE，宿主再怎么复用容器都露不出来。
     */
    private fun hookCommentWidgets(host: Host) {
        for (className in COMMENT_VIEW_CLASSES) {
            val clazz = host.resolveClass(className) ?: run {
                Logger.d("B站: $className 不存在")
                continue
            }
            for (constructor in runCatching { clazz.declaredConstructors }.getOrNull() ?: continue) {
                host.module.hookAfter(constructor, "hc.biliCmtCtor") { chain, _ ->
                    val instance = chain.thisObject as? View ?: return@hookAfter
                    val config = Host.config()
                    if (!config.isActiveFor(Host.app.id) || !config.hidePanel) return@hookAfter
                    ViewShield.hide(instance, MatchKind.PANEL)
                }
            }
        }
    }

    private val KEYWORDS = listOf("comment", "cmt", "reply")
}
