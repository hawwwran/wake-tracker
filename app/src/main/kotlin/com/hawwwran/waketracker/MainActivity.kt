package com.hawwwran.waketracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hawwwran.waketracker.ui.AppRoot
import com.hawwwran.waketracker.ui.WakeTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WakeTrackerTheme { AppRoot() }
        }
    }
}
