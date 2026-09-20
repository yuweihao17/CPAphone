# OAuth 一键登录功能实施计划（对齐 CLIProxyAPI / CPAMC 图示功能）

## Summary

在 CPAphone 凭据池界面新增"OAuth 快捷登录"区块：5 张服务商登录卡片（Claude / Codex / Antigravity / Kimi / xAI），点击"开始登录"→ 生成真实 PKCE 授权 URL（或设备码）→ 系统浏览器授权 → 回调到本机内嵌 Ktor Server 官方注册端口 → 自动兑换 token 并加密保存为凭据。同时实现 access_token 过期前用 refresh_token 自动续期。CI 编译通过后发布 v1.1.0 Release。

## Current State Analysis

### CPAphone 现状（可复用 + 缺口）

**可复用基础设施：**
- `core/session/OAuthSessionManager.kt`：state 会话池、10 分钟超时、poll/cancel 生命周期骨架
- `engine/server/ManagementRoutes.kt`：已有 5 个 `{provider}-auth-url` 端点、`get-auth-status`、POST `oauth-callback`、DELETE `oauth-session`（L131-179），但均为占位实现
- `engine/server/LocalProxyServer.kt`：Ktor CIO 监听 `127.0.0.1:8317`，本机浏览器回环可达；路由挂载简单
- `data/security/SecureCredentialStorage.kt`：`saveSecret`/`getSecret` + **refreshToken 槽位已预留但无人调用**（`refresh_$credentialId`）
- `engine/client/UpstreamHttpClient.kt` `injectAuthHeaders`（L193）：OAuth token 存入 secret 槽后请求侧零改动生效
- `data/repository/CredentialRepository.kt`：`saveCredential(cred, secret)`、`updateExpiresAt`、`getSecretKey` 链路完整
- `AndroidManifest.xml` L34-43：deep link 声明存在但为空壳（本方案不使用 deep link，不改）

**核心缺口：**
1. 授权 URL 是占位符（client_id 假值、无 redirect_uri、无 PKCE）
2. **无 code→token 兑换逻辑**（`handleCallback` 仅存 code）
3. 无本地回调端口监听（provider 官方注册的 redirect 端口 54545/1455/51121）
4. 无设备码流（Kimi/xAI）
5. `OAuthSessionManager` 实例分裂：`ManagementRouteHandler` 默认参数自建实例，`LocalProxyServer` L50-54 未传参
6. 无 refresh_token 自动续期
7. 无浏览器打开封装（无 androidx.browser 依赖，将用 `ACTION_VIEW` 免新增依赖）

### CLIProxyAPI 参考规格（已从源码核实）

| Provider | 流程 | Authorize 端点 | client_id | redirect_uri（官方注册端口） | Token 端点 | 请求体 |
|---|---|---|---|---|---|---|
| Claude | 授权码+PKCE | `https://claude.ai/oauth/authorize` | `9d1c250a-e61b-44d9-88ed-5944d1962f5e` | `http://localhost:54545/callback` | `https://platform.claude.com/v1/oauth/token` | **JSON** + axios 伪装头；scope=`user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload`；query 额外 `code=true` |
| Codex | 授权码+PKCE | `https://auth.openai.com/oauth/authorize` | `app_EMoamEEZ73f0CkXaXp7hrann` | `http://localhost:1455/auth/callback` | `https://auth.openai.com/oauth/token` | form；scope=`openid email profile offline_access`；额外 query：`prompt=login`、`id_token_add_organizations=true`、`codex_cli_simplified_flow=true`；email 从 `id_token` JWT 解析 |
| Antigravity | 授权码（client_secret，无 PKCE） | `https://accounts.google.com/o/oauth2/v2/auth` | `1071006060591-tmhs...googleusercontent.com` + secret `GOCSPX-K58F...` | `http://localhost:51121/oauth-callback` | `https://oauth2.googleapis.com/token` | form 含 `client_secret`；scope 含 `cloud-platform userinfo.email userinfo.profile cclog experimentsandconfigs`；`access_type=offline&prompt=consent`；email 从 `googleapis.com/oauth2/v2/userinfo` 取 |
| Kimi | 设备码 RFC8628 | —（`https://auth.kimi.com/api/oauth/device_authorization`，form 仅 `client_id=17e5f671-d194-4dfb-9706-5516cb48c098`） | 同左 | 无回调 | `https://auth.kimi.com/api/oauth/token` | form，`grant_type=urn:ietf:params:oauth:grant-type:device_code`，轮询 5s/上限 15min；请求头 `X-Msh-Platform` 等 |
| xAI | 设备码 + OIDC 发现 | —（先 GET `https://auth.x.ai/.well-known/openid-configuration` 取端点） | `b1a00492-073a-47ea-816f-4c329264a828`，scope=`openid profile email offline_access grok-cli:access api:access` | 无回调 | 发现所得 token_endpoint | form 设备码轮询，`slow_down` +5s，上限 30min；email/sub 从 `id_token` JWT 解析 |

