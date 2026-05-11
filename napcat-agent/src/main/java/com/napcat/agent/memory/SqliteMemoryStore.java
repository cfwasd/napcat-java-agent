package com.napcat.agent.memory;

import com.napcat.agent.session.SessionKey;
import com.napcat.core.scheduler.DbManager;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * SQLite 实现的长记忆存储。
 * 按 (userId, groupId) 隔离，支持关键词匹配检索。
 */
@Slf4j
public class SqliteMemoryStore implements MemoryStore {

    private final DbManager dbManager;

    public SqliteMemoryStore(DbManager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * memories 表 DDL（通过 MigrationManager 执行）。
     */
    public static String ddl() {
        return "CREATE TABLE IF NOT EXISTS memories (" +
                "id TEXT PRIMARY KEY," +
                "user_id INTEGER NOT NULL," +
                "group_id INTEGER NOT NULL DEFAULT 0," +
                "content TEXT NOT NULL," +
                "type TEXT DEFAULT 'summary'," +
                "importance INTEGER DEFAULT 1," +
                "created_at TEXT DEFAULT (datetime('now'))" +
                ")";
    }

    @Override
    public List<String> retrieve(SessionKey key, String query, int limit) {
        // 优先用关键词匹配，其次按时间降序
        String sql;
        if (query != null && !query.isBlank()) {
            sql = "SELECT content FROM memories WHERE user_id = ? AND group_id = ? AND content LIKE ? " +
                    "ORDER BY importance DESC, created_at DESC LIMIT ?";
        } else {
            sql = "SELECT content FROM memories WHERE user_id = ? AND group_id = ? " +
                    "ORDER BY importance DESC, created_at DESC LIMIT ?";
        }

        List<String> results = new ArrayList<>();
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, key.userId());
            ps.setLong(2, key.groupId());
            if (query != null && !query.isBlank()) {
                ps.setString(3, "%" + query + "%");
                ps.setInt(4, limit);
            } else {
                ps.setInt(3, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(rs.getString("content"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to retrieve memories for {}", key, e);
        }
        return results;
    }

    @Override
    public void persist(SessionKey key, String content, String type) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String sql = "INSERT INTO memories (id, user_id, group_id, content, type) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setLong(2, key.userId());
            ps.setLong(3, key.groupId());
            ps.setString(4, content);
            ps.setString(5, type);
            ps.executeUpdate();
            log.debug("Memory persisted: userId={}, type={}", key.userId(), type);
        } catch (SQLException e) {
            log.error("Failed to persist memory for {}", key, e);
        }
    }

    @Override
    public void clear(SessionKey key) {
        String sql = "DELETE FROM memories WHERE user_id = ? AND group_id = ?";
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(sql)) {
            ps.setLong(1, key.userId());
            ps.setLong(2, key.groupId());
            int rows = ps.executeUpdate();
            if (rows > 0) {
                log.info("Memories cleared for {}: {} records", key, rows);
            }
        } catch (SQLException e) {
            log.error("Failed to clear memories for {}", key, e);
        }
    }
}
