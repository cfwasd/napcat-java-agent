package com.napcat.agent.core;

/**
 * Agent 停止策略。
 */
@FunctionalInterface
public interface StopStrategy {
    boolean shouldStop(int round, int totalTokens);
}
