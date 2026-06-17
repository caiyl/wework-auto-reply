package com.example.chaserpa.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 本文件定义了应用使用的静态颜色常量。
 *
 * Kotlin 语法提示：
 * - val 表示只读变量（类似 Java 的 final 变量），定义后不能被重新赋值。
 * - Color(0xFFD0BCFF) 是调用 Color 的构造函数，传入一个 32 位 ARGB 颜色值。
 *   0xFF 是透明度（完全不透明），后面 D0BCFF 是 RGB 颜色。
 */

// ==================== 基础主题色 ====================
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

// ==================== 语义颜色 ====================
// 成功/已启用（绿色）
val SuccessGreen = Color(0xFF4CAF50)
// 错误/未启用（红色）
val ErrorRed = Color(0xFFE53935)
// 警告/注意（琥珀色）
val WarningAmber = Color(0xFFFFA000)
// 信息/提示（蓝色）
val InfoBlue = Color(0xFF2196F3)
// 浅灰背景，用于卡片底色或分隔
val SurfaceVariantLight = Color(0xFFF5F5F5)
