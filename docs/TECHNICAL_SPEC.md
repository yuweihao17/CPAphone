# CPAphone 技术架构设计规范书 (Technical Specification)

> **版本**：1.0.0-PROD  
> **语言与平台**：Kotlin 2.1+ / Android 8.0+ (API Level 26+)  
> **核心引擎**：Ktor 3.1+ (CIO 嵌入式服务器与客户端) + Jetpack Compose (Material 3)  
> **架构模式**：Clean Architecture + MVI (Model-View-Intent) 响应式单向数据流

---

## 1. 系统总体架构与分层设计

CPAphone 严格遵循工业级 **Clean Architecture** 架构体系，贯彻**关注点分离（SoC）**、**高内聚低耦合**与**依赖倒置原则（DIP）**。系统物理上分为四大核心模块：

```
                    ┌─────────────────────────────────────────────────┐
                    │               :app (Presentation UI)            │
                    │  Jetpack Compose MVI, Material 3, Navigation    │
                    └────────────────────────┬────────────────────────┘
                                             │ depends on
                    ┌────────────────────────┴────────────────────────┐
                    │              :engine (Proxy & Network)          │
                    │  Ktor Server (CIO), Ktor Client, SSE, Service   │
                    └────────────────────────┬────────────────────────┘
                                             │ depends on
                    ┌────────────────────────┴────────────────────────┐
                    │          :data (Storage & Security)             │
                    │  Room DB, DataStore, Android Keystore Encrypted │
                    └────────────────────────┬────────────────────────┘
                                             │ depends on
                    ┌────────────────────────┴────────────────────────┐
                    │              :core (Pure Domain Model)          │
                    │  Zero Android Deps, Routing, Translator, Entity │
                    └─────────────────────────────────────────────────┘
```

### 1.1 模块职责边界定义

1. **`:core`（核心领域层 - 纯 Kotlin 模块）**
   - **绝对约束**：100% 纯 Kotlin，**严禁引入任何 Android 平台特定依赖**（如 `android.*`、`android.util.Log` 等）。
   - **核心职责**：
     - 定义业务核心领域实体（`AuthCredential`, `RoutingStrategy`, `CpaTraceRecord`, `ModelMapping` 等）。
     - 实现多算法流量调度器（`RoundRobinRouter`, `WeightedRouter`, `FillFirstRouter`）。
     - 实现跨厂商协议转译矩阵抽象与核心算法（OpenAI <-> Claude <-> Gemini JSON AST 解析与映射）。
     - 实现会话粘性管理器（`SessionAffinityManager`）与冷却熔断状态机（`CooldownState`）。
     - 确保该模块具备 100% 单元测试覆盖能力与极佳的可复用性。

2. **`:data`（数据与持久化安全层 - Android 库模块）**
   - **核心职责**：
     - 本地 SQLite / Room 数据库：维护结构化凭据表、路由规则表、配置表与持久化冷却记录。
     - 安全凭据存储（Security Vault）：基于 Android Keystore 与 AES-256-GCM，实现 API Key 与 OAuth Refresh Token 的硬件级加密存储。
     - 响应式配置管理（DataStore）：基于 Preferences DataStore 提供强类型、非阻塞的配置读写流（`Flow<AppConfig>`）。
     - 实现 `:core` 定义的 Repository 接口契约，向外暴露单一事实来源（Single Source of Truth）。

3. **`:engine`（网络与本地代理引擎层 - Android 库模块）**
   - **核心职责**：
     - 嵌入式轻量 HTTP 代理网关：基于 **Ktor Server (CIO 引擎)** 构建，监听本地端口（默认 `8317`）。
     - 路由多路复用与分发：处理 `/v1/chat/completions`, `/v1/messages`, `/v1/models`, `/v1beta/...` 等端点。
     - 高性能流式传输（SSE）：直接对接 Ktor ByteReadChannel 与 ByteWriteChannel，实现零内存拷贝（Zero-Copy）背压透传。
     - 流首包缓冲（Stream Bootstrap Buffering）：提前解析流首包数据帧，发现假 200 故障即刻透明换号重试。
     - 远程中控客户端：基于 Ktor Client 对接远端 CLIProxyAPI `/v0/management/...` 接口与实时 WebSocket 日志流。
     - 系统前台保活服务（Foreground Service）与电源管理唤醒锁。

