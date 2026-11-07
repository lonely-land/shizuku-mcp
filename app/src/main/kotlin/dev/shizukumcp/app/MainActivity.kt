package dev.shizukumcp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.shizukumcp.app.ui.HomeRoute
import dev.shizukumcp.app.ui.ShizukuMcpTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShizukuMcpTheme {
                HomeRoute(model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshShizuku()
    }
}
