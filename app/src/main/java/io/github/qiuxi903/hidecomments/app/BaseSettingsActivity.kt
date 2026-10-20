package io.github.qiuxi903.hidecomments.app

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.qiuxi903.hidecomments.core.HideConfig

/**
 * 设置界面的公共底座。
 *
 * 把「配置怎么读、怎么存、存失败了怎么办」收敛到一处，两个界面共用，
 * 避免同样的坑在两个页面各踩一遍。
 */
abstract class BaseSettingsActivity : AppCompatActivity() {

    protected var config: HideConfig = HideConfig()

    /**
     * 程序性回写开关状态时置位，防止触发监听器造成回环。
     *
     * 开关一律用 `setOnCheckedChangeListener` 而不是 `setOnClickListener`：
     * 前者直接把**新的** checked 值传进来，不依赖「先翻转还是先回调」的顺序。
     */
    protected var bindingFlags = false

    private var frameworkWasConnected = false

    override fun onResume() {
        super.onResume()
        val connected = FrameworkRepository.connected
        // 框架是异步绑上的：刚连上时之前读到的是默认值，这时重新读一次。
        // 除此之外不重读 —— 本界面是配置的唯一写者，内存里的值才是最新的，
        // 每次 onResume 都重读会把刚改的值覆盖回旧值。
        if (connected && !frameworkWasConnected) {
            config = FrameworkRepository.load()
            safeRender()
        }
        frameworkWasConnected = connected
    }

    /**
     * 保存配置并刷新界面。
     *
     * 整条路径都兜住：写配置的失败、界面刷新的失败，都不该把 App 弄崩 ——
     * 设置界面崩掉是最难排查的问题（用户手机上既没有 logcat 也贴不出堆栈）。
     */
    protected fun commit(newConfig: HideConfig) {
        config = newConfig
        try {
            if (!FrameworkRepository.save(config)) {
                toast(getString(R.string.save_failed))
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "save config failed", throwable)
            showError("保存失败", throwable)
        }
        safeRender()
    }

    private fun safeRender() {
        try {
            renderConfig()
        } catch (throwable: Throwable) {
            Log.e(TAG, "render failed", throwable)
            showError("界面刷新失败", throwable)
        }
    }

    /** 把错误直接显示出来，截图就能看到原因。 */
    protected fun showError(title: String, throwable: Throwable) {
        runCatching {
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(throwable.stackTraceToString().take(1800))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    protected fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** 界面刷新，由子类实现。 */
    protected abstract fun renderConfig()

    protected companion object {
        const val TAG = "HideComments"
    }
}