4. **`:app`（展现交互层 - 极简收敛 UI 模块）**
   - **核心职责**：
     - 完全由 **Jetpack Compose + Material 3** 构建的收敛型用户界面。
     - 基于 MVI 架构模式（`State`, `Intent`, `Effect`）管理屏幕状态，保证单向数据流与可预测的状态驱动。
     - 管理应用级依赖注入（DI）、全局导航（Navigation Compose）与前台服务生命周期绑定。

---

## 2. 关键技术选型与依赖配置规范

| 技术组件 | 选型与推荐版本 | 选型依据与工业级优势 |
| :--- | :--- | :--- |
| **编程语言** | Kotlin 2.1.10 | 强类型安全、现代协程支持、智能推导与高性能编译 |
| **网络引擎 (Server & Client)** | Ktor 3.1.1 (CIO Engine) | 纯 Kotlin 异步非阻塞 I/O，比 Netty 占用内存少 70% 以上，极度适合移动端运行 |
| **持久化数据库** | Room 2.6.1 + SQLite | Android 官方标准 ORM，原生支持 Coroutine Flow 响应式监听与迁移保护 |
| **轻量配置存储** | Jetpack DataStore Preferences 1.1.2 | 取代传统 SharedPreferences，线程安全，强类型且无主线程阻塞卡顿 |
| **硬件安全加密** | AndroidX Security Crypto 1.1.0-alpha06 | 基于 Android KeyStore 生成硬件级 Master Key，AES-256-GCM 加密，杜绝密钥泄露 |
| **UI 交互框架** | Jetpack Compose BOM 2025.02.00 | 声明式响应式 UI，无 XML 臃肿层，动画自然流畅，渲染性能极佳 |
| **JSON 序列化** | kotlinx.serialization 1.8.0 | 编译期生成序列化代码，零反射（Zero-Reflection），解析速度快，内存消耗低 |
| **异步并发** | Kotlinx Coroutines 1.10.1 | 结构化并发体系，完善的取消与异常传播机制 |

---

## 3. 核心引擎底层设计与性能保障

### 3.1 零拷贝流式转发架构 (Zero-Copy Streaming Pipeline)
移动设备在承载长文本或大模型推理流式输出时，若将整个数据包读入内存会导致 GC 频繁触发、应用卡顿甚至 OOM。CPAphone 采用管道化流式转发：

```
[客户端请求] ──► Ktor Server CIO ──► 捕获请求体 (ByteReadChannel)
                                              │
                                              ▼
                                   【首包缓冲探测模块】
                                (预读 4KB 缓冲侦测错误)
                                      /              \
                                [发现上游错误]      [响应正常]
                                     │                  │
                           关闭通道，自动切换凭据重试    流式管道透传 (Pipe)
                                                        │
[客户端渲染] ◄── Ktor Server 流式输出 ◄── ByteWriteChannel ◄──┘
```

1. **管道化传输（Piping）**：利用 `ByteReadChannel.copyTo(ByteWriteChannel)`，在内核缓冲区之间直接搬运字节流，避免重复在 JVM 堆内存上分配大对象数组。
2. **首包缓冲侦测（Bootstrap Buffering）**：
   - 上游建立连接后，前置读取首个 4KB 数据块。
   - 检测是否包含 `server_is_overloaded`、`quota_exceeded`、`unauthorized` 等特征标记。
   - 若异常则立即将该凭据置入 `CooldownState`，当前协程换选下一个凭据透明重新连接；若正常，则立即把缓冲数据块与后续流合并下发。

