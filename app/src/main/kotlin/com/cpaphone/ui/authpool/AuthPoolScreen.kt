package com.cpaphone.ui.authpool

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.AuthType
import com.cpaphone.core.model.CredentialStatus
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.session.OAuthFlowStatus
import com.cpaphone.core.session.OAuthSession
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
    var manualPasteProvider by remember { mutableStateOf<ProviderType?>(null) }

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

            // —— OAuth 快捷登录区块（对齐 CLIProxyAPI 管理控制台） ——
            val oauthSessions by app.oauthLoginManager.sessionsFlow.collectAsState()
            val consumedResults = remember { mutableStateOf(mutableSetOf<String>()) }
            LaunchedEffect(oauthSessions) {
                oauthSessions.forEach { session ->
                    val consumed = when (session.status) {
                        OAuthFlowStatus.OK -> consumedResults.value.add(session.state)
                        OAuthFlowStatus.ERROR -> consumedResults.value.add(session.state)
                        else -> false
                    }
                    if (consumed) {
                        val message = when (session.status) {
                            OAuthFlowStatus.OK -> "登录成功：${session.resultAlias ?: session.provider.displayName}"
                            else -> "登录失败：${session.errorMessage ?: "未知错误"}"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }
            }

            Text(
                text = "OAuth 快捷登录",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))
            OAUTH_LOGIN_CARDS.forEach { card ->
                val related = oauthSessions.filter { it.provider == card.provider }
                val activeSession = related.firstOrNull { it.status == OAuthFlowStatus.WAIT }
                val lastResult = related.firstOrNull { it.status != OAuthFlowStatus.WAIT }
                OAuthLoginCardItem(
                    card = card,
                    activeSession = activeSession,
                    lastResult = lastResult,
                    onStart = {
                        scope.launch {
                            try {
                                val result = app.oauthLoginManager.startLogin(card.provider)
                                result.authorizeUrl?.let { url ->
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    Toast.makeText(context, "已打开授权页面，请在浏览器中完成登录", Toast.LENGTH_SHORT).show()
                                }
                                // 设备码流在卡片内展示 user_code，无需跳转
                            } catch (e: Exception) {
                                Toast.makeText(context, "发起登录失败：${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onCancel = { app.oauthLoginManager.cancelLogin(activeSession?.state ?: "") },
                    onManualPaste = { manualPasteProvider = card.provider }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (credentials.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无凭据，点击上方登录或右下角手动添加",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
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

    // 手动提交回调链接（浏览器被代理拦截导致 localhost 回跳超时时的兜底）
    manualPasteProvider?.let {
        ManualCallbackDialog(
            onDismiss = { manualPasteProvider = null },
            onSubmit = { raw ->
                manualPasteProvider = null
                scope.launch {
                    val accepted = try {
                        app.oauthLoginManager.handleManualCallback(raw)
                    } catch (e: Exception) {
                        Toast.makeText(context, "提交失败：${e.message}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    Toast.makeText(
                        context,
                        if (accepted) "已提交回调链接，正在完成登录" else "链接无效或登录会话已结束",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

/** OAuth 快捷登录卡片展示数据（对齐 CPAMC 控制台的 OAuth 登录列表） */
private data class OAuthLoginCard(
    val provider: ProviderType,
    val title: String,
    val subtitle: String,
    val accent: Color
)

private val OAUTH_LOGIN_CARDS = listOf(
    OAuthLoginCard(
        ProviderType.CLAUDE, "Anthropic OAuth",
        "通过 OAuth 流程登录 Claude 服务，自动获取并保存认证信息。",
        Color(0xFFD97757)
    ),
    OAuthLoginCard(
        ProviderType.OPENAI_CODEX, "Codex OAuth",
        "通过 OAuth 流程登录 Codex 服务，自动获取并保存认证信息。",
        Color(0xFF10A37F)
    ),
    OAuthLoginCard(
        ProviderType.ANTIGRAVITY, "Antigravity OAuth",
        "使用 Google 账号授权 Antigravity 服务，自动获取并保存认证信息。",
        Color(0xFF4285F4)
    ),
    OAuthLoginCard(
        ProviderType.KIMI, "Kimi OAuth",
        "通过设备码登录 Kimi 服务，在验证页输入设备码完成授权。",
        Color(0xFF16B3A6)
    ),
    OAuthLoginCard(
        ProviderType.XAI, "xAI Grok OAuth",
        "通过设备码登录 xAI 服务，在验证页输入设备码完成授权。",
        Color(0xFF9AA0A6)
    )
)

/**
 * 单个 OAuth 服务商登录卡片：空闲显示"开始登录"，进行中显示进度与设备码辅助操作
 */
@Composable
private fun OAuthLoginCardItem(
    card: OAuthLoginCard,
    activeSession: OAuthSession?,
    lastResult: OAuthSession?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onManualPaste: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(card.accent, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = card.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }

                if (activeSession == null) {
                    FilledTonalButton(onClick = onStart, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text("开始登录", fontSize = 12.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("取消", fontSize = 12.sp, color = AccentError)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = card.subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )

            // 最近一次登录结果内联展示：错误根因（含服务商 error_description）不再被 Toast 截断
            if (activeSession == null && lastResult != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (lastResult.status) {
                        OAuthFlowStatus.ERROR -> AccentError.copy(alpha = 0.10f)
                        else -> AccentSecondary.copy(alpha = 0.10f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = when (lastResult.status) {
                            OAuthFlowStatus.ERROR -> "登录失败：${lastResult.errorMessage ?: "未知错误"}"
                            else -> buildString {
                                append("登录成功：${lastResult.resultAlias ?: "凭据已保存"}")
                                lastResult.resultEmail?.let { append("（$it）") }
                            }
                        },
                        fontSize = 11.sp,
                        color = when (lastResult.status) {
                            OAuthFlowStatus.ERROR -> AccentError
                            else -> AccentSecondary
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // 浏览器授权流等待中：代理拦截 localhost 回跳时的自救指引与手动兜底入口
            if (activeSession != null && activeSession.authorizeUrl != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "若浏览器提示无法访问 localhost：可将地址栏中的 localhost 改为 127.0.0.1 后重新加载；" +
                            "或复制该页完整网址，点下方按钮提交完成登录",
                    fontSize = 11.sp,
                    color = AccentWarning,
                    lineHeight = 15.sp
                )
                TextButton(onClick = onManualPaste, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("手动粘贴回调链接", fontSize = 12.sp)
                }
            }

            // 设备码流进行中：展示 user_code 与验证页辅助操作
            val userCode = activeSession?.userCode
            if (userCode != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "设备码：$userCode",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(userCode))
                            Toast.makeText(context, "设备码已复制", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("复制", fontSize = 12.sp)
                    }
                    activeSession.verificationUrl?.let { url ->
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("打开验证页", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 手动提交回调链接对话框：粘贴浏览器地址栏中的回跳 URL（含 code/state）
 */
@Composable
private fun ManualCallbackDialog(
    onDismiss: () -> Unit,
    onSubmit: (rawUrl: String) -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var rawUrl by remember { mutableStateOf(clipboard.getText()?.toString()?.takeIf { it.contains("code=") } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴回调链接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "在浏览器无法打开 localhost 页面时：长按该错误页的地址栏复制完整网址，粘贴到此处提交。网址须包含 code= 参数。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                OutlinedTextField(
                    value = rawUrl,
                    onValueChange = { rawUrl = it },
                    label = { Text("回调链接") },
                    placeholder = { Text("http://localhost:51121/oauth-callback?code=...", fontSize = 11.sp) },
                    minLines = 2
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (rawUrl.isNotBlank()) onSubmit(rawUrl) },
                enabled = rawUrl.isNotBlank()
            ) {
                Text("提交")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
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
