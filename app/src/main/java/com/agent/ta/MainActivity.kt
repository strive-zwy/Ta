package com.agent.ta

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.agent.ta.ui.navigation.TaNavHost
import com.agent.ta.ui.theme.TaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TaTheme {
                val navController = rememberNavController()
                TaNavHost(navController = navController)
            }
        }
    }
}
