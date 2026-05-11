package com.napcat.agent.llm;

import com.napcat.agent.session.Session;
import com.napcat.agent.tool.ToolSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 支持备用模型的 LLM Provider 包装器。
 * 当主模型请求失败时，自动切换到备用模型。
 */
@Slf4j
public class FallbackLlmProvider implements LlmProvider {

    private final LlmProvider primaryProvider;
    private final LlmProvider fallbackProvider;
    private final boolean fallbackEnabled;

    public FallbackLlmProvider(LlmProvider primaryProvider, LlmProvider fallbackProvider, boolean fallbackEnabled) {
        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
        this.fallbackEnabled = fallbackEnabled;
    }

    @Override
    public String getProviderName() {
        String primaryName = primaryProvider.getProviderName();
        if (fallbackEnabled && fallbackProvider != null) {
            return primaryName + " (with fallback: " + fallbackProvider.getProviderName() + ")";
        }
        return primaryName;
    }

    @Override
    public CompletableFuture<LlmResponse> chat(Session session, String input, List<ToolSchema> tools) {
        if (!fallbackEnabled || fallbackProvider == null) {
            // 没有启用fallback，直接使用主模型
            return primaryProvider.chat(session, input, tools);
        }

        // 使用主模型
        return primaryProvider.chat(session, input, tools)
                .exceptionallyCompose(ex -> {
                    // 判断是否应该使用备用模型
                    if (shouldUseFallback(ex)) {
                        log.warn("[Fallback] Primary model failed, switching to fallback model. Error: {}", ex.getMessage());

                        // 记录错误到session历史（可选）
                        String errorMsg = String.format("⚠️ 主模型出错：%s\n正在切换到备用模型...",
                                extractErrorMessage(ex));

                        // 尝试使用备用模型
                        return fallbackProvider.chat(session, input, tools)
                                .thenApply(response -> {
                                    log.info("[Fallback] Fallback model succeeded");
                                    return response;
                                })
                                .exceptionally(fallbackEx -> {
                                    log.error("[Fallback] Both primary and fallback models failed", fallbackEx);
                                    throw new RuntimeException("主模型和备用模型均失败: " + fallbackEx.getMessage(), ex);
                                });
                    } else {
                        // 不应该使用fallback的错误（如参数错误），直接抛出
                        log.error("[Fallback] Non-recoverable error, not using fallback: {}", ex.getMessage());
                        throw new RuntimeException(ex);
                    }
                });
    }

    /**
     * 判断是否应该使用备用模型
     * 只在以下情况使用fallback：
     * - 网络超时
     * - 连接失败
     * - 服务器错误（5xx）
     * 不使用fallback的情况：
     * - 客户端错误（4xx，如参数错误、认证失败）
     */
    private boolean shouldUseFallback(Throwable ex) {
        String errorMsg = ex.getMessage();
        if (errorMsg == null) {
            return false;
        }

        // 客户端错误不使用fallback
        if (errorMsg.contains("API请求错误: 4")) {
            return false;
        }

        // 以下错误可以使用fallback
        return errorMsg.contains("timeout")
                || errorMsg.contains("SocketTimeoutException")
                || errorMsg.contains("Connection")
                || errorMsg.contains("ConnectException")
                || errorMsg.contains("服务器错误: 5")
                || errorMsg.contains("图片加载失败")
                || ex instanceof java.net.ConnectException
                || ex instanceof java.net.SocketTimeoutException;
    }

    /**
     * 提取简洁的错误消息
     */
    private String extractErrorMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            return "未知错误";
        }

        // 简化错误消息
        if (msg.contains("timeout") || msg.contains("SocketTimeoutException")) {
            return "请求超时";
        }
        if (msg.contains("Connection refused") || msg.contains("ConnectException")) {
            return "连接失败";
        }
        if (msg.contains("服务器错误: 5")) {
            return "服务器错误";
        }
        if (msg.contains("图片加载失败")) {
            return "图片加载失败";
        }

        // 返回前100个字符
        return msg.length() > 100 ? msg.substring(0, 100) + "..." : msg;
    }
}
