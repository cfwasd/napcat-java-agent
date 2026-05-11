package com.napcat.core.scheduler;

import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SQLite 单连接管理器。
 * 数据库文件路径可由构造函数传入，默认 napcat_data/schedules.db。
 */
@Slf4j
public class DbManager {

    private final String dbPath;
    private Connection connection;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean initialized = false;

    public DbManager() {
        this("napcat_data/napcat.db");
    }

    public DbManager(String dbPath) {
        this.dbPath = dbPath;
    }

    /**
     * 初始化：创建目录、建立连接、开启 WAL 模式。
     */
    public void init() {
        if (initialized) {
            log.debug("Database already initialized, skipping");
            return;
        }
        
        lock.lock();
        try {
            if (initialized) {
                return;
            }
            
            doInit();
            initialized = true;
            
        } finally {
            lock.unlock();
        }
    }
    
    private void doInit() {
        int maxRetries = 5;
        long baseWaitMs = 2000;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                java.io.File dbFile = new java.io.File(dbPath);
                java.io.File parentDir = dbFile.getParentFile();
                
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        log.error("Failed to create database directory: {}", parentDir.getAbsolutePath());
                        throw new RuntimeException("Cannot create database directory: " + parentDir.getAbsolutePath());
                    }
                    log.info("Created database directory: {}", parentDir.getAbsolutePath());
                }
                
                if (!parentDir.canWrite()) {
                    log.error("Database directory is not writable: {}", parentDir.getAbsolutePath());
                    throw new RuntimeException("Database directory is not writable: " + parentDir.getAbsolutePath());
                }

                // 诊断信息：检查文件状态
                if (dbFile.exists()) {
                    log.info("Database file exists: size={} bytes, canRead={}, canWrite={}", 
                            dbFile.length(), dbFile.canRead(), dbFile.canWrite());
                    
                    // 检查是否有锁文件
                    java.io.File walFile = new java.io.File(dbPath + "-wal");
                    java.io.File shmFile = new java.io.File(dbPath + "-shm");
                    if (walFile.exists() || shmFile.exists()) {
                        log.warn("WAL lock files detected: wal={}, shm={}", 
                                walFile.exists(), shmFile.exists());
                    }
                } else {
                    log.info("Database file does not exist, will create new one");
                }

                String jdbcUrl = "jdbc:sqlite:" + dbPath;
                
                // 添加连接参数，增强兼容性
                jdbcUrl += "?journal_mode=WAL&busy_timeout=10000&foreign_keys=ON";
                
                connection = DriverManager.getConnection(jdbcUrl);
                
                connection.setAutoCommit(true);
                
                // 再次确认设置
                try {
                    connection.createStatement().execute("PRAGMA journal_mode=WAL");
                    connection.createStatement().execute("PRAGMA busy_timeout=10000");
                    connection.createStatement().execute("PRAGMA foreign_keys=ON");
                    log.debug("SQLite PRAGMA settings applied successfully");
                } catch (SQLException e) {
                    log.warn("Failed to apply some PRAGMA settings, continuing anyway: {}", e.getMessage());
                }
                
                log.info("SQLite database opened successfully: {} (WAL mode enabled)", dbPath);
                return;
                
            } catch (SQLException e) {
                String errorMsg = e.getMessage();
                log.error("SQL error on attempt {}: {}", attempt, errorMsg);
                
                if (errorMsg != null && (errorMsg.contains("SQLITE_BUSY") || errorMsg.contains("locked"))) {
                    long waitTime = baseWaitMs * attempt;
                    log.warn("Database is locked. Waiting {}ms before retry... Attempt {}/{}", 
                            waitTime, attempt, maxRetries);
                    
                    closeConnectionSilently();
                    
                    // 尝试清理锁文件
                    if (attempt == maxRetries) {
                        tryCleanupLockFiles();
                    }
                    
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for database lock", ie);
                    }
                } else {
                    log.error("Failed to initialize SQLite database: {}", errorMsg);
                    throw new RuntimeException("Failed to open SQLite database: " + dbPath, e);
                }
            }
        }
        
        log.error("Failed to open database after {} retries. Please check:");
        log.error("1. No other process is using the database");
        log.error("2. Antivirus software is not blocking access");
        log.error("3. File permissions are correct");
        throw new RuntimeException("Failed to open SQLite database after " + maxRetries + " retries. The database file may be locked by another process.");
    }
    
    /**
     * 尝试清理锁文件
     */
    private void tryCleanupLockFiles() {
        java.io.File walFile = new java.io.File(dbPath + "-wal");
        java.io.File shmFile = new java.io.File(dbPath + "-shm");
        
        if (walFile.exists()) {
            if (walFile.delete()) {
                log.info("Cleaned up WAL lock file: {}", walFile.getAbsolutePath());
            } else {
                log.warn("Failed to delete WAL lock file: {}", walFile.getAbsolutePath());
            }
        }
        
        if (shmFile.exists()) {
            if (shmFile.delete()) {
                log.info("Cleaned up SHM lock file: {}", shmFile.getAbsolutePath());
            } else {
                log.warn("Failed to delete SHM lock file: {}", shmFile.getAbsolutePath());
            }
        }
    }
    
    private void closeConnectionSilently() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Ignore
            }
        }
    }

    /**
     * 获取数据库连接（线程安全）。
     */
    public Connection getConnection() {
        if (!initialized) {
            init();
        }
        
        lock.lock();
        try {
            if (connection == null || connection.isClosed()) {
                lock.unlock();
                init();
                lock.lock();
            }
            return connection;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get SQLite connection", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭连接。
     */
    public void close() {
        lock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("SQLite database closed: {}", dbPath);
            }
        } catch (SQLException e) {
            log.error("Failed to close SQLite connection", e);
        } finally {
            lock.unlock();
        }
    }

    public String getDbPath() {
        return dbPath;
    }
}
