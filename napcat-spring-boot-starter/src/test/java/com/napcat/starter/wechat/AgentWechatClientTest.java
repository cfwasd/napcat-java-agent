package com.napcat.starter.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentWechatClientTest {

    private MockWebServer server;
    private AgentWechatClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new AgentWechatClient(
                server.url("/").toString(),
                "test-token-123",
                Duration.ofSeconds(5),
                mapper
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request, "Expected HTTP request within timeout");
        return request;
    }

    @Test
    void listChats_shouldSendBearerTokenAndParseChats() throws Exception {
        // language=json
        String responseBody = """
                [
                  {
                    "id": "wxid_abc",
                    "username": "testuser",
                    "name": "Test User",
                    "remark": "A friend",
                    "lastMessagePreview": "Hello!",
                    "lastMessageSender": "wxid_abc",
                    "lastActivityAt": "2026-06-12T10:00:00Z",
                    "unreadCount": 3,
                    "isGroup": false,
                    "lastMsgLocalId": 1001
                  }
                ]""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        List<AgentWechatChat> chats = client.listChats(10, 0);

        // Verify request
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/api/chats?limit=10&offset=0", request.getPath());
        assertEquals("Bearer test-token-123", request.getHeader("Authorization"));

        // Verify response parsing
        assertEquals(1, chats.size());
        AgentWechatChat chat = chats.get(0);
        assertEquals("wxid_abc", chat.getId());
        assertEquals("testuser", chat.getUsername());
        assertEquals("Test User", chat.getName());
        assertEquals("A friend", chat.getRemark());
        assertEquals("Hello!", chat.getLastMessagePreview());
        assertEquals("wxid_abc", chat.getLastMessageSender());
        assertEquals("2026-06-12T10:00:00Z", chat.getLastActivityAt());
        assertEquals(3, chat.getUnreadCount());
        assertFalse(chat.isGroup());
        assertEquals(Long.valueOf(1001), chat.getLastMsgLocalId());
    }

    @Test
    void listChats_isGroupTrue_shouldDeserializeCorrectly() throws Exception {
        // language=json
        String responseBody = """
                [
                  {
                    "id": "room_xyz",
                    "username": "groupchat",
                    "name": "Group Chat",
                    "remark": "",
                    "lastMessagePreview": "Hi all",
                    "lastMessageSender": "wxid_def",
                    "lastActivityAt": "2026-06-12T11:00:00Z",
                    "unreadCount": 0,
                    "isGroup": true,
                    "lastMsgLocalId": 2002
                  }
                ]""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        List<AgentWechatChat> chats = client.listChats(5, 0);

        assertEquals(1, chats.size());
        assertTrue(chats.get(0).isGroup());
    }

    @Test
    void listMessages_shouldPathEncodeChatIdAndParseMessages() throws Exception {
        // language=json
        String responseBody = """
                [
                  {
                    "localId": 100001,
                    "serverId": 200001,
                    "chatId": "room@chatroom",
                    "sender": "wxid_sender",
                    "senderName": "Sender Name",
                    "type": 1,
                    "content": "Hello world",
                    "timestamp": "2026-06-12T10:30:00Z",
                    "isMentioned": true,
                    "isSelf": false
                  }
                ]""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        List<AgentWechatMessage> messages = client.listMessages("room@chatroom", 20, 0);

        // Verify request — @ should be encoded to %40
        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/api/messages/room%40chatroom?limit=20&offset=0", request.getPath());
        assertEquals("Bearer test-token-123", request.getHeader("Authorization"));

        // Verify response parsing
        assertEquals(1, messages.size());
        AgentWechatMessage msg = messages.get(0);
        assertEquals(100001L, msg.getLocalId());
        assertEquals(200001L, msg.getServerId());
        assertEquals("room@chatroom", msg.getChatId());
        assertEquals("wxid_sender", msg.getSender());
        assertEquals("Sender Name", msg.getSenderName());
        assertEquals(1, msg.getType());
        assertEquals("Hello world", msg.getContent());
        assertEquals("2026-06-12T10:30:00Z", msg.getTimestamp());
        assertTrue(msg.getMentioned());
        assertFalse(msg.getSelf());
    }

    @Test
    void listMessages_isSelfTrue_shouldDeserializeCorrectly() throws Exception {
        // language=json
        String responseBody = """
                [
                  {
                    "localId": 100002,
                    "serverId": 200002,
                    "chatId": "wxid_me",
                    "sender": "wxid_me",
                    "senderName": "Me",
                    "type": 1,
                    "content": "My message",
                    "timestamp": "2026-06-12T10:31:00Z",
                    "isMentioned": false,
                    "isSelf": true
                  }
                ]""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        List<AgentWechatMessage> messages = client.listMessages("wxid_me", 10, 0);

        assertEquals(1, messages.size());
        AgentWechatMessage msg = messages.get(0);
        assertFalse(msg.getMentioned());
        assertTrue(msg.getSelf());
    }

    @Test
    void sendText_shouldPostJsonBody() throws Exception {
        // language=json
        String responseBody = """
                {
                  "success": true,
                  "messageId": "msg-sent-001",
                  "error": null
                }""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        AgentWechatSendResult result = client.sendText("wxid_a", "你好");

        // Verify request
        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/messages/send", request.getPath());
        assertEquals("Bearer test-token-123", request.getHeader("Authorization"));
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"));

        // Verify request body
        String requestBody = request.getBody().readUtf8();
        assertTrue(requestBody.contains("\"chatId\":\"wxid_a\""));
        assertTrue(requestBody.contains("\"text\":\"你好\""));
        // image and file should not be present
        assertFalse(requestBody.contains("\"image\""));
        assertFalse(requestBody.contains("\"file\""));

        // Verify response parsing
        assertTrue(result.isSuccess());
        assertEquals("msg-sent-001", result.getMessageId());
        assertNull(result.getError());
    }

    @Test
    void sendText_noToken_shouldNotSendAuthHeader() throws Exception {
        // Create a client with empty token
        AgentWechatClient noAuthClient = new AgentWechatClient(
                server.url("/").toString(),
                "",
                Duration.ofSeconds(5),
                mapper
        );

        // language=json
        String responseBody = """
                {
                  "success": true,
                  "messageId": "msg-sent-002",
                  "error": null
                }""";

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        noAuthClient.sendText("wxid_a", "hello");

        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/messages/send", request.getPath());
        assertNull(request.getHeader("Authorization"));
    }

    @Test
    void listChats_non2xx_shouldThrowIOException() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "text/plain")
                .setBody("Internal Server Error"));

        IOException ex = assertThrows(IOException.class, () -> client.listChats(10, 0));
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("Internal Server Error"));
    }

    @Test
    void listChats_noToken_shouldNotSendAuthHeader() throws Exception {
        // Create a client with empty token
        AgentWechatClient noAuthClient = new AgentWechatClient(
                server.url("/").toString(),
                "",
                Duration.ofSeconds(5),
                mapper
        );

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        noAuthClient.listChats(10, 0);

        RecordedRequest request = takeRequest();
        assertNull(request.getHeader("Authorization"));
    }

    @Test
    void baseUrl_trailingSlash_shouldBeStripped() throws Exception {
        // URL with trailing slash — should still work
        AgentWechatClient slashClient = new AgentWechatClient(
                server.url("/").toString(),  // MockWebServer URL already has trailing /
                "token",
                Duration.ofSeconds(5),
                mapper
        );

        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("[]"));

        slashClient.listChats(1, 0);

        RecordedRequest request = takeRequest();
        // Path should NOT have double slash
        assertEquals("/api/chats?limit=1&offset=0", request.getPath());
    }

    @Test
    void getMedia_shouldPathEncodeChatIdAndParseMedia() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "type": "image",
                          "data": "aGVsbG8=",
                          "url": null,
                          "format": "png",
                          "filename": "pic.png"
                        }"""));

        AgentWechatMediaResult result = client.getMedia("room@chatroom", 123L);

        RecordedRequest request = takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/api/messages/room%40chatroom/media/123", request.getPath());
        assertEquals("Bearer test-token-123", request.getHeader("Authorization"));
        assertEquals("image", result.getType());
        assertEquals("aGVsbG8=", result.getData());
        assertEquals("png", result.getFormat());
        assertEquals("pic.png", result.getFilename());
    }

    @Test
    void sendImage_shouldPostImageJsonBody() throws Exception {
        Path imageFile = Files.createTempFile("agent-wechat-test", ".png");
        Files.write(imageFile, new byte[]{1, 2, 3});
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"messageId\":\"img-001\"}"));

        AgentWechatSendResult result = client.sendImage("wxid_a", imageFile.toString());

        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/messages/send", request.getPath());
        String requestBody = request.getBody().readUtf8();
        assertTrue(requestBody.contains("\"chatId\":\"wxid_a\""));
        assertTrue(requestBody.contains("\"image\":{"));
        assertTrue(requestBody.contains("\"data\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\""));
        assertTrue(requestBody.contains("\"mimeType\":\"image/png\""));
        assertFalse(requestBody.contains("\"text\""));
        assertFalse(requestBody.contains("\"file\""));
        assertTrue(result.isSuccess());
        assertEquals("img-001", result.getMessageId());
    }

    @Test
    void sendImage_url_shouldDownloadAndPostImageData() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/png")
                .setBody(new okio.Buffer().write(new byte[]{7, 8, 9})));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"messageId\":\"img-url-001\"}"));

        AgentWechatSendResult result = client.sendImage("wxid_a", server.url("/image.png").toString());

        RecordedRequest imageRequest = takeRequest();
        assertEquals("GET", imageRequest.getMethod());
        assertEquals("/image.png", imageRequest.getPath());

        RecordedRequest sendRequest = takeRequest();
        String requestBody = sendRequest.getBody().readUtf8();
        assertTrue(requestBody.contains("\"image\":{"));
        assertTrue(requestBody.contains("\"data\":\"" + Base64.getEncoder().encodeToString(new byte[]{7, 8, 9}) + "\""));
        assertTrue(requestBody.contains("\"mimeType\":\"image/png\""));
        assertTrue(result.isSuccess());
    }

    @Test
    void sendFile_shouldPostFileJsonBody() throws Exception {
        Path file = Files.createTempFile("agent-wechat-test", ".zip");
        Files.write(file, new byte[]{4, 5, 6});
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"messageId\":\"file-001\"}"));

        AgentWechatSendResult result = client.sendFile("wxid_a", file.toString());

        RecordedRequest request = takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/messages/send", request.getPath());
        String requestBody = request.getBody().readUtf8();
        assertTrue(requestBody.contains("\"chatId\":\"wxid_a\""));
        assertTrue(requestBody.contains("\"file\":{"));
        assertTrue(requestBody.contains("\"data\":\"" + Base64.getEncoder().encodeToString(new byte[]{4, 5, 6}) + "\""));
        assertTrue(requestBody.contains("\"filename\":\"" + file.getFileName() + "\""));
        assertFalse(requestBody.contains("\"text\""));
        assertFalse(requestBody.contains("\"image\""));
        assertTrue(result.isSuccess());
        assertEquals("file-001", result.getMessageId());
    }

    @Test
    void sendImage_blankImage_shouldThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> client.sendImage("wxid_a", " "));
    }

    @Test
    void sendFile_blankFile_shouldThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> client.sendFile("wxid_a", " "));
    }
}
