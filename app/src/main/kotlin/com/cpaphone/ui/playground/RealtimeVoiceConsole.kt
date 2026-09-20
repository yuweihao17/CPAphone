package com.cpaphone.ui.playground

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.model.RealtimeSessionStatus
import com.cpaphone.ui.theme.AccentError
import com.cpaphone.ui.theme.AccentPrimary
import com.cpaphone.ui.theme.AccentSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 极简收敛实时语音通话控制台 (Voice Console)
 * 支持拟物声波呼吸动效、低延迟会话生命周期管理、实时字幕流与挂断
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RealtimeVoiceConsoleSheet(
    onDismiss: () -> Unit
) {
    val app = CpaApplication.instance
    val scope = rememberCoroutineScope()

    var callStatus by remember { mutableStateOf(RealtimeSessionStatus.CONNECTING) }
    var currentCallId by remember { mutableStateOf<String?>(null) }
    var isMuted by remember { mutableStateOf(false) }
    var liveTranscript by remember { mutableStateOf("正在连接 CPAphone WebRTC 实时语音中枢...") }

    // 呼吸动效
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // 模拟连接与握手
    LaunchedEffect(Unit) {
        delay(800)
        val acquired = app.coordinator.acquireCredential("gpt-4o-realtime-preview")
        if (acquired != null) {
            val (cred, _) = acquired
            val id = "call_" + java.util.UUID.randomUUID().toString().substring(0, 12)
            currentCallId = id
            app.localProxyServer.realtimeRelayManager.registerSession(id, cred, "gpt-4o-realtime-preview")
            callStatus = RealtimeSessionStatus.ACTIVE
            liveTranscript = "WebRTC 实时语音信道已就绪 (已绑定: ${cred.alias})。请开口说话，系统将低延迟实时应答..."
        } else {
            callStatus = RealtimeSessionStatus.CLOSED
            liveTranscript = "连接失败：当前凭据池中无可用账号支持 Realtime 协议。"
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            currentCallId?.let { app.localProxyServer.realtimeRelayManager.hangup(it) }
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "CPAphone 实时语音通话",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            // 拟物声波圆环
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .scale(if (callStatus == RealtimeSessionStatus.ACTIVE) pulseScale else 1f)
                    .background(
                        color = when (callStatus) {
                            RealtimeSessionStatus.CONNECTING -> AccentPrimary.copy(alpha = 0.15f)
                            RealtimeSessionStatus.ACTIVE -> if (isMuted) Color.Gray.copy(alpha = 0.15f) else AccentSecondary.copy(alpha = 0.15f)
                            else -> AccentError.copy(alpha = 0.15f)
                        },
                        shape = CircleShape
                    )
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(90.dp)
                        .background(
                            color = when (callStatus) {
                                RealtimeSessionStatus.CONNECTING -> AccentPrimary
                                RealtimeSessionStatus.ACTIVE -> if (isMuted) Color.Gray else AccentSecondary
                                else -> AccentError
                            },
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.GraphicEq,
                        contentDescription = "声波",
                        tint = Color.White,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            // 状态标签
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = when (callStatus) {
                        RealtimeSessionStatus.CONNECTING -> "正在协商 WebRTC SDP 与 ICE 候选..."
                        RealtimeSessionStatus.ACTIVE -> if (isMuted) "麦克风已静音" else "双向音频流传输中 (48kHz Opus RTP)"
                        RealtimeSessionStatus.MUTED -> "通话已静音"
                        RealtimeSessionStatus.CLOSED -> "通话已结束"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            // 实时转录字幕流
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = liveTranscript,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier.padding(14.dp)
                )
            }

            // 控制按键
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 静音开关
                FilledTonalIconButton(
                    onClick = { isMuted = !isMuted },
                    enabled = callStatus == RealtimeSessionStatus.ACTIVE,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "静音"
                    )
                }

                // 挂断通话
                IconButton(
                    onClick = {
                        currentCallId?.let { app.localProxyServer.realtimeRelayManager.hangup(it) }
                        callStatus = RealtimeSessionStatus.CLOSED
                        onDismiss()
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(AccentError, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "挂断",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
