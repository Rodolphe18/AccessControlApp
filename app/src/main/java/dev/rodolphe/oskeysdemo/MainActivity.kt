package dev.rodolphe.oskeysdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.rodolphe.oskeysdemo.core.designsystem.theme.OskeysTheme
import dev.rodolphe.oskeysdemo.navigation.OskeysNavHost

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OskeysTheme {
                OskeysNavHost()
            }
        }
    }
}
