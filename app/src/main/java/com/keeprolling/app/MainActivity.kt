package com.keeprolling.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.keeprolling.app.ui.MainScreen
import com.keeprolling.app.viewmodel.SimulatorViewModel

class MainActivity : ComponentActivity() {

    /**
     * ViewModel **在 Activity 这一层取**，而不是让 `MainScreen()` 自己 `viewModel()`：
     *
     * 音频需要在"切到后台 / 回到前台"时暂停与续播（[SimulatorViewModel.sound]），
     * 那是 Activity 生命周期事件，Composable 里拿不到。同一份 ViewModel 传下去，
     * 界面与生命周期钩子操作的就是**同一个**音频引擎 —— 不会出现两个 SoundPool 同时响。
     *
     * `by viewModels()` 与 `viewModel()` 用的是同一个 ViewModelStore，
     * 所以传下去的这个实例和以前 `MainScreen()` 内部拿到的完全一样（屏幕旋转也不会重建）。
     */
    private val vm: SimulatorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveFullscreen()
        setContent {
            KeepRollingTheme {
                MainScreen(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 回前台：BGM **从暂停点续播**（不重头），并重新申请音频焦点
        vm.sound.onForeground()
    }

    override fun onStop() {
        // 切后台：全部暂停。放在 onStop（而不是 onPause）—— 响铃 / 授权弹窗这类
        // 短暂的失去焦点不会打断音乐，只有真的看不见了才停。
        vm.sound.onBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 用户从屏幕边缘上滑唤出系统栏、或从后台切回来之后，重新进入沉浸模式
        if (hasFocus) enterImmersiveFullscreen()
    }

    /**
     * 真全沉浸：状态栏 + 底部导航栏一起隐藏，画面铺满整块屏幕。
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE：需要时从屏幕边缘上滑可临时唤出系统栏。
     */
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/** 轻量主题：暖白底 + 紫色主色，跟随系统深浅色 */
@Composable
fun KeepRollingTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFFAFA9EC),
            surface = Color(0xFF2C2C2A),
            surfaceVariant = Color(0xFF444441),
            onSurface = Color(0xFFF1EFE8),
            onSurfaceVariant = Color(0xFFD3D1C7),
            background = Color(0xFF1C1B19),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF534AB7),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEEEDFE),
            onSurface = Color(0xFF26215C),
            onSurfaceVariant = Color(0xFF5F5E5A),
            background = Color(0xFFFAF8F3), // 温润白
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
