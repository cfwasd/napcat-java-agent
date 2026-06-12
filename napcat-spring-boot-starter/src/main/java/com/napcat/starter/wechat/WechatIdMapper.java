package com.napcat.starter.wechat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 将微信字符串 ID 稳定映射为 NapCatAgent 可用的 long 会话键。
 */
public class WechatIdMapper {

    public long toUserId(String wechatUserId) {
        return stablePositiveLong("wechat:user:" + safe(wechatUserId));
    }

    public long toGroupId(String wechatChatId) {
        if (wechatChatId == null || wechatChatId.isBlank()) {
            return 0L;
        }
        return stablePositiveLong("wechat:group:" + safe(wechatChatId));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static long stablePositiveLong(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            long result = 0;
            for (int i = 0; i < 8; i++) {
                result = (result << 8) | (bytes[i] & 0xffL);
            }
            long positive = result & Long.MAX_VALUE;
            return positive == 0L ? 1L : positive;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
