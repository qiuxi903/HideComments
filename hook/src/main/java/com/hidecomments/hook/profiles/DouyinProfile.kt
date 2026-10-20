package com.hidecomments.hook.profiles

import com.hidecomments.hook.AppProfile
import com.hidecomments.hook.Host
import com.hidecomments.hook.Logger
import com.hidecomments.hook.engine.CoreHooks
import com.hidecomments.hook.engine.DexIndex
import com.hidecomments.hook.engine.DynamicHooks

/**
 * 抖音 / 抖音极速版。
 *
 * 四家里最难的一个，原因有三：
 *  1. 评论区不是 Activity，是 `panel + VideoEvent` 驱动的一套能力
 *     （`EVENT_OPEN_COMMENT_PANEL` 事件打通 BaseListFragmentPanel），
 *     找不到一个稳定的「评论区 Activity」可以拦。
 *  2. 类名逐版本漂移，社区上流传的那些类名基本都过期了。
 *  3. 部分新界面走 Jetpack Compose，Compose 整屏只有一个 `AndroidComposeView`，
 *     View 树遍历对它无效 —— 这部分只能靠 dex 扫描在数据/Fragment 层解决。
 *
 * 所以这里**不写死任何类名**，全靠三层：
 *  - 通用 View 树规则（控件 id / 类名 / 文字）
 *  - dex 索引动态发现的 ViewGroup / Fragment
 *  - 点击与跳转拦截
 */
object DouyinProfile : AppProfile {

    override fun install(host: Host) {
        CoreHooks.install()
        Logger.i("抖音: 使用通用规则 + dex 动态发现（该 App 类名漂移严重，不写死类名）")
        DexIndex.ensureScanned(KEYWORDS) { names ->
            DynamicHooks.installFromCandidates(host, names)
        }
    }

    /**
     * `comment` 覆盖绝大多数；`discuss` 是抖音国际版/部分场景的叫法；
     * `cmt` 是缩写形态。
     */
    private val KEYWORDS = listOf("comment", "cmt", "reply", "discuss")
}
