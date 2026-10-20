package io.github.qiuxi903.hidecomments.hook.util

import io.github.qiuxi903.hidecomments.hook.Logger
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Hook 辅助。
 *
 * 全部 hook 统一用 PROTECTIVE 异常模式：hook 里抛出的异常会被框架捕获并让原方法照常执行。
 * 这对一个要挂在别人 App 上的模块是硬要求 —— 模块出 bug 的后果应该是「没屏蔽」，
 * 绝不能是「目标 App 闪退」。
 *
 * @param id hook 的标识，只用于日志。libxposed API 102 的 `HookBuilder.setId()` 能把它注册给框架
 *   做原子替换与热重载，但 102 要求 compileSdk 37（见 libs.versions.toml 里的说明），
 *   所以当前只在日志里用。升级到 102 后把 [hookExec] 里那行注释位置换成 `setId(id)` 即可。
 */
fun XposedInterface.hookExec(
    executable: Executable?,
    id: String? = null,
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    block: (XposedInterface.Chain) -> Any?,
): Boolean {
    if (executable == null) {
        Logger.d("hook skipped, executable not found (id=$id)")
        return false
    }
    return runCatching {
        hook(executable)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setPriority(priority)
            .intercept(XposedInterface.Hooker { chain -> block(chain) })
        Logger.d("hook installed id=$id -> $executable")
        true
    }.getOrElse {
        Logger.w("hook failed: $executable", it)
        false
    }
}

/**
 * after 语义：先跑原方法，再对结果做事。
 *
 * `proceed()` 在这里只会被调用一次，body 拿到的 `result` 就是原方法的返回值。
 */
fun XposedInterface.hookAfter(
    executable: Executable?,
    id: String? = null,
    block: (XposedInterface.Chain, Any?) -> Unit,
): Boolean = hookExec(executable, id) { chain ->
    val result = chain.proceed()
    runCatching { block(chain, result) }
        .onFailure { Logger.w("after-hook body failed: ${chain.executable}", it) }
    result
}

/**
 * before 语义：**返回 true 表示「我拦下了，不要调用原方法」**。
 *
 * 这里刻意不用「返回值非 null 就是拦下了」那种写法。void 方法（例如 `View.setVisibility`）
 * 的 `proceed()` 返回值就是 null，用 null 当哨兵的话，hook 体里 proceed 完之后返回值是 null，
 * 外面会当成「没拦住」再 proceed 一次 —— 原方法就被执行了两遍，是很难查的 bug。
 *
 * 需要改参数而不是拦截，用 [hookExec] 直接写。
 */
fun XposedInterface.hookBefore(
    executable: Executable?,
    id: String? = null,
    block: (XposedInterface.Chain) -> Boolean,
): Boolean = hookExec(executable, id) { chain ->
    val handled = runCatching { block(chain) }.getOrDefault(false)
    if (handled) {
        true // void 方法忽略返回值，这里只是让 hooker 有一个非 null 的返回
    } else {
        chain.proceed()
    }
}
