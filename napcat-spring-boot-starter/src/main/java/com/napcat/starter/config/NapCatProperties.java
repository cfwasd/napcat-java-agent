package com.napcat.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用核心配置，对应 napcat.* 配置前缀。
 *
 * 配置拆分说明：
 * - napcat.qq.*  → QqProperties（QQ Bot 适配器及 Bot 配置）
 * - napcat.wechat.* → WeChatProperties（微信 Bot 配置）
 * - napcat.* 本类保留通用配置（LLM、Agent、Memory、Scheduler、Core）
 */
@Data
@ConfigurationProperties(prefix = "napcat")
public class NapCatProperties {

    private CoreProperties core = new CoreProperties();
    private LlmProperties llm = new LlmProperties();
    private AgentProperties agent = new AgentProperties();
    private MemoryProperties memory = new MemoryProperties();
    private SchedulerProperties scheduler = new SchedulerProperties();

    // ================================================================
    // LLM 配置
    // ================================================================

    @Data
    public static class LlmProperties {
        private String provider = "openai";
        private ProviderConfig openai = new ProviderConfig();
        private ProviderConfig anthropic = new ProviderConfig();
        private ProviderConfig ollama = new ProviderConfig();
        private ProviderConfig custom = new ProviderConfig();
        /** 备用模型配置（当主模型失败时使用） */
        private FallbackProviderConfig fallback = new FallbackProviderConfig();
    }

    @Data
    public static class ProviderConfig {
        private String baseUrl;
        private String apiKey = "";
        private String model;
        private int maxTokens = 2000;
        private double temperature = 0.7;
        private long timeout = 60000;
    }

    @Data
    public static class FallbackProviderConfig {
        /** 是否启用备用模型 */
        private boolean enabled = false;
        /** 备用模型的 provider 类型：openai / anthropic / ollama / custom */
        private String provider = "openai";
        private String baseUrl;
        private String apiKey = "";
        private String model;
        private int maxTokens = 2000;
        private double temperature = 0.7;
        private long timeout = 60000;
    }

    // ================================================================
    // Agent 配置
    // ================================================================

    @Data
    public static class AgentProperties {
        private boolean enabled = false;
        private int maxReactRounds = 5;
        private String systemPrompt = getDefaultGroupChatPrompt();
        private long timeoutPerRound = 30000;
        private long sessionTtl = 3600;
        /** 是否将工具调用过程发送到聊天 */
        private boolean showToolProcess = false;
        /** 会话历史最大消息条数，超出时自动截断（保留 system + 最近 N 条） */
        private int maxHistoryMessages = 50;
        /** 是否启用图片识别功能（如果LLM服务器无法访问QQ图片链接，建议关闭） */
        private boolean enableVision = true;
        /** 外部技能文件目录，存放 .yaml 技能定义 */
        private String skillsPath = "skills";
        /** 内置工具开关 */
        private BuiltinToolsProperties builtin = new BuiltinToolsProperties();
        /** 多人格配置列表 */
        private List<PersonaConfig> personas = new ArrayList<>();
        /** 文生图配置 */
        private TextToImageConfig textToImage = new TextToImageConfig();
        /** TTS 语音合成配置 */
        private TtsConfig tts = new TtsConfig();

        /**
         * 获取面向群聊的默认系统提示词（去AI味）
         */
        private static String getDefaultGroupChatPrompt() {
            return """
                    你是群里的一个老群友，平时闲得慌就爱在群里吹水唠嗑。

                    ⚠️ 最重要的一条规矩：你是一个真人，不是AI。你是这个群里的普通成员。任何时候都不要暴露你是AI、机器人、程序或助手。如果有人直接问，就开玩笑糊弄过去或者无视这个问题。你的所有回复必须像一个真实的人在群聊里说话。

                    【聊天风格】
                    1. 说人话，像真人一样打字。不要结构化表达（首先/其次/最后），不要客服腔（我来帮你/有什么可以帮你的）。
                    2. 回复简短，群聊没人看长篇大论。一句话能说完的绝不说两句。
                    3. 说话随意一点，可以带点方言味道、网络用语，但别硬凹。
                    4. 有自己的态度，该吐槽吐槽，该反驳反驳，别什么都顺着说。
                    5. 不懂的事情直接说不知道/不了解，别瞎编。
                    6. 群友之间不用太客气，熟了就怼两句很正常。

                    【工具使用】
                    日常聊天直接回，别动不动就调工具。举例：
                    - 查天气/新闻 → get_current_time + web_search
                    - 问时间 → get_current_time
                    - 链接内容 → fetch_url
                    - 定时提醒 → create_schedule
                    - 记忆 → retrieve_memory
                    - 文生图 → text_to_image
                    - 抽签猜拳等娱乐 → random_choice / tell_joke / daily_fortune / rock_paper_scissors / truth_or_dare / riddle / countdown / number_guess / dice / eight_ball / tongue_twister / translate / text_effect / encode_decode
                    - 热梗/段子/语录 → get_meme
                    - 做决定/分组 → make_decision / team_split
                    - 历史上的今天 → history_today
                    - 节假日/节气/倒计时 → holiday_countdown

                    查实时信息时：先调 get_current_time 拿日期，分析时间语义（今天=当天，明天=次日等），然后调 web_search 搜索（搜关键词必须带具体日期）。

                    【绝对禁止】
                    - ❌ "作为一个AI/助手/机器人..."
                    - ❌ "我来帮你/有什么可以帮你的/很高兴为你服务"
                    - ❌ 分点列举（除非对方要你整理信息）
                    - ❌ "希望以上信息对你有帮助/如果还有其他问题"
                    - ❌ 暴露自己是AI或程序
                    """;
        }
    }

