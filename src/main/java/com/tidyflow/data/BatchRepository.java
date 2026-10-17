package com.tidyflow.data;

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
 * JDBC repository for CRUD access to the batches table.
 */
public class BatchRepository {

    private final DatabaseManager dbManager;

    public BatchRepository(DatabaseManager dbManager) {
        this.dbManager = Objects.requireNonNull(dbManager, "dbManager must not be null");
    }

    public long createBatch(String kind, Instant startedAt) throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            return insertBatch(conn, kind, startedAt);
        }
    }

    /**
     * Atomically creates a batch, inserts all of its operations, and updates batch totals in one transaction.
     */
    public long createBatchWithOperations(
            String kind,
            Instant startedAt,
            Instant finishedAt,
            int filesCount,
            long bytesMoved,
            List<OperationRecord> operations,
            OperationRepository operationRepository
    ) throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                long batchId = insertBatch(conn, kind, startedAt);
                for (OperationRecord op : operations) {
                    operationRepository.insertWithConnection(conn, batchId, op);
                }
                updateTotalsWithConnection(conn, batchId, finishedAt, filesCount, bytesMoved);
                conn.commit();
                return batchId;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(originalAutoCommit);
            }
        }
    }

    public void updateTotals(long batchId, Instant finishedAt, int filesCount, long bytesMoved) throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            updateTotalsWithConnection(conn, batchId, finishedAt, filesCount, bytesMoved);
        }
    }

    public Optional<BatchRecord> findById(long id) throws SQLException {
        String sql = """
                SELECT id, kind, started_at, finished_at, files_count, bytes_moved
                FROM batches
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

    public List<BatchRecord> findAllNewestFirst() throws SQLException {
        String sql = """
                SELECT id, kind, started_at, finished_at, files_count, bytes_moved
                FROM batches
                ORDER BY started_at DESC, id DESC
                """;
        List<BatchRecord> batches = new ArrayList<>();
        try (Connection conn = dbManager.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                batches.add(mapRow(rs));
            }
        }
        return List.copyOf(batches);
    }

    private long insertBatch(Connection conn, String kind, Instant startedAt) throws SQLException {
        String sql = """
                INSERT INTO batches (kind, started_at, files_count, bytes_moved)
                VALUES (?, ?, 0, 0)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, kind);
            ps.setString(2, startedAt.toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new SQLException("Inserting batch did not return a generated key");
            }
        }
    }

    private void updateTotalsWithConnection(
            Connection conn,
            long batchId,
            Instant finishedAt,
            int filesCount,
            long bytesMoved
    ) throws SQLException {
        String sql = """
                UPDATE batches
                SET finished_at = ?, files_count = ?, bytes_moved = ?
                WHERE id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, finishedAt != null ? finishedAt.toString() : null);
            ps.setInt(2, filesCount);
            ps.setLong(3, bytesMoved);
            ps.setLong(4, batchId);
            ps.executeUpdate();
        }
    }

    private BatchRecord mapRow(ResultSet rs) throws SQLException {
        String finishedRaw = rs.getString("finished_at");
        return new BatchRecord(
                rs.getLong("id"),
                rs.getString("kind"),
                Instant.parse(rs.getString("started_at")),
                finishedRaw != null ? Instant.parse(finishedRaw) : null,
                rs.getInt("files_count"),
                rs.getLong("bytes_moved")
        );
    }
}
