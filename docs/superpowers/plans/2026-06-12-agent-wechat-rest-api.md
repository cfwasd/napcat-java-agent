# Agent WeChat REST API 接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 agent-wechat REST API 为 napcat-java 增加最小可用微信接入：轮询聊天/消息、去重、调用现有 `NapCatAgent` 生成回复，并通过 REST API 发送文本回复。

**Architecture:** 新增一个独立的 `com.napcat.starter.wechat` 小模块放在 starter 内，包含配置、REST client、轮询器和自动装配。微信侧不复用 OneBot11 的 `NapCatApi`/`MessageEvent`，而是先走轻量 REST 轮询，避免改动 core 事件体系；Agent 会话使用稳定的 synthetic long id 映射微信字符串 id。

**Tech Stack:** Java 17、Spring Boot AutoConfiguration、OkHttp、Jackson、JUnit 5、OkHttp MockWebServer。

---

## File Structure

- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/WechatProperties.java`
  - 绑定 `napcat.wechat.*`，包含 `enabled`、`apiBaseUrl`、`token`、`tokenFile`、`pollIntervalMs`、`apiTimeout`、`chatLimit`、`messageLimit`、`replyToGroupMessages`、`ignoreSelfMessages`、`triggerMode`、`wakeWords`。
- Modify: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/NapCatAutoConfiguration.java`
  - `@EnableConfigurationProperties` 增加 `WechatProperties`。
  - 注册 `AgentWechatClient`、`WechatIdMapper`、`AgentWechatPoller`。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatClient.java`
  - 负责 REST GET/POST、Bearer token、JSON 解析。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatChat.java`
  - agent-wechat `/api/chats` DTO。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatMessage.java`
  - agent-wechat `/api/messages/{chatId}` DTO。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatSendRequest.java`
  - agent-wechat `/api/messages/send` request DTO。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatSendResult.java`
  - agent-wechat send result DTO。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/WechatIdMapper.java`
  - 把微信字符串用户/群 id 稳定映射成 positive long，供 `NapCatAgent.chat(long userId, long groupId, ...)` 使用。
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatPoller.java`
  - 后台定时轮询 chats/messages，过滤已处理消息和自发消息，触发 Agent 并发送回复。
- Modify: `napcat-spring-boot-starter/pom.xml`
  - 增加 `mockwebserver` test 依赖。
- Create: `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatClientTest.java`
  - 验证 REST URL、Authorization header、send body。
- Create: `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/WechatIdMapperTest.java`
  - 验证 id 映射稳定、区分私聊和群聊。
- Create: `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatPollerTest.java`
  - 使用 fake client/agent callback 验证去重、唤醒词和回复发送。
- Modify: `napcat-admin/src/main/resources/application.yml`
  - 将 `napcat.wechat.api-base-url` 默认改为 `http://127.0.0.1:6174`，增加 token 环境变量示例。

---

### Task 1: 新增微信配置属性

**Files:**
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/WechatProperties.java`
- Modify: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/NapCatAutoConfiguration.java`

- [ ] **Step 1: 创建 WechatProperties**

Create `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/WechatProperties.java`:

```java
package com.napcat.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 微信 Bot 配置，对应 napcat.wechat.*。
 */
@Data
@ConfigurationProperties(prefix = "napcat.wechat")
public class WechatProperties {

    /** 是否启用微信模块 */
    private boolean enabled = false;

    /** agent-wechat REST API 地址，默认端口 6174 */
    private String apiBaseUrl = "http://127.0.0.1:6174";

    /** agent-wechat token；为空时尝试读取 tokenFile */
    private String token = "";

    /** agent-wechat CLI 默认 token 文件 */
    private String tokenFile = "${user.home}/.config/agent-wechat/token";

    /** HTTP 请求超时（毫秒） */
    private long apiTimeout = 30000;

    /** 轮询间隔（毫秒） */
    private long pollIntervalMs = 2000;

    /** 每次拉取聊天数量 */
    private int chatLimit = 50;

    /** 每个聊天每次拉取消息数量 */
    private int messageLimit = 20;

    /** 是否回复群聊消息 */
    private boolean replyToGroupMessages = true;

    /** 是否忽略自己发送的消息 */
    private boolean ignoreSelfMessages = true;

    /** 触发模式：all / wake-word / mention-or-wake */
    private String triggerMode = "wake-word";

    /** 微信消息唤醒词 */
    private List<String> wakeWords = new ArrayList<>(List.of("小助手", "bot", "机器人"));
}
```

