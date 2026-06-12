package com.napcat.admin.bot;

import com.napcat.agent.agent.AgentConfig;
import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.agent.PersonaManager;
import com.napcat.agent.agent.PersonaManager.PersonaDefinition;
import com.napcat.agent.memory.MemoryExtractor;
import com.napcat.agent.session.Session;
import com.napcat.agent.session.SessionKey;
import com.napcat.agent.session.SessionManager;
import com.napcat.core.annotation.Command;
import com.napcat.core.annotation.MentionFilter;
import com.napcat.core.annotation.OnGroupMessage;
import com.napcat.core.annotation.OnPrivateMessage;
import com.napcat.core.annotation.Param;
import com.napcat.core.annotation.WakeFilter;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.config.BotProperties;
import com.napcat.core.event.GroupMessageEvent;
import com.napcat.core.event.PrivateMessageEvent;
import com.napcat.core.message.MessageChain;
import com.napcat.core.tts.VoicePreferenceStore;
import com.napcat.core.tts.VoicePreferenceStore.VoiceMode;
import com.napcat.agent.tts.TtsService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class AgentDemoBot {

    @Autowired(required = false)
    @Lazy
    private NapCatAgent agent;

    @Autowired(required = false)
    private SessionManager sessionManager;

    @Autowired(required = false)
    private MemoryExtractor memoryExtractor;

    @Autowired(required = false)
    private com.napcat.agent.memory.MemoryStore memoryStore;

    @Autowired(required = false)
    private PersonaManager personaManager;

    @Autowired
    private BotProperties botProperties;

    @Autowired(required = false)
    private NapCatApi napCatApi;

    @Autowired(required = false)
    private TtsService ttsService;

    @Autowired(required = false)
    private VoicePreferenceStore voicePreferenceStore;

    @Autowired(required = false)
    private com.napcat.core.group.GroupPreferenceStore groupPreferenceStore;

    private static  List<String> keyboards;

    @PostConstruct
    public void init() {
        if (agent == null || botProperties.getWakeWords().isEmpty()){
            keyboards= new ArrayList<>();
        }else {
            keyboards = botProperties.getWakeWords();
        }
    }

    @OnGroupMessage
    @OnPrivateMessage
    @MentionFilter
    public void onAt(GroupMessageEvent event) {
        if (agent == null) return;
        String prompt = event.getMessage().toAgentPrompt();
        if (tryClearSession(event, prompt)) return;

        List<String> processSteps = Collections.synchronizedList(new ArrayList<>());
        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), event.getGroupId(), prompt, config,
                        processSteps::add)
                .thenAccept(result -> {
                    sendReply(event, result);
                    sendProcessForward(event, processSteps);
                });
    }
    @OnGroupMessage
    @OnPrivateMessage
    @WakeFilter
    public void notAt(GroupMessageEvent event) {
        if (agent == null) return;

        String prompt = event.getMessage().toAgentPrompt();

        // 剔除唤醒关键词
        String cleanedText = removeWakeWords(prompt, keyboards);
        if (tryClearSession(event, cleanedText)) return;

        List<String> processSteps = Collections.synchronizedList(new ArrayList<>());
        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), event.getGroupId(), cleanedText, config,
                        processSteps::add)
                .thenAccept(result -> {
                    sendReply(event, result);
                    sendProcessForward(event, processSteps);
                });
    }

    @OnPrivateMessage
    public void notAt(PrivateMessageEvent event) {
        if (agent == null) return;

        String prompt = event.getMessage().toAgentPrompt();

        // 剔除唤醒关键词
        String cleanedText = removeWakeWords(prompt, keyboards);
        if (tryClearSession(event, cleanedText)) return;

        List<String> processSteps = Collections.synchronizedList(new ArrayList<>());
        AgentConfig config = AgentConfig.builder()
                .showToolProcess(true)
                .ackCallback(() -> event.reply(MessageChain.ofFace(277)))  // 👍 表示收到
                .build();

        agent.chat(event.getUserId(), 0, cleanedText, config,
                        processSteps::add)
                .thenAccept(result -> {
                    sendPrivateReplyWithImage(event, result);
                    // 私聊也发送合并转发思考过程
                    if (!processSteps.isEmpty()) {
                        sendProcessForwardPrivate(event, processSteps);
                    }
                });
    }

    /**
     * 检测是否为会话清空命令（/new 或 /clear），如果是则提取记忆后清空并返回 true。
     */
    private boolean tryClearSession(GroupMessageEvent event, String text) {
        if (sessionManager == null) return false;
        String trimmed = text.trim();
        if ( "/clear".equals(trimmed)) {
            SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
            Session session = sessionManager.get(key);
            if (session != null && !session.getHistory().isEmpty()) {
                if (memoryExtractor != null) {
                    memoryExtractor.extractAndPersistSync(key, session);
                }
                if (memoryStore != null) {
                    memoryStore.persistFullSession(key, formatSessionHistory(session));
                }
            }
            sessionManager.getAndRemove(key);
            event.reply("会话已重置");
            return true;
        }
        return false;
    }

    private boolean tryClearSession(PrivateMessageEvent event, String text) {
        if (sessionManager == null) return false;
        String trimmed = text.trim();
        if ( "/clear".equals(trimmed)) {
            SessionKey key = new SessionKey(event.getUserId(), 0);
            Session session = sessionManager.get(key);
            if (session != null && !session.getHistory().isEmpty()) {
                if (memoryExtractor != null) {
                    memoryExtractor.extractAndPersistSync(key, session);
                }
                if (memoryStore != null) {
                    memoryStore.persistFullSession(key, formatSessionHistory(session));
                }
            }
            sessionManager.getAndRemove(key);
            event.reply("会话已重置");
            return true;
        }
        return false;
    }

    private String formatSessionHistory(Session session) {
        StringBuilder sb = new StringBuilder();
        for (var msg : session.getHistory()) {
            if ("user".equals(msg.getRole()) || "assistant".equals(msg.getRole())) {
                sb.append("[").append(msg.getRole()).append("]: ")
                        .append(msg.getContent() != null ? msg.getContent() : "").append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 清理多余的空格
     * @return 剔除唤醒词后的文本
     */
    private String removeWakeWords(String text, List<String> wakeWords) {
        if (text == null || text.isEmpty() || wakeWords == null || wakeWords.isEmpty()) {
            return text;
        }

        String result = text;
        for (String wakeWord : wakeWords) {
            if (wakeWord != null && !wakeWord.isEmpty()) {
                // 忽略大小写替换
                result = result.replaceAll("(?i)" + java.util.regex.Pattern.quote(wakeWord), "");
            }
        }

        // 清理多余的空格
        result = result.replaceAll("\\s+", " ").trim();

        return result;
    }

    // ========= 人格切换命令 =========

    /**
     * /persona —— 列出所有可用人格及当前激活人格
     */
    @Command(value = "/persona", description = "查看/切换人格")
    @OnGroupMessage
    @OnPrivateMessage
    public void personaList(GroupMessageEvent event) {
        if (personaManager == null || personaManager.isEmpty()) {
            event.reply("\u6ca1\u6709\u914d\u7f6e\u4efb\u4f55\u4eba\u683c\uff5e");
            return;
        }

        SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
        PersonaDefinition active = personaManager.getActivePersona(key);

        StringBuilder sb = new StringBuilder();
        sb.append("\u53ef\u7528\u4eba\u683c\uff1a\n");
        for (PersonaDefinition p : personaManager.listPersonas()) {
            String marker = (active != null && active.getId().equals(p.getId())) ? " \u2190 \u5f53\u524d" : "";
            sb.append("\u2022 ").append(p.getName()).append(" (").append(p.getId()).append(")");
            if (p.getDescription() != null && !p.getDescription().isBlank()) {
                sb.append(" - ").append(p.getDescription());
            }
            sb.append(marker).append("\n");
        }
        sb.append("\u53d1 /persona \u4eba\u683c\u540d \u5207\u6362\u4f60\u7684\u4e13\u5c5e\u4eba\u683c\uff0c\u5982 /persona \u5a07\u50b2\n");
        sb.append("\u6bcf\u4e2a\u7528\u6237\u72ec\u7acb\u8bbe\u7f6e\uff0c\u6240\u6709\u7fa4\u901a\u7528");
        event.reply(sb.toString().trim());
    }

    /**
     * /persona {name} —— 切换到指定人格
     */
    @Command(value = "/persona {name}", description = "切换到指定人格")
    @OnGroupMessage
    @OnPrivateMessage
    public void personaSwitch(GroupMessageEvent event, @Param("name") String name) {
        if (personaManager == null || personaManager.isEmpty()) {
            event.reply("\u6ca1\u6709\u914d\u7f6e\u4efb\u4f55\u4eba\u683c\uff5e");
            return;
        }

        // 支持 "default" 关键词重置为默认人格
        if ("default".equalsIgnoreCase(name) || "\u9ed8\u8ba4".equals(name)) {
            SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
            personaManager.resetPersona(key);
            // 清空当前会话让新人格 prompt 生效
            clearSessionForPersona(event);
            PersonaDefinition def = personaManager.getActivePersona(key);
            event.reply("\u5df2\u5207\u6362\u56de\u9ed8\u8ba4\u4eba\u683c\uff1a" + (def != null ? def.getName() : ""));
            return;
        }

        // 模糊搜索人格
        PersonaDefinition target = personaManager.findPersonaByKeyword(name);
        if (target == null) {
            event.reply("\u627e\u4e0d\u5230\u4eba\u683c\uff1a" + name + "\n\u53d1 /persona \u67e5\u770b\u53ef\u7528\u4eba\u683c");
            return;
        }

        SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
        personaManager.switchPersona(key, target.getId());
        // 清空当前会话让新人格 prompt 生效
        clearSessionForPersona(event);
        event.reply("\u5df2\u5207\u6362\u4e3a\uff1a" + target.getName()
                + (target.getDescription() != null ? " (" + target.getDescription() + ")" : ""));
    }

    /**
     * 人格切换时清空当前会话，使新 system prompt 在下次对话时生效。
     */
    private void clearSessionForPersona(GroupMessageEvent event) {
        if (sessionManager == null) return;
        SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
        sessionManager.getAndRemove(key);
    }

    // ========= 语音回复 =========

    /**
     * /voice —— 查看当前语音模式
     */
    @Command(value = "/voice", description = "查看/切换语音模式")
    @OnGroupMessage
    @OnPrivateMessage
    public void voiceStatus(GroupMessageEvent event) {
        if (voicePreferenceStore == null) {
            event.reply("语音功能未启用");
            return;
        }
        VoiceMode mode = voicePreferenceStore.getVoiceMode(event.getUserId());
        event.reply("当前语音模式：" + mode.toDisplayString() + "\n\n" +
                "发 /voice on  切换为语音模式\n" +
                "发 /voice off 切换为文字模式\n" +
                "发 /voice default 切换为默认模式");
    }

    /**
     * /voice {mode} —— 切换语音模式
     */
    @Command(value = "/voice {mode}", description = "切换语音模式")
    @OnGroupMessage
    @OnPrivateMessage
    public void voiceSwitch(GroupMessageEvent event, @Param("mode") String mode) {
        if (voicePreferenceStore == null) {
            event.reply("语音功能未启用");
            return;
        }
        VoiceMode targetMode = VoiceMode.fromString(mode);
        voicePreferenceStore.setVoiceMode(event.getUserId(), targetMode);
        event.reply("语音模式已切换为：" + targetMode.toDisplayString());
    }

    // ==================== 安静模式 ====================

    /**
     * /安静 或 /silent —— 开启/关闭群聊安静模式（3分钟）
     * 安静模式下，仅 /安静（/silent）命令可执行，其余消息全部忽略。
     * 权限优先级：普通人(0) < 管理员(1) < 群主(2) < 超管(3)
     * 关闭权限：发起人 或 同级/更高级用户可关闭。
     */
    @Command(value = "/安静", description = "开启/关闭安静模式（3分钟）", silentModeAllowed = true)
    @Command(value = "/silent", description = "开启/关闭安静模式（3分钟）", silentModeAllowed = true)
    @OnGroupMessage
    public String silentMode(GroupMessageEvent event) {
        if (groupPreferenceStore == null) {
            return "安静模式功能未启用";
        }

        long groupId = event.getGroupId();
        long userId = event.getUserId();
        int userPriority = resolvePriority(event);

        // 检查当前是否已有安静模式
        com.napcat.core.group.GroupPreferenceStore.SilentInfo existing =
                groupPreferenceStore.getSilentInfo(groupId);

        if (existing != null && !existing.isExpired()) {
            // 已有安静模式 → 尝试关闭
            com.napcat.core.group.GroupPreferenceStore.DeactivateResult result =
                    groupPreferenceStore.deactivateSilent(groupId, userId, userPriority);
            return switch (result) {
                case SUCCESS -> String.format("🔊 安静模式已关闭（剩余 %d 秒被取消）", existing.getRemainingSeconds());
                case NO_PERMISSION -> String.format("🔒 安静模式由 %s 激活，你的权限不足，无法关闭\n⏳ 剩余 %d 秒",
                        com.napcat.core.group.GroupPreferenceStore.SilentInfo.priorityName(existing.priorityLevel),
                        existing.getRemainingSeconds());
                case NOT_ACTIVE -> "当前没有活跃的安静模式";
            };
        }

        // 没有安静模式 → 开启
        boolean activated = groupPreferenceStore.activateSilent(groupId, userId, userPriority, 0);
        if (activated) {
            return String.format("🤫 安静模式已开启（3分钟），期间仅 /安静 命令可用\n⏳ %s 已激活",
                    com.napcat.core.group.GroupPreferenceStore.SilentInfo.priorityName(userPriority));
        }
        return "开启安静模式失败";
    }

    /**
     * 解析用户在群中的优先级等级。
     */
    private int resolvePriority(GroupMessageEvent event) {
        // 超级管理员（最高优先级）
        if (botProperties.getSuperUsers().contains(event.getUserId())) {
            return com.napcat.core.group.GroupPreferenceStore.PRIORITY_SUPERUSER;
        }
        // 群主
        if (event.getSender() != null && event.getSender().isOwner()) {
            return com.napcat.core.group.GroupPreferenceStore.PRIORITY_OWNER;
        }
        // 管理员
        if (event.getSender() != null && event.getSender().isAdmin()) {
            return com.napcat.core.group.GroupPreferenceStore.PRIORITY_ADMIN;
        }
        // 普通成员
        return com.napcat.core.group.GroupPreferenceStore.PRIORITY_NORMAL;
    }

    /**
     * 发送回复：先处理图片标记，再根据语音模式决定是否发送语音。
     */
    private void sendReply(GroupMessageEvent event, String message) {
        // 先发送文本+图片
        sendWithImageSupport(event, message);

        // 判断是否发送语音
        if (ttsService == null || !ttsService.isEnabled()) return;
        if (voicePreferenceStore == null) return;
        if (message == null || message.isBlank()) return;

        // 去除图片标记后的纯文本
        String cleanText = normalizeImageFormats(message);
        cleanText = IMAGE_ANY_PATTERN.matcher(cleanText).replaceAll("").trim();
        if (cleanText.isEmpty()) return;

        VoiceMode mode = voicePreferenceStore.getVoiceMode(event.getUserId());
        boolean sendVoice = switch (mode) {
            case VOICE -> true;
            case TEXT -> false;
            case DEFAULT -> ThreadLocalRandom.current().nextBoolean(); // 50% 概率
        };

        if (!sendVoice) return;

        // 获取当前人格的声线配置
        String voiceProfileName = null;
        if (personaManager != null && !personaManager.isEmpty()) {
            SessionKey key = new SessionKey(event.getUserId(), event.getGroupId());
            PersonaDefinition active = personaManager.getActivePersona(key);
            if (active != null && active.getVoiceProfile() != null) {
                voiceProfileName = active.getVoiceProfile();
            }
        }

        // 异步合成并发送语音
        final String text = cleanText;
        final String profile = voiceProfileName;
        try {
            String audioPath = ttsService.synthesize(text, profile);
            if (audioPath != null) {
                // 将音频文件转为 base64 发送，避免跨机器文件路径问题
                java.nio.file.Path path = java.nio.file.Path.of(audioPath);
                byte[] audioBytes = java.nio.file.Files.readAllBytes(path);
                String base64 = java.util.Base64.getEncoder().encodeToString(audioBytes);
                String format = audioPath.substring(audioPath.lastIndexOf('.') + 1);
                // NapCat record 段支持 file 字段传 base64 data URI
                String dataUri = "base64://" + base64;
                event.reply(MessageChain.ofRecord(dataUri));
                log.debug("Sent voice reply via base64, size={} bytes", audioBytes.length);
            } else {
                log.debug("TTS synthesis returned null, skipping voice reply");
            }
        } catch (Exception e) {
            log.error("Failed to send voice reply", e);
        }
    }

    // ========= 思考过程合并转发 =========

    /**
     * 解析文本中的 [IMAGE:url=xxx] / [IMAGE:path=xxx] / ![alt](url) 标记，
     * 构建包含文本段和图片段的 MessageChain。
     */
    private MessageChain buildContentWithImages(String text) {
        // 统一图片格式：先处理 Markdown 图片
        text = normalizeImageFormats(text);
        Matcher matcher = IMAGE_ANY_PATTERN.matcher(text);
        if (!matcher.find()) {
            return MessageChain.ofText(text);
        }
        MessageChain chain = new MessageChain();
        matcher.reset();
        int lastEnd = 0;
        while (matcher.find()) {
            String before = text.substring(lastEnd, matcher.start());
            if (!before.isEmpty()) {
                chain.text(before);
            }
            String imgSource = matcher.group(1).trim();
            if (imgSource.startsWith("http://") || imgSource.startsWith("https://")) {
                chain.image(imgSource);
            } else {
                java.io.File file = new java.io.File(imgSource);
                if (file.exists()) {
                    chain.image(file.getAbsolutePath());
                } else {
                    chain.text("[图片: " + imgSource + "]");
                }
            }
            lastEnd = matcher.end();
        }
        String after = text.substring(lastEnd);
        if (!after.isEmpty()) {
            chain.text(after);
        }
        return chain;
    }

    /**
     * 将收集到的工具执行过程消息以合并转发形式发送到群聊。
     * 使用 send_group_forward_msg API 直接发送，避免序列化问题。
     */
    private void sendProcessForward(GroupMessageEvent event, List<String> processSteps) {
        if (processSteps == null || processSteps.isEmpty()) return;
        // 优先使用 raw API 方式发送（更可靠）
        if (napCatApi != null && napCatApi.isAvailable()) {
            try {
                sendForwardViaRawApi(event.getGroupId(), 0, processSteps);
                return;
            } catch (Exception e) {
                log.warn("Raw API forward failed, falling back to segment approach", e);
            }
        }
        // 备选：通过 segment 方式发送
        try {
            long selfId = botProperties.getSelfId();
            String nickname = String.valueOf(selfId);
            List<com.napcat.core.message.NodeSegment> nodes = new ArrayList<>();
            for (String step : processSteps) {
                MessageChain content = buildContentWithImages(step);
                nodes.add(new com.napcat.core.message.NodeSegment(selfId, nickname, content));
            }
            MessageChain forwardMsg = MessageChain.ofForward(nodes);
            event.reply(forwardMsg);
        } catch (Exception e) {
            log.error("Both forward methods failed, falling back to individual messages", e);
            for (String step : processSteps) {
                event.reply(step);
            }
        }
    }

    /**
     * 将收集到的工具执行过程消息以合并转发形式发送到私聊。
     */
    private void sendProcessForwardPrivate(PrivateMessageEvent event, List<String> processSteps) {
        if (processSteps == null || processSteps.isEmpty()) return;
        // 优先使用 raw API
        if (napCatApi != null && napCatApi.isAvailable()) {
            try {
                sendForwardViaRawApi(0, event.getUserId(), processSteps);
                return;
            } catch (Exception e) {
                log.warn("Raw API forward (private) failed, falling back", e);
            }
        }
        try {
            long selfId = botProperties.getSelfId();
            String nickname = String.valueOf(selfId);
            List<com.napcat.core.message.NodeSegment> nodes = new ArrayList<>();
            for (String step : processSteps) {
                MessageChain content = buildContentWithImages(step);
                nodes.add(new com.napcat.core.message.NodeSegment(selfId, nickname, content));
            }
            MessageChain forwardMsg = MessageChain.ofForward(nodes);
            event.reply(forwardMsg);
        } catch (Exception e) {
            log.error("Failed to send process forward message (private)", e);
            for (String step : processSteps) {
                event.reply(step);
            }
        }
    }

    /**
     * 通过原始 API 调用发送合并转发消息。
     * 直接构造 NapCat 期望的 JSON 结构，绕过 segment 序列化。
     */
    private void sendForwardViaRawApi(long groupId, long userId, List<String> processSteps) {
        long selfId = botProperties.getSelfId();
        String nickname = String.valueOf(selfId);

        // 构造 messages 数组
        List<Map<String, Object>> messages = new ArrayList<>();
        for (String step : processSteps) {
            // 统一图片格式：Markdown ![](url) → [IMAGE:url=url]
            step = normalizeImageFormats(step);
            // 构造 node 的 content（解析图片标记）
            List<Map<String, Object>> contentArray = new ArrayList<>();
            Matcher matcher = IMAGE_ANY_PATTERN.matcher(step);
            if (!matcher.find()) {
                // 纯文本
                Map<String, Object> textSeg = new java.util.LinkedHashMap<>();
                textSeg.put("type", "text");
                Map<String, Object> textData = new java.util.LinkedHashMap<>();
                textData.put("text", step);
                textSeg.put("data", textData);
                contentArray.add(textSeg);
            } else {
                matcher.reset();
                int lastEnd = 0;
                while (matcher.find()) {
                    String before = step.substring(lastEnd, matcher.start());
                    if (!before.isEmpty()) {
                        Map<String, Object> textSeg = new java.util.LinkedHashMap<>();
                        textSeg.put("type", "text");
                        textSeg.put("data", Map.of("text", before));
                        contentArray.add(textSeg);
                    }
                    String imgSource = matcher.group(1).trim();
                    Map<String, Object> imgSeg = new java.util.LinkedHashMap<>();
                    imgSeg.put("type", "image");
                    imgSeg.put("data", Map.of("file", imgSource));
                    contentArray.add(imgSeg);
                    lastEnd = matcher.end();
                }
                String after = step.substring(lastEnd);
                if (!after.isEmpty()) {
                    Map<String, Object> textSeg = new java.util.LinkedHashMap<>();
                    textSeg.put("type", "text");
                    textSeg.put("data", Map.of("text", after));
                    contentArray.add(textSeg);
                }
            }

            Map<String, Object> nodeData = new java.util.LinkedHashMap<>();
            nodeData.put("name", nickname);
            nodeData.put("uin", String.valueOf(selfId));
            nodeData.put("content", contentArray);

            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("type", "node");
            node.put("data", nodeData);
            messages.add(node);
        }

        // 打印实际发送的 JSON 用于调试
        try {
            com.fasterxml.jackson.databind.ObjectMapper debugMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            log.info("[ForwardDebug] Sending forward via raw API, target=group:{}, userId:{}, messages JSON: {}",
                    groupId, userId, debugMapper.writeValueAsString(messages));
        } catch (Exception e) {
            log.warn("[ForwardDebug] Failed to serialize messages for debug", e);
        }

        // 根据目标类型调用不同 API
        if (groupId > 0) {
            napCatApi.call("send_group_forward_msg", "group_id", groupId, "messages", messages)
                    .whenComplete((resp, ex) -> {
                        if (ex != null) {
                            log.error("[ForwardDebug] send_group_forward_msg failed", ex);
                        } else {
                            log.info("[ForwardDebug] send_group_forward_msg response: status={}, msg={}, data={}",
                                    resp.getStatus(), resp.getMessage(), resp.getData());
                        }
                    });
        } else if (userId > 0) {
            napCatApi.call("send_private_forward_msg", "user_id", userId, "messages", messages)
                    .whenComplete((resp, ex) -> {
                        if (ex != null) {
                            log.error("[ForwardDebug] send_private_forward_msg failed", ex);
                        } else {
                            log.info("[ForwardDebug] send_private_forward_msg response: status={}, msg={}, data={}",
                                    resp.getStatus(), resp.getMessage(), resp.getData());
                        }
                    });
        }
    }

    // ========= 图片消息支持 =========

    /** 图片标记正则：[IMAGE:url=xxx] 或 [IMAGE:path=xxx] */
    private static final Pattern IMAGE_URL_PATTERN = Pattern.compile("\\[IMAGE:url=([^\\]]+)]");
    private static final Pattern IMAGE_PATH_PATTERN = Pattern.compile("\\[IMAGE:path=([^\\]]+)]");
    private static final Pattern IMAGE_ANY_PATTERN = Pattern.compile("\\[IMAGE:(?:url|path)=([^\\]]+)]");

    /** Markdown 图片语法：![alt](url) — 当 LLM 以 markdown 格式返回图片链接时捕获 */
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    /**
     * 发送消息，自动检测图片标记并发送图片。
     * 支持 [IMAGE:url=xxx]（网络URL）、[IMAGE:path=xxx]（本地路径）和 ![alt](url)（Markdown格式）。
     * NapCat 原生支持直接传图片 URL，无需下载。
     */
    private void sendWithImageSupport(GroupMessageEvent event, String message) {
        if (message == null || message.isEmpty()) return;
        // 统一图片格式：将 Markdown ![](url) 统一转为 [IMAGE:url=url]
        message = normalizeImageFormats(message);

        // 检查是否包含任何图片标记
        Matcher anyMatcher = IMAGE_ANY_PATTERN.matcher(message);
        if (!anyMatcher.find()) {
            event.reply(message);
            return;
        }

        // 分离文本和图片
        anyMatcher.reset();
        StringBuilder textPart = new StringBuilder();
        List<String> imageSources = new ArrayList<>();
        int lastEnd = 0;

        while (anyMatcher.find()) {
            String before = message.substring(lastEnd, anyMatcher.start());
            textPart.append(before);
            imageSources.add(anyMatcher.group(1).trim());
            lastEnd = anyMatcher.end();
        }
        String after = message.substring(lastEnd);
        textPart.append(after);

        // 发送文本
        String text = textPart.toString().trim();
        if (!text.isEmpty()) {
            event.reply(text);
        }

        // 发送图片 — NapCat 原生支持直接传 URL，无需下载
        for (String source : imageSources) {
            try {
                event.reply(MessageChain.ofImage(source));
                log.debug("Sent image: source={}", source);
            } catch (Exception e) {
                log.error("Failed to send image: {}", source, e);
                event.reply("图片发送失败：" + e.getMessage());
            }
        }
    }

    /**
     * 私聊版本的图片发送支持。NapCat 原生支持直接传 URL，无需下载。
     */
    private void sendPrivateReplyWithImage(PrivateMessageEvent event, String message) {
        if (message == null || message.isEmpty()) return;
        // 统一图片格式：将 Markdown ![](url) 统一转为 [IMAGE:url=url]
        message = normalizeImageFormats(message);

        Matcher anyMatcher = IMAGE_ANY_PATTERN.matcher(message);
        if (!anyMatcher.find()) {
            event.reply(message);
            return;
        }

        anyMatcher.reset();
        StringBuilder textPart = new StringBuilder();
        List<String> imageSources = new ArrayList<>();
        int lastEnd = 0;

        while (anyMatcher.find()) {
            String before = message.substring(lastEnd, anyMatcher.start());
            textPart.append(before);
            imageSources.add(anyMatcher.group(1).trim());
            lastEnd = anyMatcher.end();
        }
        String after = message.substring(lastEnd);
        textPart.append(after);

        String text = textPart.toString().trim();
        if (!text.isEmpty()) {
            event.reply(text);
        }

        for (String source : imageSources) {
            try {
                event.reply(MessageChain.ofImage(source));
                log.debug("Sent image (private): source={}", source);
            } catch (Exception e) {
                log.error("Failed to send image (private): {}", source, e);
                event.reply("图片发送失败：" + e.getMessage());
            }
        }
    }

    // ========= 图片格式统一 =========

    /**
     * 将 Markdown 图片语法 ![alt](url) 统一转为 [IMAGE:url=url] 格式，
     * 使后续 sendWithImageSupport/sendPrivateReplyWithImage 能够正确识别并发送图片。
     * <p>
     * 原因：TextToImageTool 返回 [IMAGE:url=xxx] 格式，
     * 但 LLM 拿到 URL 后可能以 Markdown 格式返回，导致 Bot 层无法识别。
     */
    private String normalizeImageFormats(String message) {
        if (message == null || message.isBlank()) return message;
        StringBuffer sb = new StringBuffer();
        Matcher matcher = MARKDOWN_IMAGE_PATTERN.matcher(message);
        while (matcher.find()) {
            String alt = matcher.group(1);
            String url = matcher.group(2);
            // 仅转 HTTP/HTTPS 和 data URI 的图片，忽略锚点等
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) {
                matcher.appendReplacement(sb, "[IMAGE:url=" + Matcher.quoteReplacement(url) + "]");
            } else {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 下载网络图片到本地临时文件，返回本地绝对路径。失败返回 null。
     */
    private String downloadImageToLocal(String imageUrl) {
        try {
            java.net.URI uri = java.net.URI.create(imageUrl);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .GET()
                    .build();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpResponse<byte[]> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("Image download failed: HTTP {}", response.statusCode());
                return null;
            }
            // 从 Content-Type 推断扩展名
            String ext = ".png";
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("jpeg") || contentType.contains("jpg")) ext = ".jpg";
            else if (contentType.contains("gif")) ext = ".gif";
            else if (contentType.contains("webp")) ext = ".webp";

            java.nio.file.Path dir = java.nio.file.Paths.get("generated_images");
            java.nio.file.Files.createDirectories(dir);
            String filename = "img_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ext;
            java.nio.file.Path targetPath = dir.resolve(filename);
            java.nio.file.Files.write(targetPath, response.body());
            return targetPath.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("Failed to download image to local: {}", imageUrl, e);
            return null;
        }
    }
}