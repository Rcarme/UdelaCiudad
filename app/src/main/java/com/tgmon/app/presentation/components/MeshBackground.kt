package com.tgmon.app.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.tgmon.app.ui.theme.TgMonBackgroundBrush

@Composable
fun MeshBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().background(TgMonBackgroundBrush)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF6C63FF).copy(alpha = 0.35f), Color.Transparent),
                    center = Offset(size.width * 0.1f, size.height * 0.1f),
                    radius = size.width * 0.7f
                ),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.1f, size.height * 0.1f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF00FF88).copy(alpha = 0.25f), Color.Transparent),
                    center = Offset(size.width * 0.9f, size.height * 0.9f),
                    radius = size.width * 0.6f
                ),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.9f, size.height * 0.9f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFFD400FF).copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(size.width * 0.8f, size.height * 0.4f),
                    radius = size.width * 0.5f
                ),
                radius = size.width * 0.5f,
                center = Offset(size.width * 0.8f, size.height * 0.4f)
            )
        }
        content()
    }
}
