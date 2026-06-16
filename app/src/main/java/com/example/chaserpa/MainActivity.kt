package com.example.chaserpa

// Android 系统包：用于 Activity 生命周期中的状态保存/恢复
import android.os.Bundle
// Jetpack 组件：所有使用 Compose 的 Activity 都需要继承 ComponentActivity
import androidx.activity.ComponentActivity
// Jetpack 组件：让 Activity 可以直接用 Compose 编写界面
import androidx.activity.compose.setContent
// Jetpack 组件：开启全面屏/边缘到边缘显示
import androidx.activity.enableEdgeToEdge
// 导入本项目的配置界面
import com.example.chaserpa.ui.ConfigScreen
// 导入本项目的主题
import com.example.chaserpa.ui.theme.ChaserpaTheme

// 导入消息日志单例，用于在 App 内显示实时日志
import com.example.chaserpa.service.MessageLog

/**
 * 应用主入口 Activity。
 *
 * 在 Android 中，Activity 相当于 Java Swing 里的一个窗口（Window）。
 * MainActivity 是用户打开 App 时看到的第一个界面。
 *
 * Kotlin 语法提示：
 * - class MainActivity : ComponentActivity() 表示 MainActivity 继承自 ComponentActivity，
 *   冒号“:”等价于 Java 的 extends。
 * - override fun onCreate(...) 表示重写父类方法，等价于 Java 的 @Override protected void onCreate(...)。
 */
class MainActivity : ComponentActivity() {

    /**
     * Activity 创建时回调。
     *
     * @param savedInstanceState 系统回收 Activity 后再次创建时恢复状态的 Bundle，
     *                           相当于 Java 中 onCreate(Bundle savedInstanceState)。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 调用父类实现，必须放在最前面，等价于 super.onCreate(savedInstanceState);
        super.onCreate(savedInstanceState)

        // 初始化应用内日志系统，传入 Application/Activity 上下文
        MessageLog.init(this)

        // 开启边缘到边缘显示：让 App 内容延伸到状态栏/导航栏区域
        enableEdgeToEdge()

        // setContent { ... } 是 Compose 的入口。
        // 花括号里的内容是一个“Lambda 表达式”，作为最后一个参数传入时可以写在圆括号外面，
        // 这就是 Kotlin 的“尾随 Lambda（trailing lambda）”语法。
        setContent {
            // 应用主题，包裹整个界面。主题负责统一颜色、字体等视觉风格。
            ChaserpaTheme {
                // 配置界面 Composable 函数
                ConfigScreen()
            }
        }
    }
}
