package com.napcat.core.annotation;

import java.lang.annotation.*;

/**
 * 定时任务注解。标注在 @Component 方法上，启动时自动注册到调度器。
 * 与 Agent 通过 ScheduleTool 动态创建的任务共享同一套存储和调度引擎。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Schedule {

    /** Cron 表达式，如 "0 0 8 * * ?" */
    String cron();

    /** 任务名称 */
    String name() default "";

    /** 执行模式：send_message=固定文本直发, ai_generate=调用 AI 生成内容 */
    String action() default "send_message";

    /** 目标类型：group / private */
    String targetType() default "group";

    /** 目标 ID（群号或用户 QQ） */
    long targetId();

    /** 固定回复文本（action=send_message 时使用） */
    String replyText() default "";

    /** AI 生成 Prompt（action=ai_generate 时使用），支持 {time} 占位符 */
    String prompt() default "";

    /** 是否启用 */
    boolean enabled() default true;
}
