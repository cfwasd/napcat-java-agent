package com.napcat.core.group;

import com.napcat.core.scheduler.DbManager;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 群偏好设置存储。管理群级别的功能开关（如安静模式）。
 * 持久化到 SQLite group_preferences 表，按 group_id 隔离。
 */
@Slf4j
public class GroupPreferenceStore {

    private final DbManager dbManager;

    /** 优先级等级：普通人 < 管理员 < 群主 < 超管 */
    public static final int PRIORITY_NORMAL = 0;
    public static final int PRIORITY_ADMIN = 1;
    public static final int PRIORITY_OWNER = 2;
    public static final int PRIORITY_SUPERUSER = 3;

    /** 安静模式默认持续时间（毫秒）：3 分钟 */
    private static final long SILENT_DURATION_MS = 3 * 60 * 1000L;

    public GroupPreferenceStore(DbManager dbManager) {
        this.dbManager = dbManager;
    }

    /** 建表 DDL */
    public static String ddl() {
        return "CREATE TABLE IF NOT EXISTS group_preferences (" +
                "group_id INTEGER NOT NULL," +
                "pref_key TEXT NOT NULL," +
                "pref_value TEXT NOT NULL," +
                "updated_at TEXT DEFAULT (datetime('now'))," +
                "PRIMARY KEY (group_id, pref_key)" +
                ")";
    }

    /**
     * 检查群是否处于安静模式（未过期）。
     */
    public boolean isSilent(long groupId) {
        SilentInfo info = getSilentInfo(groupId);
        if (info == null) return false;
        if (info.isExpired()) {
            // 过期自动清理
            removeSilent(groupId);
            return false;
        }
        return true;
    }

