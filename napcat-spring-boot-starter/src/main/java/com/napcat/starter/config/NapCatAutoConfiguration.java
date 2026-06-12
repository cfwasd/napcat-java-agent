package com.napcat.starter.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.napcat.agent.agent.NapCatAgent;
import com.napcat.agent.agent.PersonaManager;
import com.napcat.agent.llm.LlmProvider;
import com.napcat.agent.session.SessionManager;
import com.napcat.agent.tool.ToolRegistry;
import com.napcat.agent.tool.builtin.TextToImageTool;
import com.napcat.agent.tts.TtsService;
import com.napcat.core.adapter.*;
import com.napcat.core.api.NapCatApi;
import com.napcat.core.config.BotProperties;
import com.napcat.core.handler.EventDispatcher;
import com.napcat.core.handler.HandlerRegistry;
import com.napcat.core.scheduler.*;
import com.napcat.core.tts.VoicePreferenceStore;
import com.napcat.core.group.GroupPreferenceStore;
import com.napcat.agent.memory.*;
import com.napcat.agent.scheduler.TaskExecutor;
import com.napcat.agent.scheduler.ScheduleTool;
import com.napcat.starter.adapter.HttpServerAdapter;
import com.napcat.starter.wechat.AgentWechatClient;
import com.napcat.starter.wechat.AgentWechatPoller;
import com.napcat.starter.wechat.WechatIdMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@AutoConfiguration
@EnableConfigurationProperties({NapCatProperties.class, QqProperties.class, WechatProperties.class})
@ComponentScan("com.napcat")
public class NapCatAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper napcatObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public BotProperties botProperties(QqProperties props) {
        BotProperties bp = new BotProperties();
        bp.setSelfId(props.getBot().getSelfId());
        bp.setCommandPrefix(props.getBot().getCommandPrefix());
        bp.setAtMeTrigger(props.getBot().isAtMeTrigger());
        bp.setIgnoreSelfMessage(props.getBot().isIgnoreSelfMessage());
        bp.setSuperUsers(props.getBot().getSuperUsers());
        bp.setWakeWords(props.getBot().getWakeWords());
        return bp;
    }

    // ================================================================
    // Adapter (QQ Bot)
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${napcat.qq.enabled:true} and '${napcat.qq.adapter.type:websocket-client}' == 'websocket-client'")
    public BotAdapter wsClientBotAdapter(QqProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getWebsocketClient();
        return new WsClientAdapter(c.getUrl(), c.getToken(), c.getReconnectInterval(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${napcat.qq.enabled:true} and '${napcat.qq.adapter.type:http-client}' == 'http-client'")
    public BotAdapter httpClientBotAdapter(QqProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getHttpClient();
        return new HttpClientAdapter(c.getUrl(), c.getToken(), c.getTimeout(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${napcat.qq.enabled:true} and '${napcat.qq.adapter.type:websocket-server}' == 'websocket-server'")
    public BotAdapter wsServerBotAdapter(QqProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getWebsocketServer();
        return new WsServerAdapter(c.getHost(), c.getPort(), c.getToken(), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${napcat.qq.enabled:true} and '${napcat.qq.adapter.type:http-server}' == 'http-server'")
    public BotAdapter httpServerBotAdapter(QqProperties props, ObjectMapper mapper) {
        var c = props.getAdapter().getHttpServer();
        return new HttpServerAdapter(mapper,
                c.getPath(), c.getToken(),
                c.getApiUrl(), c.getApiToken(), c.getApiTimeout());
    }

    // ================================================================
    // API + Router + Dispatcher
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public NapCatApi napCatApi(List<BotAdapter> adapters, ObjectMapper mapper) {
        return new NapCatApi(adapters.isEmpty() ? null : adapters.get(0), mapper, 30000);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.qq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MessageRouter messageRouter(ObjectMapper mapper, NapCatApi api) {
        return new MessageRouter(mapper, api);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerRegistry handlerRegistry(BotProperties botProperties,
                                           ObjectProvider<GroupPreferenceStore> groupPrefStoreProvider) {
        HandlerRegistry registry = new HandlerRegistry(botProperties);
        GroupPreferenceStore groupPrefStore = groupPrefStoreProvider.getIfAvailable();
        if (groupPrefStore != null) {
            registry.setSilentModeChecker(groupPrefStore::isSilent);
        }
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public EventDispatcher eventDispatcher(HandlerRegistry registry, BotProperties botProperties,
                                           NapCatApi api, NapCatProperties props) {
        var execProps = props.getCore().getEventExecutor();
        boolean sync = props.getCore().isSyncEventProcessing();
        Executor executor = new ThreadPoolExecutor(
                execProps.getCorePoolSize(),
                execProps.getMaxPoolSize(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(execProps.getQueueCapacity()),
                r -> {
                    Thread t = new Thread(r, "napcat-event-pool");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        return new EventDispatcher(registry, botProperties, api, sync, executor);
    }

    // ================================================================
    // Agent
    // ================================================================

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(ApplicationContext ctx) {
        ToolRegistry registry = new ToolRegistry();
        for (String name : ctx.getBeanDefinitionNames()) {
            try {
                Object bean = ctx.getBean(name);
                Class<?> clazz = bean.getClass();
                while (clazz.getName().contains("$$")) {
                    clazz = clazz.getSuperclass();
                }
                for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(com.napcat.core.annotation.Tool.class)) {
                        registry.register(bean);
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        log.info("ToolRegistry initialized with {} tools", registry.getSchemas().size());
        return registry;
    }

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public SessionManager sessionManager(NapCatProperties props, ObjectProvider<MemoryStore> memoryStoreProvider) {
        SessionManager manager = new SessionManager(props.getAgent().getSessionTtl(),
                props.getAgent().getMaxHistoryMessages());
        MemoryStore store = memoryStoreProvider.getIfAvailable();
        if (store != null) {
            manager.setMemoryStore(store);
        }
        return manager;
    }

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public NapCatAgent napCatAgent(ObjectProvider<LlmProvider> llmProvider, ToolRegistry toolRegistry,
                                    SessionManager sessionManager, NapCatProperties props,
                                    ObjectProvider<PersonaManager> personaManagerProvider,
                                    ApplicationContext ctx) {
        LlmProvider provider = llmProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("No LlmProvider bean found. Please add a provider dependency like napcat-llm-openai.");
        }

        if (props.getLlm().getFallback().isEnabled()) {
            LlmProvider fallbackProvider = createFallbackProvider(props);
            if (fallbackProvider != null) {
                provider = new com.napcat.agent.llm.FallbackLlmProvider(provider, fallbackProvider, true);
                log.info("Fallback LLM provider enabled: primary -> {}",
                        props.getLlm().getFallback().getProvider());
            }
        }

        NapCatAgent agent = new NapCatAgent(provider, toolRegistry, sessionManager,
                ctx.getBeanProvider(MemoryStore.class).getIfAvailable(),
                () -> ctx.getBeanProvider(MemoryExtractor.class).getIfAvailable(),
                props.getAgent().getSystemPrompt(), props.getAgent().getMaxReactRounds(),
                props.getAgent().isEnableVision());

        PersonaManager pm = personaManagerProvider.getIfAvailable();
        if (pm != null) {
            agent.setPersonaManager(pm);
            log.info("PersonaManager injected into NapCatAgent, {} personas loaded", pm.size());
        }

        TextToImageTool textToImageTool = ctx.getBeanProvider(TextToImageTool.class).getIfAvailable();
        if (textToImageTool != null) {
            var t2i = props.getAgent().getTextToImage();
            textToImageTool.configure(
                    t2i.getBaseUrl(), t2i.getApiKey(), t2i.getModel(),
                    t2i.getSize(), t2i.getQuality(), t2i.getTimeout(), t2i.isEnabled());
            log.info("TextToImageTool configured: enabled={}", t2i.isEnabled());
        }

        return agent;
    }

    private LlmProvider createFallbackProvider(NapCatProperties props) {
        var fallback = props.getLlm().getFallback();
        String providerType = fallback.getProvider().toLowerCase();

        try {
            return switch (providerType) {
                case "openai", "custom" -> createOpenAiProvider(fallback);
                case "anthropic" -> createAnthropicProvider(fallback);
                case "ollama" -> createOllamaProvider(fallback);
                default -> {
                    log.warn("Unknown fallback provider type: {}", providerType);
                    yield null;
                }
            };
        } catch (Exception e) {
            log.error("Failed to create fallback LLM provider: {}", providerType, e);
            return null;
        }
    }

    private LlmProvider createOpenAiProvider(NapCatProperties.FallbackProviderConfig config) {
        try {
            Class<?> clazz = Class.forName("com.napcat.llm.openai.OpenAiProvider");
            return (LlmProvider) clazz.getConstructor(
                    String.class, String.class, String.class,
                    int.class, double.class, long.class
            ).newInstance(
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    config.getMaxTokens(),
                    config.getTemperature(),
                    config.getTimeout()
            );
        } catch (ClassNotFoundException e) {
            log.error("OpenAI provider class not found. Make sure napcat-llm-openai is in classpath.");
            return null;
        } catch (Exception e) {
            log.error("Failed to create OpenAI provider", e);
            return null;
        }
    }

    private LlmProvider createAnthropicProvider(NapCatProperties.FallbackProviderConfig config) {
        try {
            Class<?> clazz = Class.forName("com.napcat.llm.anthropic.AnthropicProvider");
            return (LlmProvider) clazz.getConstructor(
                    String.class, String.class, String.class,
                    int.class, double.class, long.class
            ).newInstance(
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    config.getMaxTokens(),
                    config.getTemperature(),
                    config.getTimeout()
            );
        } catch (ClassNotFoundException e) {
            log.error("Anthropic provider class not found. Make sure napcat-llm-anthropic is in classpath.");
            return null;
        } catch (Exception e) {
            log.error("Failed to create Anthropic provider", e);
            return null;
        }
    }

    private LlmProvider createOllamaProvider(NapCatProperties.FallbackProviderConfig config) {
        try {
            Class<?> clazz = Class.forName("com.napcat.llm.ollama.OllamaProvider");
            return (LlmProvider) clazz.getConstructor(
                    String.class, String.class, long.class
            ).newInstance(
                    config.getBaseUrl(),
                    config.getModel(),
                    config.getTimeout()
            );
        } catch (ClassNotFoundException e) {
            log.error("Ollama provider class not found. Make sure napcat-llm-ollama is in classpath.");
            return null;
        } catch (Exception e) {
            log.error("Failed to create Ollama provider", e);
            return null;
        }
    }

    // ================================================================
    // Persona
    // ================================================================

    @Bean
    @ConditionalOnProperty(prefix = "napcat.agent", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean
    public PersonaManager personaManager(NapCatProperties props) {
        PersonaManager manager = new PersonaManager();
        var personaConfigs = props.getAgent().getPersonas();
        if (personaConfigs != null) {
            for (var pc : personaConfigs) {
                if (pc.getId() != null && pc.getSystemPrompt() != null) {
                    manager.register(new PersonaManager.PersonaDefinition(
                            pc.getId(),
                            pc.getName() != null ? pc.getName() : pc.getId(),
                            pc.getDescription(),
                            pc.getSystemPrompt(),
                            pc.getVoiceProfile()
                    ));
                }
            }
        }
        return manager;
    }

    // ================================================================
    // Database
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    public DbManager dbManager(NapCatProperties props) {
        DbManager db = new DbManager(props.getCore().getDatabasePath());
        db.init();
        return db;
    }

    @Bean
    @ConditionalOnMissingBean
    public MigrationManager migrationManager(DbManager dbManager) {
        MigrationManager mm = new MigrationManager(dbManager);
        mm.register(1, "create schedules table", ScheduleStore.ddl());
        mm.register(2, "create memories table", SqliteMemoryStore.memoriesDdl());
        mm.register(3, "set default created_by for existing schedules",
                "UPDATE schedules SET created_by = 0 WHERE created_by IS NULL");
        mm.register(4, "add is_recurring column to schedules",
                "ALTER TABLE schedules ADD COLUMN is_recurring INTEGER DEFAULT 1");
        mm.register(5, "create memory_summaries table", SqliteMemoryStore.summariesDdl());
        mm.register(6, "create memory indexes",
                "CREATE INDEX IF NOT EXISTS idx_memories_user_group ON memories(user_id, group_id);" +
                "CREATE INDEX IF NOT EXISTS idx_memories_created ON memories(created_at);" +
                "CREATE INDEX IF NOT EXISTS idx_summaries_user_group_date ON memory_summaries(user_id, group_id, summary_date);");
        mm.register(7, "create user_preferences table", VoicePreferenceStore.ddl());
        mm.register(8, "create group_preferences table", GroupPreferenceStore.ddl());
        mm.migrate();

        mm.ensureColumn("schedules", "is_recurring", "INTEGER DEFAULT 1");
        mm.ensureColumn("schedules", "created_by", "INTEGER DEFAULT 0");
        mm.ensureColumn("memories", "type", "TEXT DEFAULT 'summary'");
        mm.ensureColumn("memories", "importance", "INTEGER DEFAULT 1");
        mm.ensureColumn("memory_summaries", "summary_date", "TEXT NOT NULL DEFAULT ''");
        mm.ensureColumn("memory_summaries", "group_id", "INTEGER NOT NULL DEFAULT 0");

        return mm;
    }

    @Bean
    @ConditionalOnMissingBean
    public ScheduleStore scheduleStore(DbManager dbManager) {
        return new ScheduleStore(dbManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TimerWheel timerWheel() {
        return new TimerWheel();
    }

    @Bean(name = "napcatTaskExecutor")
    @ConditionalOnMissingBean(name = "napcatTaskExecutor")
    @ConditionalOnExpression("${napcat.qq.enabled:true} and ${napcat.scheduler.enabled:true}")
    public TaskExecutor taskExecutor(NapCatApi api, ObjectProvider<NapCatAgent> agentProvider,
                                      ObjectProvider<DailyMemorySummarizer> summarizerProvider) {
        return new TaskExecutor(api, agentProvider.getIfAvailable(), summarizerProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ScheduleTool scheduleTool(ScheduleStore store, ObjectProvider<SchedulePoller> pollerProvider) {
        return new ScheduleTool(store, pollerProvider.getIfAvailable());
    }

    // ================================================================
    // Memory
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.memory", name = "enabled", havingValue = "true")
    public MemoryStore memoryStore(DbManager dbManager) {
        return new SqliteMemoryStore(dbManager);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.memory", name = "enabled", havingValue = "true")
    public MemoryExtractor memoryExtractor(MemoryStore memoryStore, NapCatAgent agent, NapCatProperties props) {
        return new MemoryExtractor(memoryStore, agent, props.getMemory().getExtractThreshold());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.memory", name = {"enabled", "test-data-enabled"}, havingValue = "true")
    public com.napcat.agent.memory.MemoryTestDataInjector memoryTestDataInjector(DbManager dbManager) {
        return new com.napcat.agent.memory.MemoryTestDataInjector(dbManager);
    }

    // ================================================================
    // Scheduler
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${napcat.qq.enabled:true} and ${napcat.scheduler.enabled:true}")
    public SchedulePoller schedulePoller(ScheduleStore store, TimerWheel timerWheel,
                                          @org.springframework.beans.factory.annotation.Qualifier("napcatTaskExecutor") TaskExecutor executor, NapCatProperties props) {
        SchedulePoller poller = new SchedulePoller(store, timerWheel, executor::execute,
                props.getScheduler().getPollIntervalMs(),
                props.getScheduler().getPollWindowMs());
        return poller;
    }

    // ================================================================
    // TTS
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.agent.tts", name = "enabled", havingValue = "true")
    public TtsService ttsService(NapCatProperties props) {
        var ttsCfg = props.getAgent().getTts();
        TtsService.TtsConfig cfg = new TtsService.TtsConfig();
        cfg.setEnabled(ttsCfg.isEnabled());
        cfg.setBaseUrl(ttsCfg.getBaseUrl());
        cfg.setApiKey(ttsCfg.getApiKey());
        cfg.setModel(ttsCfg.getModel());
        cfg.setFormat(ttsCfg.getFormat());
        cfg.setSpeed(ttsCfg.getSpeed());
        cfg.setDefaultVoice(ttsCfg.getDefaultVoice());
        cfg.setTimeout(ttsCfg.getTimeout());
        cfg.setMaxTextLength(ttsCfg.getMaxTextLength());
        if (ttsCfg.getVoiceProfiles() != null) {
            for (var entry : ttsCfg.getVoiceProfiles().entrySet()) {
                TtsService.VoiceProfile vp = new TtsService.VoiceProfile();
                vp.setVoice(entry.getValue().getVoice());
                vp.setSpeed(entry.getValue().getSpeed());
                vp.setPitch(entry.getValue().getPitch());
                vp.setStyle(entry.getValue().getStyle());
                cfg.getVoiceProfiles().put(entry.getKey(), vp);
            }
        }
        return new TtsService(cfg);
    }

    @Bean
    @ConditionalOnMissingBean
    public VoicePreferenceStore voicePreferenceStore(DbManager dbManager) {
        return new VoicePreferenceStore(dbManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public GroupPreferenceStore groupPreferenceStore(DbManager dbManager) {
        return new GroupPreferenceStore(dbManager);
    }

    // ================================================================
    // WeChat (agent-wechat REST API)
    // ================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.wechat", name = "enabled", havingValue = "true")
    public WechatIdMapper wechatIdMapper() {
        return new WechatIdMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "napcat.wechat", name = "enabled", havingValue = "true")
    public AgentWechatClient agentWechatClient(WechatProperties props, ObjectMapper mapper) {
        return new AgentWechatClient(props.getApiBaseUrl(), resolveWechatToken(props),
                Duration.ofMillis(props.getApiTimeout()), mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("${napcat.wechat.enabled:false} and ${napcat.agent.enabled:false}")
    public AgentWechatPoller agentWechatPoller(AgentWechatClient client, WechatIdMapper mapper,
                                               WechatProperties wechatProps, NapCatAgent agent,
                                               HandlerRegistry handlerRegistry) {
        return new AgentWechatPoller(client, mapper, wechatProps,
                (userId, groupId, prompt) -> agent.chat(userId, groupId, prompt), handlerRegistry);
    }

    private String resolveWechatToken(WechatProperties props) {
        if (props.getToken() != null && !props.getToken().isBlank()) {
            return props.getToken().trim();
        }
        String tokenFile = props.getTokenFile();
        if (tokenFile == null || tokenFile.isBlank()) {
            return "";
        }
        String expanded = tokenFile.replace("${user.home}", System.getProperty("user.home"));
        try {
            Path path = Path.of(expanded);
            if (Files.exists(path)) {
                return Files.readString(path).trim();
            }
        } catch (Exception e) {
            log.warn("Failed to read agent-wechat token file: {}", expanded, e);
        }
        return "";
    }

    // ================================================================
    // 后处理器 + 生命周期
    // ================================================================

    @Bean
    public NapCatBeanPostProcessor napCatBeanPostProcessor(HandlerRegistry registry, ApplicationContext ctx) {
        return new NapCatBeanPostProcessor(registry, ctx);
    }

    @Bean
    @ConditionalOnProperty(prefix = "napcat.qq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public NapCatLifecycle napCatLifecycle(List<BotAdapter> adapters, EventDispatcher dispatcher,
                                           NapCatApi api, HandlerRegistry registry,
                                           MessageRouter messageRouter,
                                           BotProperties botProperties,
                                           ObjectProvider<NapCatAgent> agentProvider,
                                           ApplicationContext ctx) {
        return new NapCatLifecycle(adapters, dispatcher, api, registry, messageRouter,
                botProperties, agentProvider, ctx);
    }
}
