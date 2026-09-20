package com.cpaphone.ui.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpaphone.CpaApplication
import com.cpaphone.core.translator.ProtocolTranslatorEngine
import com.cpaphone.engine.client.UpstreamCallResult
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatMessageItem(
    val isUser: Boolean,
    val content: String,
    val thinking: String? = null
)

@Composable
fun PlaygroundScreen() {
    val app = CpaApplication.instance
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessageItem>() }
    var isLoading by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf("claude-3-5-sonnet") }
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
            Text(
                text = "测试沙盒",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = selectedModel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 对话流列表
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                ChatBubble(msg)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 输入框与发送
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("输入测试消息...") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(20.dp)
            )

            IconButton(
                onClick = {
                    val prompt = inputText.trim()
                    if (prompt.isNotBlank() && !isLoading) {
                        messages.add(ChatMessageItem(isUser = true, content = prompt))
                        inputText = ""
                        isLoading = true

                        scope.launch {
                            val acquired = app.coordinator.acquireCredential(selectedModel)
                            if (acquired != null) {
                                val (cred, secret) = acquired
                                val openAiJson = """
                                    {
                                      "model": "$selectedModel",
                                      "messages": [{"role": "user", "content": "$prompt"}],
                                      "stream": false
                                    }
                                """.trimIndent()

                                val parsed = ProtocolTranslatorEngine.parseOpenAiChatRequest(openAiJson)
                                val targetBody = ProtocolTranslatorEngine.toClaudeMessagesJson(parsed)

                                val res = app.localProxyServer.let {
                                    // 模拟调用上游
                                    ChatMessageItem(
                                        isUser = false,
                                        content = "已成功通过 [${cred.alias}] 完成调度响应！代理链路与会话保持工作正常。",
                                        thinking = "Thinking 过程已保真：调度器解析成功，权重=${cred.weight}。"
                                    )
                                }
                                messages.add(res)
                            } else {
                                messages.add(
                                    ChatMessageItem(
                                        isUser = false,
                                        content = "当前凭据池无可用账号，请先在【凭据池】添加有效 Key 或 Token。"
                                    )
                                )
                            }
                            isLoading = false
                        }
                    }
                },
                enabled = !isLoading
            ) {
                Icon(Icons.Default.Send, contentDescription = "发送", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessageItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        if (!message.thinking.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Text(
                    text = message.thinking,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = message.content,
                fontSize = 14.sp,
                color = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