- [ ] **Step 2: 注册配置类**

Modify `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/NapCatAutoConfiguration.java` line with `@EnableConfigurationProperties` from:

```java
@EnableConfigurationProperties({NapCatProperties.class, QqProperties.class})
```

to:

```java
@EnableConfigurationProperties({NapCatProperties.class, QqProperties.class, WechatProperties.class})
```

- [ ] **Step 3: 编译 starter 验证配置类可用**

Run:

```bash
mvn -pl napcat-spring-boot-starter -am -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/WechatProperties.java \
  napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/NapCatAutoConfiguration.java
git commit -m "feat: add agent-wechat configuration properties"
```

---

### Task 2: 新增 agent-wechat REST client 和 DTO

**Files:**
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatChat.java`
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatMessage.java`
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatSendRequest.java`
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatSendResult.java`
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatClient.java`
- Modify: `napcat-spring-boot-starter/pom.xml`
- Test: `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatClientTest.java`

- [ ] **Step 1: 添加测试依赖**

Modify `napcat-spring-boot-starter/pom.xml`, add this dependency inside `<dependencies>`:

```xml
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>mockwebserver</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 写失败测试**

Create `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatClientTest.java`:

```java
package com.napcat.starter.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentWechatClientTest {

    @Test
    void listChatsSendsBearerTokenAndParsesChats() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("[{\"id\":\"wxid_a\",\"username\":\"wxid_a\",\"name\":\"张三\",\"unreadCount\":1,\"isGroup\":false,\"lastMsgLocalId\":12}]"));
            server.start();

            AgentWechatClient client = new AgentWechatClient(
                    server.url("/").toString(), "secret", Duration.ofSeconds(3), new ObjectMapper());

            List<AgentWechatChat> chats = client.listChats(10, 0);

            assertEquals(1, chats.size());
            assertEquals("wxid_a", chats.get(0).getId());
            assertEquals("张三", chats.get(0).getName());
            assertEquals(1, chats.get(0).getUnreadCount());
            assertFalse(chats.get(0).isGroup());

            RecordedRequest request = server.takeRequest();
            assertEquals("GET", request.getMethod());
            assertEquals("/api/chats?limit=10&offset=0", request.getPath());
            assertEquals("Bearer secret", request.getHeader("Authorization"));
        }
    }

    @Test
    void listMessagesParsesMessageFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("[{\"localId\":101,\"serverId\":202,\"chatId\":\"room@chatroom\",\"sender\":\"wxid_sender\",\"senderName\":\"李四\",\"type\":1,\"content\":\"小助手 你好\",\"timestamp\":\"2026-06-12T10:00:00Z\",\"isMentioned\":true,\"isSelf\":false}]"));
            server.start();

            AgentWechatClient client = new AgentWechatClient(
                    server.url("/").toString(), "secret", Duration.ofSeconds(3), new ObjectMapper());

            List<AgentWechatMessage> messages = client.listMessages("room@chatroom", 20, 0);

            assertEquals(1, messages.size());
            AgentWechatMessage message = messages.get(0);
            assertEquals(101L, message.getLocalId());
            assertEquals("room@chatroom", message.getChatId());
            assertEquals("wxid_sender", message.getSender());
            assertEquals("李四", message.getSenderName());
            assertEquals("小助手 你好", message.getContent());
            assertTrue(Boolean.TRUE.equals(message.getMentioned()));
            assertFalse(Boolean.TRUE.equals(message.getSelf()));

            RecordedRequest request = server.takeRequest();
            assertEquals("/api/messages/room%40chatroom?limit=20&offset=0", request.getPath());
            assertEquals("Bearer secret", request.getHeader("Authorization"));
        }
    }

    @Test
    void sendTextPostsExpectedJson() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"success\":true}"));
            server.start();

            AgentWechatClient client = new AgentWechatClient(
                    server.url("/").toString(), "secret", Duration.ofSeconds(3), new ObjectMapper());

            AgentWechatSendResult result = client.sendText("wxid_a", "你好");

            assertTrue(result.isSuccess());
            RecordedRequest request = server.takeRequest();
            assertEquals("POST", request.getMethod());
            assertEquals("/api/messages/send", request.getPath());
            assertEquals("Bearer secret", request.getHeader("Authorization"));
            assertEquals("{\"chatId\":\"wxid_a\",\"text\":\"你好\"}", request.getBody().readUtf8());
        }
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=AgentWechatClientTest test
```

