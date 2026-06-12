package com.napcat.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.napcat.agent.llm.ChatMessage;
import com.napcat.agent.llm.LlmProvider;
import com.napcat.agent.llm.LlmResponse;
import com.napcat.agent.session.Session;
import com.napcat.agent.tool.ToolSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class OpenAiProvider implements LlmProvider {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final long timeout;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client;

    public OpenAiProvider(String baseUrl, String apiKey, String model, int maxTokens, double temperature, long timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.timeout = timeout;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public String getProviderName() {
        return "openai";
    }

    @Override
    public CompletableFuture<LlmResponse> chat(Session session, String input, List<ToolSchema> tools) {
        CompletableFuture<LlmResponse> future = new CompletableFuture<>();

        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            root.put("temperature", temperature);

            ArrayNode messages = root.putArray("messages");
            for (ChatMessage msg : session.getHistory()) {
                ObjectNode node = messages.addObject();
                node.put("role", msg.getRole());

                // 多模态消息：text + image_url array
                if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                    ArrayNode contentArray = node.putArray("content");
                    if (msg.getContent() != null && !msg.getContent().isBlank()) {
                        ObjectNode textNode = contentArray.addObject();
                        textNode.put("type", "text");
                        textNode.put("text", msg.getContent());
                    }
                    for (String imgUrl : msg.getImageUrls()) {
                        ObjectNode imgNode = contentArray.addObject();
                        imgNode.put("type", "image_url");
                        ObjectNode imgUrlObj = imgNode.putObject("image_url");
                        imgUrlObj.put("url", imgUrl);
                    }
                } else {
                    node.put("content", msg.getContent() == null ? "" : msg.getContent());
                }

                if (msg.getName() != null) {
                    node.put("name", msg.getName());
                }
                // tool 角色消息需要包含 tool_call_id
                if ("tool".equals(msg.getRole()) && msg.getToolCallId() != null) {
                    node.put("tool_call_id", msg.getToolCallId());
                }
                // 序列化 assistant 的 tool_calls
                if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                    ArrayNode tcArray = node.putArray("tool_calls");
                    for (ChatMessage.ToolCallData tc : msg.getToolCalls()) {
                        ObjectNode tcNode = tcArray.addObject();
                        tcNode.put("id", tc.getId());
                        tcNode.put("type", tc.getType());
                        ObjectNode fnNode = tcNode.putObject("function");
                        fnNode.put("name", tc.getFunction().getName());
                        fnNode.put("arguments", tc.getFunction().getArguments());
                    }
                }
            }

            if (tools != null && !tools.isEmpty()) {
                ArrayNode toolsNode = root.putArray("tools");
                for (ToolSchema tool : tools) {
                    ObjectNode toolNode = toolsNode.addObject();
                    toolNode.put("type", "function");
                    ObjectNode func = toolNode.putObject("function");
                    func.put("name", tool.getName());
                    func.put("description", tool.getDescription());
                    ObjectNode params = func.putObject("parameters");
                    params.put("type", "object");
                    ObjectNode props = params.putObject("properties");
                    for (var entry : tool.getParameters().entrySet()) {
                        ObjectNode prop = props.putObject(entry.getKey());
                        prop.put("type", entry.getValue().getType());
                        prop.put("description", entry.getValue().getDescription());
                        if (entry.getValue().getEnums() != null && !entry.getValue().getEnums().isEmpty()) {
                            ArrayNode enums = prop.putArray("enum");
                            entry.getValue().getEnums().forEach(enums::add);
                        }
                    }
                    ArrayNode req = params.putArray("required");
                    if (tool.getRequired() != null) {
                        tool.getRequired().forEach(req::add);
                    }
                }
            }

            String json = mapper.writeValueAsString(root);
            log.error("OpenAI request: {}", json);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .post(body);
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            client.newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    log.error("OpenAI request failed", e);
                    future.completeExceptionally(e);
                }

                @Override
                public void onResponse(Call call, Response response) throws java.io.IOException {
                    try (ResponseBody body = response.body()) {
                        if (body == null) {
                            future.completeExceptionally(new RuntimeException("Empty response"));
                            return;
                        }
                        String respJson = body.string();
                        log.debug("OpenAI response: {}", respJson);

                        if (!response.isSuccessful()) {
                            int statusCode = response.code();
                            String errorBody = respJson;

                            // 图片加载失败检测（优先于状态码判断，因为有些API对图片问题返回400而非500）
                            boolean isImageLoadError = errorBody.contains("IMAGE data") ||
                                                      errorBody.contains("loading data ImageData") ||
                                                      errorBody.contains("image down failed") ||
                                                      errorBody.contains("multimedia.nt.qq.com.cn") ||
                                                      errorBody.contains("image_url") ||
                                                      errorBody.contains("image call") ||
                                                      errorBody.contains("inspection failed") ||
                                                      (statusCode == 400 && sessionHasImages(session));

                            if (isImageLoadError) {
                                log.warn("Image loading failed (status={}) - LLM server cannot access image URLs. Error: {}", statusCode, errorBody);
                                future.completeExceptionally(new RuntimeException("图片加载失败: LLM服务器无法访问图片链接"));
                            } else if (statusCode >= 400 && statusCode < 500) {
                                log.warn("OpenAI API client error ({}): {}", statusCode, errorBody);
                                future.completeExceptionally(new RuntimeException("API请求错误: " + statusCode));
                            } else {
                                log.error("OpenAI API server error ({}): {}", statusCode, errorBody);
                                future.completeExceptionally(new RuntimeException("服务器错误: " + statusCode));
                            }
                            return;
                        }

                        LlmResponse llmResp = parseResponse(respJson);
                        future.complete(llmResp);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * 检查 session 历史中是否包含图片消息。
     * 用于在 400 错误时判断是否可能是图片导致的（兜底检测）。
     */
    private boolean sessionHasImages(Session session) {
        if (session == null) return false;
        for (com.napcat.agent.llm.ChatMessage msg : session.getHistory()) {
            if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                return true;
            }
        }
        return false;
    }


    private LlmResponse parseResponse(String json) {
        try {
            JsonNode root = mapper.readTree(json);

            if (root.has("error")) {
                JsonNode error = root.get("error");
                String errorMsg = error.path("message").asText("Unknown error");
                throw new RuntimeException("OpenAI API error: " + errorMsg);
            }

            JsonNode choice = root.path("choices").get(0);
            if (choice == null || choice.isNull()) {
                throw new RuntimeException("No choices in OpenAI response");
            }

            JsonNode message = choice.path("message");
            if (message == null || message.isNull()) {
                throw new RuntimeException("No message in OpenAI response choice");
            }

            LlmResponse response = new LlmResponse();
            
            // 解析 reasoning/thinking 内容（不同模型字段名不同）
            if (message.has("reasoning_content") && !message.path("reasoning_content").isNull()) {
                response.setReasoningContent(message.path("reasoning_content").asText());
            } else if (message.has("reasoning") && !message.path("reasoning").isNull()) {
                response.setReasoningContent(message.path("reasoning").asText());
            } else if (message.has("thinking") && !message.path("thinking").isNull()) {
                response.setReasoningContent(message.path("thinking").asText());
            }
            
            response.setContent(message.path("content").asText(null));

            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                List<LlmResponse.ToolCall> calls = new java.util.ArrayList<>();
                for (JsonNode tc : toolCalls) {
                    LlmResponse.ToolCall call = new LlmResponse.ToolCall();
                    call.setId(tc.path("id").asText());
                    call.setName(tc.path("function").path("name").asText());
                    call.setArguments(tc.path("function").path("arguments").asText());
                    calls.add(call);
                }
                response.setToolCalls(calls);
            }

            return response;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OpenAI response", e);
        }
    }
}