- PKCE（`internal/auth/codex/pkce.go`）：verifier = 96 CSPRNG 字节 → base64url 无 padding（128 字符）；challenge = `base64url(SHA256(verifier))`，method=S256
- state（codex/claude/antigravity）：16 字节 CSPRNG → 32 位小写 hex；Kimi/xAI 用 `kmi-<UnixNano>` / `xai-<UnixNano>`
- 刷新：Claude JSON `{"client_id","grant_type":"refresh_token","refresh_token","scope"}`；Codex form 含 scope=`openid profile email`；Antigravity form 含 client_secret；Kimi/xAI form `grant_type=refresh_token`
- 成功回调页：标题 "Authentication successful" + 自动关闭脚本
- 管理端点契约：`{provider}-auth-url` → `{"status":"ok","url","state"}`（设备码流附 `flow:"device","user_code"`）；`get-auth-status?state=` → `wait|ok|error`；`oauth-callback` 支持 GET+POST

## Proposed Changes

### 阶段 1：core 层 — 提供商规格与会话状态机

**1. 新增 `core/src/main/kotlin/com/cpaphone/core/oauth/OAuthProviderSpec.kt`**
- `enum class OAuthFlowKind { CODE_PKCE, CODE_SECRET, DEVICE_CODE }`
- `data class OAuthProviderSpec`：flowKind、authorizeUrl、tokenUrl、clientId、clientSecret、redirectUri、scope、额外 authorize query、回调端口、刷新请求格式标识
- `val PROVIDER_SPECS: Map<ProviderType, OAuthProviderSpec>`：按上表录入 5 家精确参数（Kimi/xAI 无 authorizeUrl，含 deviceAuthUrl；xAI 含 oidcDiscoveryUrl）
- `object Pkce`：`generateVerifier()`（SecureRandom 96 字节 → base64url no pad）、`generateChallenge(verifier)`（SHA-256 → base64url no pad）
- `fun generateState()`：SecureRandom 16 字节 → 32 hex
- `fun parseJwtEmail(idToken: String): String?`：Base64Url 解 payload 取 email（无第三方依赖）

**2. 重写 `core/src/main/kotlin/com/cpaphone/core/session/OAuthSessionManager.kt`**
- `OAuthSession` 增加：`verifier`、`kind`、`userCode`、`verificationUrl`、`deviceCode`、`pollIntervalMs`
- `startSession(provider, spec)`：生成 state + PKCE（按 kind），拼接完整授权 URL（含 redirect_uri/scope/额外参数）
- `MutableStateFlow<List<OAuthSession>>` 暴露会话快照（UI 轮询改观察 Flow）
- 保留/调整：`completeWithCode(state, code, error)`、`pollStatus(state)`、`cancelSession(state)`、10 分钟 TTL
- 兑换成功后的凭据创建由 engine 层 LoginManager 驱动，session 置 OK 并携带结果摘要（alias/email）

### 阶段 2：engine 层 — 回调监听、token 兑换、登录编排

**3. 新增 `engine/src/main/kotlin/com/cpaphone/engine/oauth/OAuthCallbackServer.kt`**
- Ktor CIO `embeddedServer`，绑定 `127.0.0.1:<spec 回调端口>`（Claude 54545 / Codex 1455 / Antigravity 51121）
- 捕获任意 GET 的 query（`code`/`state`/`error`）→ 回调 `onCallback(provider, code, state, error)` → 返回成功 HTML（"登录成功，可关闭此页面" + 3s 自动关闭脚本）→ `stop(1s, 2s)`
- 同端口旧实例先停再起；`startLogin` 时启动、结束/超时/取消时 shutdown

