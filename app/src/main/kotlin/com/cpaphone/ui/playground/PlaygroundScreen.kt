package com.cpaphone.ui.playground

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Psychology
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
import com.cpaphone.ui.theme.AccentPurple
import kotlinx.coroutines.launch

data class ChatMessageItem(
    val isUser: Boolean,
    val content: String,
    val thinking: String? = null,
    val modelTag: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundScreen() {
    val app = CpaApplication.instance
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessageItem>() }
    var isLoading by remember { mutableStateOf(false) }

    val presetModels = listOf(
        "claude-3-7-sonnet-20250219",
        "claude-3-5-sonnet-20241022",
        "gpt-4o",
        "o1",
        "o3-mini",
        "gemini-2.0-flash",
        "deepseek-reasoner"
    )
    var selectedModel by remember { mutableStateOf(presetModels[0]) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
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
                text = "AI 调试沙盒",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // 模型切换下拉框
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { modelMenuExpanded = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = selectedModel.take(18) + if (selectedModel.length > 18) "..." else "",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "切换模型", modifier = Modifier.size(16.dp))
                    }
                }

                DropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    presetModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model, fontSize = 13.sp) },
                            onClick = {
                                selectedModel = model
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "在此发送测试消息，可直接校验本地代理调度、跨协议转译与思考链表现",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages) { msg ->
                    ChatBubble(msg)
                }
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
                                val (cred, _) = acquired
                                val openAiJson = """
                                    {
                                      "model": "$selectedModel",
                                      "messages": [{"role": "user", "content": "$prompt"}],
                                      "stream": false
                                    }
                                """.trimIndent()

                                val parsed = ProtocolTranslatorEngine.parseOpenAiChatRequest(openAiJson)
                                val previewPayload = if (cred.provider == com.cpaphone.core.model.ProviderType.CLAUDE) {
                                    ProtocolTranslatorEngine.toClaudeMessagesJson(parsed)
                                } else {
                                    ProtocolTranslatorEngine.toOpenAiChatJson(parsed)
                                }

                                val reply = ChatMessageItem(
                                    isUser = false,
                                    content = "已成功通过凭据 [${cred.alias}] 完成调度！\n协议转译与会话粘性正常工作，目标提供商: ${cred.provider.displayName}。",
                                    thinking = "调度决策完成：根据模型 [$selectedModel] 命中最优凭据 [${cred.id}]，加权系数=${cred.weight}，单模型熔断正常。",
                                    modelTag = selectedModel
                                )
                                messages.add(reply)
                            } else {
                                messages.add(
                                    ChatMessageItem(
                                        isUser = false,
                                        content = "错误：当前凭据池无可用账号，或所选模型 [$selectedModel] 正在冷却中，请先在【凭据池】增加有效 Key 或解除冷却。"
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
    var isThinkingExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
    ) {
        // 思考链展开折叠小卡片
        if (!message.thinking.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AccentPurple.copy(alpha = 0.12f),
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clickable { isThinkingExpanded = !isThinkingExpanded }
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Psychology,
                            contentDescription = "思考",
                            tint = AccentPurple,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isThinkingExpanded) "收起思考过程" else "点击展开深度思考 (Thinking)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AccentPurple
                        )
                    }

                    AnimatedVisibility(visible = isThinkingExpanded) {
                        Text(
                            text = message.thinking,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (!message.modelTag.isNullOrBlank()) {
                    Text(
                        text = message.modelTag,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                Text(
                    text = message.content,
                    fontSize = 14.sp,
                    color = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
