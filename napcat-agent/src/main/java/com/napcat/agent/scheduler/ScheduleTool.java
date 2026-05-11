package com.napcat.agent.scheduler;

import com.napcat.core.context.EventContextHolder;
import com.napcat.core.annotation.Tool;
import com.napcat.core.annotation.ToolParam;
import com.napcat.core.event.GroupMessageEvent;
import com.napcat.core.event.MessageEvent;
import com.napcat.core.event.PrivateMessageEvent;
import com.napcat.core.scheduler.CronEvaluator;
import com.napcat.core.scheduler.SchedulePoller;
import com.napcat.core.scheduler.ScheduleStore;
import com.napcat.core.scheduler.ScheduleStore.ScheduleEntry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent 可调用的定时任务管理工具。
 * 让 AI 通过 function calling 创建/删除/查询/启停定时任务。
 * 所有操作直接写 SQLite，SchedulePoller 自动感知变更。
 */
@Slf4j
public class ScheduleTool {

    private final ScheduleStore store;
    private final SchedulePoller poller;

    public ScheduleTool() {
        this.store = null;
        this.poller = null;
    }

    public ScheduleTool(ScheduleStore store, SchedulePoller poller) {
        this.store = store;
        this.poller = poller;
    }

    /**
     * 创建一个定时任务。
     */
    @Tool(name = "create_schedule", description = "创建一个定时任务。在指定时间执行发送消息或生成内容。如果不指定目标ID，默认使用当前对话的群号或用户QQ。")
    public String createSchedule(
            @ToolParam(description = "Cron 表达式，如 0 0 8 * * ? 表示每天早上8点", required = true)
            String cron,
            @ToolParam(description = "任务名称", required = true)
            String name,
            @ToolParam(description = "目标群号或用户QQ号，不填则自动使用当前对话的目标", required = false)
            Long targetId,
            @ToolParam(description = "执行动作：send_message=发送固定文本，ai_generate=AI生成内容", required = false)
            String action,
            @ToolParam(description = "目标类型：group=群聊，private=私聊，不填则自动判断", required = false)
            String targetType,
            @ToolParam(description = "固定回复文本（action=send_message时必填）", required = false)
            String replyText,
            @ToolParam(description = "AI生成内容的提示词（action=ai_generate时必填），支持{time}占位符", required = false)
            String prompt
    ) {
        if (!CronEvaluator.isValid(cron)) {
            return "❌ Cron 表达式无效：" + cron + "。请使用标准格式，如 0 0 8 * * ?";
        }

        // 自动获取当前事件的 userId 和 groupId
        long autoTargetId = 0;
        String autoTargetType = "group";
        
        try {
            var context = EventContextHolder.get();
            if (context != null && context.getEvent() instanceof MessageEvent messageEvent) {
                if (messageEvent instanceof GroupMessageEvent groupEvent) {
                    // 群聊场景：默认发送到当前群
                    autoTargetId = groupEvent.getGroupId();
                    autoTargetType = "group";
                } else if (messageEvent instanceof PrivateMessageEvent privateEvent) {
                    // 私聊场景：默认发送到当前用户
                    autoTargetId = privateEvent.getUserId();
                    autoTargetType = "private";
                }
            }
        } catch (Exception e) {
            log.warn("Failed to get event context, targetId must be specified manually", e);
        }

        // 如果用户未提供 targetId，使用自动获取的值
        long finalTargetId = (targetId != null && targetId > 0) ? targetId : autoTargetId;
        String finalTargetType = (targetType != null && !targetType.isBlank()) ? targetType : autoTargetType;

        if (finalTargetId <= 0) {
            return "❌ 无法确定目标任务目标。请手动指定 targetId 参数，或在群聊/私聊中使用此工具。";
        }

        ScheduleEntry entry = new ScheduleEntry();
        entry.setName(name);
        entry.setCron(cron);
        entry.setTargetId(finalTargetId);
        entry.setAction(action != null ? action : "send_message");
        entry.setTargetType(finalTargetType);
        entry.setReplyText(replyText);
        entry.setPrompt(prompt);
        entry.setEnabled(true);

        String id = store.insert(entry);

        // 立即注册到调度器
        if (poller != null) {
            entry.setId(id);
            poller.scheduleNow(entry);
        }

        return "✅ 定时任务已创建：\n" +
                "- ID：" + id + "\n" +
                "- 名称：" + name + "\n" +
                "- Cron：" + cron + "\n" +
                "- 动作：" + entry.getAction() + "\n" +
                "- 目标：" + entry.getTargetType() + "/" + finalTargetId +
                (targetId == null ? "（自动检测）" : "");
    }

    /**
     * 删除一个定时任务。
     */
    @Tool(name = "delete_schedule", description = "删除一个定时任务。")
    public String deleteSchedule(
            @ToolParam(description = "任务ID（创建时返回的ID）或任务名称", required = true)
            String idOrName
    ) {
        // 先尝试 ID 匹配
        ScheduleEntry entry = store.getById(idOrName);
        if (entry == null) {
            // 再尝试名称模糊匹配
            List<ScheduleEntry> all = store.listAll();
            entry = all.stream()
                    .filter(e -> e.getName() != null && e.getName().contains(idOrName))
                    .findFirst().orElse(null);
        }

        if (entry == null) {
            return "❌ 未找到任务：" + idOrName;
        }

        if (poller != null) {
            poller.cancelTask(entry.getId());
        }
        store.delete(entry.getId());
        return "✅ 已删除任务：" + entry.getName() + " (ID: " + entry.getId() + ")";
    }

    /**
     * 列出所有定时任务。
     */
    @Tool(name = "list_schedules", description = "列出所有已创建的定时任务。")
    public String listSchedules() {
        List<ScheduleEntry> all = store.listAll();
        if (all.isEmpty()) {
            return "📋 当前没有定时任务。";
        }

        String list = all.stream()
                .map(e -> "- **" + e.getName() + "** [" + (e.isEnabled() ? "启用" : "禁用") + "]\n" +
                        "  ID：" + e.getId() + " | Cron：" + e.getCron() + "\n" +
                        "  动作：" + e.getAction() + " → " + e.getTargetType() + "/" + e.getTargetId())
                .collect(Collectors.joining("\n"));

        return "📋 定时任务列表（共 " + all.size() + " 个）：\n" + list;
    }

    /**
     * 启用/禁用一个定时任务。
     */
    @Tool(name = "toggle_schedule", description = "启用或禁用一个定时任务。")
    public String toggleSchedule(
            @ToolParam(description = "任务ID或名称", required = true)
            String idOrName,
            @ToolParam(description = "true=启用，false=禁用", required = true)
            boolean enabled
    ) {
        ScheduleEntry entry = store.getById(idOrName);
        if (entry == null) {
            List<ScheduleEntry> all = store.listAll();
            entry = all.stream()
                    .filter(e -> e.getName() != null && e.getName().contains(idOrName))
                    .findFirst().orElse(null);
        }

        if (entry == null) {
            return "❌ 未找到任务：" + idOrName;
        }

        if (enabled) {
            store.toggle(entry.getId(), true);
            if (poller != null) poller.scheduleNow(entry);
        } else {
            store.toggle(entry.getId(), false);
            if (poller != null) poller.cancelTask(entry.getId());
        }

        return "✅ 任务 **" + entry.getName() + "** 已" + (enabled ? "启用" : "禁用");
    }
}
