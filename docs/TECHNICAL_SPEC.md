# CPAphone 技术架构设计规范书 (Technical Specification)

> **版本**：2.0.0-PROD (全面对齐 CLIProxyAPI 技术规范)  
> **平台与语言**：Kotlin 2.1.10 / Android 8.0+ (API Level 26+) / Android NDK 26+  
> **核心引擎**：Ktor 3.1.1 (CIO 异步内核) + Jetpack Compose (Material 3) + Android Keystore  
> **架构准则**：Clean Architecture + MVI 响应式单向流 + 零冗余死代码 + 极低延迟工业级实现

---

## 1. 系统总体架构与物理分层设计

CPAphone 严格践行工业级 **Clean Architecture** 分层体系与**依赖倒置原则 (DIP)**，物理上解耦为四个单向依赖模块，确保核心业务领域逻辑 100% 可独立运行与单元测试：

```
                    ┌─────────────────────────────────────────────────┐
                    │               :app (Presentation UI)            │
                    │  Jetpack Compose MVI, Material 3, Navigation    │
                    └────────────────────────┬────────────────────────┘
                                             │ depends on
                    ┌────────────────────────┴────────────────────────┐
                    │              :engine (Proxy & Network)          │
                    │  Ktor Server (CIO), Ktor Client, SSE, WebRTC    │
                    │  NDK C-ABI Host, NSD Discovery, Service         │
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
                    │  Signature Sniffer, Cloak Disguise, Payload AST │
                    └─────────────────────────────────────────────────┘
```

### 1.1 模块物理边界与职责约束

1. **`:core`（核心领域层 - 纯 Kotlin 模块）**
   - **绝对约束**：100% 纯 Kotlin 源码，**严禁依赖任何 Android 平台 API**（如 `android.*`、`android.util.Log`、`Context` 等）。
   - **职责范围**：
     - 定义核心领域实体：`AuthCredential`、`ProviderType`、`AuthType`、`RealtimeSession`、`PluginManifest`、`LanDeviceNode`、`CpaTraceRecord` 等；
     - 负载均衡调度器（`RoundRobinLoadBalancer`、`SmoothWeightedRoundRobinLoadBalancer`、`FillFirstLoadBalancer`）；
     - 会话粘性管理器（`SessionAffinityManager`）与单模型多维熔断冷却状态机（`CooldownManager`）；
     - 全矩阵跨协议抽象语法树（`UnifiedChatRequest`）解析与双向生成（OpenAI <-> Claude <-> Gemini）；
     - 实时流式数据帧转译器（`StreamChunkTranslator`）；
     - 客户端指纹伪装与请求披风规范（`ClientCloakInterceptor`）；
     - 深度思考签名嗅探器（`SignatureSniffer`）；
     - 动态 Payload 规则重写算法（`PayloadRuleEngine`）。

2. **`:data`（数据持久化与安全加密层 - Android Library）**
   - **职责范围**：
     - Room SQLite 数据库：维护 `credentials` 表、`trace_logs` 表，编写类型转换器 `Converters` 与响应式 DAO 流；
     - 硬件级安全保险箱（`SecureCredentialStorage`）：基于 Android KeyStore 生成硬件级 Master Key（AES-256-GCM），对 API Key、OAuth Token 与 GCP 服务账号私钥执行硬件隔离加密；
     - 响应式配置引擎（`AppConfigRepository`）：基于 Jetpack DataStore Preferences 管理全局运行配置；
     - 仓储契约实现（`CredentialRepository`）。

3. **`:engine`（网络引擎、代理网关与宿主层 - Android Library）**
   - **职责范围**：
     - 嵌入式轻量 HTTP 代理网关（`LocalProxyServer`）：基于 Ktor Server (CIO) 监听指定端口，实现全路由多路复用与零拷贝管道流式转发；
     - 上游异步通信客户端（`UpstreamHttpClient`）：连接池复用、动态超时管理、请求披风 Header 注入与**流首包缓冲探测防御（Stream Bootstrap Buffering）**；
     - 凭据调度协同器（`CredentialCoordinator`）：将数据库、会话粘性、加权调度与单模型熔断无缝组装；
     - 实时流媒体与 WebRTC 语音中继管理器（`RealtimeRelayManager`）：管理 WebRTC 通话池、临时密钥（`ek_...`）签发、SDP 协商与伴生信令互斥 Claim；
     - 局域网服务发现与广播引擎（`NsdDiscoveryManager`）：基于 Android NSD 管理 mDNS `_cpaproxy._tcp` 广播与节点雷达扫描；
     - 动态插件宿主协调器（`NativePluginHost`）：C-ABI 接口桥接、SHA256 严苛校验、并发计数与崩溃熔断护盾；
     - 远程 CLIProxyAPI 管理中控客户端（`RemoteManagementClient`）；
     - Android 前台常驻保活服务（`CpaProxyService`）：通知栏动态更新、Wakelock 申请与网络漫游自愈监听。

