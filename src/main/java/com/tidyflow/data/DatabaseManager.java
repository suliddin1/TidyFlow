package com.tidyflow.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Manages SQLite JDBC connections and executes versioned SQL migrations.
 */
public class DatabaseManager {

    private static final List<MigrationScript> MIGRATIONS = List.of(
            new MigrationScript(1, "V1__init.sql", "/db/migrations/V1__init.sql"),
            new MigrationScript(2, "V2__dedupe_and_index.sql", "/db/migrations/V2__dedupe_and_index.sql"),
            new MigrationScript(3, "V3__stats_indexes.sql", "/db/migrations/V3__stats_indexes.sql")
    );

    private final Path dbPath;

    public DatabaseManager() {
        this(defaultDatabasePath());
    }

    public DatabaseManager(Path dbPath) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null").toAbsolutePath().normalize();
    }

    public static Path defaultAppDataDirectory() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "TidyFlow");
        }
        return Path.of(System.getProperty("user.home", "."), "AppData", "Roaming", "TidyFlow");
    }

    public static Path defaultDatabasePath() {
        return defaultAppDataDirectory().resolve("tidyflow.db");
    }

    public static Path defaultFallbackLogPath() {
        return defaultAppDataDirectory().resolve("fallback-moves.log");
    }

    public Path dbPath() {
        return dbPath;
    }

    public Connection openConnection() throws SQLException {
        ensureParentDirectory();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (Statement pragma = conn.createStatement()) {
            pragma.execute("PRAGMA foreign_keys = ON;");
        }
        return conn;
    }

    /**
     * Runs all unapplied database migrations in order. Safe to invoke multiple times.
     */
    public void migrate() throws SQLException {
        migrateUpTo(Integer.MAX_VALUE);
    }

    /**
     * Runs unapplied migrations up to {@code maxVersion} (inclusive).
     */
    public void migrateUpTo(int maxVersion) throws SQLException {
        try (Connection conn = openConnection()) {
            ensureSchemaVersionTable(conn);
            for (MigrationScript migration : MIGRATIONS) {
                if (migration.version() <= maxVersion && !isVersionApplied(conn, migration.version())) {
                    applyMigration(conn, migration);
                }
            }
        }
    }

    private void ensureParentDirectory() throws SQLException {
        Path parent = dbPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("Failed to create database directory: " + parent, e);
            }
        }
    }

    private void ensureSchemaVersionTable(Connection conn) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY,
                    script_name TEXT NOT NULL,
                    applied_at TEXT NOT NULL
                );
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    private boolean isVersionApplied(Connection conn, int version) throws SQLException {
        String sql = "SELECT 1 FROM schema_version WHERE version = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void applyMigration(Connection conn, MigrationScript migration) throws SQLException {
        String sqlContent = loadResource(migration.resourcePath());
        boolean originalAutoCommit = conn.getAutoCommit();
        try (Statement pragmaOff = conn.createStatement()) {
            pragmaOff.execute("PRAGMA foreign_keys = OFF;");
        }
        conn.setAutoCommit(false);
        try {
            for (String statementSql : sqlContent.split(";")) {
                String trimmed = statementSql.trim();
                if (!trimmed.isEmpty()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(trimmed);
                    }
                }
            }

            String insertVersion = "INSERT INTO schema_version (version, script_name, applied_at) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertVersion)) {
                ps.setInt(1, migration.version());
                ps.setString(2, migration.scriptName());
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(originalAutoCommit);
            try (Statement pragmaOn = conn.createStatement()) {
                pragmaOn.execute("PRAGMA foreign_keys = ON;");
            }
        }
    }

    private String loadResource(String resourcePath) throws SQLException {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new SQLException("Migration script not found: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("Failed to read migration script: " + resourcePath, e);
        }
    }

    private record MigrationScript(int version, String scriptName, String resourcePath) {
    }
}
