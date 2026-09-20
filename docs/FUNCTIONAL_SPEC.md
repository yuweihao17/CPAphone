# CPAphone 功能规范规划书 (Functional Specification)

> **版本**：1.0.0-PROD  
> **定位**：CLIProxyAPI 移动端（Android 原生）智能路由代理网关与管理中心  
> **基准参考**：`D:\Projects\CLIProxyAPI` (Go 1.26 + Gin + Pion WebRTC + Charm TUI)  
> **设计哲学**：极简收敛、工业级质量、低延迟、低功耗、零死代码、高内聚低耦合

---

## 1. 产品定位与核心运行形态

CPAphone 是将工业级 AI 路由代理引擎 `CLIProxyAPI` 移植并深度适配至 Android 操作系统的现代化原生解决方案。为满足移动设备在个人日常、移动办公以及专业开发者多场景下的使用诉求，CPAphone 确立为**双模架构（Dual-Mode Architecture）**：

```
                           ┌────────────────────────────────────────┐
                           │            CPAphone Android            │
                           └───────────────────┬────────────────────┘
                                               │
                    ┌──────────────────────────┴──────────────────────────┐
                    ▼                                                     ▼
      【模式一：本地代理网关模式】                          【模式二：远程中控管理模式】
    (Local Standalone Proxy Engine)                     (Remote Management Console)
                    │                                                     │
   • 手机内部常驻前台服务 (Ktor Server)                   • 对接远端已部署的 CLIProxyAPI 实例
   • 监听 127.0.0.1:8317 或 局域网广播                   • 安全 RESTful 与 WebSocket 实时通道
   • 提供完整多协议中转、多账号调度与转译                 • 远程配置编辑、账号池监控、实时日志
   • 供 Termux、移动端开发工具、AI App 消费               • 跨设备一键触发移动端 OAuth 授权
```

---

## 2. 核心功能全景矩阵

| 功能模块 | 对应 CLIProxyAPI 能力 | CPAphone 移动端落地规范 |
| :--- | :--- | :--- |
| **多厂商凭据池管理** | `auths/*.json`, `*-api-key` | 支持分类管理 OpenAI Codex、Claude、Gemini、Antigravity、Vertex AI、xAI Grok、Kimi、OpenAI-Compatible 凭据；直观卡片呈现健康度、剩余额度、冷却状态与权重 |
| **移动端 OAuth 授权** | CLI `-codex-login`, `-claude-login` | 利用 Android Chrome Custom Tabs 与 Deep Link 协议回调，在手机端一键拉起官方登录完成授权并无缝回传 Token |
| **多算法负载均衡** | `round-robin`, `weighted`, `fill-first` | 纯 Kotlin 实现三种调度策略，毫秒级无锁原子分发，支持单账号加权与就近选优 |
| **会话粘性优化** | Universal Session Affinity | 自动提取 `X-Claude-Code-Session-Id`、`session_id`、`prompt_cache_key`，会话期内锁定单号以最大化命中官方 Prompt Cache |
| **智能熔断与自动冷却** | Cooldown Manager, `.cds` 持久化 | 遭遇 403/429/5xx 时毫秒级将故障号打入冷却队列，并在当前请求上下文中直接透明切换下一可用凭据重试，保障调用零中断 |
| **双向全矩阵协议转译** | `internal/translator` | 客户端发起 OpenAI / Claude / Gemini 任意协议，网关按需转译为目标提供商专用协议（支持流式 SSE、工具调用与多模态） |
| **思考过程保真与签名** | `internal/thinking`, `signature` | 支持 Claude Thinking、OpenAI Reasoning 块的结构化解析、签名缓存、防篡改校验与跨模型优雅降级 |
| **安全伪装与请求披风** | `claude-cloak`, `codex identity-confuse` | 模拟官方 CLI 的 User-Agent、Header 拓扑顺序、CCH 签名注入与安全白名单混淆 |
| **首包缓冲防御** | Stream Bootstrap Buffering | 缓冲流式初始数据包，拦截上游“假 200”内部异常并自动重试，确保下游收到的流绝对有效 |
| **移动端安全防御** | `safemode` 机制 | 启动时检测默认弱密钥或空密钥暴露，强制启用安全隔离模式，杜绝局域网未授权滥用 |
| **实时日志与 Trace 追踪**| Request Logging, CPA Trace | 内存定长环形缓冲区（Ring Buffer）实现流式请求记录，呈现耗时、TTFT（首字延迟）、状态码与错误详情 |
| **极简 AI 调试台** | 管理面板测试能力 | 内置极简流式对话控制台，支持快速切换模型、参数调优、展开思考过程，实时验证网关表现 |
| **前台保活与状态常驻** | 守护进程与系统服务 | 采用 Android 前台服务（Foreground Service）配合动态常驻通知栏，显示当前状态、QPS、端口及快捷开关 |