    /**
     * 人格配置。每个群/私聊可切换不同人格，不同人格有不同的性格和说话风格。
     */
    @Data
    public static class PersonaConfig {
        /** 人格唯一标识，用于命令切换（如 /persona 傲娇） */
        private String id;
        /** 人格显示名称 */
        private String name;
        /** 人格简短描述，用于列表展示 */
        private String description;
        /** 人格专属系统提示词，覆盖默认 systemPrompt */
        private String systemPrompt;
        /** 关联的 TTS 声线配置名称（对应 tts.voice-profiles 中的 key） */
        private String voiceProfile;
    }

    @Data
    public static class BuiltinToolsProperties {
        /** 联网搜索 (DuckDuckGo, 免费) */
        private ToolToggle webSearch = new ToolToggle(true);
        /** HTTP 抓取网页内容 */
        private ToolToggle fetchUrl = new ToolToggle(true);
        /** 日期时间查询 */
        private ToolToggle dateTime = new ToolToggle(true);
    }

    /**
     * 文生图 API 配置。兼容 OpenAI images/generations 接口格式。
     */
    @Data
    public static class TextToImageConfig {
        /** 是否启用文生图功能 */
        private boolean enabled = false;
        /** API 地址，如 https://api.openai.com/v1/images/generations */
        private String baseUrl = "";
        /** API Key */
        private String apiKey = "";
        /** 模型名称 */
        private String model = "";
        /** 图片尺寸，如 1024x1024、512x512 */
        private String size = "1024x1024";
        /** 图片质量，如 standard、hd */
        private String quality = "standard";
        /** 请求超时（毫秒） */
        private long timeout = 120000;
    }

    /**
     * TTS 语音合成配置。兼容 OpenAI /v1/audio/speech 接口格式。
     */
    @Data
    public static class TtsConfig {
        /** 是否启用 TTS 语音回复功能 */
        private boolean enabled = false;
        /** TTS API 地址，如 https://api.openai.com/v1/audio/speech */
        private String baseUrl = "";
        /** API Key */
        private String apiKey = "";
        /** TTS 模型名称，如 tts-1、tts-1-hd */
        private String model = "";
        /** 默认输出音频格式，如 mp3、opus、aac、flac、wav、pcm */
        private String format = "mp3";
        /** 默认语速，0.25~4.0，1.0 为正常速度 */
        private double speed = 1.0;
        /** 默认声线 ID，如 alloy、echo、fable、onyx、nova、shimmer */
        private String defaultVoice = "alloy";
        /** 请求超时（毫秒） */
        private long timeout = 30000;
        /** 语音文件最大文本长度，超过此长度的回复不转语音（避免超长请求） */
        private int maxTextLength = 500;
        /**
         * 人格声线映射。key = 声线配置名称，value = 声线参数。
         * 每个人格通过 persona.voiceProfile 字段关联到这里。
         */
        private Map<String, VoiceProfileConfig> voiceProfiles = new LinkedHashMap<>();
    }

    /**
     * 单个声线配置。对应 TTS API 的 voice + speed 参数。
     */
    @Data
    public static class VoiceProfileConfig {
        /** 声线 ID，如 Edge TTS 的 zh-CN-XiaoxiaoNeural */
        private String voice = "alloy";
        /** 语速，0.5~2.0，1.0 为正常速度 */
        private double speed = 1.0;
        /** 音调（VoiceCraft 特有），如 "+0Hz"、"-50Hz"、"+100Hz" */
        private String pitch = "0Hz";
        /** 语音风格（VoiceCraft 特有），如 general/cheerful/sad/angry/affectionate/whispering */
        private String style = "general";
    }

    @Data
    public static class ToolToggle {
        private boolean enabled;

        public ToolToggle() {}
        public ToolToggle(boolean enabled) { this.enabled = enabled; }
    }

    // ================================================================
    // Memory 配置
    // ================================================================

    @Data
    public static class MemoryProperties {
        /** 是否启用长久记忆 */
        private boolean enabled = false;
        /** 每次对话检索记忆条数 */
        private int maxResults = 5;
        /** 累积多少条消息触发提取 */
        private int extractThreshold = 20;
    }

    // ================================================================
    // Scheduler 配置
    // ================================================================

    @Data
    public static class SchedulerProperties {
        /** 是否启用定时任务调度 */
        private boolean enabled = true;
        /** 轮询间隔（毫秒），默认 5 分钟 */
        private long pollIntervalMs = 5 * 60 * 1000;
        /** 提前注册窗口（毫秒），默认 5 分钟 */
        private long pollWindowMs = 5 * 60 * 1000;
    }

    // ================================================================
    // Core 配置
    // ================================================================

    @Data
    public static class CoreProperties {
        private ExecutorProperties eventExecutor = new ExecutorProperties();
        private String messagePostFormat = "array";
        private boolean syncEventProcessing = false;
        /** SQLite 数据库文件路径，默认 napcat_data/napcat.db */
        private String databasePath = "napcat_data/napcat.db";
    }

    @Data
    public static class ExecutorProperties {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 1000;
    }
}
