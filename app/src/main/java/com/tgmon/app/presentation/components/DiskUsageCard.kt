package com.tgmon.app.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tgmon.app.data.models.DiskInfo
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DiskUsageCard(
    usedGb: Double,
    freeGb: Double,
    totalGb: Double?,
    disks: List<DiskInfo>? = null,
    modifier: Modifier = Modifier
) {
    val displayDisks = remember(disks, usedGb, freeGb, totalGb) {
        val valid = disks?.filter { it.totalGb > 0.0 }
        if (!valid.isNullOrEmpty()) {
            valid
        } else {
            val effTotal = totalGb ?: (usedGb + freeGb)
            val effPercent = if (effTotal > 0) (usedGb / effTotal) * 100.0 else 0.0
            listOf(
                DiskInfo(
                    name = "System",
                    device = "System",
                    mountpoint = "/",
                    fstype = "",
                    totalGb = effTotal,
                    usedGb = usedGb,
                    freeGb = freeGb,
                    percent = effPercent
                )
            )
        }
    }

    val pagerState = rememberPagerState(pageCount = { displayDisks.size })
    val coroutineScope = rememberCoroutineScope()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0x22FFFFFF)),
        border = BorderStroke(1.dp, Color(0x33FFFFFF)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
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
                        text = if (displayDisks.size > 1) {
                            "STORAGE (${pagerState.currentPage + 1}/${displayDisks.size})"
                        } else {
                            "STORAGE"
                        },
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }

                if (displayDisks.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (pagerState.currentPage > 0) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage > 0,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Previous disk",
                                tint = if (pagerState.currentPage > 0) Color.White else Color(0x33FFFFFF),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Indicator dots
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            repeat(displayDisks.size) { index ->
                                val isSelected = pagerState.currentPage == index
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 6.dp else 4.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color(0xFF00FF88) else Color(0x44FFFFFF))
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                if (pagerState.currentPage < displayDisks.size - 1) {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                }
                            },
                            enabled = pagerState.currentPage < displayDisks.size - 1,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Next disk",
                                tint = if (pagerState.currentPage < displayDisks.size - 1) Color.White else Color(0x33FFFFFF),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                val disk = displayDisks[page]
                SingleDiskSection(
                    label = disk.displayName,
                    fstype = disk.fstype,
                    usedGb = disk.usedGb,
                    freeGb = disk.freeGb,
                    totalGb = disk.totalGb,
                    percent = disk.percent
                )
            }
        }
    }
}

@Composable
private fun SingleDiskSection(
    label: String,
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

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${String.format(Locale.US, "%.1f", progress * 100)}%",
            color = barColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
        color = barColor,
        trackColor = Color(0x22FFFFFF),
        strokeCap = StrokeCap.Round
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("Used", color = Color(0xFFAAAAAA), fontSize = 10.sp)
            Text("${String.format(Locale.US, "%.1f", usedGb)} GB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Free", color = Color(0xFFAAAAAA), fontSize = 10.sp)
            Text("${String.format(Locale.US, "%.1f", freeGb)} GB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Total", color = Color(0xFFAAAAAA), fontSize = 10.sp)
            Text("${String.format(Locale.US, "%.1f", totalGb)} GB", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
