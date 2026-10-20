package io.github.qiuxi903.hidecomments.hook.engine

import android.content.pm.ApplicationInfo
import io.github.qiuxi903.hidecomments.hook.Logger
import dalvik.system.DexFile
import java.io.File
import java.util.Enumeration

/**
 * 运行时 dex 类名索引。
 *
 * 存在的原因很实际：抖音、B站这些 App 的类名被混淆过，而且**每个版本都在变**。
 * 现成的开源模块（douyim、DouyinEnhancer、mbga）无一例外都上了 DexKit，
 * 就是因为写死类名等于每次发版都要重写一遍 hook 点。
 *
 * 这里用了一个更轻的办法：不反编译、不扫方法体，只用 `DexFile.entries()` 把类名列表读出来，
 * 按关键字过滤，拿到「名字里带 comment 的类」，再挑其中确实是控件或 Fragment 的挂 hook。
 * 代价是读一遍几百 MB 的 dex（后台线程，几秒钟），换来的是改版后大概率不用动代码。
 *
 * 插件化的 App（快手的评论区在 DVA 插件里）主 dex 扫不到，
 * 所以支持在拿到插件 ClassLoader 之后通过 [registerExtraSource] 把插件的 dex 补进来。
 */
object DexIndex {

    private val lock = Any()

    /** 小写关键字 -> 命中的类名 */
    private val index = LinkedHashMap<String, MutableList<String>>()

    private val sourcePaths = LinkedHashSet<String>()

    /** 已经扫过的路径，避免插件补进来时把主 dex 重扫一遍。 */
    private val scannedPaths = LinkedHashSet<String>()

    private var scanned = false
    private var lastKeywords: List<String> = emptyList()

    private val readyCallbacks = ArrayList<(List<String>) -> Unit>()

    fun init(applicationInfo: ApplicationInfo) {
        synchronized(lock) {
            applicationInfo.sourceDir?.let { sourcePaths.add(it) }
            applicationInfo.splitSourceDirs?.let { sourcePaths.addAll(it) }
        }
    }

    /**
     * 触发扫描。已扫完就立刻回调，否则排队等扫完。
     *
     * @param keywords 只保留类名里包含这些关键字的类，先过滤再入库省内存
     */
    fun ensureScanned(keywords: List<String>, onReady: (List<String>) -> Unit) {
        val paths: List<String>
        val alreadyDone: Boolean
        synchronized(lock) {
            lastKeywords = keywords
            alreadyDone = scanned
            if (alreadyDone) {
                // 注意：不能在锁内回调。onReady 去做 Class.forName 和挂 hook，
                // 持有锁做这些重活会把并发的 ensureScanned 全堵死。
                paths = emptyList()
            } else {
                readyCallbacks.add(onReady)
                if (readyCallbacks.size > 1) return // 已经有一个扫描在跑
                paths = sourcePaths.filter { !scannedPaths.contains(it) }
            }
        }

        if (alreadyDone) {
            onReady(allCandidates())
            return
        }
        if (paths.isEmpty()) {
            finishScan()
            return
        }

        Thread({ scan(paths, keywords) }, "hidecomments-dex-index").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 把某个 ClassLoader 背后的 dex 也纳入索引。
     *
     * 快手的评论区是 DVA 插件（APK 内的 `assets/dva_feature/comment_detail-master.zip`），
     * 主 dex 里根本没有 `com.yxcorp.gifshow.comment.*` 这些类，必须走这条路。
     */
    fun registerExtraSource(loader: ClassLoader) {
        val paths = extractDexPaths(loader)
        if (paths.isEmpty()) {
            Logger.d("没能从 $loader 里提取 dex 路径，插件类只能靠具体类名 hook")
            return
        }
        var added = false
        synchronized(lock) {
            for (path in paths) {
                if (scannedPaths.contains(path)) continue
                if (sourcePaths.add(path)) {
                    added = true
                    Logger.d("plugin dex added: $path")
                }
            }
        }
        if (!added) return

        // 让下一次 ensureScanned 只补扫新加入的路径
        synchronized(lock) { scanned = false }
        Thread({
            val pending = synchronized(lock) { sourcePaths.filter { !scannedPaths.contains(it) } }
            if (pending.isEmpty()) {
                finishScan()
                return@Thread
            }
            scan(pending, lastKeywords)
            finishScan()
        }, "hidecomments-dex-index-plugin").apply {
            isDaemon = true
            start()
        }
    }

    private fun scan(paths: List<String>, keywords: List<String>) {
        val lowered = keywords.map { it.lowercase() }
        var walked = 0

        for (path in paths) {
            if (!File(path).exists()) {
                Logger.w("dex not found: $path")
                continue
            }
            val dexFile = runCatching { DexFile(path) }.getOrElse {
                Logger.w("cannot open dex: $path", it)
                null
            } ?: continue

            try {
                // DexFile.entries() 返回的已经是类名全限定字符串，没有 Entry 对象这一层
                val classes: Enumeration<String> = dexFile.entries()
                while (classes.hasMoreElements()) {
                    walked++
                    val name = classes.nextElement() ?: continue
                    val lower = name.lowercase()
                    if (lowered.none { lower.contains(it) }) continue
                    synchronized(lock) {
                        val bucket = index.getOrPut(lower) { ArrayList() }
                        if (name !in bucket) bucket.add(name)
                    }
                }
            } catch (throwable: Throwable) {
                Logger.w("dex scan aborted at $path", throwable)
            } finally {
                runCatching { dexFile.close() }
                synchronized(lock) { scannedPaths.add(path) }
            }
        }

        Logger.i("dex index: walked $walked classes, ${index.size} keyword buckets")
    }

    private fun finishScan() {
        val callbacks: List<(List<String>) -> Unit>
        val candidates: List<String>
        synchronized(lock) {
            scanned = true
            callbacks = ArrayList(readyCallbacks)
            readyCallbacks.clear()
            candidates = allCandidates()
        }
        for (callback in callbacks) {
            runCatching { callback(candidates) }
                .onFailure { Logger.w("dex ready callback failed", it) }
        }
    }

    private fun allCandidates(): List<String> = index.values.flatten().distinct()

    /** 索引里目前有多少候选类，供诊断输出。 */
    fun peek(): Int = synchronized(lock) { index.values.sumOf { it.size } }

    /**
     * 从 ClassLoader 里抠出它背后所有 dex / apk / zip 的路径。
     *
     * 走的是 `BaseDexClassLoader.pathList` → `dexElements[].path` 这条反射链，
     * 字段名在不同 Android 版本上一直是这几个，风险可控。
     */
    private fun extractDexPaths(loader: ClassLoader): List<String> = runCatching {
        var current: Class<*>? = loader.javaClass
        var pathListField: java.lang.reflect.Field? = null
        var depth = 0
        while (current != null && pathListField == null && depth < 5) {
            pathListField = runCatching { current.getDeclaredField("pathList") }.getOrNull()
            current = current.superclass
            depth++
        }
        val field = pathListField ?: return@runCatching emptyList()
        field.isAccessible = true
        val pathList = field.get(loader) ?: return@runCatching emptyList()

        val elementsField = pathList.javaClass.getDeclaredField("dexElements")
        elementsField.isAccessible = true
        val elements = elementsField.get(pathList) as? Array<*> ?: return@runCatching emptyList()

        elements.mapNotNull { element ->
            runCatching {
                val pathField = element?.javaClass?.getDeclaredField("path")
                pathField?.isAccessible = true
                pathField?.get(element) as? String
            }.getOrNull()
        }
    }.getOrElse {
        Logger.w("extractDexPaths failed", it)
        emptyList()
    }
}
