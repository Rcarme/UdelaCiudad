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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.models.MetricForecast
import com.tgmon.app.data.models.MetricHistory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalTextApi::class)
@Composable
fun LineChart(
    title: String,
    metrics: List<MetricHistory>,
    valueExtractor: (MetricHistory) -> Double,
    unit: String = "%",
    color: Color = MaterialTheme.colorScheme.primary,
    maxValue: Double = 100.0,
    forecast: MetricForecast? = null
) {
    // Solo se dibuja la proyección si el servidor devolvió al menos 2 puntos y hay historial
    val fc = forecast?.takeIf { it.points.size >= 2 && metrics.isNotEmpty() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (fc != null) 280.dp else 240.dp)
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
                val histMin = values.minOrNull() ?: 0.0
                val histMax = values.maxOrNull() ?: 0.0
                // Con pronóstico, el eje debe abarcar también la banda de confianza proyectada
                val minValue = if (fc != null) min(histMin, fc.points.minOf { it.lower }) else histMin
                val effectiveMax = if (fc != null) {
                    max(max(maxValue, histMax), fc.points.maxOf { it.upper })
                } else {
                    max(maxValue, histMax)
                }

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
                                textColor = Color.White,
                                forecast = fc
                            )
                        }
                )

                Text(
                    text = "Min: ${String.format("%.1f", histMin)}$unit  |  Max: ${String.format("%.1f", histMax)}$unit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (fc != null) {
                    val first = fc.points.first()
                    val last = fc.points.last()
                    val horizonMin = ((last.timestamp - first.timestamp) / 60000L).toInt()
                    val arrow = when (fc.trend) {
                        "rising" -> "↗"
                        "falling" -> "↘"
                        else -> "→"
                    }
                    val eta = fc.minutesToThreshold?.let { m ->
                        if (m <= 0.0) "  |  Threshold ${fc.threshold.roundToInt()}% reached"
                        else "  |  ${fc.threshold.roundToInt()}% in ~${m.roundToInt()} min"
                    } ?: ""
                    Text(
                        text = "Forecast +${horizonMin} min: ${String.format("%.1f", last.value)}$unit $arrow " +
                            "${String.format("%+.2f", fc.slopePerMin)}$unit/min  |  R² ${String.format("%.2f", fc.rSquared)}$eta",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fc.minutesToThreshold != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** Punto del gráfico usado para el tooltip (historial real o proyección). */
private data class ChartPoint(
    val offset: Offset,
    val value: Double,
    val timestamp: Long,
    val isForecast: Boolean = false,
    val lower: Double = 0.0,
    val upper: Double = 0.0
)

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
    textColor: Color,
    forecast: MetricForecast? = null
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

    // Conversión valor -> coordenada Y
    fun yOf(value: Double): Float {
        val normalized = (value - minValue) / valueRange
        return height - padding - (normalized * (height - 2 * padding)).toFloat()
    }

    // Con pronóstico el eje X pasa a ser temporal (historial + futuro); sin pronóstico queda por índice
    val tMin = metrics.first().timestamp
    val tMax = if (forecast != null) {
        maxOf(forecast.points.last().timestamp, metrics.last().timestamp)
    } else {
        metrics.last().timestamp
    }
    fun xOf(index: Int, timestamp: Long): Float {
        return if (forecast != null && tMax > tMin) {
            padding + ((timestamp - tMin).toFloat() / (tMax - tMin).toFloat()) * (width - 2 * padding)
        } else {
            (index / (values.size - 1).toFloat()) * (width - 2 * padding) + padding
        }
    }

    // Grid line
    val midValue = (minValue + maxValue) / 2
    val midY = yOf(midValue)
    drawLine(
        color = color.copy(alpha = 0.2f),
        start = Offset(0f, midY),
        end = Offset(width, midY),
        strokeWidth = 2f
    )

    // Calculate points
    val points = values.mapIndexed { index, value ->
        Offset(xOf(index, metrics[index].timestamp), yOf(value))
    }

    // --- Proyección (regresión lineal del servicio Python) ---
    val forecastChartPoints = ArrayList<ChartPoint>()
    if (forecast != null) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), 0f)

        // Línea de umbral crítico (p. ej. 85%) si cae dentro del rango visible
        if (forecast.threshold >= minValue && forecast.threshold <= maxValue) {
            val ty = yOf(forecast.threshold)
            drawLine(
                color = Color(0xFFE53935).copy(alpha = 0.7f),
                start = Offset(0f, ty),
                end = Offset(width, ty),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            )
        }

        val fcOffsets = forecast.points.mapIndexed { i, p ->
            ChartPoint(
                offset = Offset(xOf(i, p.timestamp), yOf(p.value)),
                value = p.value,
                timestamp = p.timestamp,
                isForecast = true,
                lower = p.lower,
                upper = p.upper
            )
        }
        forecastChartPoints.addAll(fcOffsets)

        // Banda de confianza 95% (polígono: límite superior ida, inferior vuelta)
        val band = Path()
        forecast.points.forEachIndexed { i, p ->
            val x = xOf(i, p.timestamp)
            if (i == 0) band.moveTo(x, yOf(p.upper)) else band.lineTo(x, yOf(p.upper))
        }
        for (i in forecast.points.indices.reversed()) {
            val p = forecast.points[i]
            band.lineTo(xOf(i, p.timestamp), yOf(p.lower))
        }
        band.close()
        drawPath(path = band, color = color.copy(alpha = 0.18f))

        // Marca vertical "ahora"
        val nowX = fcOffsets.first().offset.x
        drawLine(
            color = Color.White.copy(alpha = 0.35f),
            start = Offset(nowX, 0f),
            end = Offset(nowX, height),
            strokeWidth = 2f,
            pathEffect = dash
        )

        // Línea punteada de la proyección
        for (i in 0 until fcOffsets.size - 1) {
            drawLine(
                color = color,
                start = fcOffsets[i].offset,
                end = fcOffsets[i + 1].offset,
                strokeWidth = 4f,
                pathEffect = dash
            )
        }
        // Puntos huecos para diferenciarlos del historial
        for (fp in fcOffsets.drop(1)) {
            drawCircle(
                color = color,
                radius = 5f,
                center = fp.offset,
                style = Stroke(width = 3f)
            )
        }

        // Etiqueta
        drawText(
            textMeasurer = textMeasurer,
            text = "forecast",
            style = TextStyle(color = textColor.copy(alpha = 0.6f), fontSize = 9.sp),
            topLeft = Offset(min(nowX + 6f, width - 60f), height - padding + 4f)
        )
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

    // Interaction Line and Tooltip (historial + proyección)
    if (touchX >= 0) {
        val chartPoints = points.mapIndexed { i, offset ->
            ChartPoint(offset, values[i], metrics[i].timestamp)
        } + forecastChartPoints
        val closestIndex = chartPoints.indexOfMinBy { abs(it.offset.x - touchX) }
        if (closestIndex != -1) {
            val selected = chartPoints[closestIndex]
            val closestPoint = selected.offset

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
            val tooltipText = if (selected.isForecast) {
                val timeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(selected.timestamp))
                "~${String.format("%.1f", selected.value)}$unit (forecast)\n" +
                    "${String.format("%.1f", selected.lower)}–${String.format("%.1f", selected.upper)}$unit\n$timeString"
            } else {
                val timeString = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(selected.timestamp))
                "${String.format("%.1f", selected.value)}$unit \n$timeString"
            }

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
