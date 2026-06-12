package com.napcat.core.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(Commands.class)
public @interface Command {
    String value();

    /**
     * 优先级，数值越小优先级越高。
     * 默认 1（最高优先级）。
     */
    int priority() default 1;

    /**
     * 命令描述，用于 /help 展示。
     */
    String description() default "";

    /**
     * 是否仅管理员可见。
     * true 时普通成员在 /help 中看不到该命令。
     */
    boolean adminOnly() default false;

    /**
     * 是否允许在安静模式下执行。
     * true 时即使群处于安静模式，该命令也能正常匹配和执行。
     * 默认 false。
     */
    boolean silentModeAllowed() default false;
}
