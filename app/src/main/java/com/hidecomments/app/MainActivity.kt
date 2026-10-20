package com.hidecomments.app

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import com.hidecomments.app.databinding.ActivityMainBinding
import com.hidecomments.app.databinding.ItemAppBinding
import com.hidecomments.core.TargetApp

/**
 * 主界面：只放最常用的三件事 —— 总开关、四个 App 的开关、更多设置入口。
 *
 * 其余（屏蔽内容、识别强度、框架状态、日志路径）都在 [SettingsActivity] 里。
 */
class MainActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivityMainBinding

    /**
     * 每个 App 一行。
     *
     * 不能靠子节点下标去对应 App —— 组内插了分隔线之后，
     * 容器里的子节点和 App 就不再一一对应了。
     */
    private val appRows = mutableListOf<Pair<String, ItemAppBinding>>()

    /** 框架连上/断开时要刷新状态栏。 */
    private val connectionListener: (Boolean) -> Unit = { connected ->
        runCatching {
            if (connected) config = FrameworkRepository.loadOrNull() ?: config
            safeRenderPublic()
        }.onFailure { Log.w(TAG, "connection refresh failed", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            FrameworkRepository.loadOrNull()?.let { config = it }

            buildAppRows()
            bindListeners()
            safeRenderPublic()
        } catch (throwable: Throwable) {
            Log.e(TAG, "main UI failed to initialize", throwable)
            CrashDisplay.show(this, throwable)
        }
    }

    override fun onStart() {
        super.onStart()
        FrameworkRepository.addConnectionListener(connectionListener)
    }

    override fun onStop() {
        FrameworkRepository.removeConnectionListener(connectionListener)
        super.onStop()
    }

    private fun safeRenderPublic() {
        runCatching { renderConfig() }
            .onFailure { Log.e(TAG, "render failed", it) }
    }

    // ------------------------------------------------------------ 绑定

    private fun buildAppRows() {
        val container = binding.appList
        container.removeAllViews()
        appRows.clear()
        val inflater = LayoutInflater.from(this)

        for (app in TargetApp.entries) {
            val row = ItemAppBinding.inflate(inflater, container, false)
            row.appName.text = app.displayName

            // 组内分隔线：MIUI 风格，左缩进对齐文字
            if (container.childCount > 0) {
                container.addView(
                    inflater.inflate(R.layout.view_divider_inset, container, false),
                )
            }

            // 用 checked 变化回调，不用 click：
            // click 回调里读 isChecked 依赖「先 toggle 还是先回调」的实现细节，
            // 一旦框架版本或控件实现变了就会拿到旧值，表现是「怎么点都关不上」。
            row.appSwitch.setOnCheckedChangeListener { _, isChecked ->
                if (bindingFlags) return@setOnCheckedChangeListener
                commit(config.withAppEnabled(app.id, isChecked))
            }

            row.appAllow.setOnClickListener {
                config = config.toggledSessionAllow(app.id)
                commit(config)
                toast(
                    if (app.id in config.sessionAllow) "本次会话已放行 ${app.displayName}"
                    else "已恢复屏蔽 ${app.displayName}",
                )
            }

            container.addView(row.root)
            appRows.add(app.id to row)
        }
    }

    private fun bindListeners() {
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (bindingFlags) return@setOnCheckedChangeListener
            commit(config.copy(masterEnabled = isChecked))
        }
        binding.moreSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // ------------------------------------------------------------ 渲染

    override fun renderConfig() {
        bindingFlags = true
        try {
            binding.switchMaster.isChecked = config.masterEnabled

            for ((appId, row) in appRows) {
                val allowed = appId in config.sessionAllow

                row.appSwitch.isChecked = config.isAppEnabled(appId) && !allowed
                row.appSwitch.isEnabled = config.masterEnabled
                row.appAllow.text = getString(
                    if (allowed) R.string.app_restore else R.string.app_allow,
                )
                row.appAllow.isEnabled = config.masterEnabled && config.isAppEnabled(appId)
                row.root.alpha = if (config.isAppEnabled(appId)) 1f else 0.45f
            }
        } finally {
            bindingFlags = false
        }

        renderStatus()
    }

    /**
     * 状态压成一行，只有出问题时才需要用户看更多。
     *
     * 注意格式化参数要和 strings 里的占位符**一一对应**：
     * `status_ok` 是 `%1$s · API %2$d · 已授权 %3$d/%4$d`，四个参数一个都不能少，
     * 少一个 `String.format` 会直接抛 `MissingFormatArgumentException` 把界面打崩。
     */
    private fun renderStatus() {
        val connected = FrameworkRepository.connected
        val granted = FrameworkRepository.grantedPackages().toSet()
        val authorized = TargetApp.entries.count { app -> app.packages.any { it in granted } }

        binding.statusText.text = if (!connected) {
            getString(R.string.status_not_connected)
        } else {
            getString(
                R.string.status_ok,
                FrameworkRepository.frameworkName(),
                FrameworkRepository.apiVersion(),
                authorized,
                TargetApp.entries.size,
            )
        }

        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            if (connected) 0xFF4CAF50.toInt() else 0xFFE53935.toInt(),
        )
    }
}
