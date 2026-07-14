package com.cyrusublerman.distaut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cyrusublerman.distaut.editor.EditorRoute

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
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
            surfaceVariant = Color(0xFFD8D3C8),
            onBackground = Color(0xFF171717),
            onSurface = Color(0xFF171717),
            primary = Color(0xFF171717),
            onPrimary = Color(0xFFF5F2EA),
            secondary = Color(0xFF171717),
            onSecondary = Color(0xFFF5F2EA),
            outline = Color(0xFF171717),
            error = Color(0xFF171717),
            onError = Color(0xFFF5F2EA),
        ),
        typography = distautTypography(),
    ) {
        EditorRoute()
    }
}

private fun distautTypography(): Typography {
    val mono = FontFamily.Monospace
    return Typography(
        bodyLarge = TextStyle(
            fontFamily = mono,
            fontSize = 13.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = mono,
            fontSize = 12.sp,
        ),
        bodySmall = TextStyle(
            fontFamily = mono,
            fontSize = 10.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
        labelMedium = TextStyle(
            fontFamily = mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        ),
        titleMedium = TextStyle(
            fontFamily = mono,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}
