# CPAphone 阶段性实施规划与演进蓝图 (Phase Planning & Roadmap)

> **文档版本**：1.0.0-PROD  
> **项目名称**：CPAphone (CLIProxyAPI for Android)  
> **核心原则**：阶段性验证、线性提交、工业级标准、低耦合高内聚、零死代码

---

## 一、 规划总体目标与阶段推进模型

为了保证 CPAphone 在开发过程中严格契合“代码工整、运行不笨重、响应延迟低、高内聚低耦合、工业级化、界面极简丝滑”的要求，本项目采取 **渐进式分层迭代模型（Progressive Layered Iteration Model）**。

整个项目划分为 6 个基础实施阶段（阶段 0 至阶段 5）及 3 个后续进阶演进阶段（阶段 6 及以后）。每个阶段设立明确的**边界约束**、**交付物清单**、**质量门禁（Quality Gate）**与**本地 Git 追溯锚点**。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                             阶段演进总览流水线                              │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
      【阶段 0：规范基石】 ──────────► 功能与技术规格书编制、环境与 Git 初始化
                                       │
      【阶段 1：核心领域】 ──────────► 纯 Kotlin 调度算法、统一 AST 跨协议转译
                                       │
      【阶段 2：数据安全】 ──────────► Room SQLite 持久化、Keystore 硬件级加密
                                       │
      【阶段 3：网络引擎】 ──────────► Ktor CIO 嵌入式网关、前台保活服务、远程中控
                                       │
      【阶段 4：收敛 UI 】 ──────────► Material 3 极简 5 大核心模块、MVI 架构
                                       │
      【阶段 5：质量审计】 ──────────► 受管协程、防爆 JSON、死代码剔除与性能调优
                                       │
      【阶段 6+：未来演进】 ────────► WebRTC 实时语音、NDK 插件、局域网 mDNS
