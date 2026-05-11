package com.napcat.agent.scheduler;

import com.napcat.agent.agent.NapCatAgent;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.message.MessageChain;
import com.napcat.core.scheduler.ScheduleStore.ScheduleEntry;
import lombok.extern.slf4j.Slf4j;

/**
 * 定时任务执行器。
 * 从 SchedulePoller 的回调中触发，根据 schedule 的 action 字段选择执行路径：
 * - send_message：直接用 NapCatApi 发送固定文本（0 token）
 * - ai_generate：调用 NapCatAgent 生成动态内容后发送
 */
@Slf4j
public class TaskExecutor {

    private final NapCatApi api;
    private final NapCatAgent agent;

    public TaskExecutor(NapCatApi api, NapCatAgent agent) {
        this.api = api;
        this.agent = agent;
    }

    /**
     * 执行一个定时任务。
     */
    public void execute(ScheduleEntry entry) {
        if (!entry.isEnabled()) {
            log.debug("Skipping disabled schedule: {}", entry.getId());
            return;
        }

        String action = entry.getAction() != null ? entry.getAction() : "send_message";

        try {
            switch (action) {
                case "send_message" -> executeSendMessage(entry);
                case "ai_generate" -> executeAiGenerate(entry);
                default -> log.warn("Unknown schedule action '{}' for {}", action, entry.getId());
            }
        } catch (Exception e) {
            log.error("Schedule execution failed: id={}, name={}", entry.getId(), entry.getName(), e);
        }
    }

    private void executeSendMessage(ScheduleEntry entry) {
        String text = entry.getReplyText();
        if (text == null || text.isBlank()) {
            log.warn("Schedule {} has no replyText", entry.getId());
            return;
        }

        MessageChain msg = MessageChain.ofText(text);
        if ("private".equals(entry.getTargetType())) {
            api.sendPrivateMessage(entry.getTargetId(), msg);
        } else {
            api.sendGroupMessage(entry.getTargetId(), msg);
        }
        log.info("Sent scheduled message: id={}, target={}/{}", entry.getId(), entry.getTargetType(), entry.getTargetId());
    }

    private void executeAiGenerate(ScheduleEntry entry) {
        if (agent == null) {
            log.warn("NapCatAgent not available, falling back to replyText for {}", entry.getId());
            if (entry.getReplyText() != null && !entry.getReplyText().isBlank()) {
                executeSendMessage(entry);
            }
            return;
        }

        String prompt = entry.getPrompt();
        if (prompt == null || prompt.isBlank()) {
            log.warn("Schedule {} has no prompt for ai_generate", entry.getId());
            return;
        }

        // 支持 {time} 占位符替换
        prompt = prompt.replace("{time}", java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        long targetId = entry.getTargetId();
        boolean isPrivate = "private".equals(entry.getTargetType());

        String finalPrompt = prompt;
        agent.chat(isPrivate ? targetId : 0, isPrivate ? 0 : targetId, finalPrompt)
                .thenAccept(reply -> {
                    if (reply != null && !reply.isBlank()) {
                        MessageChain msg = MessageChain.ofText(reply);
                        if (isPrivate) {
                            api.sendPrivateMessage(targetId, msg);
                        } else {
                            api.sendGroupMessage(targetId, msg);
                        }
                    }
                })
                .exceptionally(ex -> {
                    log.error("AI generate failed for schedule {}", entry.getId(), ex);
                    return null;
                });
    }
}
