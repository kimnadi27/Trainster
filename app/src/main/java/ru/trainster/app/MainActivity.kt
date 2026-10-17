package ru.trainster.app

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class TrainsterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapRuntime.initialize(this)
        TripNotifications.initialize(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NotificationNavigation.accept(intent)
        enableEdgeToEdge()
        setContent { TrainsterTheme { TrainsterApp() } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationNavigation.accept(intent)
    }
}

@Composable
fun TrainsterTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(primary = Color(0xFF126B62), secondary = Color(0xFF6B5A37), background = Color(0xFFF8F7F2), surface = Color(0xFFF8F7F2), surfaceContainer = Color(0xFFEDEEE6), surfaceVariant = Color(0xFFE3E8DE), onSurface = Color(0xFF202B27))
    val dark = darkColorScheme(primary = Color(0xFF85D6C5), secondary = Color(0xFFE3C28E), background = Color(0xFF14211D), surface = Color(0xFF14211D), surfaceContainer = Color(0xFF21302A))
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