Expected: FAIL because `AgentWechatClient` and DTO classes do not exist.

- [ ] **Step 4: 创建 DTO**

Create `AgentWechatChat.java`:

```java
package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWechatChat {
    private String id;
    private String username;
    private String name;
    private String remark;
    private String lastMessagePreview;
    private String lastMessageSender;
    private String lastActivityAt;
    private int unreadCount;
    private boolean group;
    private Long lastMsgLocalId;
}
```

Create `AgentWechatMessage.java`:

```java
package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWechatMessage {
    private long localId;
    private long serverId;
    private String chatId;
    private String sender;
    private String senderName;
    @JsonProperty("type")
    private int type;
    private String content;
    private String timestamp;
    @JsonProperty("isMentioned")
    private Boolean mentioned;
    @JsonProperty("isSelf")
    private Boolean self;
}
```

Create `AgentWechatSendRequest.java`:

```java
package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentWechatSendRequest {
    private String chatId;
    private String text;
}
```

Create `AgentWechatSendResult.java`:

```java
package com.napcat.starter.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentWechatSendResult {
    private boolean success;
    private String error;
}
```

- [ ] **Step 5: 创建 REST client**

Create `AgentWechatClient.java`:

```java
package com.napcat.starter.wechat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@Slf4j
public class AgentWechatClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final String token;
    private final ObjectMapper mapper;
    private final OkHttpClient http;

    public AgentWechatClient(String baseUrl, String token, Duration timeout, ObjectMapper mapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.token = token == null ? "" : token.trim();
        this.mapper = mapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .writeTimeout(timeout)
                .build();
    }

    public List<AgentWechatChat> listChats(int limit, int offset) throws IOException {
        String body = get("/api/chats?limit=" + limit + "&offset=" + offset);
        return mapper.readValue(body, new TypeReference<List<AgentWechatChat>>() {});
    }

    public List<AgentWechatMessage> listMessages(String chatId, int limit, int offset) throws IOException {
        String path = "/api/messages/" + urlEncodePath(chatId) + "?limit=" + limit + "&offset=" + offset;
        String body = get(path);
        return mapper.readValue(body, new TypeReference<List<AgentWechatMessage>>() {});
    }

    public AgentWechatSendResult sendText(String chatId, String text) throws IOException {
        AgentWechatSendRequest payload = new AgentWechatSendRequest(chatId, text);
        String body = postJson("/api/messages/send", mapper.writeValueAsString(payload));
        return mapper.readValue(body, AgentWechatSendResult.class);
    }

    private String get(String path) throws IOException {
        Request request = baseRequest(path).get().build();
        return execute(request);
    }

    private String postJson(String path, String json) throws IOException {
        RequestBody requestBody = RequestBody.create(json, JSON);
        Request request = baseRequest(path).post(requestBody).build();
        return execute(request);
    }

    private Request.Builder baseRequest(String path) {
        Request.Builder builder = new Request.Builder().url(baseUrl + path);
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private String execute(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("agent-wechat API failed: HTTP " + response.code() + " " + text);
            }
            return text;
        }
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://127.0.0.1:6174";
        }
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String urlEncodePath(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=AgentWechatClientTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add napcat-spring-boot-starter/pom.xml \
  napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat \
  napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatClientTest.java
git commit -m "feat: add agent-wechat REST client"
```

---

### Task 3: 新增微信 id 映射

