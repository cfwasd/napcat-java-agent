# 叮咚 Bot (DingDong Bot)

基于 NapCat OneBot11 协议的 Java Bot 开发框架，集成 AI Agent 能力，支持 QQ 官方 API v2 与 QQ 群聊双渠道。

---

## 前置要求

- JDK 17+
- Maven 3.8+
- （NapCat 渠道）已部署并配置好的 NapCat：[安装指南](https://napneko.github.io/guide/start-install)
- （QQ 官方渠道）在 [QQ 开放平台](https://q.qq.com/) 创建应用，获取 AppID 和 AppSecret

## 快速开始

> **注意**：目前尚未发布到 Maven Central，请按以下步骤本地安装后开发。

### 1. 克隆并安装到本地

```bash
git clone https://github.com/cfwasd/dingdong-bot.git
cd dingdong-bot
mvn install -DskipTests
```

### 2. 配置

复制示例配置并编辑：

```bash
cp dingdong-admin/src/main/resources/application.example.yml dingdong-admin/src/main/resources/application.yml
```

根据你的渠道配置相应项（详见示例文件中的注释）。

### 3. 运行

```bash
mvn spring-boot:run -pl dingdong-admin
```

---

## 架构

```
dingdong-bot/
├── dingdong-parent                  # BOM，统一依赖版本
├── dingdong-core                    # OneBot11 协议、通信适配器、事件路由
├── dingdong-agent                   # LLM Agent 引擎、Tool 注册、ReAct 循环
├── dingdong-cultivation             # 修仙系统模块
├── dingdong-channel-api             # 渠道抽象 API
├── dingdong-qqofficial              # QQ 官方 API v2 渠道实现
├── dingdong-llm-providers           # LLM 厂商实现
│   ├── dingdong-llm-openai          # OpenAI 协议兼容（含多模态/vision）
│   ├── dingdong-llm-anthropic       # Claude
│   └── dingdong-llm-ollama          # Ollama 本地模型
├── dingdong-boot-starter            # Spring Boot 自动配置
└── dingdong-admin                   # 示例机器人应用
```

---

## 功能特性

- **双渠道支持**：QQ 官方 API v2（Markdown + 按钮面板） + NapCat OneBot11（WebSocket/HTTP）
- **全协议通信**：支持 HTTP Server / Client、WebSocket Server / Client 四种 NapCat 通信方式
- **双编程模型**：注解式（`@OnGroupMessage`、`@Command`）与接口式（`EventHandler`、`CommandHandler`）并存
- **OneBot11 完整模型**：消息链（MessageChain）、事件、API 请求/响应全覆盖
- **AI Agent 引擎**：内置 ReAct 轻量循环（默认最多 5 轮），支持 Function Calling / Tool Use
- **多人格角色系统**：内置 5 种人格（默认/傲娇/学者/逗比/知心姐姐），可自由增删改
- **TTS 语音回复**：集成 Edge TTS，支持人格声线映射
- **文生图**：Agent 支持通过 DALL·E / 兼容 API 生成图片
- **多模态理解**：支持图片/语音/视频富文本标记，自动识别发送
- **多 LLM 后端**：OpenAI 协议兼容、Anthropic Claude、Ollama 本地模型，支持备用模型自动切换
- **Spring Boot 开箱即用**：自动配置，高度可配置化
- **关键词唤醒**：消息包含唤醒词时自动触发，无需 @
- **持久化长期记忆**：SQLite 两层存储（碎片化记忆 + 每日 LLM 自动归纳摘要）
- **会话上下文**：按用户 ID + 群号隔离，支持过期自动清理
- **定时任务调度**：Agent 可通过工具创建 Cron 定时任务
- **丰富群聊工具**：20+ 内置工具（搜索/文生图/翻译/编码解码/定时提醒/猜拳/骰子/抽签/运势等）
- **QQ 官方按钮面板**：Markdown + 键盘按钮交互（修仙面板、婚姻面板、帮助面板等）
- **修仙系统**：境界突破、丹药炼制、宗门系统、道侣双修等完整 RPG 玩法
- **日志按天分割**：自动滚动归档，保留 30 天

---

## 微信接入

本框架也支持通过 [agent-wechat](https://github.com/thisnick/agent-wechat) REST API 接入微信，与 QQ 共用同一套命令体系和 Agent 引擎。

详见 [微信接入文档](docs/01-quick-start.md#微信接入) 或配置中的 `dingdong.wechat.*` 项。

---

## 文档

在线文档站：https://cfwasd.github.io/dingdong-bot/

完整文档见 [docs/](docs/) 目录：

| 文档 | 内容 |
|------|------|
| [快速开始](docs/01-quick-start.md) | 环境准备、依赖引入、第一个 Bot、启用 Agent |
| [编程模型](docs/02-programming-model.md) | 所有注解、接口定义、返回值处理、路由优先级 |
| [配置参考](docs/03-configuration-reference.md) | 完整配置项、适配器配置、多环境配置 |
| [事件与消息](docs/04-event-message-model.md) | 事件体系、MessageChain、Sender、API 列表 |
| [通信适配器](docs/05-adapter-guide.md) | 四种适配器对比、配置示例、混合模式 |
| [Agent 指南](docs/06-agent-guide.md) | ReAct 循环、Tool 注册、会话管理、多模态、LLM Provider |
| [内部架构](docs/07-internal-architecture.md) | 模块职责、启动流程、线程模型、扩展点 |

---

## 相关链接

- [NapCatQQ 官方文档](https://napneko.github.io/guide/start-install)
- [QQ 官方 API v2 文档](https://bot.q.qq.com/wiki/develop/api-v2/)
- [本项目 GitHub](https://github.com/cfwasd/dingdong-bot)

## License

MIT
