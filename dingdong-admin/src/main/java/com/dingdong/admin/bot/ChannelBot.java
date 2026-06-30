package com.dingdong.admin.bot;

import com.dingdong.admin.DinDongApplication;
import com.dingdong.channel.api.ChannelEvent;
import com.dingdong.core.annotation.Command;
import com.dingdong.core.annotation.OnGroupMessage;
import com.dingdong.core.annotation.OnPrivateMessage;
import com.dingdong.core.annotation.RoleFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 通道管理 Bot。提供通过 OneBot 命令完整重启 Spring 应用的保底机制。
 */
@Slf4j
@Component
public class ChannelBot {

    @Autowired
    private ApplicationContext context;

    @OnGroupMessage
    @OnPrivateMessage
    @Command(value = "/restart", description = "完整重启Spring应用", channels = {"onebot"})
    @RoleFilter(RoleFilter.Role.SUPERUSER)
    public String restart(ChannelEvent event) {
        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.warn("管理员触发完整应用重启...");
            int exitCode = SpringApplication.exit(context, () -> 0);
            log.info("旧上下文已关闭 (exitCode={})，等待 JMX 清理...", exitCode);

            // JMX MBean 注销有延迟，等一会儿
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            log.info("正在启动新实例...");
            SpringApplication app = new SpringApplication(DinDongApplication.class);
            app.setRegisterShutdownHook(false);
            app.setDefaultProperties(java.util.Map.of(
                    "spring.application.admin.enabled", "false"));
            app.run();
        }, "app-full-restart");
        restartThread.setDaemon(false);
        restartThread.start();
        return "🔄 正在完整重启应用，预计10秒后恢复...";
    }
}