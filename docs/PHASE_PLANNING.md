# CPAphone 阶段性实施规划与全量演进蓝图 (Phase Planning & Roadmap)

> **文档版本**：2.0.0-PROD (全面对齐 CLIProxyAPI 全量功能清单)  
> **项目名称**：CPAphone (CLIProxyAPI for Android)  
> **核心原则**：渐进式交付、阶段性验证、线性提交、工业级标准、低耦合高内聚、零死代码、100% 功能完备

---

## 一、 规划总体目标与阶段演进模型

为了确保 CPAphone 在移动端严格契合“代码工整、运行不笨重、延迟极低、高内聚低耦合、工业级化、界面极简丝滑、100% 覆盖 CLIProxyAPI 全部功能”的最高工程标准，项目采取 **渐进式分层迭代模型（Progressive Layered Iteration Model）**。

整个演进蓝图划分为两个主要阶段族：
1. **已实施完成阶段（阶段 0 至阶段 8）**：打通基础规范、纯 Kotlin 核心领域层、Room/Keystore 硬件安全存储、Ktor CIO 嵌入式双模网络引擎、Material 3 极简收敛 UI 体系、代码质量调优、WebRTC 实时语音中继、Android NSD 局域网服务自发现与 C-ABI 动态插件宿主。
2. **后续全量对齐进阶演进（阶段 9 至阶段 12）**：将 CLIProxyAPI 的剩余高阶特性（全量 `/v0/management` 管理 API 闭环、动态 Payload 规则引擎、思考签名嗅探与零宽字符风控混淆、多存储后端与 Home 集群模式）全量对齐至移动端。

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           CPAphone 阶段演进全景总览                         │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │
      【阶段 0：规范基石】 ──────────► 业务与技术规格书编制、环境与 Git 初始化 (Commit: 4f7cc1e)
                                       │
      【阶段 1：核心领域】 ──────────► 调度算法、单模型冷却、双向转译、SSE帧重构 (Commit: 93c3c59, 2fc5985)
                                       │
      【阶段 2：数据安全】 ──────────► Room SQLite 持久化、Keystore 硬件级加密 (Commit: dc69c4f, 85ad829)
                                       │
      【阶段 3：网络引擎】 ──────────► Ktor CIO 嵌入式网关、首包缓冲防御、前台服务 (Commit: 05d62fe, 83164e9)
                                       │
      【阶段 4：收敛 UI 】 ──────────► Material 3 极简 5 大核心模块、MVI 架构 (Commit: 47a51b8, 65c6805)
                                       │
      【阶段 5：质量审计】 ──────────► 受管协程、防爆 JSON、定长日志环形防爆 (Commit: fa3ec09, b71067a)
                                       │
      【阶段 6：实时语音】 ──────────► WebRTC SDP 协商、临时密钥、声波控制台 (Commit: 9ebea14)
                                       │
      【阶段 7：局域网发现】 ────────► Android NSD/mDNS 广播、设备雷达一键配对 (Commit: fad7b2b)
                                       │
      【阶段 8：动态插件】 ──────────► C-ABI NDK 动态库宿主、SHA256 验真、熔断护盾 (Commit: 1b71f3c)
                                       │
      【阶段 9：管理全覆盖】 ────────► 40+ 管理端点闭环、动态 YAML 读写热重载、OAuth 托管
                                       │
      【阶段 10：规则与风控】 ───────► 动态 Payload 重写引擎、零宽字符混淆、签名特征嗅探
                                       │
      【阶段 11：存储与集群】 ───────► Postgres/Git/S3 适配器、Home 集群 RESP 客户端
                                       │
      【阶段 12：端到端验证】 ───────► 全场景自动化回归、性能基线压测与最终 Release 打包
