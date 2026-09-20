package com.cpaphone.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.model.RoutingStrategyType
import com.cpaphone.data.repository.RunningMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = CpaApplication.instance
    val config by app.appConfigRepository.configFlow.collectAsState(initial = null)
    val discoveredNodes by app.nsdDiscoveryManager.discoveredNodes.collectAsState()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var remoteHostInput by remember(config?.remoteHostUrl) { mutableStateOf(config?.remoteHostUrl ?: "http://192.168.1.100:8317") }
    var remoteSecretInput by remember(config?.remoteSecretKey) { mutableStateOf(config?.remoteSecretKey ?: "") }
    var isTestingRemote by remember { mutableStateOf(false) }
    var showPluginManager by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
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
                Text(text = "负载均衡调度算法", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
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
                        label = { Text("普通轮询") }
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

        // 安全与防御选项
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
                        Text(text = "绑定 0.0.0.0 (局域网内其他设备可连接)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = config?.allowLanAccess == true,
                        onCheckedChange = { allow ->
                            scope.launch { app.appConfigRepository.updateAllowLanAccess(allow) }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "安全防御模式 (Safe Mode)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(text = "自动阻断官方测试 Key 访问防滥用", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = config?.safeModeEnabled == true,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                app.appConfigRepository.updateSafeMode(enabled)
                                app.localProxyServer.setSafeMode(enabled)
                            }
                        }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = "客户端指纹伪装 (请求披风)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text(text = "伪装官方 CLI User-Agent 与版本头", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(
                        checked = config?.enableCloaking == true,
                        onCheckedChange = { enabled ->
                            scope.launch { app.appConfigRepository.updateCloaking(enabled) }
                        }
                    )
                }
            }
        }

        // 远程 CLIProxyAPI 节点中控连接配置
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text = "远程节点中控连接", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                // 局域网发现节点一键填入
                if (discoveredNodes.isNotEmpty()) {
                    Text(
                        text = "点击下方同网段已发现节点一键填入:",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        discoveredNodes.take(3).forEach { node ->
                            SuggestionChip(
                                onClick = {
                                    remoteHostInput = node.baseUrl
                                    Toast.makeText(context, "已填入节点: ${node.name}", Toast.LENGTH_SHORT).show()
                                },
                                label = { Text(node.name.take(16), fontSize = 11.sp) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = remoteHostInput,
                    onValueChange = { remoteHostInput = it },
                    label = { Text("远程实例 Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = remoteSecretInput,
                    onValueChange = { remoteSecretInput = it },
                    label = { Text("远程管理密钥 (secret-key)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            isTestingRemote = true
                            app.remoteClient.updateConnection(remoteHostInput, remoteSecretInput)
                            scope.launch {
                                val res = app.remoteClient.checkHealth()
                                if (res.isSuccess) {
                                    Toast.makeText(context, "远程节点连通正常！", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "连接失败: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                                isTestingRemote = false
                            }
                        },
                        enabled = !isTestingRemote
                    ) {
                        Text(if (isTestingRemote) "测试中..." else "测试连接")
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                app.appConfigRepository.updateRemoteConnection(remoteHostInput, remoteSecretInput)
                                app.remoteClient.updateConnection(remoteHostInput, remoteSecretInput)
                                Toast.makeText(context, "远程配置已保存", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("保存配置")
                    }
                }
            }
        }

        // 动态插件中心入口卡片
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "动态扩展插件 (C-ABI NDK)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = "加载与管理 .so 原生插件与自定义 Provider", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }

                FilledTonalButton(onClick = { showPluginManager = true }) {
                    Text("管理插件")
                }
            }
        }
    }

    if (showPluginManager) {
        PluginManagerSheet(onDismiss = { showPluginManager = false })
    }
}
