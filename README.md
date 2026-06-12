# NapCat Java SDK

基于 [NapCat](https://github.com/NapNeko/NapCatQQ) OneBot11 协议的 Java Bot 开发框架，集成 AI Agent 能力，支持注解驱动与接口驱动两种编程模型。

---

## 前置要求

- JDK 17+
- Maven 3.8+
- 已部署并配置好的 NapCat（[安装指南](https://napneko.github.io/guide/start-install)）

## 快速开始

> **注意**：目前尚未发布到 Maven Central，请按以下步骤本地安装后开发。

### 1. 克隆并安装到本地

```bash
git clone https://github.com/cfwasd/napcat-java-agent.git
cd napcat-java-agent
mvn install -DskipTests
```

### 2. 配置 NapCat 连接

编辑 `napcat-admin/src/main/resources/application.yml`：

```yaml
napcat:
  qq:
    enabled: true
    adapter:
      type: websocket-client
      websocket-client:
        url: ws://127.0.0.1:3001
        token: ""
    bot:
      self-id: 123456789
      command-prefix: ""
      wake-words:
        - 小助手
```

### 3. 在 napcat-admin 中编写 Bot

直接在 `napcat-admin` 模块下创建你的 Bot 类即可：

```java
@Component
public class HelloBot {

    @OnGroupMessage
    @Command("/hello")
    public void hello(GroupMessageEvent event) {
        event.reply("Hello NapCat!");
    }

    @OnGroupMessage
    public void onGroup(GroupMessageEvent event) {
        if (event.getRawMessage().contains("在吗")) {
            event.reply("在的！");
        }
    }
}
```

然后运行 `napcat-admin` 的 `main` 方法启动 Bot。

更多示例见 [napcat-admin/src/main/java/com/napcat/admin/bot/](napcat-admin/src/main/java/com/napcat/admin/bot/)。

---

### 在自己的项目中使用

如果你想在自己的项目中引入，先执行上面的 `mvn install`，然后在 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>com.napcat</groupId>
    <artifactId>napcat-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## 微信接入（agent-wechat REST API）

微信通过 [agent-wechat](https://github.com/thisnick/agent-wechat) 容器接入：先在另一台/同一台机器部署 agent-wechat 并扫码登录，napcat-java 通过 REST API 轮询消息并发送回复。

### 1. 启动 agent-wechat

参考其官方文档（Docker 容器，默认端口 `6174`）：

```bash
wx up
wx auth login
```

记录生成的 token：`/root/.config/agent-wechat/token`。

### 2. 配置微信

在 `napcat-admin/src/main/resources/application.yml`：

```yaml
napcat:
  wechat:
    enabled: true
    api-base-url: http://127.0.0.1:6174
    token: ${AGENT_WECHAT_TOKEN:}              # 优先用 token；为空时读 token-file
    token-file: ${AGENT_WECHAT_TOKEN_FILE:${user.home}/.config/agent-wechat/token}
    api-timeout: 30000
    poll-interval-ms: 1000
    chat-limit: 50
    message-limit: 20
    reply-to-group-messages: true
    ignore-self-messages: true
    trigger-mode: private-all-group-mention-or-wake
    wake-words:
      - emiya
      - 小助手
```

### 3. 行为说明

- **触发策略**
  - 私聊：默认全部消息都触发 Agent；
  - 群聊：必须 @ 机器人或包含唤醒词才触发；
  - 关键词命中后会从传给 Agent 的内容中**去掉关键词**。
- **命令分发**：微信消息会先按现有 `@Command` 注解尝试匹配（与 QQ 共用同一套命令体系），命令未命中再交给 Agent。命令前缀沿用 `napcat.qq.bot.command-prefix`。
- **启动行为**：`AgentWechatPoller.start()` 时只把当前消息标记为已读，不会把启动前的历史消息再回复一遍。
- **图片识别**
  - 私聊单图自动识图，`AgentWechatPoller` 调用 agent-wechat `getMedia(chatId, localId)` 取出 base64，拼成 `[图片:data:image/png;base64,...]` 走现有 `enable-vision` 多模态链路；
  - 群聊不识图，避免噪声。
- **发送能力**：文本、图片（URL 自动下载、本地路径、base64）、文件（本地路径、base64）。
- **不可用项**：微信通道无 OneBot raw API，因此 QQ 的"思考过程合并转发"在微信下自动跳过；当前未接入 TTS 语音发送。

### 4. 路径映射注意

agent-wechat 容器只能读到挂载到容器内的路径（默认 `/data` 或 `/home/wechat`）。

如果给 agent-wechat 的 `image` / `file` 字段是本地路径，确保：

- Java 进程能读到该文件；
- agent-wechat 容器**不需要**直接读这个路径——`AgentWechatClient` 已经把文件读成 bytes 后 base64 上传，无需共享文件系统。

如果用 HTTP/HTTPS URL，napcat-java 会先下载再上传 base64，所以也无需让 agent-wechat 直接访问该 URL。

---

## 文档

在线文档站：https://cfwasd.github.io/napcat-java-agent/

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

## 功能特性

- **多协议接入**：QQ 走 NapCat OneBot11；微信走 [agent-wechat](https://github.com/thisnick/agent-wechat) REST API（轮询拉聊天/消息，支持文本/图片/文件发送）
- **全协议通信**：支持 HTTP Server / Client、WebSocket Server / Client 四种 NapCat 通信方式
- **双编程模型**：注解式（`@OnGroupMessage`、`@Command`）与接口式（`EventHandler`、`CommandHandler`）并存
- **OneBot11 完整模型**：消息链（MessageChain）、事件、API 请求/响应全覆盖；支持 array / string（CQ 码）双格式上报解析
- **AI Agent 引擎**：内置 ReAct 轻量循环（默认最多 5 轮），支持 Function Calling / Tool Use
- **多人格角色系统**：内置 5 种人格（默认/傲娇/学者/逗比/知心姐姐），群友可通过 `/persona` 命令随时切换，每个人独立设置
- **TTS 语音回复**：集成 VoiceCraft（免费 Edge TTS），支持人格声线映射，50% 概率随机触发语音（可切换纯文字/纯语音/默认模式）
- **文生图（DALL·E/商汤）**：Agent 支持通过 `text_to_image` 工具生成图片，NapCat 原生支持直接发送图片 URL，无需下载
- **多模态理解**：`MessageChain.toAgentPrompt()` 保留图片、语音、视频等富文本标记；OpenAI Provider 自动将 `[图片:url]` 提取为 `image_url` 多模态消息；LLM 回复中的 Markdown 图片 `![alt](url)` 自动识别并发送；微信私聊单图自动调用 agent-wechat `getMedia` 拼成 `data:image/...;base64` 走 vision 链路（群聊不识图）
- **多 LLM 后端**：OpenAI 协议兼容（含多模态/vision）、Anthropic Claude、Ollama 本地模型、自定义 OpenAI 端点
- **LLM 备用模型**：主模型失败时自动切换到备用模型，支持 openai / anthropic / ollama / custom
- **Spring Boot 开箱即用**：`napcat-spring-boot-starter` 自动配置，高度可配置化
- **组合注解**：支持自定义元注解，如 `@OnGroupAt`、`@AdminCommand`
- **关键词唤醒**：消息包含配置唤醒词时自动触发，无需 @
- **持久化长期记忆**：SQLite 两层存储（碎片化记忆 + 每日 LLM 自动归纳摘要），跨会话自动检索注入上下文
- **会话上下文**：按用户 ID + 群号隔离的会话管理，支持过期自动清理与 `/clear` 手动重置
- **定时任务调度**：Agent 可通过工具创建 Cron 定时任务，支持 AI 生成内容或固定文本推送，持久化到 SQLite
- **丰富群聊工具**：内置 20+ 工具，覆盖搜索、图片生成、翻译、编码解码、定时提醒、历史事件查询、节假日倒计时、分组分队伍、选择困难症终结、热梗段子、真心话大冒险、猜拳、骰子、猜数字、抽签、运势、绕口令等
- **日志按天分割**：自动滚动归档，保留 30 天，总大小上限 1GB

---

## 模块架构

```
napcat-java/
├── napcat-parent                  # BOM，统一依赖版本
├── napcat-core                    # OneBot11 协议、通信适配器、事件路由
├── napcat-agent                   # LLM Agent 引擎、Tool 注册、ReAct 循环
├── napcat-llm-providers           # LLM 厂商实现
│   ├── napcat-llm-openai          # OpenAI 协议兼容（含多模态/vision、reasoning_content）
│   ├── napcat-llm-anthropic       # Claude
│   └── napcat-llm-ollama          # Ollama 本地模型
├── napcat-spring-boot-starter     # Spring Boot 自动配置
└── napcat-admin                   # 示例机器人应用
```

---

## 相关链接

- [NapCatQQ 官方文档](https://napneko.github.io/guide/start-install)
- [NapCat API 文档 (Apifox)](https://napcat.apifox.cn/llms.txt)
- [本项目 GitHub](https://github.com/cfwasd/napcat-java-agent)

## License

MIT
