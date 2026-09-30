package com.tgmon.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.models.MetricHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

@OptIn(ExperimentalTextApi::class)
@Composable
fun LineChart(
    title: String,
    metrics: List<MetricHistory>,
    valueExtractor: (MetricHistory) -> Double,
    unit: String = "%",
    color: Color = MaterialTheme.colorScheme.primary,
    maxValue: Double = 100.0
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (metrics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No data available",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                val values = metrics.map { valueExtractor(it) }
                val minValue = values.minOrNull() ?: 0.0
                val effectiveMax = max(maxValue, values.maxOrNull() ?: maxValue)

                var touchX by remember { mutableStateOf(-1f) }
                val textMeasurer = rememberTextMeasurer()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(top = 16.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { touchX = it.x },
                                onDragEnd = { touchX = -1f },
                                onDragCancel = { touchX = -1f }
                            ) { change, _ ->
                                touchX = change.position.x
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    touchX = offset.x
                                    tryAwaitRelease()
                                    touchX = -1f
                                }
                            )
                        }
                        .drawBehind {
                            drawInteractiveChart(
                                metrics = metrics,
                                values = values,
                                minValue = minValue,
                                maxValue = effectiveMax,
                                color = color,
                                touchX = touchX,
                                unit = unit,
                                textMeasurer = textMeasurer,
                                textColor = Color.White
                            )
                        }
                )

                Text(
                    text = "Min: ${String.format("%.1f", minValue)}$unit  |  Max: ${String.format("%.1f", values.maxOrNull() ?: 0.0)}$unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
private fun DrawScope.drawInteractiveChart(
    metrics: List<MetricHistory>,
    values: List<Double>,
    minValue: Double,
    maxValue: Double,
    color: Color,
    touchX: Float,
    unit: String,
    textMeasurer: TextMeasurer,
    textColor: Color
) {
    if (values.isEmpty()) return

    val width = size.width
    val height = size.height
    val padding = 24f

    val valueRange = maxValue - minValue
    if (values.size == 1 || valueRange == 0.0) {
        val y = height / 2f
        drawLine(
            color = color,
            start = Offset(padding, y),
            end = Offset(width - padding, y),
            strokeWidth = 4f
        )
        drawCircle(
            color = color,
            radius = 6f,
            center = Offset(width / 2f, y)
        )
        return
    }

    // Grid line
    val midValue = (minValue + maxValue) / 2
    val midY = height - padding - ((midValue - minValue) / valueRange * (height - 2 * padding)).toFloat()
    drawLine(
        color = color.copy(alpha = 0.2f),
        start = Offset(0f, midY),
        end = Offset(width, midY),
        strokeWidth = 2f
    )

    // Calculate points
    val points = values.mapIndexed { index, value ->
        val x = (index / (values.size - 1).toFloat()) * (width - 2 * padding) + padding
        val normalizedValue = (value - minValue) / valueRange
        val y = height - padding - (normalizedValue * (height - 2 * padding)).toFloat()
        Offset(x, y)
    }

    // Draw lines
    for (i in 0 until points.size - 1) {
        drawLine(
            color = color,
            start = points[i],
            end = points[i + 1],
            strokeWidth = 4f
        )
    }

    // Draw points
    for (point in points) {
        drawCircle(
            color = color,
            radius = 4f,
            center = point
        )
    }

    // Interaction Line and Tooltip
    if (touchX >= 0) {
        val closestIndex = points.indexOfMinBy { abs(it.x - touchX) }
        if (closestIndex != -1) {
            val closestPoint = points[closestIndex]
            val actualValue = values[closestIndex]
            val timestamp = metrics[closestIndex].timestamp

            // Vertical line
            drawLine(
                color = Color.White.copy(alpha = 0.5f),
                start = Offset(closestPoint.x, 0f),
                end = Offset(closestPoint.x, height),
                strokeWidth = 2f
            )

            // Highlight point
            drawCircle(
                color = Color.White,
                radius = 8f,
                center = closestPoint
            )

            // Tooltip text
            val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
            val tooltipText = "${String.format("%.1f", actualValue)}$unit \n$timeString"

            val textLayoutResult = textMeasurer.measure(
                text = tooltipText,
                style = TextStyle(color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            )

            val tooltipWidth = textLayoutResult.size.width + 16f
            val tooltipHeight = textLayoutResult.size.height + 16f

            var tooltipX = closestPoint.x - tooltipWidth / 2
            if (tooltipX < 0f) tooltipX = 0f
            if (tooltipX + tooltipWidth > width) tooltipX = width - tooltipWidth

            val tooltipY = 0f

            drawRoundRect(
                color = Color(0xCC000000),
                topLeft = Offset(tooltipX, tooltipY),
                size = Size(tooltipWidth, tooltipHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
            )

            drawText(
                textMeasurer = textMeasurer,
                text = tooltipText,
                style = TextStyle(color = textColor, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                topLeft = Offset(tooltipX + 8f, tooltipY + 8f)
            )
        }
    }
}

fun <T> List<T>.indexOfMinBy(selector: (T) -> Float): Int {
    if (isEmpty()) return -1
    var minIndex = 0
    var minValue = selector(this[0])
    for (i in 1 until size) {
        val value = selector(this[i])
        if (value < minValue) {
            minValue = value
            minIndex = i
        }
    }
    return minIndex
}

@Composable
fun ProgressChart(
    title: String,
    value: Double,
    unit: String = "%",
    color: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // same progress box ...
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.small
                    )
                    .drawBehind {
                        drawProgressBar(value = value, color = color)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${String.format("%.1f", value)}$unit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun DrawScope.drawProgressBar(value: Double, color: Color) {
    val width = size.width
    val height = size.height
    val padding = 4f

    drawRect(
        color = color.copy(alpha = 0.2f),
        topLeft = Offset(padding, padding),
        size = androidx.compose.ui.geometry.Size(width - 2 * padding, height - 2 * padding)
    )

    val progressWidth = ((value.coerceIn(0.0, 100.0) / 100.0) * (width - 2 * padding)).toFloat()
    drawRect(
        color = color,
        topLeft = Offset(padding, padding),
        size = androidx.compose.ui.geometry.Size(progressWidth, height - 2 * padding)
    )
}
