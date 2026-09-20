package com.cpaphone.ui.authpool

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.model.AuthCredential
import com.cpaphone.core.model.CredentialQuota
import com.cpaphone.core.model.ProviderType
import com.cpaphone.core.model.QuotaGroup
import com.cpaphone.core.model.QuotaWindow
import com.cpaphone.ui.theme.AccentError
import com.cpaphone.ui.theme.AccentPrimary
import com.cpaphone.ui.theme.AccentPurple
import com.cpaphone.ui.theme.AccentSecondary
import com.cpaphone.ui.theme.AccentWarning
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简动感美学配额监控大盘视图
 * 完美对齐 D:\Projects\CLIProxyAPI CPAMC 控制台配额管理：
 * - 凭据统计与一键全量并发刷新微动效
 * - Provider 胶囊筛选流（带徽标数量）
 * - Antigravity（Gemini/Claude分组、5h与周限额）、Codex（5h/月度限额与主动重置次数）、Claude 等多平台配额卡片
 * - 动态渐变流体进度条（根据剩余率智能变色：充沛绿/警惕黄/危险红）
 * - 未加载原地平滑加载展开与本地秒开缓存
 */
@Composable
fun QuotaManagementView(
    credentials: List<AuthCredential>
) {
    val context = LocalContext.current
    val app = CpaApplication.instance
    val scope = rememberCoroutineScope()

    // 监听本地持久化的配额快照 Flow（本地秒开）
    val quotasMap by app.credentialRepository.getAllQuotasFlow().collectAsState(initial = emptyMap())

    // 选中的 Provider 筛选器
    var selectedProviderFilter by remember { mutableStateOf<ProviderType?>(null) }

    // 正在刷新的凭据 ID 集合
    val refreshingIds = remember { mutableStateMapOf<String, Boolean>() }
    var isRefreshingAll by remember { mutableStateOf(false) }

    // 筛选出符合条件的凭据
    val displayedCredentials = remember(credentials, selectedProviderFilter) {
        if (selectedProviderFilter == null) {
            credentials
        } else {
            credentials.filter { it.provider == selectedProviderFilter }
        }
    }

    val loadedQuotasCount = remember(credentials, quotasMap) {
        credentials.count { quotasMap[it.id]?.groups?.isNotEmpty() == true }
    }

    // 刷新单张卡片
    val refreshSingle: (AuthCredential) -> Unit = { cred ->
        scope.launch {
            refreshingIds[cred.id] = true
            try {
                val secretKey = app.credentialRepository.getSecretKey(cred.id) ?: ""
                val refreshToken = app.credentialRepository.getRefreshToken(cred.id)
                val quota = app.oauthQuotaEngine.fetchQuota(
                    credential = cred,
                    accessToken = secretKey,
                    refreshToken = refreshToken,
                    onTokenRefreshed = { newAccess, newRefresh ->
                        app.credentialRepository.saveCredential(cred, newAccess)
                        newRefresh?.let { app.credentialRepository.saveRefreshToken(cred.id, it) }
                    }
                )
                app.credentialRepository.saveQuota(quota)
                Toast.makeText(context, "已刷新 ${cred.alias} 的额度", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "刷新失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                refreshingIds.remove(cred.id)
            }
        }
    }

    // 刷新全部卡片
    val refreshAll: () -> Unit = {
        scope.launch {
            isRefreshingAll = true
            try {
                credentials.forEach { cred ->
                    refreshingIds[cred.id] = true
                }
                credentials.forEach { cred ->
                    try {
                        val secretKey = app.credentialRepository.getSecretKey(cred.id) ?: ""
                        val refreshToken = app.credentialRepository.getRefreshToken(cred.id)
                        val quota = app.oauthQuotaEngine.fetchQuota(
                            credential = cred,
                            accessToken = secretKey,
                            refreshToken = refreshToken,
                            onTokenRefreshed = { newAccess, newRefresh ->
                                app.credentialRepository.saveCredential(cred, newAccess)
                                newRefresh?.let { app.credentialRepository.saveRefreshToken(cred.id, it) }
                            }
                        )
                        app.credentialRepository.saveQuota(quota)
                    } catch (_: Exception) {
                    } finally {
                        refreshingIds.remove(cred.id)
                    }
                }
                Toast.makeText(context, "全部凭据配额刷新完毕", Toast.LENGTH_SHORT).show()
            } finally {
                isRefreshingAll = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // —— 1. 顶栏总览指标与一键刷新 ——
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
                            .background(AccentSecondary, shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "配额监控",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${credentials.size} 个凭据 · $loadedQuotasCount 个已加载",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // 一键刷新按钮（带微动效）
            val infiniteTransition = rememberInfiniteTransition(label = "spinAll")
            val angleAll by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(900, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "angleAll"
            )

            Button(
                onClick = { if (!isRefreshingAll) refreshAll() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新全部",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(if (isRefreshingAll) angleAll else 0f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isRefreshingAll) "刷新中..." else "刷新全部凭据",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // —— 2. Provider 筛选胶囊条 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedProviderFilter == null,
                onClick = { selectedProviderFilter = null },
                label = { Text("全部 ${credentials.size}") },
                shape = RoundedCornerShape(10.dp)
            )

            // 按平台分组展示数量
            val providersWithCount = remember(credentials) {
                ProviderType.entries.mapNotNull { provider ->
                    val count = credentials.count { it.provider == provider }
                    if (count > 0) provider to count else null
                }
            }

            providersWithCount.forEach { (provider, count) ->
                FilterChip(
                    selected = selectedProviderFilter == provider,
                    onClick = {
                        selectedProviderFilter = if (selectedProviderFilter == provider) null else provider
                    },
                    label = { Text("${provider.displayName} $count") },
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        // —— 3. 配额卡片列表 ——
        if (displayedCredentials.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无匹配凭据",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        } else {
            displayedCredentials.forEach { cred ->
                val quota = quotasMap[cred.id]
                val isRefreshing = refreshingIds[cred.id] == true

                QuotaCardItem(
                    credential = cred,
                    quota = quota,
                    isRefreshing = isRefreshing,
                    onRefresh = { refreshSingle(cred) }
                )
            }
        }
    }
}

/**
 * 极简动感单凭据配额卡片
 */
@Composable
fun QuotaCardItem(
    credential: AuthCredential,
    quota: CredentialQuota?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 头部：Provider 药丸 + 别名 + 套餐 Badge + 刷新按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    ProviderIconBadge(credential.provider)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = credential.alias,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            quota?.planType?.let { plan ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "套餐 $plan",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            quota?.resetCredits?.let { credits ->
                                Text(
                                    text = "主动重置 $credits",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                // 旋转刷新图标
                val infiniteTransition = rememberInfiniteTransition(label = "spinSingle")
                val angle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "angle"
                )

                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(if (isRefreshing) angle else 0f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))

            // 核心配额展示区
            when {
                // 1. 正在首次刷新且无缓存
                isRefreshing && quota == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "正在连接上游查询配额...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // 2. 存在配额组
                quota != null && quota.groups.isNotEmpty() -> {
                    quota.groups.forEach { group ->
                        QuotaGroupItem(group)
                    }
                }

                // 3. 不支持或暂无数据
                quota != null && !quota.isSupported -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = quota.statusMessage ?: "当前提供商暂无官方配额接口",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                // 4. 空态提示（点击立即拉取）
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onRefresh() }
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "轻触获取剩余配额与重置窗口",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // 卡片底部时效信息
            if (quota != null && quota.updatedAt > 0) {
                val timeStr = formatPastTime(quota.updatedAt)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "更新于 $timeStr",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

/**
 * 配额逻辑分组（如 Gemini 模型 / Claude 和 GPT 模型）
 */
@Composable
fun QuotaGroupItem(group: QuotaGroup) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(
                text = group.groupName.uppercase(Locale.getDefault()),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.primary
            )
            group.description?.let { desc ->
                Text(
                    text = desc,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }

        group.windows.forEach { window ->
            QuotaWindowProgressBar(window)
        }
    }
}

/**
 * 流体动感进度条与剩余提示
 */
@Composable
fun QuotaWindowProgressBar(window: QuotaWindow) {
    val animatedProgress by animateFloatAsState(
        targetValue = window.remainingFraction.toFloat(),
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "progress"
    )

    // 智能动态色彩渐变（健康翡翠绿 -> 警戒琥珀黄 -> 告急珊瑚红）
    val progressColor by animateColorAsState(
        targetValue = when {
            window.remainingFraction > 0.5 -> AccentSecondary
            window.remainingFraction > 0.2 -> AccentWarning
            else -> AccentError
        },
        label = "color"
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = window.name,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "剩余 ${window.remainingPercent}%",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = progressColor,
                    fontFamily = FontFamily.Monospace
                )
                if (window.formattedCountdown.isNotBlank()) {
                    Text(
                        text = " · ${window.formattedCountdown}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 动感平滑胶囊进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                progressColor.copy(alpha = 0.7f),
                                progressColor
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun ProviderIconBadge(provider: ProviderType) {
    val color = when (provider) {
        ProviderType.ANTIGRAVITY -> AccentPrimary
        ProviderType.OPENAI_CODEX -> AccentSecondary
        ProviderType.CLAUDE -> AccentPurple
        ProviderType.GEMINI -> AccentWarning
        else -> MaterialTheme.colorScheme.primary
    }

    Box(
        modifier = Modifier
            .size(30.dp)
            .background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = provider.displayName.take(1),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = color
        )
    }
}

private fun formatPastTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        hours < 24 -> "${hours} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