```

---

## 二、 基础交付阶段详细回顾 (Phases 0 ~ 8)

### 【阶段 0】：初始化与规范基石构建 (Phase 0: Specifications & Baseline)
- **核心目标**：建立标准化版本控制，确立 Android 双模运行形态与四层 Clean 架构，完成第一版权威规范文档。
- **交付成果**：`.gitignore`, `docs/FUNCTIONAL_SPEC.md`, `docs/TECHNICAL_SPEC.md`, `README.md`。
- **Git 提交**：`4f7cc1e docs: 初始化项目规范，完成移动端双模功能与技术规划文档`。

### 【阶段 1】：纯 Kotlin 核心领域层与高级转译 (Phase 1: Pure Kotlin Core Domain)
- **核心目标**：构建零 Android 平台依赖的纯粹业务内核，实现负载均衡调度、会话粘性、单模型熔断与跨协议 AST 转译。
- **交付成果**：
  - `AuthCredential` 扩展 `AuthType`、`expiresAt`、单模型局部冷却隔离（`modelCooldowns`）；
  - `SmoothWeightedRoundRobinLoadBalancer` 平滑加权轮询、`RoundRobinLoadBalancer`、`FillFirstLoadBalancer`；
  - `SessionAffinityManager` 会话粘性租约与定期垃圾回收；
  - `CooldownManager` 支持凭据级全局熔断与特定模型的局部冷却隔离；
  - `ProtocolTranslatorEngine` 支持 OpenAI <-> Claude 双向无损转译与 Gemini 输出；
  - `StreamChunkTranslator` 将 Claude 流式事件逐行实时重构为 OpenAI 兼容的 `chat.completion.chunk`；
  - `ClientCloakInterceptor` 客户端指纹与请求披风规范化。
- **Git 提交**：
  - `93c3c59 feat(core): 定义核心领域模型、协议契约与调度算法抽象`
  - `2fc5985 feat(core): 强化领域模型多维冷却、双向协议转译与流式SSE数据帧重构`

### 【阶段 2】：数据持久化与硬件级安全层 (Phase 2: Data Persistence & Security)
- **核心目标**：构建单一事实来源，实现硬件级隔离加密与非阻塞响应式配置管理。
- **交付成果**：
  - `CredentialEntity` 与 `TraceLogEntity` Room 数据库表设计，严格对齐领域模型；
  - `Converters` 支持枚举与 JSON 字典安全转换；
  - `SecureCredentialStorage` 基于 Android KeyStore 生成 `AES-256-GCM` 硬件主密钥，加密存储 API Key、OAuth Token 与 GCP 服务账号私钥；
  - `AppConfigRepository` 基于 DataStore Preferences 暴露强类型响应式配置流；
  - `CredentialRepository` 仓储实现与多维持久化测试。
- **Git 提交**：
  - `dc69c4f feat(data): 实现基于 Room 与 Keystore 的本地持久化与安全配置引擎`
  - `85ad829 feat(data): 升级凭据表结构支持局部冷却与OAuth，增强安全保险箱与配置项`

### 【阶段 3】：双模网络引擎与代理服务 (Phase 3: Dual-Mode Engine & Background Service)
- **核心目标**：在移动端构建高性能异步网络中枢，支持前台保活、零拷贝流式转发与首包缓冲重试。
- **交付成果**：
  - `CredentialCoordinator` 凭据调度协同器，多维校验可用性与单模型局部冷却；
  - `UpstreamHttpClient` 异步客户端，集成请求披风 Header 与**流首包缓冲探测（Stream Bootstrap Buffering）**；
  - `LocalProxyServer` 嵌入式轻量网关，多路复用路由，集成跨协议流式实时重构管道；
  - `RemoteManagementClient` 远程中控客户端；
  - `CpaProxyService` 前台保活服务、常驻通知栏、Partial WakeLock 与网络漫游自愈监听。
- **Git 提交**：
  - `05d62fe feat(engine): 实现本地嵌入式代理服务与远程中控网络引擎`
  - `83164e9 feat(engine): 升级网络引擎，实现跨协议流式重构转译、单模型熔断隔离与首包防御`

### 【阶段 4】：Material 3 极简收敛 UI 体系 (Phase 4: Minimalist Convergence UI)
- **核心目标**：基于 Jetpack Compose 构建视觉克制、呼吸感自然、交互流畅的收敛型界面。
- **交付成果**：
  - `Theme.kt` 沉浸式深炭黑与极简象牙白双调设计系统，`CompactShapes` 规范圆角；
  - `DashboardScreen` 仪表盘：动态呼吸状态指示灯、Base URL 点击一键复制、指标矩阵卡片；
  - `AuthPoolScreen` 凭据池：卡片平铺流、**单模型局部冷却微标签**、一键测活与重置冷却、极简录入抽屉；
  - `LiveTracesScreen` 实时追踪：流式请求瀑布流、**内联平滑展开详情**、一键清空日志；
  - `PlaygroundScreen` 调试沙盒：主流模型切换器、**思考链（Thinking）展开/折叠**、会话测试；
  - `SettingsScreen` 设置中心：模式切换、算法调整、Safe Mode 防御开关、远程中控节点一键测试连通性。
- **Git 提交**：
  - `47a51b8 feat(ui): 实现 Material 3 极简收敛风格的移动端交互体系`
  - `65c6805 feat(ui): 强化Material 3极简收敛UI体系，完善单模型冷却标签、思考链折叠与远程中控测连`

### 【阶段 5】：工业级质量审计与加固 (Phase 5: Quality Audit & Hardening)
- **核心目标**：彻底排查死代码与异常漏洞，确保移动端定长防爆与受管协程生命周期闭环。
- **交付成果**：
  - 协程生命周期规范化：去除 `GlobalScope.launch`，引入 `serverScope` 并在服务停止时彻底取消；
  - 防爆标准 JSON 错误格式：采用 `buildJsonObject` 替代非转义字符串拼装；
  - **定长环形防爆日志自愈**：每写入 20 条日志异步触发批量裁剪（最多保留 500 条），杜绝内存与存储颠簸；
  - 会话粘性定期垃圾回收清理。
- **Git 提交**：
  - `fa3ec09 refactor: 优化流式调度性能，完善受管协程生命周期与标准 JSON 错误格式`
  - `b71067a refactor: 全工程代码审计，优化环形日志定长防爆与会话粘性定期垃圾回收`

### 【阶段 6】：实时流媒体与语音中继能力 (Phase 6: WebRTC & Audio Relay)
- **核心目标**：对齐 CLIProxyAPI Codex Live 与 OpenAI Realtime 协议规范。
- **交付成果**：
  - `RealtimeModel.kt` 定义会话模型、SDP 协商载荷、临时凭据（`ek_...`）与 `oai-events` 事件；
  - `RealtimeRelayManager` 实现会话池管理、伴生信令互斥排他认领（Sideband Claim，防 409 冲突）、48kHz Opus 双声道 SDP Answer 生成；
  - `LocalProxyServer` 挂载 `client_secrets`、`calls`、`hangup` 路由端点；
  - `RealtimeVoiceConsole.kt` 极简拟物声波呼吸动效语音通话控制台、实时字幕流、静音与挂断控制。
- **Git 提交**：
  - `9ebea14 feat(realtime): 实现WebRTC实时音视频中继引擎、SDP协商与极简拟物语音控制台`

### 【阶段 7】：跨设备自动局域网发现与配对 (Phase 7: LAN mDNS Discovery)
- **核心目标**：消除手动敲打 IP 与端口的繁琐步骤，实现同网段节点自发现与一键配对。
- **交付成果**：
  - `LanDiscoveryModel.kt` 规范 mDNS 服务类型 `_cpaproxy._tcp` 与 TXT 扩展属性（`id`, `name`, `v`, `safe`）；
  - `NsdDiscoveryManager` 封装 Android `NsdManager`，管理广播注册、雷达扫描、异步解析与自节点过滤；
  - 仪表盘新增“mDNS 雷达卡片”与设备雷达弹窗；
  - 设置中心新增“同网已发现节点智能芯片”，**点击一秒自动填入远程 Base URL**。
- **Git 提交**：
  - `fad7b2b feat(discovery): 实现Android NSD/mDNS局域网服务广播与设备雷达自发现引擎`

### 【阶段 8】：动态 C-ABI 插件引擎 NDK 移植 (Phase 8: Native Plugin Host)
- **核心目标**：对齐 CLIProxyAPI 原生共享动态库（`.so`）插件扩展体系。
- **交付成果**：
  - `PluginManifest.kt` 规范化插件 ID、能力开关、架构支持与 JSON RPC 信封协议；
  - `NativePluginHost` 宿主管理器，支持 SHA-256 严苛完整性验真与 Android 安全代码目录管理；
  - **`GuardedPluginClient` 并发守卫与崩溃熔断护盾 (Circuit Breaker / Fuse)**：自动捕获未知 C/C++ 异常并置入 `FUSED_CRASHED` 态，**杜绝原生代码闪退主程序**；
  - `PluginManagerDialog.kt` 插件管理抽屉，支持启停切换、熔断重置、本地安装与官方示例插件载入。
- **Git 提交**：
  - `1b71f3c feat(plugin): 实现C-ABI动态插件宿主引擎、SHA256完整性验真、熔断护盾与插件管理面板`

---

## 三、 后续全量对齐进阶演进阶段 (Phases 9 ~ 12)

为了实现与 CLIProxyAPI 的 100% 功能完备性与技术对齐，规划以下进阶演进阶段：

### 【阶段 9】：全量管理端点闭环与动态 YAML 热重载 (Phase 9: Management API & Config YAML)
- **核心目标**：使 CPAphone 本地嵌入式网关完整对外暴露 `/v0/management/...` 全部 40+ 管理 API，支持原生 YAML 配置导出与在线覆盖热重载。
- **核心任务清单**：
  1. **配置格式双向转换与热重载 (`server_reload`)**：
     - 在 `:engine` 模块挂载 `/v0/management/config.yaml`（GET 读取 / PUT 覆盖上传）；
     - 支持将当前数据库凭据池与配置动态反序列化为原生标准 YAML，或解析上传的 YAML 原子重载本地内存与数据库。
  2. **原子字段配置端点补齐**：
     - 实现 `/v0/management/proxy-url`、`request-retry`、`max-retry-credentials`、`max-retry-interval` 等原子更新端点。
  3. **高级诊断与日志检索 API**：
     - 实现 `/v0/management/logs`（带游标与时间戳检索）、`/v0/management/api-call`（带 `$TOKEN$` 凭据代发测试工具）；
     - 实现 `/v0/management/usage-queue` 内存用量分桶流水弹出。
  4. **移动端托管 OAuth 会话轮询引擎**：
     - 实现 `/v0/management/get-auth-status` 与 `/v0/management/oauth-callback`，支持在手机后台静默完成 OAuth 状态交换。

### 【阶段 10】：动态 Payload 重写引擎与高级风控防御 (Phase 10: Dynamic Payload & Cloak Obfuscation)
- **核心目标**：对齐 CLIProxyAPI 的动态 Payload 重写能力与敏感词零宽字符插入防风控算法。
- **核心任务清单**：
  1. **动态 Payload 规则重写引擎 (`PayloadRuleEngine`)**：
     - 在 `:core` 模块实现基于 JSON 路径快速定位改写算法；
     - 完整支持 5 种操作模式（`default`, `default-raw`, `override`, `override-raw`, `filter`）；
     - 复合条件支持：`models`（模型正则通配符）、`protocol`、`headers`、字段匹配断言（`exist` / `not-exist` / `match`）。
  2. **敏感词零宽字符插入混淆 (`cloak_obfuscate`)**：
     - 针对 Antigravity 与 Claude 系统提示词，在敏感词首个字形后动态插入 `\u200B`（Unicode 零宽空格）；
     - 毫秒级多词正则编译，规避大模型服务商的字符串风控审查。
  3. **思考签名（Thinking Signature）首字节嗅探状态机**：
     - 识别 `'C'` (CAIS), `'E'` (Raw), `'R'` (Nested), `'g'` (Fernet) 首字节特征；
     - 实现签名缓存（16 字符短哈希索引，LRU 3 小时自动回收）与 Gemini 哨兵 Bypass 替换。

### 【阶段 11】：多后端存储适配与 Home 分布式集群模式 (Phase 11: Multi-Store & Home Cluster Mode)
- **核心目标**：提供企业级数据同步能力与多设备集群集中管理支持。
- **核心任务清单**：
  1. **PostgreSQL / GitStore / S3 适配模块**：
     - 在 `:data` 模块扩展远程存储适配器，支持凭据与配置的远程云端备份与双向同步。
  2. **Home 控制平面 RESP 客户端 (`engine/home/`)**：
     - 基于 Ktor Client CIO 实现 Redis RESP 协议与 mTLS 证书自签与自动轮换；
     - 实现分布式在途并发快照（`in-flight-snapshot`）与集中租约锁。

### 【阶段 12】：端到端全场景自动化验收与交付发布 (Phase 12: Release & E2E Validation)
- **核心目标**：工业级全功能回归、端到端自动化验证与正式版本发布。
- **核心任务清单**：
  1. **全链路回归测试套件**：
     - 编写从客户端请求、本地网关调度、协议转译、流式 SSE 重构到 UI 展现的集成测试；
     - 验证持续高并发长流式传输时的 GC 压力与内存占用（维持在 35MB~65MB 基线内）。
  2. **APK/AAB 混淆打包与发布**：
     - 验证 Proguard 混淆规则生效，产物体积精简，发布 CPAphone v2.0 正式交付版本。

---

## 四、 全量阶段交付跟踪与 Git 追溯矩阵 (Traceability Matrix)

| 阶段编号 | 状态 | 交付核心亮点 | 代码提交 Hash | 对应提交说明 |
| :---: | :---: | :--- | :---: | :--- |
| **阶段 0** | **已完成** | 业务规划书、技术设计书、`.gitignore` | `4f7cc1e` | `docs: 初始化项目规范，完成移动端双模功能与技术规划文档` |
| **阶段 1** | **已完成** | 纯 Kotlin 调度算法 (WRR/RR)、单模型冷却隔离、全双向转译、SSE帧重构 | `93c3c59`<br>`2fc5985` | `feat(core): 定义核心领域模型、协议契约与调度算法抽象`<br>`feat(core): 强化领域模型多维冷却、双向协议转译与流式SSE数据帧重构` |
| **阶段 2** | **已完成** | Room SQLite 数据库、Android Keystore 硬件级 AES-256 加密、DataStore | `dc69c4f`<br>`85ad829` | `feat(data): 实现基于 Room 与 Keystore 的本地持久化与安全配置引擎`<br>`feat(data): 升级凭据表结构支持局部冷却与OAuth，增强安全保险箱与配置项` |
| **阶段 3** | **已完成** | Ktor CIO 嵌入式网关、首包缓冲探测防御、前台保活服务与网络自愈 | `05d62fe`<br>`83164e9` | `feat(engine): 实现本地嵌入式代理服务与远程中控网络引擎`<br>`feat(engine): 升级网络引擎，实现跨协议流式重构转译、单模型熔断隔离与首包防御` |
| **阶段 4** | **已完成** | Material 3 极简收敛 5 大视图、单模型冷却微标签、思考链折叠组件 | `47a51b8`<br>`65c6805` | `feat(ui): 实现 Material 3 极简收敛风格的移动端交互体系`<br>`feat(ui): 强化Material 3极简收敛UI体系，完善单模型冷却标签、思考链折叠与远程中控测连` |
| **阶段 5** | **已完成** | 受管协程作用域、防爆标准 JSON 错误格式、定长日志环形防爆裁剪 | `fa3ec09`<br>`b71067a` | `refactor: 优化流式调度性能，完善受管协程生命周期与标准 JSON 错误格式`<br>`refactor: 全工程代码审计，优化环形日志定长防爆与会话粘性定期垃圾回收` |
| **阶段 6** | **已完成** | WebRTC 实时音视频中继、SDP 协商、临时凭据 `ek_...`、拟物声波控制台 | `9ebea14` | `feat(realtime): 实现WebRTC实时音视频中继引擎、SDP协商与极简拟物语音控制台` |
| **阶段 7** | **已完成** | Android NSD/mDNS 局域网服务广播、设备雷达自发现、Base URL 一键填入 | `fad7b2b` | `feat(discovery): 实现Android NSD/mDNS局域网服务广播与设备雷达自发现引擎` |
| **阶段 8** | **已完成** | C-ABI 动态插件 NDK 宿主、SHA-256 验真、并发守卫与熔断防崩护盾 | `1b71f3c` | `feat(plugin): 实现C-ABI动态插件宿主引擎、SHA256完整性验真、熔断护盾与插件管理面板` |
| **文档升级** | **已完成** | 全量功能深度审查后重构功能与技术规划文档（2.0 规格） | `5a27cad` | `docs: 全面重构功能与技术规划文档，100%对齐CLIProxyAPI全量功能清单与架构规范` |
| **阶段 9** | *待演进* | 40+ 管理端点闭环、动态 YAML 读写热重载、OAuth 会话静默托管 | *规划中* | 预计 Commit: `feat(management): 补齐全量管理API与YAML热重载` |
| **阶段 10** | *待演进* | 动态 Payload 重写引擎、零宽字符插入混淆、思考签名首字节嗅探 | *规划中* | 预计 Commit: `feat(rules): 实现动态Payload引擎与零宽字符风控混淆` |
| **阶段 11** | *待演进* | Postgres/Git/S3 云端备份适配、Home 集群 RESP 客户端与 mTLS 轮换 | *规划中* | 预计 Commit: `feat(cluster): 实现多后端存储与Home分布式控制平面客户端` |
| **阶段 12** | *待演进* | 全场景自动化端到端验收、性能基准压测与正式版本发布 | *规划中* | 预计 Commit: `release: CPAphone v2.0 正式交付版本` |