4. **`:app`（展现交互层 - Android Application）**
   - **职责范围**：
     - 由 **Jetpack Compose + Material 3** 构建的收敛型界面；
     - 5 大核心界面：仪表盘、凭据池治理、实时链路追踪瀑布流、AI 调试沙盒（含实时语音通话控制台）、系统设置（含局域网节点一键配对与动态插件抽屉）；
     - 全局单例无反射依赖注入与生命周期协同。

---

## 2. 关键底层技术机制与性能保障设计

### 2.1 零延迟流式重构转译管道 (Zero-Delay SSE Streaming Pipeline)
解决跨协议调用（如客户端发起 OpenAI 流式请求，目标命中 Anthropic Claude 账号）时流式格式不兼容的问题：

```
[下游客户端] ──(OpenAI SSE 协议)──► Ktor Server CIO
                                        │
                                        ▼
                           【UpstreamHttpClient】
                                        │ (预读前置 4KB 缓冲包)
                           【首包隐式错误探测】
                                /              \
                       [发现服务过载]          [流式建立正常]
                            │                        │
                      触发换号透明重试         【StreamChunkTranslator】
                                            (逐行实时重构事件流)
                                            - content_block_delta -> text
                                            - thinking_delta -> reasoning_content
                                            - message_delta -> finish_reason: stop
                                                     │
[客户端实时渲染] ◄── Ktor ByteWriteChannel ◄─────────┘ (即时 flush，零中间堆积)
```

1. **通道零堆积**：流式管道使用 `ByteReadChannel.readUTF8Line()` 逐行捕获事件，经过轻量级状态机转译后，直接调用 `ByteWriteChannel.writeFully()` 与 `flush()`，首包输出延迟（TTFT Overhead）控制在 5ms 以内；
2. **原生直通零拷贝**：若入站协议与上游目标厂商一致（如 OpenAI 客户端请求 OpenAI 兼容提供商），系统跳过转译器，直接调用 `channel.copyTo(this)` 实现内核级缓冲区直接对拷，杜绝 JVM 堆内存碎片。

---

### 2.2 思考签名（Thinking Signature）特征识别状态机
大模型思考过程附带加密防伪签名，CPAphone 在 `:core` 模块建立基于首字节匹配的状态机：

```kotlin
object SignatureSniffer {
    enum class SignatureProvider {
        CLAUDE_CAIS,    // 首字节 0x08..0x0b ('C')
        CLAUDE_RAW,     // 首字节 0x10..0x13 ('E')
        CLAUDE_NESTED,  // 首字节 0x44..0x47 ('R')
        GPT_FERNET,     // 首字节 0x80..0x83 ('g')
        GEMINI_PROTO,   // Protobuf Field 2 Tag
        GENERIC_HIGH_ENTROPY
    }

    fun detect(signatureBase64: String): SignatureProvider {
        if (signatureBase64.isBlank()) return SignatureProvider.GENERIC_HIGH_ENTROPY
        val firstChar = signatureBase64[0]
        return when (firstChar) {
            'C' -> SignatureProvider.CLAUDE_CAIS
            'E' -> SignatureProvider.CLAUDE_RAW
            'R' -> SignatureProvider.CLAUDE_NESTED
            'g' -> SignatureProvider.GPT_FERNET
            else -> SignatureProvider.GENERIC_HIGH_ENTROPY
        }
    }
}
```
- **跨厂商决策策略**：
  - `preserve`：目标厂商一致，原样保留；
  - `drop_signature`：剥离签名，只保留可读思考文本；
  - `drop_block`：目标模型不支持，移除整段思考块；
  - `replace_with_gemini_bypass`：自动填充 Gemini 哨兵 Bypass 签名绕过上游校验。

---

### 2.3 WebRTC 音视频中继与伴生信令技术规范
针对 OpenAI Realtime 与 Codex Live 媒体会话：
1. **音频协商规范**：
   - 编码格式：`Opus`（Payload Type 111）；
   - 采样率：48,000 Hz，双声道（Channels 2）；
   - SDP fmtp 参数：`minptime=10;useinbandfec=1`（开启带内前向纠错）。
