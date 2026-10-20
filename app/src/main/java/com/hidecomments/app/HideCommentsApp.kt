package com.hidecomments.app

import android.app.Application
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class HideCommentsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 用户可能是正常点图标进来的（框架没连上），也可能是框架绑上来的。
        // 这里的异常绝不能把整个 App 带崩，所以包一层：连不上只影响设置功能，不影响界面。
        runCatching {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    runCatching { FrameworkRepository.onBind(service) }
                        .onFailure { Log.e(TAG, "onServiceBind failed", it) }
                }

                override fun onServiceDied(service: XposedService) {
                    runCatching { FrameworkRepository.onDied() }
                        .onFailure { Log.e(TAG, "onServiceDied failed", it) }
                }
            })
        }.onFailure {
            Log.e(TAG, "failed to register Xposed service listener", it)
        }
    }

    private companion object {
        const val TAG = "HideComments"
    }
}
