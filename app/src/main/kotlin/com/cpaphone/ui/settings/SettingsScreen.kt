package com.cpaphone.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.model.RoutingStrategyType
import com.cpaphone.data.repository.RunningMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val app = CpaApplication.instance
    val config by app.appConfigRepository.configFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "系统与网络设置",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // 运行模式切换
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "运行形态", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = config?.runningMode == RunningMode.LOCAL_PROXY,
                        onClick = {
                            scope.launch { app.appConfigRepository.updateRunningMode(RunningMode.LOCAL_PROXY) }
                        },
                        label = { Text("本地独立代理") }
                    )
                    FilterChip(
                        selected = config?.runningMode == RunningMode.REMOTE_MANAGEMENT,
                        onClick = {
                            scope.launch { app.appConfigRepository.updateRunningMode(RunningMode.REMOTE_MANAGEMENT) }
                        },
                        label = { Text("远程中控管理") }
                    )
                }
            }
        }

        // 调度策略选择
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "负载均衡算法", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = config?.routingStrategy == RoutingStrategyType.WEIGHTED_ROUND_ROBIN,
                        onClick = {
                            scope.launch {
                                app.appConfigRepository.updateRoutingStrategy(RoutingStrategyType.WEIGHTED_ROUND_ROBIN)
                                app.coordinator.updateStrategy(RoutingStrategyType.WEIGHTED_ROUND_ROBIN)
                            }
                        },
                        label = { Text("平滑加权") }
                    )
                    FilterChip(
                        selected = config?.routingStrategy == RoutingStrategyType.ROUND_ROBIN,
                        onClick = {
                            scope.launch {
                                app.appConfigRepository.updateRoutingStrategy(RoutingStrategyType.ROUND_ROBIN)
                                app.coordinator.updateStrategy(RoutingStrategyType.ROUND_ROBIN)
                            }
                        },
                        label = { Text("轮询") }
                    )
                    FilterChip(
                        selected = config?.routingStrategy == RoutingStrategyType.FILL_FIRST,
                        onClick = {
                            scope.launch {
                                app.appConfigRepository.updateRoutingStrategy(RoutingStrategyType.FILL_FIRST)
                                app.coordinator.updateStrategy(RoutingStrategyType.FILL_FIRST)
                            }
                        },
                        label = { Text("深度优先") }
                    )
                }
            }
        }

        // 开关配置
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "允许局域网设备接入", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(text = "绑定 0.0.0.0 (其它设备可连接)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = config?.allowLanAccess == true,
                        onCheckedChange = { allow ->
                            scope.launch { app.appConfigRepository.updateAllowLanAccess(allow) }
                        }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "安全防御模式 (Safe Mode)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(text = "自动拦截默认 Key 防滥用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = config?.safeModeEnabled == true,
                        onCheckedChange = { enabled ->
                            scope.launch { app.appConfigRepository.updateSafeMode(enabled) }
                        }
                    )
                }
            }
        }
    }
}
