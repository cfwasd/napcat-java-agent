package com.napcat.agent.core;

import com.napcat.agent.agent.AgentConfig;
import com.napcat.agent.llm.ChatMessage;
import com.napcat.agent.llm.LlmProvider;
import com.napcat.agent.llm.LlmResponse;
import com.napcat.agent.session.Session;
import com.napcat.agent.tool.ToolRegistry;
import com.napcat.agent.tool.ToolSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Agent ReAct 循环引擎。
 * 封装 ReAct 循环的核心逻辑，可被 NapCatAgent 或其他入口调用。
 */
@Slf4j
public class AgentEngine {

    private final LlmProvider llmProvider;
    private final ToolRegistry toolRegistry;
    private final AgentConfig config;

    public AgentEngine(LlmProvider llmProvider, ToolRegistry toolRegistry, AgentConfig config) {
        this.llmProvider = llmProvider;
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    /**
     * ReAct 循环：调用 LLM → 处理 tool calls → 继续或返回
     *
     * @param session           当前会话
     * @param systemPrompt      系统提示词
     * @param toolProcessConsumer 工具执行过程回调（可为 null）
     * @param round             当前轮次（从 0 开始）
     */
    public CompletableFuture<String> reactLoop(Session session, String systemPrompt,
                                                Consumer<String> toolProcessConsumer, int round) {
        int maxRounds = config.getMaxRounds();
        if (round >= maxRounds) {
            String msg = "思考次数过多，请简化问题。";
            session.addMessage(new ChatMessage("assistant", msg, null));
            return CompletableFuture.completedFuture(msg);
        }

        log.debug("[AgentEngine] Round {}/{} for {}", round + 1, maxRounds, session.getKey());

        List<ToolSchema> tools = config.isDisableTools()
                ? java.util.Collections.emptyList()
                : toolRegistry.getSchemas();

        return llmProvider.chat(session, systemPrompt, tools)
                .thenCompose(response -> {
                    if (response.hasToolCalls()) {
                        // 记录 assistant tool_calls 到历史
                        ChatMessage assistantMsg = ChatMessage.fromToolCalls(response.getToolCalls());
                        if (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty()) {
                            assistantMsg.setReasoningContent(response.getReasoningContent());
                        }
                        session.addMessage(assistantMsg);

                        for (LlmResponse.ToolCall tc : response.getToolCalls()) {
                            log.debug("[AgentEngine] Tool call: {}({})", tc.getName(), tc.getArguments());
                            Object result = toolRegistry.invoke(tc.getName(), tc.getArguments(), session.getKey());
                            String resultStr = result == null ? "null" : result.toString();
                            session.addMessage(new ChatMessage("tool", resultStr, tc.getName(), tc.getId()));
                            log.debug("[AgentEngine] Tool result: {} -> {}", tc.getName(), resultStr);

                            if (toolProcessConsumer != null) {
                                String toolMsg = String.format("调用工具：%s(%s)\n结果：%s",
                                        tc.getName(), tc.getArguments(), resultStr);
                                toolProcessConsumer.accept(toolMsg);
                            }
                        }

                        return reactLoop(session, systemPrompt, toolProcessConsumer, round + 1);
                    } else {
                        String content = response.getContent();
                        if (content != null) {
                            content = content.strip();
                        }
                        ChatMessage assistantMsg = new ChatMessage("assistant", content, null);
                        if (response.getReasoningContent() != null && !response.getReasoningContent().isEmpty()) {
                            assistantMsg.setReasoningContent(response.getReasoningContent());
                        }
                        session.addMessage(assistantMsg);

                        return CompletableFuture.completedFuture(content);
                    }
                })
                .exceptionally(ex -> {
                    log.error("[AgentEngine] Error in round {}", round, ex);
                    String fallbackMsg = "处理出错了，请稍后再试。";
                    session.addMessage(new ChatMessage("assistant", fallbackMsg, null));
                    return fallbackMsg;
                });
    }
}