---

## 3. 功能模块详细设计规范

### 3.1 凭据池治理系统 (Credential Governance)
1. **多厂商统一凭据抽象**：
   - 凭据类型覆盖：`OpenAI OAuth (Codex)`, `Claude OAuth`, `Gemini API Key`, `Google Antigravity OAuth`, `Vertex AI Service Account`, `xAI OAuth / API Key`, `Moonshot Kimi OAuth / API Key`, `OpenAI Compatible Provider`。
   - 字段支持：唯一别名、账号前缀（Prefix）、权重（Weight, 1~100）、自定义 BaseURL、请求头覆盖、关联模型别名。
2. **生命周期状态机**：
   - **Active (健康活跃)**：就绪并可承载流量调度。
   - **Cooldown (冷却熔断)**：遭遇限流或过载，处于冷却倒计时，倒计时结束自动探活恢复。
   - **Expired (凭据过期)**：Token 过期且自动刷新失败，标红警示并提示一键重新授权。
   - **Disabled (手动禁用)**：用户主动挂起，不参与流量分发。
3. **移动端 OAuth 授权闭环**：
   - 支持 PKCE 机制，通过系统浏览器 Custom Tabs 打开授权地址。
   - 应用注册自定义 Scheme（如 `cpaphone://auth/callback`）拦截重定向，自动交换 `access_token` 与 `refresh_token`，无需用户手工复制粘贴。

### 3.2 流量调度与容灾引擎 (Routing & Fault Tolerance)
1. **调度策略配置**：
   - **轮询 (Round-Robin)**：请求在所有可用活跃凭据之间均匀分布。
   - **加权轮询 (Weighted Round-Robin)**：基于平滑加权轮询算法（Smooth WRR），高权重账号承担更多流量。
   - **深度优先 (Fill-First)**：单账号未达限流前持续使用，降低多账号并发暴露特征。
2. **会话粘性 (Session Affinity)**：
   - 识别请求头中的会话标识或对请求上下文计算指纹，维护 `Session -> Credential` 映射表，默认维持 3600 秒（支持自定义 TTL）。
   - 当绑定的凭据进入冷却时，立即平滑迁移至同厂商下一可用凭据并刷新绑定关系。
3. **透明重试 (Transparent Retry)**：
   - 上游返回可重试状态码（如 429 Too Many Requests, 503 Service Unavailable, 408 Request Timeout）或流首包包含指定错误字符串时，网关在当前 HTTP 连接保持打开的情况下，立即切换下一个凭据发起请求，直至达到最大重试上限（默认 3 次）。

### 3.3 协议矩阵转译规范 (Protocol Translator Matrix)
1. **转译覆盖度**：
   - **输入支持**：
     - OpenAI Chat Completions 规范 (`/v1/chat/completions`)
     - Anthropic Messages 规范 (`/v1/messages`)
     - Google Gemini 规范 (`/v1beta/models/*:generateContent`)
   - **目标模型适配**：
     - 将 OpenAI 格式转译为 Claude Messages（含 system 提示词提取、工具参数转换）；
     - 将 Claude/OpenAI 格式转译为 Google Gemini Parts / Contents 结构；
     - 双向互转 Tools / Function Calling 结构与调用结果。
