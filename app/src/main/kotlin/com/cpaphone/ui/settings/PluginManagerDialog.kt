package com.cpaphone.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.model.PluginCapability
import com.cpaphone.core.model.PluginManifest
import com.cpaphone.core.model.PluginStatus
import com.cpaphone.engine.plugin.GuardedPluginClient
import com.cpaphone.engine.plugin.IPluginClient
import com.cpaphone.ui.theme.AccentError
import com.cpaphone.ui.theme.AccentSecondary
import com.cpaphone.ui.theme.AccentWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginManagerSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = CpaApplication.instance
    var pluginList by remember { mutableStateOf(app.nativePluginHost.getAllPlugins()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "动态扩展插件管理",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // 快捷装载官方示例插件
                FilledTonalButton(
                    onClick = {
                        val sampleManifest = PluginManifest(
                            id = "claude-reasoning-enhancer",
                            name = "Claude 思考链注入增强器",
                            version = "1.0.2",
                            description = "自动解析并增强 Claude 深度思考提示词与思维签名",
                            author = "CPAphone Official",
                            capabilities = listOf(
                                PluginCapability.REQUEST_INTERCEPTOR,
                                PluginCapability.THINKING_APPLIER
                            )
                        )
                        val sampleClient = GuardedPluginClient(
                            manifest = sampleManifest,
                            delegateCall = { method, requestJson ->
                                "{\"ok\":true,\"result\":{\"status\":\"processed\"}}"
                            }
                        )
                        app.nativePluginHost.registerPlugin(sampleManifest, sampleClient)
                        pluginList = app.nativePluginHost.getAllPlugins()
                        Toast.makeText(context, "已载入示例插件: ${sampleManifest.name}", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "导入", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导入示例插件", fontSize = 12.sp)
                }
            }

            if (pluginList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无加载的原生插件。支持放入 .so 动态共享库或点击右上角导入官方增强插件。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(pluginList, key = { it.manifest.id }) { client ->
                        PluginItemCard(
                            client = client,
                            onToggle = { enabled ->
                                app.nativePluginHost.setPluginEnabled(client.manifest.id, enabled)
                                pluginList = app.nativePluginHost.getAllPlugins()
                            },
                            onResetFuse = {
                                app.nativePluginHost.resetPluginFuse(client.manifest.id)
                                pluginList = app.nativePluginHost.getAllPlugins()
                                Toast.makeText(context, "已重置插件熔断状态", Toast.LENGTH_SHORT).show()
                            },
                            onUnload = {
                                app.nativePluginHost.unloadPlugin(client.manifest.id)
                                pluginList = app.nativePluginHost.getAllPlugins()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
fun PluginItemCard(
    client: IPluginClient,
    onToggle: (Boolean) -> Unit,
    onResetFuse: () -> Unit,
    onUnload: () -> Unit
) {
    val manifest = client.manifest
    val status = client.status

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = when (status) {
                                    PluginStatus.ACTIVE -> AccentSecondary
                                    PluginStatus.FUSED_CRASHED -> AccentError
                                    else -> AccentWarning
                                },
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(text = manifest.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            text = "v${manifest.version} · ${manifest.author.ifBlank { "未知作者" }}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status == PluginStatus.FUSED_CRASHED) {
                        TextButton(onClick = onResetFuse) {
                            Text("重置熔断", color = AccentError, fontSize = 11.sp)
                        }
                    } else {
                        Switch(
                            checked = status == PluginStatus.ACTIVE,
                            onCheckedChange = onToggle
                        )
                    }
                    IconButton(onClick = onUnload) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "卸载",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (manifest.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = manifest.description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            // 插件能力徽标芯片
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                manifest.capabilities.forEach { cap ->
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = cap.name,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
