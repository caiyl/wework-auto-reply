package com.example.chaserpa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.chaserpa.ui.ConfigScreen
import com.example.chaserpa.ui.theme.ChaserpaTheme

import com.example.chaserpa.service.MessageLog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageLog.init(this)
        enableEdgeToEdge()
        setContent {
            ChaserpaTheme {
                ConfigScreen()
            }
        }
    }
}
