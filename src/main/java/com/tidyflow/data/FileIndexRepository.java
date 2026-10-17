package com.tidyflow.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC repository for accessing and batch-upserting cached file hashes in the file_index table.
 */
public class FileIndexRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO file_index (path, size_bytes, mtime, quick_hash, sha256, last_seen_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET
                size_bytes = excluded.size_bytes,
                mtime = excluded.mtime,
                quick_hash = excluded.quick_hash,
                sha256 = excluded.sha256,
                last_seen_at = excluded.last_seen_at
            """;

    private final DatabaseManager dbManager;

    public FileIndexRepository(DatabaseManager dbManager) {
        this.dbManager = Objects.requireNonNull(dbManager, "dbManager must not be null");
    }

    public Optional<FileIndexEntry> findByPath(Path path) throws SQLException {
        String normalizedPath = path.toAbsolutePath().normalize().toString();
        String sql = """
                SELECT id, path, size_bytes, mtime, quick_hash, sha256, last_seen_at
                FROM file_index
                WHERE path = ?
                """;
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizedPath);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public Map<Path, FileIndexEntry> findAll() throws SQLException {
        String sql = """
                SELECT id, path, size_bytes, mtime, quick_hash, sha256, last_seen_at
                FROM file_index
                """;
        Map<Path, FileIndexEntry> result = new HashMap<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                FileIndexEntry entry = mapRow(rs);
                result.put(entry.path(), entry);
            }
        }
        return Map.copyOf(result);
    }

    public void upsertBatch(Collection<FileIndexEntry> entries) throws SQLException {
        Objects.requireNonNull(entries, "entries must not be null");
        if (entries.isEmpty()) {
            return;
        }

        try (Connection conn = dbManager.openConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {
                for (FileIndexEntry entry : entries) {
                    ps.setString(1, entry.path().toAbsolutePath().normalize().toString());
                    ps.setLong(2, entry.sizeBytes());
                    ps.setString(3, entry.mtime().toString());
                    ps.setString(4, entry.quickHash());
                    ps.setString(5, entry.sha256());
                    ps.setString(6, entry.lastSeenAt().toString());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public List<FileIndexEntry> findAllEntries() throws SQLException {
        return List.copyOf(findAll().values());
    }

    private FileIndexEntry mapRow(ResultSet rs) throws SQLException {
        return new FileIndexEntry(
                rs.getLong("id"),
                Path.of(rs.getString("path")).toAbsolutePath().normalize(),
                rs.getLong("size_bytes"),
                Instant.parse(rs.getString("mtime")),
                rs.getString("quick_hash"),
                rs.getString("sha256"),
                Instant.parse(rs.getString("last_seen_at"))
        );
    }
}