2. **DataChannel 规范**：
   - 通道固定 Label：`oai-events`；
   - 属性：有序传输（`ordered: true`），缓冲区上限 1MB，高低水位阈值 512KB 控制背压。
3. **伴生信令长连接互斥认领 (Sideband Claim)**：
   - 会话接入 `GET /v1/realtime/calls/:call_id` 升级为 WebSocket；
   - 采用内存无锁原子排他标记 `sidebandClaims.putIfAbsent(callId, true)`，已被其他连接占用的会话直接返回 HTTP 409 Conflict，防止多端并发串流。

---

### 2.4 动态 Payload 规则重写引擎设计 (`PayloadRuleEngine`)
在请求序列化前执行基于 gjson/sjson 语法的动态报文重写规则：
1. **匹配判定**：支持对 `model`、`protocol`、`headers` 与 JSON 路径字段进行正则匹配或存在性断言（`exist` / `not-exist`）；
2. **操作模式执行**：
   - `default` / `default-raw`：目标路径为 null 或缺失时回填默认值；
   - `override` / `override-raw`：强制重写目标路径字段；
   - `filter`：剔除指定路径的 JSON 节点（如客户端误传的不兼容字段）。

---

### 2.5 动态 C-ABI 插件宿主与熔断防崩护盾
基于 Android NDK C-ABI 规范设计，支持第三方原生共享动态库（`.so`）动态挂载：
```c
// 标准入口符号
int cliproxy_plugin_init(const cliproxy_host_api* host, cliproxy_plugin_api* plugin);
```
- **Android 安全代码存储**：动态库解压至 `context.codeCacheDir/plugins/<id>/`，赋予可执行权限，杜绝 SELinux W^X 违规；
- **并发计数与熔断隔离 (Circuit Breaker)**：
  - `GuardedPluginClient` 维护活跃请求原子计数；
  - 遇到未知 C/C++ 崩溃（如非法指针或内存段错误异常），宿主捕获后自动将插件置入 `FUSED_CRASHED` 熔断态，并在路由分发器中即刻摘除，**保障手机主 App 永不崩溃闪退**。

---

## 3. 数据安全与隐私加固设计

1. **硬件级加密凭据保险箱 (Secure Vault)**：
   - 依赖 Android 原生硬件安全模块（TEE / StrongBox）；
   - 主密钥存储于 Android KeyStore：`MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`；
   - 结合 `EncryptedSharedPreferences` 对 API Key、OAuth Token 与 GCP 服务账号私钥进行 `AES-256-GCM` 硬件隔离加密落盘。
2. **局域网安全与 Safe Mode 防御机制**：
   - 本地网关默认仅监听回环地址 `127.0.0.1`；
   - 开启局域网广播（`0.0.0.0`）时，自动启用 Safe Mode 嗅探：检测到使用官方模板默认测试 Key（如 `your-api-key-1`）或缺少鉴权时，强制阻断代理请求并返回标准 403 JSON，杜绝公网弱口令暴露。
3. **敏感词风控零宽字符插入混淆 (`cloak_obfuscate`)**：
   - 支持敏感词库配置；
   - 请求构建时，自动在敏感词首个字形（Grapheme）后动态插入 `\u200B`（Unicode 零宽空格），绕过文本硬匹配审查。

---

## 4. 工业级质量指标基线 (Quality Metrics Baseline)

| 指标维度 | 工业级基线要求 | CPAphone 实现机制 |
| :--- | :--- | :--- |
| **路由调度开销** | $\le 3\text{ms}$ | 纯 Kotlin 内存无锁原子计算，缓存凭据与单模型状态 |
| **流式首字额外延迟 (Overhead)** | $\le 5\text{ms}$ | 零拷贝管道透传与逐行即时 flush，无中间缓冲堆叠 |
| **应用静默基准内存占用** | $\le 35\text{MB}$ | Ktor CIO 轻量异步协程，无 Netty 繁重反射层 |
| **高并发流式峰值内存** | $\le 65\text{MB}$ | 响应式流背压控制，内存定长环形缓冲区（500 条）自动裁剪 |
| **后台长连接稳定性** | 99.9% 零意外断连 | 前台常驻通知栏服务 + Partial WakeLock + 网络漫游自愈 |
| **异常防护指标** | 零主进程闪退 (Zero Crash) | 全局标准 JSON 异常捕获 + 插件崩溃熔断防崩护盾 |