**4. 新增 `engine/src/main/kotlin/com/cpaphone/engine/oauth/OAuthTokenClient.kt`**
- 复用 Ktor Client（CIO，已有依赖）
- `suspend fun exchangeCode(spec, code, verifier): OAuthTokens` —— 按各家格式：Codex/Antigravity/Kimi/xAI form 表单；Claude JSON body + 伪装头（`User-Agent: axios/1.15.2`、`Accept: application/json, text/plain, */*` 等）
- `suspend fun pollDeviceToken(spec, deviceCode, intervalMs, timeoutMs): OAuthTokens` —— `authorization_pending` 继续、`slow_down` +5s
- `suspend fun refreshToken(provider, spec, refreshToken): OAuthTokens` —— 各家刷新格式（供自动续期）
- `suspend fun fetchEmail(spec, accessToken)`：Antigravity userinfo / Claude profile 端点
- `data class OAuthTokens(accessToken, refreshToken, idToken, expiresIn, email)`
- xAI 首次调用做 OIDC 发现并缓存端点

**5. 新增 `engine/src/main/kotlin/com/cpaphone/engine/oauth/OAuthLoginManager.kt`**
- 编排者，构造注入：`OAuthSessionManager`、`CredentialRepository`、`OAuthTokenClient`（工厂创建）
- `suspend fun startLogin(provider): LoginStartResult`（url+state 或 verificationUrl+userCode）
  - CODE 流：启动 `OAuthCallbackServer`，5 分钟超时协程
  - DEVICE 流：后台协程 `pollDeviceToken` 循环
- 收到 code（回调 or 管理端点 POST）→ `exchangeCode` → `finalizeLogin`
- `finalizeLogin`：alias 自动编号（email 本地部分优先，如 `Claude-zhang.san`；无 email 用 `${prefix}-${n}`）→ `AuthCredential(provider, authType=OAUTH, expiresAt=now+expiresIn*1000-60_000)` → `repository.saveCredential(cred, accessToken)` → `repository.saveRefreshToken(id, refreshToken)` → session 置 OK → 清理回调服务器
- `fun cancelLogin(state)`：停服务器、停轮询、session 置 ERROR/移除
- 单飞保护：同 provider 已有进行中会话时先取消旧会话

**6. 修改 `data/src/main/kotlin/com/cpaphone/data/repository/CredentialRepository.kt`**
- 新增 `suspend fun saveRefreshToken(id, token)` 与 `fun getRefreshToken(id): String?`（一行委托 `SecureCredentialStorage`，refresh 槽位首次投入使用）

**7. 修改 `engine/src/main/kotlin/com/cpaphone/engine/server/LocalProxyServer.kt` + `ManagementRoutes.kt`（消除实例分裂 + 端点接真实现）**
- `LocalProxyServer` 构造函数追加 `oauthLoginManager: OAuthLoginManager` 参数；`ManagementRouteHandler` 接收并持有
- `{provider}-auth-url` 端点：委托 `oauthLoginManager.startLogin(provider)`，返回 `{status:"ok", url, state}`（设备码流附 `flow:"device","user_code","verification_url"`）
- `oauth-callback`（POST）：委托 `completeWithCode`；**新增 GET 版**同路径（浏览器重定向兜底通道）
- `get-auth-status` / `oauth-session` DELETE：委托共享 manager

### 阶段 3：app 层 — 组装与 UI

**8. 修改 `app/src/main/kotlin/com/cpaphone/CpaApplication.kt`**
- 组装顺序：`oauthSessionManager` → `oauthLoginManager = OAuthLoginManager(sessionManager, credentialRepository)` → `LocalProxyServer(..., oauthLoginManager)`，暴露 `oauthLoginManager` 供 UI 访问