```

---

## 二、 各阶段详细实施规划与交付标准

### 【阶段 0】：初始化与规范基石构建 (Phase 0: Specifications & Baseline)

#### 1. 核心目标
建立工程标准化版本控制环境，明确产品在 Android 平台的双模运行形态（本地独立代理 + 远程中控管理），确立 Clean Architecture 与 MVI 技术栈，完成业务与技术双权威规范文档。

#### 2. 关键任务清单
- [x] 初始化本地 Git 仓库，配置针对 Android / Kotlin / Gradle 的工业级 `.gitignore`。
- [x] 撰写 `docs/FUNCTIONAL_SPEC.md`（详尽业务功能规格说明书）：
  - 梳理双模架构、多提供商凭据池治理、移动端 OAuth 深度链接规范；
  - 负载均衡（WRR / RR / Fill-First）、会话粘性（Session Affinity）、自动冷却机制；
  - 实时日志审计、测试沙盒与移动端安全防御（Safe Mode）。
- [x] 撰写 `docs/TECHNICAL_SPEC.md`（详尽技术架构设计说明书）：
  - 四层架构物理隔离机制（`:core`, `:data`, `:engine`, `:app`）；
  - 零拷贝管道化流式传输（Zero-Copy Streaming）与首包侦测（Bootstrap Buffering）；
  - 内存定长环形防爆设计（Ring Buffer）与性能指标基线。
- [x] 编写 `README.md`，提供清晰的项目导览与构建指引。

#### 3. 验收标准与交付物
- 交付物：`.gitignore`, `docs/FUNCTIONAL_SPEC.md`, `docs/TECHNICAL_SPEC.md`, `README.md`。
- 质量门禁：文档结构完整、技术参数清晰、职责明确。
- Git 提交：`4f7cc1e docs: 初始化项目规范，完成移动端双模功能与技术规划文档`。

---

### 【阶段 1】：纯 Kotlin 核心领域层构建 (Phase 1: Pure Kotlin Core Domain)

#### 1. 核心目标
构建系统的灵魂——核心领域层（`:core` 模块）。此模块必须为纯 Kotlin 库，零 Android 平台 API 依赖，实现业务调度算法、会话粘性、熔断倒计时与跨协议 AST 转译。

#### 2. 关键任务清单
- [x] 搭建统一构建系统，配置 Version Catalogs（`gradle/libs.versions.toml`）与多模块构建脚本。
- [x] 领域模型建模：
  - `ProviderType`：对齐 CLIProxyAPI 支持的 8 大厂商提供商；
  - `AuthCredential`：承载多权重、状态机（Active / Cooldown / Expired / Disabled）与统计指标；
  - `CpaTraceRecord`：端到端请求审计跟踪实体。
- [x] 调度算法实现与封装（`LoadBalancer`）：
  - `RoundRobinLoadBalancer`：无锁原子轮询；
  - `SmoothWeightedRoundRobinLoadBalancer`：Nginx 同款平滑加权轮询；
  - `FillFirstLoadBalancer`：深度优先分发。
- [x] 会话与状态管理：
  - `SessionAffinityManager`：提取会话标识，锁定单号提升 Prompt Cache 命中率，支持超时自动清理；
  - `CooldownManager`：毫秒级冷却倒计时与并发防重入。
- [x] 跨协议统一 AST 转译引擎（`ProtocolTranslatorEngine`）：
  - 构建 `UnifiedChatRequest` 抽象语法树；
  - 实现 OpenAI Chat 请求的结构化解析；
  - 实现 OpenAI -> Anthropic Claude Messages 官方格式转译（含 system 提示词提取、Thinking 思考块支持）；
  - 实现 OpenAI -> Google Gemini GenerateContent 官方格式转译。
- [x] 编写完整的 JVM 单元测试套件（`CoreDomainTest`），覆盖调度、冷却、会话与转译。

#### 3. 验收标准与交付物
- 交付物：`:core` 模块全套源码与单元测试。
- 质量门禁：纯 JVM 编译通过，无任何 `android.*` 依赖污染，100% 单元测试通过。
- Git 提交：`93c3c59 feat(core): 定义核心领域模型、协议契约与调度算法抽象`。

---

### 【阶段 2】：数据持久化与硬件级安全层 (Phase 2: Data Persistence & Security)

#### 1. 核心目标
构建 `:data` 模块，为应用提供单一事实来源（Single Source of Truth）。保障敏感凭据的硬件级加密，提供强类型响应式配置管理。

#### 2. 关键任务清单
- [x] Room 数据库架构：
  - 设计 `CredentialEntity`（凭据表）与 `TraceLogEntity`（审计日志表）；
  - 编写 `Converters` 实现枚举与 JSON 字典的高性能转换；
  - 编写 `CredentialDao` 与 `TraceLogDao`，查询全面暴露响应式 `Flow<T>`；
  - 封装单例 `CpaDatabase`。
- [x] 硬件级安全保险箱（`SecureCredentialStorage`）：
  - 基于 Android KeyStore 生成硬件级 Master Key（AES-256-GCM）；
  - 结合 `EncryptedSharedPreferences` 对 OAuth Refresh Token 及 API Key 执行硬件隔离存储。
- [x] 响应式配置仓库（`AppConfigRepository`）：
  - 基于 Jetpack DataStore Preferences 实现无阻塞配置持久化；
  - 支持运行模式（本地/远程）、端口号、局域网访问权限、Safe Mode 等响应式状态流。
- [x] 仓储实现层（`CredentialRepository`）：
  - 连接 DAO 与安全保险箱，向外统一提供脱敏后的领域实体。

#### 3. 验收标准与交付物
- 交付物：`:data` 模块全套源码、实体、DAO、加密工具与仓库。
- 质量门禁：所有数据库操作支持挂起或响应式流，绝不在主线程执行 I/O；敏感字段完全硬件级加密。
- Git 提交：`dc69c4f feat(data): 实现基于 Room 与 Keystore 的本地持久化与安全配置引擎`。

---

### 【阶段 3】：双模网络引擎与代理服务 (Phase 3: Dual-Mode Engine & Background Service)

#### 1. 核心目标
构建 `:engine` 模块，实现核心网络通信中枢。在移动端低资源环境下运行高吞吐、低延迟的嵌入式网关，并提供长效保活的前台服务。

#### 2. 关键任务清单
- [x] 核心凭据调度协同器（`CredentialCoordinator`）：
  - 串联凭据仓储、会话粘性管理器与冷却熔断器；
  - 动态切换负载均衡算法，处理请求失败后的阶梯式惩罚冷却。
- [x] 上游异步 HTTP 客户端（`UpstreamHttpClient`）：
  - 基于 Ktor Client (CIO 引擎)，实现连接池复用与 Keep-Alive 保持；
  - 针对不同提供商智能注入官方专属鉴权头与自定义 Header。
- [x] 本地嵌入式轻量代理网关（`LocalProxyServer`）：
  - 基于 Ktor Server (CIO 引擎) 构建非阻塞 HTTP 服务；
  - 多路复用路由：`/healthz`, `/v1/models`, `/v1/chat/completions`, `/v1/messages`；
  - 零拷贝管道透传（`ByteReadChannel.copyTo(ByteWriteChannel)`）；
  - 首包侦测与透明重试：发生限流或异常时，在当前连接内无缝换号重试。
- [x] 远程中控客户端（`RemoteManagementClient`）：
  - 对接远端已部署的 CLIProxyAPI 服务（`/v0/management/...`）；
  - 支持远程健康检查、配置拉取与凭据状态同步。
- [x] Android 前台保活服务（`CpaProxyService`）：
  - 注册 `ForegroundService` 绑定常驻通知栏；
  - 动态展示当前服务状态、端口与请求计数，提供通知栏快捷停止操作；
  - 申请局部唤醒锁（`Partial WakeLock`），防止系统深度休眠掐断长连接。

#### 3. 验收标准与交付物
- 交付物：`:engine` 模块全套网络与服务源码。
- 质量门禁：服务启动与停止平滑无泄漏，流式响应支持零拷贝透传，异常中断时自动回收端口。
- Git 提交：`05d62fe feat(engine): 实现本地嵌入式代理服务与远程中控网络引擎`。

---

### 【阶段 4】：Material 3 极简收敛 UI 体系 (Phase 4: Minimalist Convergence UI)

#### 1. 核心目标
构建 `:app` 展现层。采用声明式 **Jetpack Compose + Material 3**，贯彻极简收敛的设计语言，拒绝层层嵌套的啰嗦弹窗，实现丝滑、直观的高效交互。

#### 2. 关键任务清单
- [x] 设计系统与主题配置（`CPAphoneTheme`）：
  - 定制极简低饱和深色/浅色配色方案，强化状态语义色（活跃绿、冷却黄、故障红）。
- [x] 全局单例装配与入口初始化（`CpaApplication`）：
  - 零反射轻量依赖装配，完成数据库、加密库、网络引擎与前台服务的生命周期绑定。
- [x] 主界面脚手架与收敛底部导航（`MainActivity`）：
  - 统一 5 大核心标签页：仪表盘、凭据池、实时追踪、调试台、系统设置。
- [x] 五大收敛视图实现：
  - **仪表盘（DashboardScreen）**：运行状态动效圆点、模式标签、一键服务开关、Base URL 展示与极简指标卡片；
  - **凭据池（AuthPoolScreen）**：平铺账号卡片流、健康状态指示、加权调节、极简无废话添加凭据对话框；
  - **实时追踪（LiveTracesScreen）**：请求瀑布流、响应码、耗时、TTFT 首字延迟、一键清空日志；
  - **测试沙盒（PlaygroundScreen）**：极简双栏气泡聊天、思考过程（Thinking）内联折叠展示、一键验证代理链路；
  - **系统设置（SettingsScreen）**：运行模式切换、算法动态调整、局域网访问授权与 Safe Mode 安全开关。
- [x] Android 清单与权限配置（`AndroidManifest.xml`）：
  - 网络权限、前台服务权限、通知权限、Wakelock 与 Deep Link 协议头注册。

#### 3. 验收标准与交付物
- 交付物：`:app` 模块全套 UI 视图、主题与清单配置。
- 质量门禁：无 XML 遗留布局，全 Compose 声明式构建，UI 线程 60fps 丝滑流畅。
- Git 提交：`47a51b8 feat(ui): 实现 Material 3 极简收敛风格的移动端交互体系`。

---

### 【阶段 5】：工业级质量审计、死代码剔除与性能调优 (Phase 5: Quality Audit & Hardening)

#### 1. 核心目标
对全工程进行严苛的工业级审查。排查并发隐患、消除未捕获异常、确保内存不颠簸，落实规范化受管生命周期与统一错误 JSON 输出。

#### 2. 关键任务清单
- [x] 协程生命周期规范化：
  - 排查并彻底清除 `GlobalScope.launch` 等反模式代码；
  - 在 `LocalProxyServer` 中引入受管的 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`；
  - 服务停止时显式执行 `serverScope?.cancel()`，杜绝协程泄露与野指针。
