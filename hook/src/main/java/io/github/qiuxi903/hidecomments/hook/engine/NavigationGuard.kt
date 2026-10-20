package io.github.qiuxi903.hidecomments.hook.engine

import android.content.Intent
import android.os.Bundle

/**
 * 判断一个 Intent 是不是奔着评论区去的。
 *
 * 只在用户开着「拦截跳转」时才用，判定刻意收得比较紧 —— 宁可漏一个深链，
 * 也不能把正常的页面跳转给拦了，那比不禁还糟。
 */
object NavigationGuard {

    private val CLASS_TOKENS = arrayOf("comment", "reply", "cmt")
    private val KEY_TOKENS = arrayOf("comment", "reply", "cmt", "danmu_exclude")

    /** @return 命中的原因描述；没命中返回 null。 */
    fun commentRelatedReason(intent: Intent): String? {
        intent.component?.className?.let { className ->
            val lower = className.lowercase()
            if (CLASS_TOKENS.any { lower.contains(it) }) {
                return "component=$className"
            }
        }

        intent.action?.let { action ->
            val lower = action.lowercase()
            if (CLASS_TOKENS.any { lower.contains(it) }) {
                return "action=$action"
            }
        }

        intent.data?.toString()?.let { data ->
            val lower = data.lowercase()
            if (lower.contains("comment") || lower.contains("/reply")) {
                return "data=$data"
            }
        }

        extrasReason(intent.extras)?.let { return it }

        return null
    }

    private fun extrasReason(extras: Bundle?): String? {
        if (extras == null || extras.isEmpty) return null
        return try {
            for (key in extras.keySet()) {
                val lower = key.lowercase()
                if (!KEY_TOKENS.any { lower.contains(it) }) continue
                val value = runCatching { extras.get(key) }.getOrNull()
                // 只认「值是评论 id / 作者 id」这种明确语义，跳过布尔开关类
                when (value) {
                    is String -> return "extra:$key=${value.take(32)}"
                    is Long, is Int -> return "extra:$key=$value"
                    else -> Unit
                }
            }
            null
        } catch (throwable: Throwable) {
            null
        }
    }
}
