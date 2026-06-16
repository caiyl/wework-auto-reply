package com.example.chaserpa.ui.theme

// Compose 中的颜色类，对应 Android 的 Color 包装
import androidx.compose.ui.graphics.Color

/**
 * 本文件定义了应用使用的静态颜色常量。
 *
 * Kotlin 语法提示：
 * - val 表示只读变量（类似 Java 的 final 变量），定义后不能被重新赋值。
 * - Color(0xFFD0BCFF) 是调用 Color 的构造函数，传入一个 32 位 ARGB 颜色值。
 *   0xFF 是透明度（完全不透明），后面 D0BCFF 是 RGB 颜色。
 *   等价于 Java：Color Purple80 = new Color(0xFFD0BCFF);
 */

// 深色主题下的主色（浅紫色）
val Purple80 = Color(0xFFD0BCFF)

// 深色主题下的辅助色（灰紫色）
val PurpleGrey80 = Color(0xFFCCC2DC)

// 深色主题下的第三色（浅粉色）
val Pink80 = Color(0xFFEFB8C8)

// 浅色主题下的主色（深紫色）
val Purple40 = Color(0xFF6650a4)

// 浅色主题下的辅助色（深灰紫色）
val PurpleGrey40 = Color(0xFF625b71)

// 浅色主题下的第三色（深粉色）
val Pink40 = Color(0xFF7D5260)
