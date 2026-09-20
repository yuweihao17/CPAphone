# CPAphone 功能规范规划书 (Functional Specification)

> **版本**：2.0.0-PROD (全面对齐 CLIProxyAPI 架构规范)  
> **定位**：CLIProxyAPI 移动端（Android 原生）智能路由代理网关与管理中心  
> **基准参考**：`D:\Projects\CLIProxyAPI` (Go 1.26 + Gin + Pion WebRTC + Charm TUI)  
> **设计哲学**：极简收敛、工业级质量、低延迟、低功耗、零死代码、高内聚低耦合、100% 功能完备

---

## 1. 产品定位与运行双模架构

CPAphone 是将服务器级高性能 AI 路由代理引擎 `CLIProxyAPI` 移植并深度适配至 Android 操作系统的现代化原生解决方案。它将服务器级多模型路由调度、多账号池治理、全双工跨协议转译、深度思考链（Thinking）签名保真、安全指纹伪装与 WebRTC 实时流媒体能力，浓缩至移动端轻量、丝滑、极低功耗的独立应用中。

CPAphone 确立为**双模架构（Dual-Mode Architecture）**：

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
   • 手机内部常驻前台服务 (Ktor CIO Server)               • 直连远端已部署的 CLIProxyAPI / CPAphone
   • 默认监听 127.0.0.1:8317，支持 0.0.0.0 局域网共享     • 采用安全 RESTful 与 WebSocket 双向通道
   • 承载全量 API 路由与矩阵转译，本地并发调度            • 完整的远程 YAML/JSON 配置读写热重载
   • 供 Termux、移动端开发工具、AI App 消费               • 账号池监控、远程日志检索、OAuth 会话托管
   • 移动端局域网 mDNS (NSD) 广播自动发现                 • 局域网设备雷达一键发现与配对
