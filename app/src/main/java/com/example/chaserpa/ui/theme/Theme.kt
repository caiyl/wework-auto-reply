package com.example.chaserpa.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 深色主题颜色方案。
 *
 * Kotlin 语法提示：
 * - private val 表示“私有只读变量”，只在本文件内可见，类似 Java 的 private static final。
 * - darkColorScheme(...) 是 Compose Material3 提供的工厂函数，用于创建深色配色方案。
 */
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,       // 主色：按钮、开关激活状态等
    secondary = PurpleGrey80, // 辅助色：次要按钮、标签等
    tertiary = Pink80         // 第三色：用于强调或区分层级
)

/**
 * 浅色主题颜色方案。
 */
private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* 其他可覆盖的默认颜色，当前使用系统默认，暂时注释掉：
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/**
 * 应用根主题 Composable。
 *
 * 在 Jetpack Compose 中：
 * - 界面由一个个 @Composable 函数组合而成，类似 React 的组件。
 * - Composable 函数只能在其他 Composable 函数内部调用。
 * - 本函数接收一个子界面内容 content，并为其配置统一的颜色、字体主题。
 *
 * Kotlin 语法提示：
 * - @Composable 是注解，标记这个函数可被 Compose 编译器识别为 UI 组件。
 * - fun ChaserpaTheme(...) 是一个普通函数，但因为有 @Composable 所以可以返回 UI。
 * - darkTheme: Boolean = isSystemInDarkTheme() 是“带默认值的参数”。
 *   调用时可以不传，默认跟随系统深色模式设置。
 * - content: @Composable () -> Unit 是一个 Composable Lambda 参数，
 *   表示传入一段 UI 代码。Unit 类似 Java 的 void。
 */
@Composable
fun ChaserpaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // 动态取色：Android 12+ 支持从系统壁纸提取颜色
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    // when 是 Kotlin 的多分支表达式，类似 Java 的 switch，但更强大，可以直接返回值。
    val colorScheme = when {
        // 如果开启动态取色且系统版本 >= Android 12（API 31，对应 Build.VERSION_CODES.S）
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // LocalContext.current 获取当前 Composable 所在的 Context（通常是 Activity）
            val context = LocalContext.current
            // 根据当前是深色还是浅色，使用系统动态颜色方案
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        // 用户选择了深色模式
        darkTheme -> DarkColorScheme

        // 默认使用浅色模式
        else -> LightColorScheme
    }

    // MaterialTheme 是 Compose Material3 提供的主题容器，
    // 把 colorScheme（颜色方案）和 typography（字体样式）应用到 content 内部的全部 UI。
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