**9. 修改 `app/src/main/kotlin/com/cpaphone/ui/authpool/AuthPoolScreen.kt`**
- 凭据池顶部新增"OAuth 快捷登录"区块（对齐图示）：5 张登录卡片横排或纵列 —— 图标色点 + 服务商名 + 一行说明 + "开始 XX 登录"按钮
- 点击逻辑：
  - CODE 流：`startLogin` → `ACTION_VIEW` 打开系统浏览器（`Intent(Intent.ACTION_VIEW, Uri.parse(url))`，不新增依赖）
  - DEVICE 流：卡片内展示 `user_code` + "复制"按钮 + 打开 `verification_uri` 按钮
- 观察会话 Flow 轮询渲染：等待授权（进度态）→ 成功（Toast + 列表自动刷新）/ 失败（Toast 错误）
- 进行中卡片按钮变为"取消登录"
- 保留现有手动添加凭据对话框不动

**10. 自动刷新 — 修改 `engine/src/main/kotlin/com/cpaphone/engine/coordinator/CredentialCoordinator.kt`**
- 构造注入 `OAuthTokenClient`（或经 LoginManager 共享实例）
- `acquireCredential` 返回前：若 `credential.authType == OAUTH && refreshToken 存在 && expiresAt - now < 5min` → 全局 `Mutex` 内二次校验后 `refreshToken` → `repository.saveCredential(updated)` + `saveRefreshToken` + `updateExpiresAt`，失败则保持原 token 照常调度（不中断请求）
- 401 仍走现有 `reportFailure` 冷却逻辑（刷新由 proactive 通道负责，避免扩大范围）

### 阶段 4：验证与发版

**11. 新增 `core/src/test/kotlin/com/cpaphone/core/PkceAndSpecTest.kt`**
- PKCE RFC 7636 附录 B 向量验证（verifier `test verifier...` → challenge `E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM`）
- state 格式（32 hex）、`parseJwtEmail` 解析、5 家 spec 关键字段完整性（端口/URL 非空）

**12. CI 发版**
- 推送 master → Actions 编译通过 → tag `v1.1.0` → 自动发布 Release（工作流已具备）

## Assumptions & Decisions

| 决策 | 说明 |
|---|---|
| 回调通道用 localhost 固定端口 | provider 官方注册的 redirect_uri 端口不可更改，deep link（`cpaphone://`）会被 OAuth 服务商拒绝；手机浏览器访问 `127.0.0.1` 即本机回环，与桌面版机制一致 |
| 不新增任何依赖 | 浏览器用 `ACTION_VIEW`，HTTP 用现有 Ktor Client，PKCE/JWT 手写（各 <30 行） |
| 不实现 deep link 回调 | Manifest 空壳保持原样，后续如需再加 |
| Antigravity 不做 loadCodeAssist/onboardUser | project_id 获取属后续增强，本次仅保存 token+email，与现有 UpstreamHttpClient 的 ANTIGRAVITY 支持水平一致 |
| Claude TLS 指纹不复刻 | CLIProxyAPI 用 uTLS 伪装 Firefox 指纹绕 Cloudflare，JVM 侧无法直接移植；若兑换被 Cloudflare 拦截属已知限制，表现为登录失败可重试 |
| 管理端点同步接真实现 | 远程中控（Remote Console）模式下同一套端点即可驱动远端 OAuth 会话，避免双轨 |
| 刷新策略：proactive only | 过期前 5 分钟内刷新；不做 401 反应式刷新重试，控制范围 |
| 版本号 v1.1.0 | 新功能 minor 升级 |

## Verification

1. `./gradlew :core:test`（CI 内跑）— PKCE 向量与 spec 测试通过
2. GitHub Actions `assembleDebug` 编译通过（本地无 JDK，以 CI 为准）
3. 发版 v1.1.0，APK 附件就绪，README 徽章自动更新
4. 手动验收清单（真机，交由用户执行）：
   - 凭据池出现 5 张登录卡片
   - Claude/Codex/Antigravity：点登录 → 浏览器授权 → 自动回跳成功页 → 凭据池出现带 email 别名的 OAUTH 凭据
   - Kimi/xAI：点登录 → 卡片显示 user_code → 打开验证页输入 → 凭据自动落库
   - token 过期前 5 分钟调用代理接口无 401（日志可查刷新记录）
