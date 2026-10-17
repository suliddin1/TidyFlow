package com.tidyflow.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC repository for CRUD access to the operations table.
 */
public class OperationRepository {

    private static final String INSERT_SQL = """
            INSERT INTO operations (batch_id, action_type, rule_name, source_path, dest_path, size_bytes, status, error, created_at, undone_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final DatabaseManager dbManager;

    public OperationRepository(DatabaseManager dbManager) {
        this.dbManager = Objects.requireNonNull(dbManager, "dbManager must not be null");
    }

    public long create(long batchId, OperationRecord operation) throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            return insertWithConnection(conn, batchId, operation);
        }
    }

    long insertWithConnection(Connection conn, long batchId, OperationRecord op) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, batchId);
            ps.setString(2, op.actionType().name());
            ps.setString(3, op.ruleName());
            ps.setString(4, op.sourcePath().toString());
            ps.setString(5, op.destPath().toString());
            ps.setLong(6, op.sizeBytes());
            ps.setString(7, op.status().name());
            ps.setString(8, op.error());
            ps.setString(9, op.createdAt().toString());
            ps.setString(10, op.undoneAt() != null ? op.undoneAt().toString() : null);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Inserting operation did not return a generated key");
            }
        }
    }

    public Optional<OperationRecord> findById(long id) throws SQLException {
        String sql = """
                SELECT id, batch_id, action_type, rule_name, source_path, dest_path, size_bytes, status, error, created_at, undone_at
                FROM operations
                WHERE id = ?
                """;
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        }
    }

    public List<OperationRecord> findByBatchId(long batchId) throws SQLException {
        String sql = """
                SELECT id, batch_id, action_type, rule_name, source_path, dest_path, size_bytes, status, error, created_at, undone_at
                FROM operations
                WHERE batch_id = ?
                ORDER BY id ASC
                """;
        List<OperationRecord> results = new ArrayList<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, batchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRow(rs));
                }
            }
        }
        return List.copyOf(results);
    }

    public void updateStatus(long id, OperationStatus status, String error, Instant undoneAt) throws SQLException {
        String sql = """
                UPDATE operations
                SET status = ?, error = ?, undone_at = ?
                WHERE id = ?
                """;
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, error);
            ps.setString(3, undoneAt != null ? undoneAt.toString() : null);
            ps.setLong(4, id);
            ps.executeUpdate();
        }
    }

    private OperationRecord mapRow(ResultSet rs) throws SQLException {
        String undoneAtRaw = rs.getString("undone_at");
        String actionTypeRaw = rs.getString("action_type");
        OperationActionType actionType = actionTypeRaw != null
                ? OperationActionType.valueOf(actionTypeRaw)
                : OperationActionType.MOVE;
        return new OperationRecord(
                rs.getLong("id"),
                rs.getLong("batch_id"),
                actionType,
                rs.getString("rule_name"),
                Path.of(rs.getString("source_path")),
                Path.of(rs.getString("dest_path")),
                rs.getLong("size_bytes"),
                OperationStatus.valueOf(rs.getString("status")),
                rs.getString("error"),
                Instant.parse(rs.getString("created_at")),
                undoneAtRaw != null ? Instant.parse(undoneAtRaw) : null
        );
    }
}