- [x] 异常响应与 JSON 防爆加固：
  - 杜绝直接拼装非转义错误字符串导致 JSON 格式破坏的漏洞；
  - 引入 `kotlinx.serialization.json.buildJsonObject`，严格按照标准 OpenAI Error 结构封装返回。
- [x] 性能与静态分析自检：
  - 检查全部 Kotlin 源码，剔除未使用的 import、未使用的局部变量与无效日志；
  - 校验 Proguard 混淆规则（`consumer-rules.pro` 与 `proguard-rules.pro`）。

#### 3. 验收标准与交付物
- 交付物：重构优化后的 `:engine` 与 `:app` 关键链路源码。
- 质量门禁：静态代码整洁无警告，协程生命周期闭环受管，网络异常均能输出合规 JSON。
- Git 提交：`fa3ec09 refactor: 优化流式调度性能，完善受管协程生命周期与标准 JSON 错误格式`。

---

## 三、 阶段交付跟踪与 Git 追溯矩阵 (Traceability Matrix)

| 阶段编号 | 阶段名称 | 交付关键点 | 代码提交 Hash | 提交说明 |
| :---: | :--- | :--- | :---: | :--- |
| **阶段 0** | 规范基石 | 业务规划书、技术设计书、`.gitignore` | `4f7cc1e` | `docs: 初始化项目规范，完成移动端双模功能与技术规划文档` |
| **阶段 1** | 核心领域 | 纯 Kotlin 调度算法 (WRR/RR)、统一 AST 跨协议转译、单元测试 | `93c3c59` | `feat(core): 定义核心领域模型、协议契约与调度算法抽象` |
| **阶段 2** | 数据安全 | Room SQLite 数据库、Android Keystore 硬件级 AES-256 加密、DataStore | `dc69c4f` | `feat(data): 实现基于 Room 与 Keystore 的本地持久化与安全配置引擎` |
| **阶段 3** | 网络引擎 | Ktor CIO 嵌入式代理、前台保活服务、Wakelock、远程中控客户端 | `05d62fe` | `feat(engine): 实现本地嵌入式代理服务与远程中控网络引擎` |
| **阶段 4** | 收敛 UI | Material 3 极简 5 大视图 (仪表盘/凭据/追踪/沙盒/设置)、MVI 模式 | `47a51b8` | `feat(ui): 实现 Material 3 极简收敛风格的移动端交互体系` |
| **阶段 5** | 质量审计 | 受管协程作用域、防爆 JSON 序列化、混淆规则与死代码剔除 | `fa3ec09` | `refactor: 优化流式调度性能，完善受管协程生命周期与标准 JSON 错误格式` |

