package com.cpaphone.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.data.repository.RunningMode
import com.cpaphone.engine.service.CpaProxyService
import com.cpaphone.ui.theme.AccentSecondary
import com.cpaphone.ui.theme.AccentWarning

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val app = CpaApplication.instance
    val config by app.appConfigRepository.configFlow.collectAsState(initial = null)
    var isRunning by remember { mutableStateOf(app.localProxyServer.isServerRunning()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "运行仪表盘",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 核心运行状态卡片
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        if (isRunning) AccentSecondary else AccentWarning,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRunning) "服务监听中" else "服务已就绪 (停止)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (config?.runningMode == RunningMode.LOCAL_PROXY) "当前形态: 本地轻量级代理" else "当前形态: 远程中控管理端",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }

                    FilledTonalIconButton(
                        onClick = {
                            if (isRunning) {
                                toggleService(context, false)
                                isRunning = false
                            } else {
                                val port = config?.localPort ?: 8317
                                app.localProxyServer.start(port, config?.allowLanAccess ?: false)
                                toggleService(context, true)
                                isRunning = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isRunning) "停止" else "启动"
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                // 地址与端口信息（可点击一键复制）
                val baseUrl = "http://${if (config?.allowLanAccess == true) "0.0.0.0" else "127.0.0.1"}:${config?.localPort ?: 8317}/v1"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            copyToClipboard(context, "CPAphone Base URL", baseUrl)
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "客户端接入 Base URL (点击复制):",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = baseUrl,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 极简指标卡片矩阵
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "网络接入模式",
                value = if (config?.allowLanAccess == true) "局域网广播 (0.0.0.0)" else "本机回环 (127.0.0.1)"
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "活跃会话保持",
                value = "${app.coordinator.cooldownManager.totalCoolingCount()} 冷却中"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "Safe Mode 防御",
                value = if (config?.safeModeEnabled == true) "已激活 (拦截默认Key)" else "未激活"
            )
            MetricTile(
                modifier = Modifier.weight(1f),
                label = "指纹伪装披风",
                value = if (config?.enableCloaking == true) "已开启 (官方CLI)" else "原始直通"
            )
        }
    }
}

@Composable
fun MetricTile(modifier: Modifier = Modifier, label: String, value: String) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "已复制到剪贴板: $text", Toast.LENGTH_SHORT).show()
}

private fun toggleService(context: Context, start: Boolean) {
    val intent = Intent(context, CpaProxyService::class.java).apply {
        action = if (start) CpaProxyService.ACTION_START else CpaProxyService.ACTION_STOP
    }
    if (start) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } else {
        context.startService(intent)
    }
}
