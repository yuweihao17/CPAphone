# CPAphone (CLIProxyAPI for Android)

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Kotlin-2.1%2B-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20(Material%203)-blue.svg)](https://developer.android.com/jetpack/compose)
[![Network Engine](https://img.shields.io/badge/Engine-Ktor%203.1%20CIO-orange.svg)](https://ktor.io)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20%2B%20MVI-red.svg)](docs/TECHNICAL_SPEC.md)

**CPAphone** 是基于 [CLIProxyAPI](https://github.com/...) 深度重塑的现代化 Android 原生智能 AI 代理网关与中控管理平台。它将服务器级多模型路由调度、多账号池治理、全双工跨协议转译与思考链（Thinking）签名保真能力，完美浓缩至手机端轻量、丝滑、极低功耗的原生应用中。

---

## 🌟 核心特性

- 📱 **双模无缝切换**：
  - **本地代理网关模式（Local Standalone Proxy）**：手机内嵌高性能 Ktor CIO 异步网络内核，常驻前台服务，为手机内 Termux、浏览器、AI 编程工具或局域网设备提供统一的 `http://127.0.0.1:8317/v1` 代理。
  - **远程中控管理模式（Remote Console）**：通过安全的 RESTful 与 WebSocket 直连已部署在 PC 或云端的 CLIProxyAPI 节点，实现移动端随身运维与实时监控。
- 🔄 **全矩阵双向协议转译**：
  - 客户端以 OpenAI、Claude 或 Gemini 任意协议调用，网关根据目标模型透明转译请求体、流式 SSE 帧与 Tool Calls。
- ⚖️ **智能流量分发与账号池**：
  - 支持 **轮询（Round-Robin）**、**平滑加权轮询（Smooth Weighted Round-Robin）** 与 **深度优先（Fill-First）** 调度策略。
  - 支持全局会话粘性（Universal Session Affinity），最大化复用官方 Prompt Cache，降低延迟与成本。
- 🛡️ **自动故障容灾与熔断冷却**：
  - 实时识别 403 / 429 / 5xx 异常，毫秒级熔断故障凭据并自动进入冷却队列，在当前请求内透明换号重试。
  - **首包缓冲防御（Stream Bootstrap Buffering）**：预读流式初始帧，拦截伪装成 HTTP 200 的隐藏错误。
- 🧠 **深度思考过程（Thinking / Reasoning）保真**：
  - 原生解析与保持 Claude Thinking 思考签名与 OpenAI Reasoning 结构。
- 🎨 **极简收敛设计哲学**：
  - 基于 Material 3 收敛型设计规范，杜绝冗长堆叠，界面呼吸感自然，动画顺畅丝滑。

---

## 📐 系统架构与目录结构

本项目严格遵循 **Clean Architecture** 架构规范，自底向上严格单向依赖：

```
CPAphone/
├── docs/                           # 核心规范与架构设计文档
│   ├── FUNCTIONAL_SPEC.md          # 业务与功能详细规范书
│   └── TECHNICAL_SPEC.md           # 技术架构与实现规范书
├── core/                           # 纯 Kotlin 核心领域层 (零 Android 依赖)
│   └── src/main/kotlin/com/cpaphone/core/
│       ├── model/                  # 凭据、调度、追踪与通用模型
│       ├── routing/                # 负载均衡调度器实现 (WRR / RR / FillFirst)
│       ├── translator/             # 跨协议统一 AST 解析与转译引擎
│       └── session/                # 会话粘性与动态冷却状态机
├── data/                           # 数据持久化与硬件级安全层
│   └── src/main/kotlin/com/cpaphone/data/
│       ├── local/                  # Room 数据库实体、DAO 与迁移
│       ├── security/               # Android Keystore 硬件级 AES-256 加密
│       └── repository/             # 仓库层实现与 DataStore 响应式配置
├── engine/                         # 本地代理网关与远程网络引擎
│   └── src/main/kotlin/com/cpaphone/engine/
│       ├── server/                 # Ktor Server CIO 嵌入式轻量网关
│       ├── client/                 # Ktor Client 异步请求转发器与 SSE 管道
│       ├── remote/                 # 远程 CLIProxyAPI 节点中控协议桥
│       └── service/                # Android ForegroundService 前台保活服务
├── app/                            # 极简收敛 UI 展现层 (Jetpack Compose)
│   └── src/main/kotlin/com/cpaphone/app/
│       ├── ui/                     # Material 3 极简设计系统与 5 大核心视图
│       │   ├── dashboard/          # 运行状态仪表盘
│       │   ├── authpool/           # 凭据池治理中心
│       │   ├── traces/             # 实时请求流式审计
│       │   ├── playground/         # 极简 AI 对话测试台
│       │   └── settings/           # 系统与网络设置
│       └── di/                     # 轻量化依赖组装容器
└── build.gradle.kts                # 工程构建与 Version Catalogs
```

---

## 🚀 快速上手与运行

### 运行环境要求
- Android 8.0 (API Level 26) 及以上
- 开发环境：Android Studio (Ladybug / Jellyfish) 或配置 JDK 17+ 及 Android SDK 34+

### 构建与打包
```bash
# Debug 调试包构建
./gradlew assembleDebug

# 执行纯 Kotlin 核心领域层单元测试
./gradlew :core:test
```

---

## 📜 许可协议
本项目遵循 MIT 协议开源。