```

---

## 2. 全量 API 路由与接口规范清单

CPAphone 手机端本地嵌入式代理服务器（Ktor Server）与远程管理接口 100% 覆盖 CLIProxyAPI 的所有 API 端点：

### 2.1 核心大模型代理路由组 (`/v1`)
所有接口支持通过 `Authorization: Bearer <KEY>`、`x-api-key: <KEY>`（Claude 风格）、`x-goog-api-key: <KEY>`（Gemini 风格）或 Query 参数 `?key=<KEY>` 访问：

1. **`GET /v1/models`（智能感知统一模型聚合列表）**：
   - 客户端嗅探与按需返回：
     - 若请求携带 Grok-Shell 标识，返回 Grok 格式规范；
     - 若携带 `client_version` 查询参数，返回 Codex 专用客户端模型列表；
     - 若请求头表明为 Claude Code CLI，返回 Claude 原生模型列表；
     - 默认返回符合 OpenAI 规范的标准模型聚合字典。
2. **`POST /v1/chat/completions`（标准聊天补全接口）**：
   - 兼容普通模式与 SSE 流式数据帧输出（`stream=true`）；
   - 支持多模态图文输入（Base64 / URL）、工具调用（Tool Calls / Function Calling）、结构化输出（JSON Schema）；
   - 支持跨模型深度思考过程（`reasoning_content`）保真透传。
3. **`POST /v1/completions`（传统文本补全接口）**：
   - 适配单段 Prompt 补全场景。
4. **`POST /v1/messages` & `POST /v1/messages/count_tokens`（Anthropic Claude 原生端点）**：
   - 在 `/v1` 下无缝支持 Claude Code CLI、Aider 等原生 Anthropic 客户端直接接入，无需客户端配置复杂 Base URL 路径重写；
   - 支持 Claude 原生 Prompt Caching（提示词缓存）与 Token 计数。
5. **多模态生图与编辑端点**：
   - `POST /v1/images/generations`：图片生成端点；
   - `POST /v1/images/edits`：图片编辑端点；
   - 支持 `gpt-image-2` 专用生图工具协议与多模态转译直通。
6. **xAI 视频生成与轮询端点**：
   - `POST /v1/videos` & `POST /v1/videos/generations`：发起视频生成；
   - `POST /v1/videos/edits`：视频编辑；
   - `POST /v1/videos/extensions`：视频延长；
   - `GET /v1/videos/:request_id`：轮询查询视频生成任务状态与结果视频 URL。
7. **OpenAI 原生视频端点 (`/openai/v1`)**：
   - `POST /openai/v1/videos`：创建 Sora/OpenAI 视频生成任务；
   - `GET /openai/v1/videos/:video_id`：查询视频任务详情；
   - `GET /openai/v1/videos/:video_id/content`：获取已生成视频的二进制内容。
8. **OpenAI Responses API 端点**：
   - `GET /v1/responses`：Responses WebSocket 全双工长连接；
   - `POST /v1/responses`：Responses HTTP / SSE 协议；
   - `POST /v1/responses/compact`：会话历史上下文智能压缩。
9. **Codex 深度代码检索端点 (`/v1/alpha/search`)**：
   - Codex 内部专用深度代码检索端点，支持模型动态路由与 Payload 上下文净化。
10. **Codex 客户端兼容前缀路由 (`/backend-api/codex/...`)**：
    - `/backend-api/codex/responses`
    - `/backend-api/codex/responses/compact`
    - `/backend-api/codex/alpha/search`

---

### 2.2 实时语音与流媒体通信路由组 (`/v1/realtime` & `/v1/live`)
对齐 CLIProxyAPI Codex Live 与 OpenAI Realtime 协议规范：

1. **`POST /v1/realtime/client_secrets`**：
   - 签发短期临时客户端密钥（以 `ek_` 开头，支持设置 10 秒 ~ 2 小时 TTL），让移动端在无需暴露 Master API Key 的前提下安全建立实时连接。
2. **`POST /v1/realtime/calls` & `POST /v1/live`（WebRTC 通话建联）**：
   - 接收客户端 SDP Offer，调度 Codex / GPT-4o-Realtime 账号；
   - 完成 WebRTC SDP 协商（48kHz Opus 双声道、带内 FEC 开启），下发 SDP Answer（HTTP 201 Created）；
   - 在响应头注入 `Location: /v1/realtime/calls/:call_id`，标识媒体与信令通道。
3. **`GET /v1/realtime/calls/:call_id` & `GET /v1/live/:call_id`（伴生信令通道）**：
   - HTTP 升级为 WebSocket，对同一 Call ID 执行单连接排他认领（互斥防并发冲突，冲突报 409）；
   - 双向透传文本转录、Function Calling 与语音打断（Barge-In）事件。
4. **`GET /v1/realtime`（纯 WebSocket 直连模式）**：
   - 面向免 WebRTC 的轻量场景，直接通过 WebSocket 交互 JSON 事件与 Base64 音频帧；
   - 自动在建联时向上游发送 `session.update` 配置帧。
5. **`POST /v1/realtime/calls/:call_id/hangup`**：
   - 主动挂断指定通话，释放 WebRTC PeerConnection、关闭 DataChannel、回收伴生信令并释放账号调度锁。
6. **兼容端点**：
   - `POST /v1/realtime/sessions`：旧版兼容会话创建；
   - `POST /v1/realtime/transcription_sessions`、`/v1/realtime/translations`：实时翻译与转录扩展。

---

### 2.3 Google Gemini 原生路由组 (`/v1beta`)
1. **`GET /v1beta/models`**：获取 Google Gemini 原生模型列表。
2. **`POST /v1beta/models/*action`**：
   - 支持 `:generateContent`（阻塞生成）；
   - 支持 `:streamGenerateContent`（SSE 流式生成）；
   - 支持 `:countTokens`（Token 计数）。
3. **`GET /v1beta/models/*action`**：单模型详情查询。
4. **`POST /v1beta/interactions`**：Google DeepMind 原生交互协议接口。

---

### 2.4 OAuth 顶级回调路由组
在网关主端口上直接监听各大厂商的 OAuth 重定向回调：
- `GET /anthropic/callback`：Anthropic Claude 授权回调；
- `GET /codex/callback`：OpenAI Codex 授权回调；
- `GET /antigravity/callback`：Google Antigravity 授权回调。

---

### 2.5 全量远程管理 API 路由组 (`/v0/management/...`)
支持通过管理密钥认证（`Authorization: Bearer <secret-key>` 或 `X-Management-Key: <secret-key>`）：

| 分类 | 接口路径与方法 | 业务功能说明 |
| :--- | :--- | :--- |
| **配置读写** | `GET /v0/management/config` | 获取全量运行期配置 JSON（包含敏感凭据脱敏数据） |
| | `GET /v0/management/config.yaml` | 读取原始 YAML 配置文件文本 |
| | `PUT /v0/management/config.yaml` | 覆盖上传完整 YAML 文件并立即触发运行时热重载 |
| | `GET /v0/management/latest-version` | 获取管理端前端静态面板的最新发布版本号 |
| **原子字段配置** | `GET/PUT/PATCH /v0/management/debug` | 切换调试模式 |
| | `GET/PUT/PATCH /v0/management/logging-to-file` | 切换日志落盘文件开关 |
| | `GET/PUT/PATCH /v0/management/logs-max-total-size-mb` | 动态调整日志目录最大容量上限（MB） |
| | `GET/PUT/PATCH /v0/management/error-logs-max-files` | 调整错误日志最大保留文件数 |
| | `GET/PUT/PATCH /v0/management/usage-statistics-enabled` | 动态切换使用量分桶统计 |
| | `GET/PUT/PATCH/DELETE /v0/management/proxy-url` | 查看、修改或清除出网代理（HTTP/HTTPS/SOCKS5） |
| | `GET/PUT/PATCH /v0/management/ws-auth` | 切换 WebSocket 连接是否强制鉴权 |
| | `GET/PUT/PATCH /v0/management/request-retry` | 动态修改凭据调度失败最大重试轮次 |
| | `GET/PUT/PATCH /v0/management/max-retry-credentials` | 动态修改单轮重试最大尝试凭据数量 |
| | `GET/PUT/PATCH /v0/management/max-retry-interval` | 调整重试轮次间允许的最大冷却等待秒数 |
| | `GET/PUT/PATCH /v0/management/routing/strategy` | 动态切换调度算法（`round-robin` / `weighted` / `fill-first`） |
| **容灾与配额** | `GET/PUT/PATCH /v0/management/quota-exceeded/switch-project` | 配额超限自动切换关联项目开关 |
| | `GET/PUT/PATCH /v0/management/quota-exceeded/switch-preview-model` | 配额超限自动降级预览版模型开关 |
| | `POST /v0/management/reset-quota` | 主动重置指定凭据（`auth_index`）的冷却状态与配额封锁 |
| **凭据池管理** | `GET /v0/management/auth-files` | 查询所有已载入凭据（支持按名称/索引过滤，含使用量与配额详情） |
| | `GET /v0/management/auth-files/models?name=xxx` | 查询特定凭据支持的模型规格字典 |
| | `GET /v0/management/auth-files/download?name=xxx` | 导出/下载指定凭据的原始 JSON 文件 |
| | `POST /v0/management/auth-files` | 新增凭据（支持文件批量上传或 JSON Body 上传） |
| | `DELETE /v0/management/auth-files` | 删除凭据（支持单个删除、批量删除或 `?all=true` 一键清空） |
| | `PATCH /v0/management/auth-files/status` | 启用或禁用特定凭据（`disabled: true|false`） |
| | `PATCH /v0/management/auth-files/fields` | 细粒度修改凭据字段（权重、优先级、备注、请求头等） |
| | `POST /v0/management/vertex/import` | 导入 GCP Vertex AI 服务账号 JSON 私钥凭据 |
| **移动端 OAuth** | `GET /v0/management/anthropic-auth-url` | 生成 Claude OAuth 授权链接并建立待办会话 |
| | `GET /v0/management/codex-auth-url` | 生成 Codex OAuth 授权链接（带 PKCE 验真） |
| | `GET /v0/management/antigravity-auth-url` | 生成 Google Antigravity 授权链接 |
| | `GET /v0/management/kimi-auth-url` | 生成 Moonshot Kimi 授权链接 |
| | `GET /v0/management/xai-auth-url` | 生成 xAI Grok 授权链接 |
| | `GET /v0/management/get-auth-status?state=xxx` | 轮询 OAuth 授权进度（返回 `pending`, `ok`, `error`） |
| | `DELETE /v0/management/oauth-session?state=xxx` | 取消进行中的 OAuth 授权会话 |
| | `POST/GET /v0/management/oauth-callback` | 通用 OAuth 重定向回调中继接收器 |
| **审计与诊断** | `GET /v0/management/api-key-usage` | 查看内存中各 API Key 凭据的请求成功/失败数与分桶统计 |
| | `GET /v0/management/usage-queue?count=N` | 批量弹出最新的请求使用量流水记录 |
| | `POST /v0/management/api-call` | 网关内代发 HTTP 请求测试工具（支持 `$TOKEN$` 变量自动填充） |
| | `GET /v0/management/logs` | 实时流式或分页检索系统审计日志（支持游标与时间戳） |
| | `DELETE /v0/management/logs` | 清空已归档的日志文件并截断当前活跃日志 |
| | `GET /v0/management/request-error-logs` | 获取请求错误详情日志文件列表 |
| | `GET /v0/management/request-error-logs/:name` | 下载指定错误日志文件 |
| | `GET /v0/management/request-log-by-id/:id` | 根据 Trace UUID 精准提取该请求的全链路报文与日志 |
| | `GET /v0/management/model-definitions/:channel`| 获取指定渠道内置的静态模型定义列表 |
| **动态插件市场** | `GET /v0/management/plugins` | 获取已加载 C-ABI 原生动态插件列表、状态与配置项 |
| | `GET /v0/management/plugin-store` | 浏览官方与第三方插件市场在线目录 |
| | `POST /v0/management/plugin-store/:id/install` | 在线下载目标架构动态库并校验 SHA256 安全安装 |
| | `PATCH /v0/management/plugins/:id/enabled` | 动态启用或停用指定插件 |
| | `GET/PUT/PATCH /v0/management/plugins/:id/config` | 查看或更新特定插件的私有配置 |
| | `DELETE /v0/management/plugins/:id` | 彻底卸载指定插件并清理本地文件 |

---

## 3. 多厂商凭据池与生命周期治理

### 3.1 覆盖厂商全景
CPAphone 完整覆盖 8 大厂商提供商凭据：
1. **OpenAI Codex**：支持 ChatGPT Plus/Pro/Team OAuth 凭据及原生 API Key，支持 MultiAgent V2 与 Alpha Search；
2. **Anthropic Claude**：支持 Claude Code CLI OAuth 授权及原生 `sk-ant-api...`，支持自适应思考与 Prompt Caching；
3. **Google Gemini**：支持 Google AI Studio 原生 API Key，支持 Gemini 2.x/3.x 原生多模态；
4. **Google Antigravity**：支持 Google Cloud/Google One 账号体系，具备 Google One 商业点数自动切换与防风控零宽字符插入；
5. **Google Cloud Vertex AI**：支持企业级 Service Account JSON 私钥导入，自动换取 GCP Bearer Token；
6. **xAI Grok**：支持 xAI OAuth 登录与 API Key，自动注入 `x_search` 工具并支持 Grok 原生视频生成；
7. **Moonshot Kimi**：支持 Kimi K3 / K2.7 OAuth 与 API Key，支持百万上下文与思考块；
8. **OpenAI-Compatible**：支持接入 DeepSeek、SiliconFlow、OpenRouter、GLM、Qwen 等任意兼容端点，支持聚合模型池与 Alias 重映射。

### 3.2 凭据生命周期与健康状态机
每个凭据维护明确的状态机流转：
- **`ACTIVE`（健康活跃）**：凭据未过期且未冷却，优先参与流量调度；
- **`COOLDOWN`（熔断冷却）**：遭遇限流或服务过载，处于倒计时隔离状态，到期自动探活恢复；
- **`EXPIRED`（凭据失效）**：OAuth Token 刷新失败或被官方作废，UI 标红警示并提示一键重新授权；
- **`DISABLED`（手动挂起）**：用户主动停用，不参与流量分发。

---

## 4. 流量调度、高级容灾与动态 Payload 规则

### 4.1 负载均衡算法
- **平滑加权轮询（Smooth Weighted Round-Robin, 默认）**：Nginx 同款算法，根据每个账号配置的权重（1~100）均匀交错调度，杜绝短时间内打满单号；
- **普通轮询（Round-Robin）**：无锁原子计数器平铺分发；
- **深度优先（Fill-First）**：优先使用单号直至限流，降低多号并发暴露特征。

### 4.2 全局会话粘性 (Universal Session Affinity)
- 提取 `X-Claude-Code-Session-Id`、`session_id`、`prompt_cache_key` 或请求哈希特征建立粘性映射；
- 默认维持 3600 秒（支持自定义 TTL），会话期内锁定同一凭据，**最大化利用上游官方 Prompt Cache，大幅降低 Token 成本与首字时延（TTFT）**；
- 若绑定的凭据进入冷却，自动平滑漂移至同厂商下一可用凭据并刷新会话绑定。

### 4.3 分轮重试与单模型局部冷却隔离 (Per-Model Cooldown)
- **多轮重试体系**：Round 0 为初次请求，失败后在当前 HTTP 连接未关闭前进入 Round 1..N 重试，最多重试 `request-retry` 轮（默认 3 次），每轮受 `max-retry-credentials` 保护；
- **局部模型冷却隔离**：
  - 上游返回 429 限流或单模型过载时，**仅将该凭据下的特定模型（如 `claude-3-7-sonnet`）置入冷却**，同一凭据下的其他健康模型（如 `claude-3-5-haiku`）依然活跃可用！
  - 仅在遇到 401/403 等账号级鉴权错误时，触发账号全局冷却；
- **持久化冷却记录**：支持保存至本地 `.cds` 文件，应用重启不丢失冷却记录。

### 4.4 配额耗尽降级策略 (`quota-exceeded`)
- **`switch-project`**：当 GCP/Vertex 项目配额用尽时，自动切换到该凭据绑定的备用项目；
- **`switch-preview-model`**：当正式版模型配额耗尽，自动将请求转译下发给对应的 Preview 预览模型兜底；
- **`antigravity-credits`**：免费额度耗尽后，自动激活 Google One AI 商业点数凭据作为最后保底防线。

### 4.5 动态 Payload 参数修改规则引擎 (`payload`)
支持基于 gjson/sjson 语法的动态报文重写规则：
- **5 种操作模式**：
  - `default`：当字段不存在时，设置默认值（支持类型推导）；
  - `default-raw`：设置未经转换的原始 JSON 片段作为默认值；
  - `override`：强制覆盖字段值；
  - `override-raw`：强制覆盖原始 JSON 片段；
  - `filter`：剔除指定 JSON 路径字段（如过滤不支持的特定参数）。
- **复合触发条件**：支持指定适用模型正则（`models`）、目标协议（`protocol`）、来源协议（`from-protocol`）、请求头匹配（`headers`）、字段匹配（`match` / `not-match` / `exist` / `not-exist`）。

---

## 5. 跨协议矩阵转译与深度思考链保真

### 5.1 全矩阵双向协议转译
覆盖 `OpenAI`、`OpenAI-Response`、`Claude`、`Gemini`、`Codex`、`Antigravity`、`Interactions` 七大格式的网状全互转：
- **输入请求转译**：智能解析客户端输入的 System 提示词、多模态图文、Tool Calls、Tool Results 并映射为目标厂商标准格式；
- **流式 SSE 帧实时重构 (`StreamChunkTranslator`)**：逐行拦截上游流式事件（如 Claude `content_block_delta`），实时重构为下游客户端要求的标准 OpenAI `chat.completion.chunk`，包括将思考块转译为 `reasoning_content`；
- **Token 计数规范对齐**：统一换算各厂商返回的 Prompt / Completion Tokens。

### 5.2 思考签名（Thinking Signature）特征嗅探与跨模型保真
解决携带思考过程（Reasoning/Thinking）在多轮对话回传时被官方严格校验防作弊的难题：
- **超低开销首字节签名提供商嗅探**：
  - `'C'` (0x08..0x0b)：识别为 Claude CAIS 签名 Envelope；
  - `'E'` (0x10..0x13)：识别为 Claude 单层签名或 Gemini Protobuf 签名；
  - `'R'` (0x44..0x47)：识别为 Claude 双层嵌套签名；
  - `'g'` (0x80..0x83)：识别为 GPT Fernet 加密 Reasoning 块；
  - `kimi` / `grok`：基于高熵特征识别。
- **跨厂商签名决策策略**：
  - `preserve`：目标厂商一致，无损原样保留签名；
  - `drop_signature`：目标厂商允许纯思考内容，安全剥离加密签名；
  - `drop_block`：目标厂商不支持思考回传，优雅剥离整个思考块；
  - `replace_with_gemini_bypass`：自动注入 Gemini 哨兵 Bypass 签名绕过上游校验。
- **思考签名高速缓存**：对思考文本计算 SHA-256 建立 16 位短哈希索引，存入 LRU 缓存，默认 TTL 3 小时，自动回收。

### 5.3 四种思考模式与模型后缀语法
- `ModeBudget`：数值型 Token 预算（如 `claude-3-7-sonnet(8192)`）；
- `ModeLevel`：离散思考级别（如 `gemini-2.5-pro:high`，支持 `minimal`, `low`, `medium`, `high`, `xhigh`, `max`）；
- `ModeNone`：强制关闭思考（如 `(none)`）；
- `ModeAuto`：自适应自动思考（如 `(auto)`）。

---

## 6. 客户端安全伪装、请求披风与风控防御

### 6.1 Claude Code 深度请求披风 (Cloak Mode)
- **官方指纹注入**：自动伪装官方 CLI User-Agent（如 `claude-cli/1.0.32 (darwin; arm64)`）及版本头；
- **系统提示词动态重排与注入**：根据官方规范，将自定义 System 提示词移至历史消息末端或特定位置，注入官方白名单引导词；
- **指纹稳定化**：模拟官方 BIP39 随机词库生成设备指纹，注入 `user_id` 与 `session_id`；
- **uTLS 协议指纹模拟**：模拟标准操作系统 TLS Client Hello，规避特定 WAF 网关指纹识别。

### 6.2 Codex 身份混淆 (`identity-confuse`)
- 动态扰乱 Prompt Cache Key 与客户端设备指纹，防止多账号在同一 IP 下因相同特征引发官方风控。

### 6.3 敏感词零宽字符插入混淆 (`cloak_obfuscate`)
- 针对 Antigravity 与 Claude 的风控词审查机制；
- 配置敏感词库后，系统在敏感词首个字形（Grapheme）后动态插入 `\u200B`（Unicode 零宽空格），在不改变人类肉眼阅读与大模型语义理解的前提下，完美规避字符串硬匹配风控。

### 6.4 首包缓冲防御 (Stream Bootstrap Buffering)
- 预读流式首个数据包（4KB 缓冲），拦截上游“伪装成 HTTP 200”但流中抛出的 `server_is_overloaded` 或配额不足错误；
- 命中隐式错误时，在客户端完全无感知的情况下，直接在当前连接内切换下一凭据透明重试。

### 6.5 安全防御模式 (Safe Mode)
- 启动时自动嗅探，若检测到使用了官方配置模板中的默认弱密钥（如 `your-api-key-1`）或开启局域网共享但未配置访问鉴权，强制阻断代理请求并返回标准 403 JSON，杜绝公网弱口令暴露风险。

---

## 7. 动态 C-ABI 插件宿主引擎

支持通过动态共享库（`.so`）为 CPAphone 注入第三方模型执行器与拦截器：
- **C-ABI 接口标准**：对齐 `cliproxy_plugin_init` 与 JSON RPC 信封协议（`PluginRpcEnvelope`）；
- **全生命周期能力**：支持扩展 ModelProvider、Executor、RequestInterceptor、StreamInterceptor 与 ThinkingApplier；
- **SHA256 完整性验真**：从插件市场下载或本地导入时，严格校验 SHA256 哈希，哈希不匹配立即终止；
- **防 Zip-Slip 路径逃逸**：规范化解压至私有安全代码目录（`codeCacheDir/plugins/<id>/`）；
- **并发计数与熔断防崩护盾 (Circuit Breaker / Fuse)**：捕获插件调用异常，一旦出现未知严重崩溃，自动将插件置入 `FUSED_CRASHED` 熔断保护态并从路由中摘除，**彻底杜绝第三方原生代码导致手机主程序闪退**。

---

## 8. 持久化存储与多节点集群机制

- **本地存储（默认）**：基于 SQLite / Room 数据库与 JSON 单文件凭据存储，提供单文件导入与全量备份导出；
- **硬件级安全保险箱 (Secure Vault)**：基于 **Android Keystore** 与 **AES-256-GCM**，硬件级隔离加密存储 API Key、OAuth Refresh Token 与 GCP 服务账号私钥；
- **多后端兼容扩展**：
  - **PostgreSQL 数据库同步**：支持连接远端 DB 并通过本地 Spool 工作区镜像同步；
  - **Git 版本化持久化**：内置版本管理，凭据变更自动 Commit & Push；
  - **S3 / MinIO 对象存储**：远程云端备份；
  - **Home 控制平面集群模式**：通过 Redis RESP 协议与 mTLS 证书轮换接入分布式 Home 控制中枢，实现多设备凭据同步与集中租约控制。

---

## 9. 移动端特色能力与后台保活规范

1. **常驻前台服务 (Foreground Service)**：
   - 绑定常驻通知栏，实时展示服务运行状态、监听地址端口与处理请求数，提供“一键停止”快捷操作；
   - 申请 `Partial WakeLock`，防止 Android 深度休眠断开网络长连接；
   - 提供直观的“系统电池优化白名单”一键跳转指引。
2. **网络漫游自愈与状态自适应**：
   - 注册 `ConnectivityManager.NetworkCallback`，实时感知 Wi-Fi / 移动蜂窝网络切换，动态刷新可用局域网 IP 与监听状态。
3. **局域网 mDNS (NSD) 服务广播与设备雷达**：
   - 本地代理启动时自动注册 `_cpaproxy._tcp` 广播本机服务；
   - 内置局域网雷达，自动发现同网段运行中的 CLIProxyAPI PC 节点和其他 CPAphone 手机实例，一键自动填入远程 Base URL，免除键盘手动输入。
4. **定长环形防爆日志自愈**：
   - 移动端 SQLite 请求日志采用定长 500 条环形缓冲区（Ring Buffer）策略，每 20 次请求自动触发批量异步裁剪，内存严格控制在 35MB~65MB 安全基准内。

---

## 10. Material 3 极简收敛 UI/UX 体系

全界面由 **Jetpack Compose + Material 3** 驱动，采用 MVI 响应式单向数据流，包含 5 大核心收敛界面：

1. **运行仪表盘 (Dashboard)**：
   - 动态呼吸状态指示灯、运行形态切换标签；
   - 客户端接入 Base URL 展示与一键复制；
   - 极简指标矩阵：网络模式、会话保持、Safe Mode 防御、指纹伪装指示；
   - 局域网 mDNS 发现雷达卡片与弹窗列表。
2. **凭据池治理中心 (Auth Pool)**：
   - 按厂商展示凭据卡片流，包含提供商徽标、认证形态标签、权重与成功率；
   - **单模型局部冷却微标签**：直观展示具体限流模型与冷却倒计时；
   - 一键测活与重置冷却按钮；
   - 极简添加/编辑抽屉，支持设置别名、厂商、Key/Token、权重、自定义 Base URL。
3. **实时链路追踪瀑布流 (Live Traces)**：
   - 瀑布流展示请求流水：时间戳、HTTP 动词、请求模型、命中凭据别名、响应码、耗时、TTFT 首字延迟；
   - **内联平滑折叠区**：点击卡片使用 `AnimatedVisibility` 优雅展开 Trace ID、完整路径、入站协议、传输形态与错误堆栈；
   - 一键清空审计日志。
4. **AI 调试沙盒 (Playground)**：
   - 极简对话面板，无需配置第三方客户端直接验证网关表现；
   - 预置主流模型切换器（Claude 3.7 / GPT-4o / o1 / o3-mini / Gemini 2.0 / DeepSeek Reasoner）；
   - **深度思考链（Thinking）折叠组件**：带有紫色微芯片标签，点击平滑展开完整思维过程；
   - **实时语音通话控制台 (Voice Console)**：拟物声波呼吸动效、低延迟字幕流、静音与一键挂断。
5. **系统与网络设置 (Settings)**：
   - 运行模式热切换（本地独立代理 / 远程中控管理）；
   - 负载均衡调度策略选择（平滑加权 / 轮询 / 深度优先）；
   - 局域网访问授权、Safe Mode 防御开关、请求披风伪装开关；
   - 远程节点连接配置，支持点击局域网已发现节点一键填入，一键测试连通性；
   - 动态 C-ABI 插件管理抽屉入口（支持插件启停、熔断重置、本地安装与示例插件载入）。
