package com.napcat.agent.agent;

import com.napcat.agent.core.AgentEngine;
import com.napcat.agent.llm.ChatMessage;
import com.napcat.agent.llm.LlmProvider;
import com.napcat.agent.llm.LlmResponse;
import com.napcat.agent.memory.MemoryExtractor;
import com.napcat.agent.memory.MemoryStore;
import com.napcat.agent.session.Session;
import com.napcat.agent.session.SessionKey;
import com.napcat.agent.session.SessionManager;
import com.napcat.agent.tool.ToolRegistry;
import com.napcat.agent.tool.ToolSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class NapCatAgent {

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final MemoryStore memoryStore;
    private final java.util.function.Supplier<MemoryExtractor> memoryExtractorSupplier;
    private final String defaultSystemPrompt;
    private final int defaultMaxRounds;
    private final boolean enableVision;
    private PersonaManager personaManager;
    private final AgentEngine agentEngine;

    public NapCatAgent(LlmProvider llmProvider, ToolRegistry toolRegistry, SessionManager sessionManager,
                       String defaultSystemPrompt, int defaultMaxRounds) {
        this(llmProvider, toolRegistry, sessionManager, null,
                (java.util.function.Supplier<MemoryExtractor>) null, defaultSystemPrompt, defaultMaxRounds, true);
    }

    public NapCatAgent(LlmProvider llmProvider, ToolRegistry toolRegistry, SessionManager sessionManager,
                       String defaultSystemPrompt, int defaultMaxRounds, boolean enableVision) {
        this(llmProvider, toolRegistry, sessionManager, null,
                (java.util.function.Supplier<MemoryExtractor>) null, defaultSystemPrompt, defaultMaxRounds, enableVision);
    }

    public NapCatAgent(LlmProvider llmProvider, ToolRegistry toolRegistry, SessionManager sessionManager,
                       MemoryStore memoryStore, java.util.function.Supplier<MemoryExtractor> memoryExtractorSupplier,
                       String defaultSystemPrompt, int defaultMaxRounds, boolean enableVision) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.memoryStore = memoryStore;
        this.memoryExtractorSupplier = memoryExtractorSupplier;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.defaultMaxRounds = defaultMaxRounds;
        this.enableVision = enableVision;
        this.agentEngine = new AgentEngine(llmProvider, toolRegistry,
                AgentConfig.builder().maxRounds(defaultMaxRounds).build());
    }

    private MemoryExtractor getMemoryExtractor() {
        return memoryExtractorSupplier != null ? memoryExtractorSupplier.get() : null;
    }

    public void setPersonaManager(PersonaManager personaManager) {
        this.personaManager = personaManager;
    }

    // ========= 便捷方法：仅 userId（私聊场景，保持向后兼容） =========

    /** @deprecated 使用 {@link #chat(long, long, String)} 明确指定 groupId */
    @Deprecated
    public CompletableFuture<String> chat(long userId, String input) {
        return chat(SessionKey.ofPrivate(userId), input,
                AgentConfig.builder()
                        .maxRounds(defaultMaxRounds)
                        .systemPrompt(defaultSystemPrompt)
                        .build(),
                null);
    }

    // ========= 推荐方法：userId + groupId =========

    /**
     * 群聊场景：自动使用 userId + groupId 复合键隔离会话。
     *
     * @param userId  用户 QQ 号
     * @param groupId 群号（私聊时传 0 或使用 {@link SessionKey#PRIVATE}）
     * @param input   用户输入文本
     */
    public CompletableFuture<String> chat(long userId, long groupId, String input) {
        return chat(new SessionKey(userId, groupId), input,
                AgentConfig.builder()
                        .maxRounds(defaultMaxRounds)
                        .systemPrompt(defaultSystemPrompt)
                        .build(),
                null);
    }

    /**
     * 群聊 + 自定义配置 + 工具回调。
     *
     * @param userId             用户 QQ 号
     * @param groupId            群号（私聊时传 0）
     * @param input              用户输入
     * @param config             Agent 配置
     * @param toolProcessConsumer 工具执行过程回调（可为 null）
     */
    public CompletableFuture<String> chat(long userId, long groupId, String input, AgentConfig config,
                                          Consumer<String> toolProcessConsumer) {
        return chat(new SessionKey(userId, groupId), input, config, toolProcessConsumer);
    }

    // ========= 底层方法：SessionKey =========

    /**
     * 以 SessionKey 发起对话。
     */
    public CompletableFuture<String> chat(SessionKey sessionKey, String input, AgentConfig config,
                                          Consumer<String> toolProcessConsumer) {
        Session session = sessionManager.get(sessionKey);

        // 新会话时注入 system prompt，并自动追加可用工具说明
        if (session.getHistory().isEmpty()) {
            String prompt = buildEffectivePrompt(sessionKey, config);
            if (prompt != null && !prompt.isBlank()) {
                // 自动记忆注入已关闭，改由 Agent 通过 retrieve_memory 工具按需检索
                session.addMessage(new ChatMessage("system", prompt, null));
            }
        }

        String safeInput = (input != null) ? input : "";
        ChatMessage userMsg = new ChatMessage("user", safeInput, null);
        if (enableVision) {
            java.util.List<String> imageUrls = extractImageUrls(safeInput);
            if (!imageUrls.isEmpty()) {
                userMsg.setImageUrls(imageUrls);
                // 文本中保留 [图片] 占位，避免 URL 重复干扰模型
                userMsg.setContent(safeInput.replaceAll("\\[图片:[^\\]]+\\]", "[图片]"));
            }
        } else {
            // 如果禁用图片功能，移除图片标记
            if (safeInput.contains("[图片:")) {
                userMsg.setContent(safeInput.replaceAll("\\[图片:[^\\]]+\\]", "[图片]"));
                log.debug("[Agent] Vision disabled, ignored image URLs in message");
            }
        }
        session.addMessage(userMsg);

        // 消息确认：立即通知用户"已收到，正在处理"
        if (config.getAckCallback() != null) {
            try {
                config.getAckCallback().run();
            } catch (Exception e) {
                log.warn("[Agent] Ack callback failed for {}", sessionKey, e);
            }
        }

        return reactLoop(session, config, 0, toolProcessConsumer);
    }

    /**
     * 构建最终 system prompt = 用户配置的 systemPrompt + 可用工具清单。
     * 工具清单确保即使模型 function-calling 能力弱，也能从文本中了解可用工具。
     */
    /**
     * 遍历异常链，提取完整错误信息。
     * FallbackLlmProvider 会包装原始异常，导致 ex.getMessage() 丢失根因信息。
     */
    private static String extractFullErrorMessage(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                if (sb.length() > 0) sb.append(" | ");
                sb.append(current.getMessage());
            }
            current = current.getCause();
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 清除 session 历史中所有消息的图片 URL，避免后续请求因图片加载失败而持续报错。
     */
    private static void clearImageUrls(Session session) {
        for (ChatMessage msg : session.getHistory()) {
            if (msg.getImageUrls() != null && !msg.getImageUrls().isEmpty()) {
                log.debug("[Agent] Clearing image URLs from session message: role={}", msg.getRole());
                msg.setImageUrls(null);
            }
        }
    }

    /**
     * 从用户输入文本中提取图片 URL（[图片:xxx] 格式）。只保留 http/https 协议的可访问地址。
     */
    private static java.util.List<String> extractImageUrls(String input) {
        if (input == null || input.isBlank()) return java.util.Collections.emptyList();
        java.util.List<String> urls = new java.util.ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\[图片:([^\\]]+)\\]");
        java.util.regex.Matcher matcher = pattern.matcher(input);
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            if (url.isEmpty()) continue;
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:image/")) {
                urls.add(url);
            } else {
                log.warn("[Agent] Ignoring unsupported image URL: {}", url);
            }
        }
        if (!urls.isEmpty()) {
            log.debug("[Agent] Extracted image URLs: {}", urls);
        }
        return urls;
    }

    private String buildEffectivePrompt(AgentConfig config) {
        return buildEffectivePrompt(null, config);
    }

    /**
     * 构建最终 system prompt = 人格 prompt（优先）或用户配置的 systemPrompt + 可用工具清单。
     * 工具清单确保即使模型 function-calling 能力弱，也能从文本中了解可用工具。
     *
     * @param sessionKey 当前会话键，用于查询 PersonaManager 获取激活人格。可为 null。
     * @param config     Agent 配置
     */
    private String buildEffectivePrompt(SessionKey sessionKey, AgentConfig config) {
        StringBuilder sb = new StringBuilder();

        // 优先级：PersonaManager 激活人格 > AgentConfig 自定义 > 默认 systemPrompt
        String prompt = null;
        if (sessionKey != null && personaManager != null && !personaManager.isEmpty()) {
            prompt = personaManager.getActiveSystemPrompt(sessionKey);
        }
        if (prompt == null || prompt.isBlank()) {
            prompt = config.getSystemPrompt();
        }
        if (prompt == null || prompt.isBlank()) {
            prompt = defaultSystemPrompt;
        }
        if (prompt != null && !prompt.isBlank()) {
            sb.append(prompt);
        }

        // disableTools 时不在 system prompt 中注入工具说明，避免 LLM 文本层面误调用
        if (!config.isDisableTools()) {
            List<ToolSchema> tools = toolRegistry.getSchemas();
            if (!tools.isEmpty()) {
                sb.append("\n\n可用工具：\n");
                for (ToolSchema tool : tools) {
                    sb.append("\n- **").append(tool.getName()).append("**：").append(tool.getDescription()).append("\n");
                    if (tool.getParameters() != null && !tool.getParameters().isEmpty()) {
                        sb.append("  参数：\n");
                        for (var entry : tool.getParameters().entrySet()) {
                            boolean required = tool.getRequired() != null && tool.getRequired().contains(entry.getKey());
                            sb.append("    - ").append(entry.getKey())
                              .append(" (").append(entry.getValue().getType()).append(")")
                              .append(required ? " [必填]" : " [可选]")
                              .append("：").append(entry.getValue().getDescription()).append("\n");
                        }
                    }
                }
                sb.append("\n仅在用户明确要求时才调用工具，非必要不调用。\n");
            }
        }

        // 追加格式引导
        sb.append("\n\n【格式要求】请使用 Markdown 格式回复，支持加粗、列表、代码块等。如需发送图片，使用 [IMAGE:url=图片地址] 格式。");
        return sb.toString().trim().isEmpty() ? null : sb.toString().trim();
    }

    private CompletableFuture<String> reactLoop(Session session, AgentConfig config, int round,
                                                 Consumer<String> toolProcessConsumer) {
        // 委托给 AgentEngine 执行核心 ReAct 循环
        // 注意：AgentEngine 不包含记忆提取和错误处理逻辑，此处保留完整实现
        return doReactLoop(session, config, round, toolProcessConsumer);
    }

    /**
     * 实际的 ReAct 循环实现。
     * 与 AgentEngine.reactLoop 逻辑一致，但保留了：
     * - 记忆提取（MemoryExtractor）
     * - 详细的错误分类处理（图片加载失败、认证错误、超时等）
     * - 工具执行过程回调
     */
    private CompletableFuture<String> doReactLoop(Session session, AgentConfig config, int round,
                                                 Consumer<String> toolProcessConsumer) {
        if (round >= config.getMaxRounds()) {
            String msg = "思考次数过多，请简化问题。";
            session.addMessage(new ChatMessage("assistant", msg, null));
            return CompletableFuture.completedFuture(msg);
        }

        log.debug("[Agent] Round {}/{} for {}", round + 1, config.getMaxRounds(), session.getKey());

        List<ToolSchema> tools = config.isDisableTools() ? java.util.Collections.emptyList() : toolRegistry.getSchemas();

        return llmProvider.chat(session, null, tools)
                .thenCompose(response -> {
                    if (response.hasToolCalls()) {
                        // 记录 assistant tool_calls 到历史（包含 reasoning_content）
                        ChatMessage assistantMsg = ChatMessage.fromToolCalls(response.getToolCalls());
                        if (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty()) {
                            assistantMsg.setReasoningContent(response.getReasoningContent());
                        }
                        session.addMessage(assistantMsg);

                        for (LlmResponse.ToolCall tc : response.getToolCalls()) {
                            log.debug("[Agent] Tool call: {}({})", tc.getName(), tc.getArguments());
                            Object result = toolRegistry.invoke(tc.getName(), tc.getArguments(), session.getKey());
                            String resultStr = result == null ? "null" : result.toString();
                            session.addMessage(new ChatMessage("tool", resultStr, tc.getName(), tc.getId()));
                            log.debug("[Agent] Tool result: {} -> {}", tc.getName(), resultStr);

                            // 工具执行过程上报
                            if (config.isShowToolProcess() && toolProcessConsumer != null) {
                                String toolMsg = String.format("🔧 调用工具：%s(%s)\n📊 结果：%s",
                                        tc.getName(), tc.getArguments(), resultStr);
                                toolProcessConsumer.accept(toolMsg);
                            }
                        }

                        return reactLoop(session, config, round + 1, toolProcessConsumer);
                    } else {
                        String content = response.getContent();
                        // 去除首尾空行，避免回复开头有空行
                        if (content != null) {
                            content = content.strip();
                        }
                        ChatMessage assistantMsg = new ChatMessage("assistant", content, null);
                        if (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty()) {
                            assistantMsg.setReasoningContent(response.getReasoningContent());
                        }
                        session.addMessage(assistantMsg);

                        // 触发记忆提取（异步，不阻塞响应；内部调用跳过避免递归）
                        MemoryExtractor extractor = getMemoryExtractor();
                        if (config.isMemoryEnabled() && extractor != null && !config.isInternalCall()) {
                            extractor.extractIfNeeded(session.getKey(), session);
                        }

                        return CompletableFuture.completedFuture(content);
                    }
                })
                .exceptionally(ex -> {
                    // 遍历异常链提取完整错误信息（FallbackLlmProvider 会包装原始异常）
                    String errorMsg = extractFullErrorMessage(ex);

                    if (errorMsg != null) {
                        // 图片加载失败检测（优先于参数错误，因为图片问题也会返回400）
                        if (errorMsg.contains("图片加载失败") || errorMsg.contains("IMAGE data")
                                || errorMsg.contains("image down failed")
                                || errorMsg.contains("multimedia.nt.qq.com.cn")) {
                            log.warn("[Agent] Image loading failed in round {}. LLM cannot access image URLs.", round);
                            // 清除 session 中的图片 URL，避免后续请求继续失败
                            clearImageUrls(session);
                            String imageErrorMsg = "我看到你发了图片，但是我这边的AI服务器无法直接访问QQ的图片链接😅\n你可以描述一下图片内容，我来帮你分析~";
                            session.addMessage(new ChatMessage("assistant", imageErrorMsg, null));
                            return imageErrorMsg;
                        }

                        if (errorMsg.contains("API请求错误: 401") || errorMsg.contains("API请求错误: 403")) {
                            log.warn("[Agent] API auth error in round {}: {}", round, ex.getMessage());
                            String clientErrMsg = "API认证失败，请检查配置。";
                            session.addMessage(new ChatMessage("assistant", clientErrMsg, null));
                            return clientErrMsg;
                        }

                        if (errorMsg.contains("API请求错误: 4")) {
                            log.warn("[Agent] API client error in round {}: {}", round, ex.getMessage());
                            String clientErrMsg = "请求参数有误，请检查输入内容或稍后重试。";
                            session.addMessage(new ChatMessage("assistant", clientErrMsg, null));
                            return clientErrMsg;
                        }

                        if (errorMsg.contains("timeout") || errorMsg.contains("SocketTimeoutException")
                                || errorMsg.contains("timed out")) {
                            log.warn("[Agent] Request timeout in round {}. The API server may be slow or unreachable.", round);
                            String timeoutMsg = "哎呀，网络有点卡，服务器响应超时了😅 要不再试一次？";
                            session.addMessage(new ChatMessage("assistant", timeoutMsg, null));
                            return timeoutMsg;
                        }

                        if (errorMsg.contains("Connection refused") || errorMsg.contains("ConnectException")) {
                            log.warn("[Agent] Connection failed in round {}: {}", round, ex.getMessage());
                            String connMsg = "网络连接失败了，可能是服务器暂时不可用😔";
                            session.addMessage(new ChatMessage("assistant", connMsg, null));
                            return connMsg;
                        }
                    }

                    log.error("[Agent] Error in round {}", round, ex);
                    String fallbackMsg = "处理出错了，请稍后再试。";
                    session.addMessage(new ChatMessage("assistant", fallbackMsg, null));
                    return fallbackMsg;
                });

    }
}