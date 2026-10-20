package com.hidecomments.hook

import com.hidecomments.core.TargetApp
import com.hidecomments.hook.engine.DexIndex
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口。
 *
 * 框架会为每个作用域内的**每个进程**新建一个实例，所以第一件事是把非目标进程踢掉。
 * 抖音、快手都是多进程 App（`:push`、`:webview` 之类），在那些进程里挂 View 树 hook
 * 既没意义又是白白的性能开销。
 */
class HideCommentsModule : XposedModule() {

    private var processName: String = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        processName = param.processName
        Logger.attach(this)
        Logger.i(
            "loaded in $processName | framework=${getFrameworkName()} " +
                "${getFrameworkVersion()} api=${getApiVersion()} props=${getFrameworkProperties()}",
        )
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val packageName = param.packageName
        val app = TargetApp.byPackage(packageName)

        if (app == null) {
            // 不在支持列表里（理论上 scope.list 已经限死了，这里是双保险）。
            // API 102 有 detach() 可以彻底停掉后续回调，101 没有，但直接 return 的效果一样。
            Logger.d("package $packageName not supported, ignoring")
            return
        }

        if (processName != packageName) {
            Logger.d("skipping secondary process $processName of $packageName")
            return
        }

        DexIndex.init(param.applicationInfo)

        runCatching {
            Host.install(this, app, packageName, param.classLoader)
        }.onFailure {
            Logger.e("install failed for $packageName", it)
        }
    }
}
