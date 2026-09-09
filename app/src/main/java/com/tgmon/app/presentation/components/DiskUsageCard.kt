package com.tgmon.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.models.DiskInfo

@Composable
fun DiskUsageCard(
    usedGb: Double,
    freeGb: Double,
    totalGb: Double?,
    disks: List<DiskInfo>? = null,
    modifier: Modifier = Modifier
) {
    val displayDisks = if (!disks.isNullOrEmpty()) {
        disks.filter { it.totalGb > 0.0 }
    } else {
        emptyList()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x22FFFFFF)),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = Color(0xFFAAAAAA),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (displayDisks.size > 1) "STORAGE (${displayDisks.size} DRIVES)" else "STORAGE",
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            if (displayDisks.size > 1) {
                displayDisks.forEachIndexed { index, disk ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0x1FFFFFFF),
                            thickness = 1.dp
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    } else {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    SingleDiskSection(
                        label = disk.device.ifBlank { disk.mountpoint },
                        fstype = disk.fstype,
                        usedGb = disk.usedGb,
                        freeGb = disk.freeGb,
                        totalGb = disk.totalGb,
                        percent = disk.percent
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                val singleDisk = displayDisks.firstOrNull()
                val effectiveUsed = singleDisk?.usedGb ?: usedGb
                val effectiveFree = singleDisk?.freeGb ?: freeGb
                val effectiveTotal = singleDisk?.totalGb ?: (totalGb ?: (usedGb + freeGb))
                val effectivePercent = singleDisk?.percent ?: if (effectiveTotal > 0) (effectiveUsed / effectiveTotal) * 100.0 else 0.0
                SingleDiskSection(
                    label = singleDisk?.device?.ifBlank { null } ?: singleDisk?.mountpoint,
                    fstype = singleDisk?.fstype ?: "",
                    usedGb = effectiveUsed,
                    freeGb = effectiveFree,
                    totalGb = effectiveTotal,
                    percent = effectivePercent
                )
            }
        }
    }
}

@Composable
private fun SingleDiskSection(
    label: String?,
    fstype: String,
    usedGb: Double,
    freeGb: Double,
    totalGb: Double,
    percent: Double
) {
    val progress = (if (totalGb > 0) (usedGb / totalGb).toFloat() else (percent / 100.0).toFloat()).coerceIn(0f, 1f)

    val barColor = when {
        progress > 0.9f -> Color(0xFFFF4444) // Red
        progress > 0.7f -> Color(0xFFFFBB00) // Orange/Yellow
        else -> Color(0xFF00FF88) // Cyber Green
    }

    if (!label.isNullOrBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (fstype.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "[$fstype]",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                }
            }
            Text(
                text = "${String.format("%.1f", progress * 100)}%",
                color = barColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = "${String.format("%.1f", progress * 100)}%",
                color = barColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
    }

    LinearProgressIndicator(
        progress = progress,
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = barColor,
        trackColor = Color(0x22FFFFFF),
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
    )

    Spacer(modifier = Modifier.height(10.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Used", color = Color(0xFFAAAAAA), fontSize = 10.sp)
            Text("${String.format("%.1f", usedGb)} GB", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Free", color = Color(0xFFAAAAAA), fontSize = 10.sp)
            Text("${String.format("%.1f", freeGb)} GB", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Total", color = Color(0xFFAAAAAA), fontSize = 10.sp)
            Text("${String.format("%.1f", totalGb)} GB", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