### 3.2 多算法路由与平滑加权轮询 (Smooth Weighted Round-Robin)
在 `:core` 中实现 Nginx 同款的 **平滑加权轮询（Smooth WRR）**，确保高权重凭据被均匀分散调度，而不是连续调用：
- 设凭据池 $S = \{c_1, c_2, ..., c_n\}$，每个凭据具备固定权重 $W_i$ 与当前动态权重 $CW_i$（初始为 0）。
- 每次调度：
  1. 过滤掉处于冷却期（`isCooldown == true`）与不可用的凭据；
  2. 遍历候选集，令每个凭据的当前权重 $CW_i = CW_i + W_i$；
  3. 选取 $CW_i$ 最大的凭据作为本次命中的执行者 $c_{max}$；
  4. 令命中凭据的当前权重减去总权重：$CW_{max} = CW_{max} - \sum W_k$；
  5. 返回 $c_{max}$ 执行请求。

### 3.3 跨协议 AST 转换器设计 (Protocol AST Translator)
为避免在字符串级别做低效的正则替换，`:core` 构建统一抽象语法树（Unified Protocol AST）：
```kotlin
sealed interface ProtocolMessage {
    val role: MessageRole
    val contents: List<ContentBlock>
}

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Thinking(val reasoning: String, val signature: String? = null) : ContentBlock
    data class Image(val mimeType: String, val base64Data: String) : ContentBlock
    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : ContentBlock
    data class ToolResult(val toolCallId: String, val content: String) : ContentBlock
}
```
- **输入反序列化**：Ktor Server 接收到请求后，由对应的 InputDecoder（如 `OpenAiDecoder`）解析为 `UnifiedChatRequest`。
- **内部规则重写**：应用系统提示词注入、思维链剥离/保留、MCP 工具结构适配。
- **输出序列化**：由目标提供商的 OutputEncoder（如 `ClaudeEncoder` 或 `GeminiEncoder`）序列化为官方原生协议请求体。

---

## 4. 数据安全与隐私加固规范

1. **凭据安全加密方案**：
   - 依赖 Android 原生硬件安全模块（TEE / StrongBox）。
   - 主密钥存储于 Android KeyStore：`AndroidKeyStoreProvider.getKeyStore()`。
   - 凭据数据采用 `AES/GCM/NoPadding`（256 位密钥）加密后落入本地数据库或 Preferences。
   - 内存中敏感凭据在使用完毕后尽快解除强引用，防止内存 Dump 攻击。
2. **局域网安全与 Safe Mode**：
   - 默认仅绑定回环地址 `127.0.0.1`。
   - 用户显式开启“局域网共享”时，强制要求设置 `API Key`，若未配置则自动拦截非本机 IP 的接入并记录审计日志。

---

## 5. 代码工业级质量基准与开发戒律

1. **零冗余死代码**：
   - 每次代码提交前，必须经过静态分析，清除所有未使用的 import、未使用的本地变量与过时弃用接口。
2. **严禁在主线程进行阻塞 I/O**：
   - 所有数据库操作、网络请求、文件读写必须显式限定在 `Dispatchers.IO`。
3. **严格的异常封闭性（No Uncaught Exceptions）**：
   - 代理网关任何内部解析失败、网络闪断，必须被优雅捕获并封装为符合 OpenAI 规范的标准 JSON 错误对象（如 `{"error": {"message": "...", "type": "proxy_error"}}`），确保客户端连接永不发生裸断。
4. **性能指标基线**：
   - 本地代理单次请求路由决策开销：$\le 3\text{ms}$。
   - 流式首字节转发追加延迟（Overhead）：$\le 5\text{ms}$。
   - 应用程序静默运行基准内存占用：$\le 35\text{MB}$。
   - 持续高并发流式代理峰值内存占用：$\le 65\text{MB}$。