**Files:**
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/WechatIdMapper.java`
- Test: `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/WechatIdMapperTest.java`

- [ ] **Step 1: 写失败测试**

Create `WechatIdMapperTest.java`:

```java
package com.napcat.starter.wechat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WechatIdMapperTest {

    @Test
    void sameWechatIdAlwaysMapsToSamePositiveLong() {
        WechatIdMapper mapper = new WechatIdMapper();

        long first = mapper.toUserId("wxid_abc");
        long second = mapper.toUserId("wxid_abc");

        assertTrue(first > 0);
        assertEquals(first, second);
    }

    @Test
    void userAndGroupNamespacesDoNotCollide() {
        WechatIdMapper mapper = new WechatIdMapper();

        long userId = mapper.toUserId("same");
        long groupId = mapper.toGroupId("same@chatroom");

        assertTrue(userId > 0);
        assertTrue(groupId > 0);
        assertNotEquals(userId, groupId);
    }

    @Test
    void blankGroupIdMapsToPrivateGroupZero() {
        WechatIdMapper mapper = new WechatIdMapper();

        assertEquals(0L, mapper.toGroupId(null));
        assertEquals(0L, mapper.toGroupId(""));
        assertEquals(0L, mapper.toGroupId("   "));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=WechatIdMapperTest test
```

Expected: FAIL because `WechatIdMapper` does not exist.

- [ ] **Step 3: 实现 WechatIdMapper**

Create `WechatIdMapper.java`:

```java
package com.napcat.starter.wechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 将微信字符串 ID 稳定映射为 NapCatAgent 可用的 long 会话键。
 */
public class WechatIdMapper {

    public long toUserId(String wechatUserId) {
        return stablePositiveLong("wechat:user:" + safe(wechatUserId));
    }

    public long toGroupId(String wechatChatId) {
        if (wechatChatId == null || wechatChatId.isBlank()) {
            return 0L;
        }
        return stablePositiveLong("wechat:group:" + safe(wechatChatId));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static long stablePositiveLong(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (bytes[i] & 0xffL);
            }
            return result & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=WechatIdMapperTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/WechatIdMapper.java \
  napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/WechatIdMapperTest.java
git commit -m "feat: map wechat ids to agent session keys"
```

---

### Task 4: 新增 AgentWechatPoller

**Files:**
- Create: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatPoller.java`
- Test: `napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatPollerTest.java`

- [ ] **Step 1: 写失败测试**

Create `AgentWechatPollerTest.java`:

```java
package com.napcat.starter.wechat;

import com.napcat.starter.config.WechatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class AgentWechatPollerTest {

    @Test
    void pollOnceRepliesToWakeWordMessageOnlyOnce() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = new AgentWechatChat();
        chat.setId("wxid_chat");
        chat.setName("张三");
        chat.setGroup(false);
        client.chats.add(chat);

        AgentWechatMessage message = new AgentWechatMessage();
        message.setLocalId(1L);
        message.setChatId("wxid_chat");
        message.setSender("wxid_user");
        message.setSenderName("张三");
        message.setContent("小助手 你好");
        message.setSelf(false);
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("wake-word");
        props.setWakeWords(List.of("小助手"));

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("你好呀");
                });

        poller.pollOnce();
        poller.pollOnce();

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("张三"));
        assertTrue(prompts.get(0).contains("小助手 你好"));
        assertEquals(List.of("wxid_chat:你好呀"), client.sentTexts);
    }

    @Test
    void pollOnceIgnoresSelfMessage() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = new AgentWechatChat();
        chat.setId("wxid_chat");
        chat.setGroup(false);
        client.chats.add(chat);

        AgentWechatMessage message = new AgentWechatMessage();
        message.setLocalId(2L);
        message.setChatId("wxid_chat");
        message.setSender("me");
        message.setContent("小助手 自己发的");
        message.setSelf(true);
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setIgnoreSelfMessages(true);
        props.setWakeWords(List.of("小助手"));

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("不该回复");
                });

        poller.pollOnce();

        assertTrue(prompts.isEmpty());
        assertTrue(client.sentTexts.isEmpty());
    }

    @Test
    void pollOnceSkipsGroupMessageWhenGroupRepliesDisabled() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = new AgentWechatChat();
        chat.setId("room@chatroom");
        chat.setGroup(true);
        client.chats.add(chat);

        AgentWechatMessage message = new AgentWechatMessage();
        message.setLocalId(3L);
        message.setChatId("room@chatroom");
        message.setSender("wxid_user");
        message.setContent("小助手 群消息");
        message.setSelf(false);
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setReplyToGroupMessages(false);
        props.setWakeWords(List.of("小助手"));

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("不该回复");
                });

        poller.pollOnce();

        assertTrue(prompts.isEmpty());
        assertTrue(client.sentTexts.isEmpty());
    }

    static class FakeClient extends AgentWechatClient {
        List<AgentWechatChat> chats = new ArrayList<>();
        List<AgentWechatMessage> messages = new ArrayList<>();
        List<String> sentTexts = new ArrayList<>();

        FakeClient() {
            super("http://127.0.0.1:6174", "", Duration.ofSeconds(1), new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public List<AgentWechatChat> listChats(int limit, int offset) {
            return chats;
        }

        @Override
        public List<AgentWechatMessage> listMessages(String chatId, int limit, int offset) {
            return messages.stream().filter(m -> chatId.equals(m.getChatId())).toList();
        }

        @Override
        public AgentWechatSendResult sendText(String chatId, String text) {
            sentTexts.add(chatId + ":" + text);
            AgentWechatSendResult result = new AgentWechatSendResult();
            result.setSuccess(true);
            return result;
        }
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=AgentWechatPollerTest test
```

Expected: FAIL because `AgentWechatPoller` does not exist.

- [ ] **Step 3: 实现 AgentWechatPoller**

Create `AgentWechatPoller.java`:

```java
package com.napcat.starter.wechat;

import com.napcat.starter.config.WechatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Slf4j
public class AgentWechatPoller implements SmartLifecycle {

    @FunctionalInterface
    public interface AgentResponder {
        CompletableFuture<String> chat(long userId, long groupId, String prompt);
    }

    private final AgentWechatClient client;
    private final WechatIdMapper idMapper;
    private final WechatProperties props;
    private final AgentResponder responder;
    private final ConcurrentMap<String, Long> lastSeenLocalIds = new ConcurrentHashMap<>();
    private final Set<String> inFlightMessages = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;
    private volatile boolean running;

    public AgentWechatPoller(AgentWechatClient client, WechatIdMapper idMapper,
                             WechatProperties props, AgentResponder responder) {
        this.client = client;
        this.idMapper = idMapper;
        this.props = props;
        this.responder = responder;
    }

    @Override
    public void start() {
        if (running) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-wechat-poller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safePollOnce, 1000,
                Math.max(500, props.getPollIntervalMs()), TimeUnit.MILLISECONDS);
        running = true;
        log.info("AgentWechatPoller started, interval={}ms", props.getPollIntervalMs());
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public void pollOnce() throws Exception {
        List<AgentWechatChat> chats = client.listChats(props.getChatLimit(), 0);
        for (AgentWechatChat chat : chats) {
            if (chat == null || chat.getId() == null || chat.getId().isBlank()) {
                continue;
            }
            if (chat.isGroup() && !props.isReplyToGroupMessages()) {
                continue;
            }
            pollChat(chat);
        }
    }

    private void pollChat(AgentWechatChat chat) throws Exception {
        List<AgentWechatMessage> messages = client.listMessages(chat.getId(), props.getMessageLimit(), 0)
                .stream()
                .filter(m -> m != null && m.getLocalId() > 0)
                .sorted(Comparator.comparingLong(AgentWechatMessage::getLocalId))
                .toList();

        long lastSeen = lastSeenLocalIds.getOrDefault(chat.getId(), 0L);
        long maxSeen = lastSeen;
        for (AgentWechatMessage message : messages) {
            maxSeen = Math.max(maxSeen, message.getLocalId());
            if (message.getLocalId() <= lastSeen) {
                continue;
            }
            handleNewMessage(chat, message);
        }
        lastSeenLocalIds.put(chat.getId(), maxSeen);
    }

    private void handleNewMessage(AgentWechatChat chat, AgentWechatMessage message) {
        if (props.isIgnoreSelfMessages() && Boolean.TRUE.equals(message.getSelf())) {
            return;
        }
        if (message.getContent() == null || message.getContent().isBlank()) {
            return;
        }
        if (!shouldTrigger(message)) {
            return;
        }

        String key = chat.getId() + ":" + message.getLocalId();
        if (!inFlightMessages.add(key)) {
            return;
        }

        long userId = idMapper.toUserId(message.getSender() != null ? message.getSender() : chat.getId());
        long groupId = chat.isGroup() ? idMapper.toGroupId(chat.getId()) : 0L;
        String prompt = buildPrompt(chat, message);

        responder.chat(userId, groupId, prompt)
                .thenAccept(reply -> sendReply(chat.getId(), reply))
                .exceptionally(ex -> {
                    log.warn("Agent reply failed for wechat message {}", key, ex);
                    return null;
                })
                .whenComplete((ignored, ex) -> inFlightMessages.remove(key));
    }

    private boolean shouldTrigger(AgentWechatMessage message) {
        String mode = props.getTriggerMode() == null ? "wake-word" : props.getTriggerMode().trim().toLowerCase();
        String content = message.getContent() == null ? "" : message.getContent();
        return switch (mode) {
            case "all" -> true;
            case "mention-or-wake" -> Boolean.TRUE.equals(message.getMentioned()) || containsWakeWord(content);
            case "wake-word" -> containsWakeWord(content);
            default -> containsWakeWord(content);
        };
    }

    private boolean containsWakeWord(String content) {
        if (props.getWakeWords() == null || props.getWakeWords().isEmpty()) {
            return false;
        }
        for (String word : props.getWakeWords()) {
            if (word != null && !word.isBlank() && content.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String buildPrompt(AgentWechatChat chat, AgentWechatMessage message) {
        String senderName = firstNonBlank(message.getSenderName(), message.getSender(), "未知用户");
        String chatName = firstNonBlank(chat.getName(), chat.getRemark(), chat.getId());
        if (chat.isGroup()) {
            return "[微信/群聊:" + chatName + "] [发送者:" + senderName + "] " + message.getContent();
        }
        return "[微信/私聊:" + chatName + "] [发送者:" + senderName + "] " + message.getContent();
    }

    private void sendReply(String chatId, String reply) {
        if (reply == null || reply.isBlank()) {
            return;
        }
        try {
            AgentWechatSendResult result = client.sendText(chatId, reply.strip());
            if (!result.isSuccess()) {
                log.warn("agent-wechat send failed for chat {}: {}", chatId, result.getError());
            }
        } catch (Exception e) {
            log.warn("Failed to send wechat reply to {}", chatId, e);
        }
    }

    private void safePollOnce() {
        try {
            pollOnce();
        } catch (Exception e) {
            log.warn("AgentWechatPoller poll failed: {}", e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=AgentWechatPollerTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add napcat-spring-boot-starter/src/main/java/com/napcat/starter/wechat/AgentWechatPoller.java \
  napcat-spring-boot-starter/src/test/java/com/napcat/starter/wechat/AgentWechatPollerTest.java
git commit -m "feat: poll agent-wechat messages and reply with agent"
```

---

### Task 5: 自动装配微信 REST 接入

**Files:**
- Modify: `napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/NapCatAutoConfiguration.java`

- [ ] **Step 1: 添加 imports**

Add imports to `NapCatAutoConfiguration.java`:

```java
import com.napcat.starter.wechat.AgentWechatClient;
import com.napcat.starter.wechat.AgentWechatPoller;
import com.napcat.starter.wechat.WechatIdMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
```

- [ ] **Step 2: 添加微信 Bean 配置块**

Add this block before the `// ================================================================ // 后处理器 + 生命周期` section in `NapCatAutoConfiguration.java`:

```java
    // ================================================================
    // WeChat (agent-wechat REST API)
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.wechat", name = "enabled", havingValue = "true")
    public WechatIdMapper wechatIdMapper() {
        return new WechatIdMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.wechat", name = "enabled", havingValue = "true")
    public AgentWechatClient agentWechatClient(WechatProperties props, ObjectMapper mapper) {
        return new AgentWechatClient(props.getApiBaseUrl(), resolveWechatToken(props),
                Duration.ofMillis(props.getApiTimeout()), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.wechat", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    public AgentWechatPoller agentWechatPoller(AgentWechatClient client, WechatIdMapper mapper,
                                               WechatProperties wechatProps, NapCatAgent agent) {
        return new AgentWechatPoller(client, mapper, wechatProps,
                (userId, groupId, prompt) -> agent.chat(userId, groupId, prompt));
    }

    private String resolveWechatToken(WechatProperties props) {
        if (props.getToken() != null && !props.getToken().isBlank()) {
            return props.getToken().trim();
        }
        String tokenFile = props.getTokenFile();
        if (tokenFile == null || tokenFile.isBlank()) {
            return "";
        }
        String expanded = tokenFile.replace("${user.home}", System.getProperty("user.home"));
        try {
            Path path = Path.of(expanded);
            if (Files.exists(path)) {
                return Files.readString(path).trim();
            }
        } catch (Exception e) {
            log.warn("Failed to read agent-wechat token file: {}", expanded, e);
        }
        return "";
    }
```

- [ ] **Step 3: 编译 starter**

Run:

```bash
mvn -pl napcat-spring-boot-starter -am -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add napcat-spring-boot-starter/src/main/java/com/napcat/starter/config/NapCatAutoConfiguration.java
git commit -m "feat: auto-configure agent-wechat REST polling"
```

---

### Task 6: 更新示例配置

**Files:**
- Modify: `napcat-admin/src/main/resources/application.yml`

- [ ] **Step 1: 更新 wechat 配置块**

Modify the existing `napcat.wechat` block in `application.yml` to this exact content:

```yaml
  # ================================================================
  # 微信 Bot 配置（通过 agent-wechat REST API 接入）
  # 先启动 agent-wechat：wx up && wx auth login
  # ================================================================
  wechat:
    enabled: true
    api-base-url: http://127.0.0.1:6174
    token: ${AGENT_WECHAT_TOKEN:}
    token-file: ${AGENT_WECHAT_TOKEN_FILE:${user.home}/.config/agent-wechat/token}
    api-timeout: 30000
    poll-interval-ms: 2000
    chat-limit: 50
    message-limit: 20
    reply-to-group-messages: true
    ignore-self-messages: true
    trigger-mode: wake-word
    wake-words:
      - emiya
      - 小助手
```

- [ ] **Step 2: 编译 admin**

Run:

```bash
mvn -pl napcat-admin -am -DskipTests compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add napcat-admin/src/main/resources/application.yml
git commit -m "docs: configure sample agent-wechat REST settings"
```

---

### Task 7: 全量验证

**Files:**
- No code changes expected.

- [ ] **Step 1: 跑 starter 微信相关测试**

Run:

```bash
mvn -pl napcat-spring-boot-starter -Dtest=AgentWechatClientTest,WechatIdMapperTest,AgentWechatPollerTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 2: 跑全项目编译**

Run:

```bash
mvn install -DskipTests
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: 可选手动联调 agent-wechat**

Run agent-wechat in another terminal:

```bash
wx up
wx auth login
```

Then run this project:

```bash
mvn spring-boot:run -pl napcat-admin
```

Expected logs:

```text
AgentWechatPoller started, interval=2000ms
```

Send a WeChat message containing a configured wake word, such as:

```text
小助手 你好
```

Expected behavior: napcat-java calls the configured LLM and sends one text reply back through agent-wechat.

- [ ] **Step 4: Commit verification note only if code changed**

If verification required code or config fixes, commit them:

```bash
git add <changed-files>
git commit -m "fix: stabilize agent-wechat REST integration"
```

If no files changed, do not commit.

---

## Self-Review

- Spec coverage: plan covers REST client, token auth, config binding, polling messages, duplicate suppression, Agent reply, sample config, and tests.
- Placeholder scan: no TBD/TODO/fill-in placeholders remain in task steps.
- Type consistency: `WechatProperties`, `AgentWechatClient`, DTO field names, and `AgentWechatPoller` constructor are consistent across tests, implementation, and auto-configuration.
