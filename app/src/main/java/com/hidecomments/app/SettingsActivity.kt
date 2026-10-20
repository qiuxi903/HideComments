package com.hidecomments.app

import android.os.Bundle
import android.util.Log
import android.view.View
import com.hidecomments.app.databinding.ActivitySettingsBinding
import com.hidecomments.core.Strength
import com.hidecomments.core.TargetApp

/**
 * 二级设置页：屏蔽内容、识别强度、高级选项、框架状态、日志路径。
 *
 * 主界面只留最常用的开关，这里放需要细调的东西。
 */
class SettingsActivity : BaseSettingsActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private var pathsVisible = false

    private val connectionListener: (Boolean) -> Unit = { connected ->
        runCatching {
            if (connected) config = FrameworkRepository.loadOrNull() ?: config
            renderSafely()
        }.onFailure { Log.w(TAG, "connection refresh failed", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySettingsBinding.inflate(layoutInflater)
            setContentView(binding.root)

            binding.toolbar.setNavigationOnClickListener { finish() }

            FrameworkRepository.loadOrNull()?.let { config = it }

            bindListeners()
            renderSafely()
        } catch (throwable: Throwable) {
            Log.e(TAG, "settings page failed to initialize", throwable)
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

    private fun renderSafely() {
        runCatching { renderConfig() }
            .onFailure { Log.e(TAG, "render failed", it) }
    }

    private fun bindListeners() {
        binding.switchEntry.setOnCheckedChangeListener { _, checked ->
            if (bindingFlags) return@setOnCheckedChangeListener
            commit(config.copy(hideEntry = checked))
        }
        binding.switchPanel.setOnCheckedChangeListener { _, checked ->
            if (bindingFlags) return@setOnCheckedChangeListener
            commit(config.copy(hidePanel = checked))
        }
        binding.switchNavigation.setOnCheckedChangeListener { _, checked ->
            if (bindingFlags) return@setOnCheckedChangeListener
            commit(config.copy(blockNavigation = checked))
        }
        binding.switchVerbose.setOnCheckedChangeListener { _, checked ->
            if (bindingFlags) return@setOnCheckedChangeListener
            commit(config.copy(verboseLog = checked))
        }
        binding.switchDump.setOnCheckedChangeListener { _, checked ->
            if (bindingFlags) return@setOnCheckedChangeListener
            commit(config.copy(dumpViewTree = checked))
            if (checked) {
                toast("进入目标 App 的页面后，View 树会写到该 App 的 files/hidecomments 目录")
            }
        }
        binding.strengthGroup.setOnCheckedChangeListener { _, checkedId ->
            if (bindingFlags) return@setOnCheckedChangeListener
            val strength = when (checkedId) {
                R.id.strength_gentle -> Strength.GENTLE
                R.id.strength_aggressive -> Strength.AGGRESSIVE
                else -> Strength.BALANCED
            }
            commit(config.copy(strength = strength))
        }
        binding.pathsToggle.setOnClickListener {
            pathsVisible = !pathsVisible
            binding.pathsText.visibility = if (pathsVisible) View.VISIBLE else View.GONE
            binding.pathsToggle.setText(
                if (pathsVisible) R.string.paths_hide else R.string.paths_show,
            )
        }
    }

    override fun renderConfig() {
        bindingFlags = true
        try {
            binding.switchEntry.isChecked = config.hideEntry
            binding.switchPanel.isChecked = config.hidePanel
            binding.switchNavigation.isChecked = config.blockNavigation
            binding.switchVerbose.isChecked = config.verboseLog
            binding.switchDump.isChecked = config.dumpViewTree

            binding.strengthGroup.check(
                when (config.strength) {
                    Strength.GENTLE -> R.id.strength_gentle
                    Strength.AGGRESSIVE -> R.id.strength_aggressive
                    Strength.BALANCED -> R.id.strength_balanced
                },
            )
            binding.strengthDesc.text = config.strength.description
        } finally {
            bindingFlags = false
        }

        renderFramework()
        renderPaths()
    }

    private fun renderFramework() {
        val connected = FrameworkRepository.connected
        val granted = FrameworkRepository.grantedPackages().toSet()
        val authorized = TargetApp.entries.filter { app -> app.packages.any { it in granted } }
        val missing = TargetApp.entries.filter { app -> app.packages.none { it in granted } }

        binding.frameworkText.text = buildString {
            append("框架      : ")
            append(if (connected) FrameworkRepository.frameworkName() else "未连接")
            append('\n')
            append("API 版本  : ")
            append(if (connected) FrameworkRepository.apiVersion().toString() else "—")
            append('\n')
            append("已授权    : ")
            append(authorized.size)
            append('/')
            append(TargetApp.entries.size)
            append('\n')
            if (missing.isNotEmpty()) {
                append("未授权    : ")
                append(missing.joinToString("、") { it.displayName })
                append('\n')
            }
            if (!connected) {
                append('\n')
                append("请在 LSPosed / Vector 管理器里勾选本模块并启用作用域，")
                append("然后重新打开这个页面。")
            }
        }
    }

    private fun renderPaths() {
        binding.pathsText.text = buildString {
            append("目录里有两个文件：\n")
            append("  module.log      运行日志\n")
            append("  viewtree-*.txt  View 树\n\n")
            for (app in TargetApp.entries) {
                append(app.displayName)
                append('\n')
                for (pkg in app.packages) {
                    append("  /sdcard/Android/data/")
                    append(pkg)
                    append("/files/hidecomments/")
                    append('\n')
                }
            }
        }
    }
}
