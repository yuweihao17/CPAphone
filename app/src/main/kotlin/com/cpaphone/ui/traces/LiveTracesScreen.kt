package com.cpaphone.ui.traces

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.data.local.entity.TraceLogEntity
import com.cpaphone.ui.theme.AccentError
import com.cpaphone.ui.theme.AccentSecondary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LiveTracesScreen() {
    val app = CpaApplication.instance
    val logs by app.database.traceLogDao().getRecentLogsFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "实时链路追踪",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "最近 500 条流式审计流水",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            IconButton(
                onClick = {
                    scope.launch {
                        app.database.traceLogDao().clearAll()
                    }
                }
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "清空日志")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无请求追踪记录，发起请求后将在此实时显示",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = { it.traceId }) { trace ->
                    ExpandableTraceCard(trace)
                }
            }
        }
    }
}

@Composable
fun ExpandableTraceCard(trace: TraceLogEntity) {
    var isExpanded by remember { mutableStateOf(false) }
    val isSuccess = trace.statusCode in 200..299
    val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    val timeStr = dateFormat.format(Date(trace.timestamp))

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (isSuccess) AccentSecondary else AccentError, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${trace.requestMethod} ${trace.requestedModel}",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "命中: ${trace.credentialAlias} · 耗时: ${trace.durationMs}ms · TTFT: ${trace.ttftMs}ms",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "${trace.statusCode}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isSuccess) AccentSecondary else AccentError
                        )
                        Text(
                            text = timeStr,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // 详情折叠区
            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailRow(label = "Trace ID", value = trace.traceId)
                    DetailRow(label = "请求端点", value = trace.requestPath)
                    DetailRow(label = "入站协议", value = trace.inboundProtocol)
                    DetailRow(label = "传输形态", value = if (trace.isStreaming) "SSE 流式管道" else "阻塞块式传输")
                    DetailRow(label = "重试换号次数", value = "${trace.retryCount} 次")
                    val errorMessage = trace.errorMessage
                    if (!errorMessage.isNullOrBlank()) {
                        DetailRow(label = "错误详情", value = errorMessage, isError = true)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (isError) AccentError else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
        )
    }
}