2. **Thinking / Reasoning 结构转换**：
   - 解析 Claude 思考块 `<thinking>...</thinking>` 与官方 `thinking` 类型对象；
   - 兼容 DeepSeek / OpenAI 的 `reasoning_content`；
   - 在转译为不支持思考的目标模型时，自动提供“剥离”或“作为普通文本前置注入”的策略选项。

### 3.4 极简收敛用户界面设计 (Minimalist UI/UX)
1. **设计调性**：
   - 遵循 Material 3（Material You）设计准则，色彩收敛，留白得当。
   - 禁用繁复的渐变堆叠与杂乱无章的图表，以高对比度、清晰排版和流畅微交互为核心。
2. **核心导航框架（5 个收敛视图）**：
   - **视图 1：仪表盘 (Dashboard)**
     - 状态总览：本地网关运行状态卡片、监听端口、已运行时间、快捷启停开关。
     - 极简指标：今日请求总量、成功率、当前并发量、平均首字延迟（TTFT）。
     - 快捷动作：局域网连接二维码、一键复制本地 Base URL。
   - **视图 2：凭据中心 (Auth Pool)**
     - 按厂商分组折叠的卡片流，直观展示各账号状态徽标。
     - 单击卡片弹出底部抽屉（BottomSheet），快速查看剩余额度、修改权重或手动触发测活。
     - 右上角“+”号极简添加向导，支持扫码导入、OAuth 授权、手动录入。
   - **视图 3：实时追踪 (Live Traces)**
     - 请求瀑布流：显示时间、客户端类型、目标模型、命中凭据、耗时、状态码。
     - 点击展开详细审计：查看请求头、转译前后的模型、响应首包耗时与异常日志。
   - **视图 4：测试沙盒 (Playground)**
     - 极简单聊测试面板，无需配置第三方工具即可验证代理链路。
     - 支持模型选择器、流式输出、思考过程优雅展开折叠、Token 输出统计。
   - **视图 5：系统设置 (Settings)**
     - 运行模式切换（本地独立代理 / 远程管理中控）。
     - 本地网络配置（绑定端口、局域网访问授权、安全防御 Safe Mode 开关）。
     - 远程实例连接配置（Host, Secret Key, 证书信任）。
     - 数据备份与导出导入。

### 3.5 移动端后台保活与稳定性规范
1. **前台服务（Foreground Service）机制**：
   - 绑定常驻通知栏，通知提供：服务当前状态、端口号、已处理请求数、快捷“停止服务”Action。
2. **电量与系统唤醒锁（Wakelock）管理**：
   - 代理运行期间申请局部唤醒锁（`PARTIAL_WAKE_LOCK`），防止系统深度休眠导致网络连接被强制掐断。
   - 提供明确的用户引导界面，一键跳转系统设置将 CPAphone 加入“电池优化白名单”（忽略电池优化）。
3. **网络变化与 Wi-Fi 漫游自愈**：
   - 监听网络状态变更（Wi-Fi / 移动蜂窝切换），自动平滑重建本地服务监听并上报可用 IP 变动。

---

## 4. 异常处理与移动端安全防御规范

1. **Safe Mode 移动端移植**：
   - 当检测到使用默认测试 API Key 或开启了局域网访问但未设置访问鉴权时，系统自动切换至只读告警模式，阻断非本机回环（Non-Loopback）请求，并在 UI 显著位置发出安全配置提醒。
2. **内存定长环形防爆保护**：
   - 移动设备内存资源有限，严禁无限制追加日志列表。Trace 记录采用定长 500 条环形缓冲区（Ring Buffer），超出自动覆盖最老记录，内存占用严格控制在安全阈值（< 20MB）内。
3. **敏感凭据脱敏呈现**：
   - 界面上所有 API Key、OAuth Refresh Token 均执行星号脱敏（如 `sk-ant-api...a1b2`），只有通过生物识别（指纹/人脸）验证后方可复制或显式查看。
