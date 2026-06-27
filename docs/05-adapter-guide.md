# 通信适配器指南

本文档说明框架与 NapCat 的四种通信方式，以及如何选择和配置。

---

## 一、四种通信方式对比

| 维度 | WS Client | WS Server | HTTP Client | HTTP Server |
|------|-----------|-----------|-------------|-------------|
| 连接方向 | Bot → NapCat | NapCat → Bot | Bot → NapCat | NapCat → Bot |
| 双工通信 | ✅ | ✅ | ❌ | ❌ |
| 事件推送 | 实时推送 | 实时推送 | 需额外配置 | 实时推送 |
| API 调用 | 通过 WS | 通过 WS | 直接 HTTP | 需反向 HTTP Client |
| 多 NapCat 支持 | 每个 NapCat 一个连接 | 一个端口等多连接 | 每个 NapCat 一个配置 | 一个端口接收多上报 |
| 推荐场景 | 单/多 NapCat，一般推荐 | 中心化 Bot 服务 | 简单轮询场景 | Webhook 风格部署 |

---

## 二、WebSocket Client（默认、推荐）

Bot 主动连接 NapCat 的 WebSocket Server，建立长连接后双向收发。

**NapCat 侧：** `onebot11.json`

```json
{
  "network": {
    "websocketServers": [{
      "name": "WsServer",
      "enable": true,
      "host": "0.0.0.0",
      "port": 3001,
      "messagePostFormat": "array",
      "token": ""
    }]
  }
}
```

**Bot 侧：** `application.yml`

```yaml
dingdong:
  qq:
    enabled: true
    adapter:
      type: websocket-client
      websocket-client:
        url: ws://127.0.0.1:3001
        token: ""
```

### 多 NapCat 实例

```java
@Configuration
public class MultiBotConfig {
    @Bean
    public BotAdapter bot1() {
        return new WsClientAdapter("ws://napcat1:3001", "");
    }
    @Bean
    public BotAdapter bot2() {
        return new WsClientAdapter("ws://napcat2:3001", "");
    }
}
```

---

## 三、WebSocket Server

Bot 开启 WebSocket Server，等待 NapCat 主动连接。适合中心化 Bot 服务：多个 NapCat 实例连接同一个 Bot 后端。

```yaml
dingdong:
  qq:
    adapter:
      type: websocket-server
      websocket-server:
        host: 0.0.0.0
        port: 3001
        token: ""
```

---

## 四、HTTP Client

Bot 主动发送 HTTP 请求调用 NapCat API。注意纯 HTTP Client 模式下无法被动接收事件，需额外配置 HTTP Server 接收上报。

```yaml
dingdong:
  qq:
    adapter:
      type: http-client
      http-client:
        url: http://127.0.0.1:3000
        token: ""
        timeout: 30000
```

---

## 五、HTTP Server

Bot 开启 HTTP Server，被动接收 NapCat 的 HTTP 上报。需配置 `api-url` 用于主动调用 API。

```yaml
dingdong:
  qq:
    adapter:
      type: http-server
      http-server:
        host: 0.0.0.0
        port: 8080
        path: /dingdong/webhook
        token: ""
        api-url: "http://127.0.0.1:3000"
        api-token: ""
```

---

## 六、混合模式

框架支持同时运行多个适配器：

```java
@Configuration
public class HybridAdapterConfig {
    @Bean
    public BotAdapter wsAdapter() {
        return new WsClientAdapter("ws://napcat:3001", "");
    }
    @Bean
    public BotAdapter httpAdapter() {
        return new HttpClientAdapter("http://napcat:3000", "");
    }
}
```

---

## 七、事件处理线程池

```yaml
dingdong:
  core:
    event-executor:
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 1000
```

默认使用 `ThreadPoolExecutor`，线程名前缀 `dingdong-event-pool`。同步模式：`sync-event-processing: true`。
