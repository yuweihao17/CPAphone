package com.cpaphone.ui.authpool

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
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.ui.theme.AccentError
import com.cpaphone.ui.theme.AccentSecondary
import com.cpaphone.ui.theme.AccentWarning
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthPoolScreen() {
    val context = LocalContext.current
    val app = CpaApplication.instance
    val credentials by app.credentialRepository.getAllCredentialsFlow().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加凭据")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "凭据池治理",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text = "共 ${credentials.size} 个凭据",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (credentials.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无凭据，点击右下角添加账号或 API Key",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(credentials, key = { it.id }) { credential ->
                        CredentialItemCard(
                            credential = credential,
                            onResetCooldown = {
                                app.coordinator.cooldownManager.resetCooldown(credential.id)
                                scope.launch {
                                    app.credentialRepository.updateGlobalCooldown(credential.id, 0L)
                                }
                                Toast.makeText(context, "已重置 [${credential.alias}] 的冷却状态", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                scope.launch {
                                    app.credentialRepository.deleteCredential(credential.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddCredentialDialog(
            existingAliases = credentials.map { it.alias },
            onDismiss = { showAddDialog = false },
            onConfirm = { alias, provider, authType, secret, weight, customBaseUrl ->
                scope.launch {
                    val newCred = AuthCredential(
                        id = UUID.randomUUID().toString().substring(0, 8),
                        alias = alias,
                        provider = provider,
                        authType = authType,
                        weight = weight,
                        customBaseUrl = customBaseUrl.ifBlank { null }
                    )
                    app.credentialRepository.saveCredential(newCred, secret)
                    showAddDialog = false
                }
            }
        )
    }
}

@Composable
fun CredentialItemCard(
    credential: AuthCredential,
    onResetCooldown: () -> Unit,
    onDelete: () -> Unit
) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (credential.status) {
                        CredentialStatus.ACTIVE -> AccentSecondary
                        CredentialStatus.COOLDOWN -> AccentWarning
                        else -> AccentError
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = credential.alias, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${credential.provider.displayName} · ${credential.authType.name} · 权重: ${credential.weight}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Row {
                    IconButton(onClick = onResetCooldown) {
                        Icon(Icons.Default.Refresh, contentDescription = "重置冷却", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }

            // 单模型局部冷却标签
            val coolingModels = credential.modelCooldowns.filter { it.value > System.currentTimeMillis() }
            if (coolingModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    coolingModels.forEach { (model, _) ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AccentWarning.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "冷却中: $model",
                                fontSize = 10.sp,
                                color = AccentWarning,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 各服务商的别名前缀，用于自动编号 */
private fun providerAliasPrefix(p: ProviderType): String = when (p) {
    ProviderType.CLAUDE -> "Claude"
    ProviderType.OPENAI_CODEX -> "Codex"
    ProviderType.GEMINI -> "Gemini"
    ProviderType.ANTIGRAVITY -> "Antigravity"
    ProviderType.VERTEX_AI -> "Vertex"
    ProviderType.XAI -> "Grok"
    ProviderType.KIMI -> "Kimi"
    ProviderType.OPENAI_COMPATIBLE -> "Compat"
}

/** 各认证形态的展示标签 */
private fun authTypeLabel(t: AuthType): String = when (t) {
    AuthType.API_KEY -> "API Key"
    AuthType.OAUTH -> "OAuth"
    AuthType.SERVICE_ACCOUNT -> "服务账号"
}

/** 各服务商与认证形态组合下的凭据格式占位提示，对齐 CLIProxyAPI 凭据矩阵 */
private fun secretPlaceholder(p: ProviderType, t: AuthType): String = when (t) {
    AuthType.OAUTH -> "粘贴 OAuth Access Token"
    AuthType.SERVICE_ACCOUNT -> "GCP 服务账号 JSON 内容"
    AuthType.API_KEY -> when (p) {
        ProviderType.CLAUDE -> "sk-ant-api03-..."
        ProviderType.OPENAI_CODEX -> "sk-proj-..."
        ProviderType.GEMINI, ProviderType.ANTIGRAVITY -> "AIza..."
        ProviderType.VERTEX_AI -> "Vertex 推荐使用服务账号认证"
        ProviderType.XAI -> "xai-..."
        ProviderType.KIMI -> "sk-..."
        ProviderType.OPENAI_COMPATIBLE -> "sk-..."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCredentialDialog(
    existingAliases: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (alias: String, provider: ProviderType, authType: AuthType, secret: String, weight: Int, customBaseUrl: String) -> Unit
) {
    var selectedProvider by remember { mutableStateOf(ProviderType.CLAUDE) }
    var selectedAuthType by remember { mutableStateOf(AuthType.API_KEY) }
    var alias by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var weightText by remember { mutableStateOf("1") }
    var customBaseUrl by remember { mutableStateOf("") }
    var providerExpanded by remember { mutableStateOf(false) }

    // 切换服务商时自动预填别名编号（别名未被手动编辑或已被采纳才跟随刷新）
    LaunchedEffect(selectedProvider) {
        if (alias.isBlank() || providerAliasPrefix(selectedProvider).let { prefix ->
                ProviderType.entries.any { p -> p != selectedProvider && alias.startsWith(providerAliasPrefix(p)) }
            }
        ) {
            val serial = existingAliases.count { it.startsWith(providerAliasPrefix(selectedProvider)) } + 1
            alias = "${providerAliasPrefix(selectedProvider)}-$serial"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加凭据") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 服务商选择（预设 8 大提供商矩阵）
                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedProvider.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("服务商") },
                        supportingText = { Text(selectedProvider.identifier, fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        ProviderType.entries.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.displayName, fontSize = 14.sp)
                                        Text(
                                            p.identifier,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                },
                                onClick = {
                                    selectedProvider = p
                                    providerExpanded = false
                                }
                            )
                        }
                    }
                }

                // 认证形态三段选择
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    AuthType.entries.forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = selectedAuthType == type,
                            onClick = { selectedAuthType = type },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = AuthType.entries.size)
                        ) {
                            Text(authTypeLabel(type), fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }

                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = { Text("别名") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("凭据") },
                    placeholder = { Text(secretPlaceholder(selectedProvider, selectedAuthType), fontSize = 12.sp) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = it.filter { char -> char.isDigit() } },
                    label = { Text("调度权重 (1~100)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = customBaseUrl,
                    onValueChange = { customBaseUrl = it },
                    label = { Text("自定义 Base URL (选填)") },
                    placeholder = {
                        Text(selectedProvider.defaultBaseUrl, fontSize = 12.sp, maxLines = 1)
                    },
                    supportingText = { Text("留空使用官方端点", fontSize = 10.sp) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (alias.isNotBlank() && secret.isNotBlank()) {
                        val weight = weightText.toIntOrNull() ?: 1
                        onConfirm(alias, selectedProvider, selectedAuthType, secret, weight, customBaseUrl)
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