    /**
     * 获取安静模式详细信息。
     */
    public SilentInfo getSilentInfo(long groupId) {
        String sql = "SELECT pref_value FROM group_preferences WHERE group_id = ? AND pref_key = 'silent_mode'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return SilentInfo.fromJson(rs.getString("pref_value"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get silent mode for group {}", groupId, e);
        }
        return null;
    }

    /**
     * 激活安静模式。
     *
     * @param groupId       群号
     * @param activatorId   激活者 QQ 号
     * @param priorityLevel 激活者优先级
     * @param durationMs    持续时间（毫秒），传 0 使用默认 3 分钟
     * @return true 如果成功激活，false 如果已有更高级别的安静模式
     */
    public boolean activateSilent(long groupId, long activatorId, int priorityLevel, long durationMs) {
        SilentInfo existing = getSilentInfo(groupId);
        if (existing != null && !existing.isExpired()) {
            // 已有未过期的安静模式
            if (existing.activatorId == activatorId) {
                // 同一人重复激活 → 不处理
                return false;
            }
            // 检查优先级：低级别不能覆盖高级别
            if (priorityLevel < existing.priorityLevel) {
                return false;
            }
        }

        long actualDuration = durationMs > 0 ? durationMs : SILENT_DURATION_MS;
        SilentInfo info = new SilentInfo(activatorId, priorityLevel,
                System.currentTimeMillis() + actualDuration);
        return setSilent(groupId, info);
    }

    /**
     * 关闭安静模式。
     *
     * @param groupId       群号
     * @param operatorId    操作者 QQ 号
     * @param operatorLevel 操作者优先级
     * @return 关闭结果
     */
    public DeactivateResult deactivateSilent(long groupId, long operatorId, int operatorLevel) {
        SilentInfo info = getSilentInfo(groupId);
        if (info == null || info.isExpired()) {
            return DeactivateResult.NOT_ACTIVE;
        }

        // 权限检查：操作者必须是发起人，或同级/更高级
        if (operatorId != info.activatorId && operatorLevel < info.priorityLevel) {
            return DeactivateResult.NO_PERMISSION;
        }

        removeSilent(groupId);
        return DeactivateResult.SUCCESS;
    }

    /**
     * 设置安静模式。
     */
    private boolean setSilent(long groupId, SilentInfo info) {
        String sql = "INSERT INTO group_preferences (group_id, pref_key, pref_value, updated_at) " +
                "VALUES (?, ?, ?, datetime('now')) " +
                "ON CONFLICT(group_id, pref_key) DO UPDATE SET pref_value = ?, updated_at = datetime('now')";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            String json = info.toJson();
            ps.setLong(1, groupId);
            ps.setString(2, "silent_mode");
            ps.setString(3, json);
            ps.setString(4, json);
            ps.executeUpdate();
            log.info("Silent mode activated for group {} by user {} (priority={}), expires at {}",
                    groupId, info.activatorId, info.priorityLevel, info.expiryTime);
            return true;
        } catch (SQLException e) {
            log.error("Failed to set silent mode for group {}", groupId, e);
            return false;
        }
    }

    /**
     * 移除安静模式。
     */
    private void removeSilent(long groupId) {
        String sql = "DELETE FROM group_preferences WHERE group_id = ? AND pref_key = 'silent_mode'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, groupId);
            ps.executeUpdate();
            log.info("Silent mode deactivated for group {}", groupId);
        } catch (SQLException e) {
            log.error("Failed to remove silent mode for group {}", groupId, e);
        }
    }

    /**
     * 清理所有过期的安静模式。
     */
    public void cleanupExpired() {
        // 由于过期判断需要应用层逻辑，这里遍历所有记录检查
        String selectSql = "SELECT group_id, pref_value FROM group_preferences WHERE pref_key = 'silent_mode'";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long groupId = rs.getLong("group_id");
                SilentInfo info = SilentInfo.fromJson(rs.getString("pref_value"));
                if (info != null && info.isExpired()) {
                    removeSilent(groupId);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to cleanup expired silent modes", e);
        }
    }

    // ===== 内部数据结构 =====

    /**
     * 安静模式状态信息。
     */
    public static class SilentInfo {
        public final long activatorId;
        public final int priorityLevel;
        public final long expiryTime;

        public SilentInfo(long activatorId, int priorityLevel, long expiryTime) {
            this.activatorId = activatorId;
            this.priorityLevel = priorityLevel;
            this.expiryTime = expiryTime;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }

        public long getRemainingSeconds() {
            long remaining = (expiryTime - System.currentTimeMillis()) / 1000;
            return Math.max(0, remaining);
        }

        public String toJson() {
            return String.format("{\"activatorId\":%d,\"priorityLevel\":%d,\"expiryTime\":%d}",
                    activatorId, priorityLevel, expiryTime);
        }

        public static SilentInfo fromJson(String json) {
            if (json == null || json.isBlank()) return null;
            try {
                // 简单 JSON 解析，避免引入额外依赖
                long activatorId = extractLong(json, "activatorId");
                int priorityLevel = (int) extractLong(json, "priorityLevel");
                long expiryTime = extractLong(json, "expiryTime");
                return new SilentInfo(activatorId, priorityLevel, expiryTime);
            } catch (Exception e) {
                log.warn("Failed to parse silent mode JSON: {}", json, e);
                return null;
            }
        }

        private static long extractLong(String json, String key) {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) throw new IllegalArgumentException("Key not found: " + key);
            start += search.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
                end++;
            }
            return Long.parseLong(json.substring(start, end));
        }

        /** 优先级名称 */
        public static String priorityName(int level) {
            return switch (level) {
                case PRIORITY_NORMAL -> "普通成员";
                case PRIORITY_ADMIN -> "管理员";
                case PRIORITY_OWNER -> "群主";
                case PRIORITY_SUPERUSER -> "超管";
                default -> "未知";
            };
        }
    }

    /**
     * 关闭安静模式的结果。
     */
    public enum DeactivateResult {
        SUCCESS,         // 成功关闭
        NOT_ACTIVE,      // 当前没有安静模式
        NO_PERMISSION    // 权限不足
    }
}