---

## 四、 后续演进阶段规划 (Phase 6+: Future Enhancements)

在基础六大阶段稳固交付的基础上，规划以下面向进阶场景的演进路线：

### 【阶段 6】：实时流媒体与语音中继能力演进 (Phase 6: WebRTC & Audio Relay)
- **目标**：对齐 CLIProxyAPI 的 Pion WebRTC / Codex Live 实时音视频中继。
- **规划内容**：
  1. 引入 Android 官方 WebRTC SDK 或轻量级 WebRTC 客户端，实现移动端直连 Codex Live 实时双向语音通话；
  2. 实现移动端低延迟音频采集（OpenSL ES / AAudio）与静音检测（VAD）。

### 【阶段 7】：跨设备自动局域网发现与配对 (Phase 7: LAN mDNS Discovery)
- **目标**：消除手动输入 IP 与端口的繁琐步骤。
- **规划内容**：
  1. 集成 Android NSD（Network Service Discovery / mDNS），局域网内自动广播 `_cpaproxy._tcp` 服务；
  2. 桌面端 CLI 工具或浏览器打开时可一键自动发现手机端代理并完成无缝绑定。

### 【阶段 8】：动态 C-ABI 插件引擎 NDK 移植 (Phase 8: Native Plugin Host)
- **目标**：对齐 CLIProxyAPI 的本地动态共享库（`.so`）插件扩展体系。
- **规划内容**：
  1. 基于 Android NDK 提供 `.so` 动态库加载宿主接口；
  2. 允许高级用户编写自定义提供商执行器并通过文件管理器热加载到 CPAphone 内部。
