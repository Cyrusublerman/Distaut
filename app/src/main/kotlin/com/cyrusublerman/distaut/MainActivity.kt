package com.cyrusublerman.distaut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cyrusublerman.distaut.editor.EditorRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DistautApp() }
    }
}

@Composable
private fun DistautApp() {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color(0xFFF5F2EA),
            surface = Color(0xFFF5F2EA),
            surfaceVariant = Color(0xFFE6E2D8),
            onBackground = Color(0xFF171717),
            onSurface = Color(0xFF171717),
            primary = Color(0xFF171717),
            onPrimary = Color(0xFFF5F2EA),
        ),
    ) {
        EditorRoute()
    }
}
