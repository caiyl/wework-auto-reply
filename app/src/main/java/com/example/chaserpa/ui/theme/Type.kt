package com.example.chaserpa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 应用全局字体样式配置。
 *
 * Compose 的 Typography 类相当于 Material Design 的字体规范，
 * 定义了 bodyLarge、titleLarge、labelSmall 等场景中文字的默认样式。
 *
 * Kotlin 语法提示：
 * - val Typography = Typography(...) 这里左边是变量名，右边是构造函数。
 *   变量名和类名相同在 Kotlin 中允许，编译器能根据上下文区分。
 * - TextStyle(...) 是数据类构造函数，用于描述一段文字的字体、字号、行高等。
 * - 16.sp 中的 .sp 是 Kotlin 的扩展属性，把数字转换为 Compose 的“缩放无关像素（Scalable Pixel）”。
 *   类似 Java 中你可能写 new TextUnit(16, TextUnitType.Sp)。
 */

// 初始的一组 Material 字体样式，目前只覆盖了 bodyLarge，其他可按需扩展
val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,  // 使用系统默认字体
        fontWeight = FontWeight.Normal,   // 正常字重（不加粗）
        fontSize = 16.sp,                 // 字号 16sp
        lineHeight = 24.sp,               // 行高 24sp
        letterSpacing = 0.5.sp            // 字间距 0.5sp
    )
    /* 其他可覆盖的默认字体样式，示例：
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)
