package com.napcat.starter.wechat;

import com.napcat.core.adapter.BotAdapter;
import com.napcat.core.api.ApiRequest;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.annotation.Command;
import com.napcat.core.annotation.OnGroupMessage;
import com.napcat.core.annotation.OnPrivateMessage;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.GroupMessageEvent;
import com.napcat.core.event.PrivateMessageEvent;
import com.napcat.core.handler.HandlerRegistry;
import com.napcat.core.message.MessageChain;
import com.napcat.starter.config.WechatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentWechatPollerTest {

    @Test
    void pollOnceRepliesToWakeWordMessageOnlyOnce() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_chat")
                .name("张三")
                .isGroup(false)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(1L)
                .chatId("wxid_chat")
                .sender("wxid_user")
                .senderName("张三")
                .content("小助手 你好")
                .self(false)
                .build();
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
        assertTrue(prompts.get(0).contains("你好"));
        assertFalse(prompts.get(0).contains("小助手"));
        assertEquals(List.of("wxid_chat:你好呀"), client.sentTexts);
    }

    @Test
    void pollOnceIgnoresSelfMessage() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_chat")
                .isGroup(false)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(2L)
                .chatId("wxid_chat")
                .sender("me")
                .content("小助手 自己发的")
                .self(true)
                .build();
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
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .isGroup(true)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(3L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .content("小助手 群消息")
                .self(false)
                .build();
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

    @Test
    void privateAllGroupMentionOrWakeRepliesToPrivateMessageWithoutWakeWord() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_private")
                .name("王五")
                .isGroup(false)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(4L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .senderName("王五")
                .content("你好")
                .self(false)
                .build();
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setWakeWords(List.of("小助手"));

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("自动回复");
                });

        poller.pollOnce();

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("你好"));
        assertEquals(List.of("wxid_private:自动回复"), client.sentTexts);
    }

    @Test
    void privateAllGroupMentionOrWakeSkipsGroupMessageWithoutMentionOrWakeWord() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .name("测试群")
                .isGroup(true)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(5L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .content("大家好")
                .mentioned(false)
                .self(false)
                .build();
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
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
    void privateAllGroupMentionOrWakeRepliesToMentionedGroupMessage() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .name("测试群")
                .isGroup(true)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(6L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .senderName("赵六")
                .content("你好")
                .mentioned(true)
                .self(false)
                .build();
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setWakeWords(List.of("小助手"));

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("群聊艾特回复");
                });

        poller.pollOnce();

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("赵六"));
        assertEquals(List.of("room@chatroom:群聊艾特回复"), client.sentTexts);
    }

    @Test
    void wakeWordTriggerRemovesWakeWordBeforeCallingAgent() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .name("测试群")
                .isGroup(true)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(7L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .senderName("钱七")
                .content("小助手 /help")
                .mentioned(false)
                .self(false)
                .build();
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setWakeWords(List.of("小助手"));

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("agent reply");
                });

        poller.pollOnce();

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("/help"));
        assertFalse(prompts.get(0).contains("小助手"));
    }

    @Test
    void wakeWordCommandDispatchesToRegisteredCommandBeforeAgent() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .name("测试群")
                .isGroup(true)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(8L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .senderName("钱七")
                .content("小助手 /ping")
                .mentioned(false)
                .self(false)
                .build();
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setWakeWords(List.of("小助手"));

        BotProperties botProperties = new BotProperties();
        botProperties.setCommandPrefix("");
        HandlerRegistry registry = new HandlerRegistry(botProperties);
        registry.registerBean(new PingCommandBot());

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("agent should not reply");
                }, registry);

        poller.pollOnce();

        assertTrue(prompts.isEmpty());
        assertEquals(List.of("room@chatroom:pong"), client.sentTexts);
    }

    @Test
    void privateCommandDispatchesToPrivateCommandHandler() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_private")
                .name("私聊")
                .isGroup(false)
                .build();
        client.chats.add(chat);

        AgentWechatMessage message = AgentWechatMessage.builder()
                .localId(9L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .senderName("私聊用户")
                .content("/private-ping")
                .self(false)
                .build();
        client.messages.add(message);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setWakeWords(List.of("小助手"));

        BotProperties botProperties = new BotProperties();
        botProperties.setCommandPrefix("");
        HandlerRegistry registry = new HandlerRegistry(botProperties);
        registry.registerBean(new PingCommandBot());

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("agent should not reply");
                }, registry);

        poller.pollOnce();

        assertTrue(prompts.isEmpty());
        assertEquals(List.of("wxid_private:private pong"), client.sentTexts);
    }

    @Test
    void startInitializesLastSeenImmediatelyWithoutReplyingHistory() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_private")
                .name("私聊")
                .isGroup(false)
                .build();
        client.chats.add(chat);
        client.messages.add(AgentWechatMessage.builder()
                .localId(10L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .content("你好")
                .self(false)
                .build());

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setPollIntervalMs(60_000);

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("马上回复");
                });

        poller.start();
        Thread.sleep(100);
        poller.stop();

        assertTrue(prompts.isEmpty());
        assertTrue(client.sentTexts.isEmpty());
    }

    @Test
    void startOnlyMarksExistingMessagesAsSeen() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_private")
                .name("私聊")
                .isGroup(false)
                .build();
        client.chats.add(chat);
        client.messages.add(AgentWechatMessage.builder()
                .localId(11L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .content("历史消息")
                .self(false)
                .build());

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setPollIntervalMs(60_000);

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("不应该回复历史消息");
                });

        poller.start();
        Thread.sleep(100);
        assertTrue(prompts.isEmpty());
        client.messages.add(AgentWechatMessage.builder()
                .localId(12L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .content("新消息")
                .self(false)
                .build());
        poller.pollOnce();
        poller.stop();

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("新消息"));
        assertFalse(prompts.get(0).contains("历史消息"));
        assertEquals(List.of("wxid_private:不应该回复历史消息"), client.sentTexts);
    }

    @Test
    void commandReplyMessageChainSendsTextAndImageAndFile() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .name("测试群")
                .isGroup(true)
                .build();
        client.chats.add(chat);
        client.messages.add(AgentWechatMessage.builder()
                .localId(13L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .senderName("钱七")
                .content("小助手 /media")
                .mentioned(false)
                .self(false)
                .build());

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");
        props.setWakeWords(List.of("小助手"));

        BotProperties botProperties = new BotProperties();
        botProperties.setCommandPrefix("");
        HandlerRegistry registry = new HandlerRegistry(botProperties);
        registry.registerBean(new PingCommandBot());

        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> CompletableFuture.completedFuture("agent should not reply"), registry);

        poller.pollOnce();

        assertEquals(List.of(
                "text:room@chatroom:文字",
                "image:room@chatroom:https://example.com/a.png",
                "file:room@chatroom:/tmp/a.zip"
        ), client.sentMessages);
    }

    @Test
    void agentReplyTextWithImageAndFileMarkersSendsMedia() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_private")
                .name("私聊")
                .isGroup(false)
                .build();
        client.chats.add(chat);
        client.messages.add(AgentWechatMessage.builder()
                .localId(14L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .senderName("私聊用户")
                .content("发图和文件")
                .self(false)
                .build());

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");

        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> CompletableFuture.completedFuture(
                        "先看图 [IMAGE:url=https://example.com/b.png] 再收文件 [FILE:path=/tmp/b.zip] 完事"));

        poller.pollOnce();

        assertEquals(List.of(
                "text:wxid_private:先看图",
                "image:wxid_private:https://example.com/b.png",
                "text:wxid_private:再收文件",
                "file:wxid_private:/tmp/b.zip",
                "text:wxid_private:完事"
        ), client.sentMessages);
    }

    @Test
    void privateImageMessageFetchesMediaAndPassesImageToAgent() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("wxid_private")
                .name("私聊")
                .isGroup(false)
                .build();
        client.chats.add(chat);
        client.messages.add(AgentWechatMessage.builder()
                .localId(15L)
                .chatId("wxid_private")
                .sender("wxid_user")
                .senderName("私聊用户")
                .type(3)
                .content("")
                .self(false)
                .build());
        AgentWechatMediaResult media = new AgentWechatMediaResult();
        media.setType("image");
        media.setData("aGVsbG8=");
        media.setFormat("png");
        media.setFilename("pic.png");
        client.mediaResults.add(media);

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("看到了");
                });

        poller.pollOnce();

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("[图片:data:image/png;base64,aGVsbG8=]"));
        assertTrue(prompts.get(0).contains("图片已附在本条消息中"));
        assertTrue(prompts.get(0).contains("不要调用 fetch_url"));
        assertEquals(List.of("wxid_private:15"), client.mediaRequests);
        assertEquals(List.of("wxid_private:看到了"), client.sentTexts);
    }

    @Test
    void groupImageMessageDoesNotFetchMediaOrTriggerAgent() throws Exception {
        FakeClient client = new FakeClient();
        AgentWechatChat chat = AgentWechatChat.builder()
                .id("room@chatroom")
                .name("群聊")
                .isGroup(true)
                .build();
        client.chats.add(chat);
        client.messages.add(AgentWechatMessage.builder()
                .localId(16L)
                .chatId("room@chatroom")
                .sender("wxid_user")
                .senderName("群友")
                .type(3)
                .content("")
                .self(false)
                .build());

        WechatProperties props = new WechatProperties();
        props.setTriggerMode("private-all-group-mention-or-wake");

        List<String> prompts = new ArrayList<>();
        AgentWechatPoller poller = new AgentWechatPoller(
                client, new WechatIdMapper(), props,
                (userId, groupId, prompt) -> {
                    prompts.add(prompt);
                    return CompletableFuture.completedFuture("不该回复");
                });

        poller.pollOnce();

        assertTrue(prompts.isEmpty());
        assertTrue(client.mediaRequests.isEmpty());
        assertTrue(client.sentTexts.isEmpty());
    }

    static class PingCommandBot {
        @OnGroupMessage
        @Command("/ping")
        public String ping(GroupMessageEvent event) {
            return "pong";
        }

        @OnPrivateMessage
        @Command("/private-ping")
        public String privatePing(PrivateMessageEvent event) {
            return "private pong";
        }

        @OnGroupMessage
        @Command("/media")
        public MessageChain media(GroupMessageEvent event) {
            return MessageChain.ofText("文字")
                    .image("https://example.com/a.png")
                    .file("/tmp/a.zip", "a.zip");
        }
    }

    static class FakeClient extends AgentWechatClient {
        List<AgentWechatChat> chats = new ArrayList<>();
        List<AgentWechatMessage> messages = new ArrayList<>();
        List<String> sentTexts = new ArrayList<>();
        List<String> sentMessages = new ArrayList<>();
        List<AgentWechatMediaResult> mediaResults = new ArrayList<>();
        List<String> mediaRequests = new ArrayList<>();

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
        public AgentWechatMediaResult getMedia(String chatId, long localId) {
            mediaRequests.add(chatId + ":" + localId);
            return mediaResults.isEmpty() ? null : mediaResults.remove(0);
        }

        @Override
        public AgentWechatSendResult sendText(String chatId, String text) {
            sentTexts.add(chatId + ":" + text);
            sentMessages.add("text:" + chatId + ":" + text);
            AgentWechatSendResult result = new AgentWechatSendResult();
            result.setSuccess(true);
            return result;
        }

        @Override
        public AgentWechatSendResult sendImage(String chatId, String image) {
            sentMessages.add("image:" + chatId + ":" + image);
            AgentWechatSendResult result = new AgentWechatSendResult();
            result.setSuccess(true);
            return result;
        }

        @Override
        public AgentWechatSendResult sendFile(String chatId, String file) {
            sentMessages.add("file:" + chatId + ":" + file);
            AgentWechatSendResult result = new AgentWechatSendResult();
            result.setSuccess(true);
            return result;
        }
    }
}
