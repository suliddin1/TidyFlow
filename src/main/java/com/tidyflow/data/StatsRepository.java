package com.tidyflow.data;

import com.tidyflow.util.DailyBucketer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes dashboard statistics from batches and operations using aggregated SQL queries.
 */
public class StatsRepository {

    private final DatabaseManager dbManager;
    private final ZoneId zoneId;
    private final Clock clock;

    public StatsRepository(DatabaseManager dbManager) {
        this(dbManager, ZoneId.systemDefault(), Clock.systemDefaultZone());
    }

    public StatsRepository(DatabaseManager dbManager, ZoneId zoneId) {
        this(dbManager, zoneId, Clock.system(zoneId));
    }

    public StatsRepository(DatabaseManager dbManager, ZoneId zoneId, Clock clock) {
        this.dbManager = Objects.requireNonNull(dbManager, "dbManager must not be null");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public DashboardStats loadStats(Instant startInclusive) throws SQLException {
        String startIso = startInclusive != null ? startInclusive.toString() : null;

        try (Connection conn = dbManager.openConnection()) {
            SummaryRow summary = querySummary(conn, startIso);
            Instant lastActivity = queryLastActivity(conn, startIso);
            List<RuleCount> filesByRule = queryFilesByRule(conn, startIso);
            List<RuleCount> topRules = RuleCount.topEightPlusOther(filesByRule);
            boolean monthly = (startInclusive == null);
            List<TimeBucketCount> filesPerPeriod = queryFilesPerPeriod(conn, startInclusive, startIso);
            List<BatchRecord> recentBatches = queryRecentBatches(conn, startIso, 10);

            return new DashboardStats(
                    summary.filesOrganized(),
                    summary.spaceOrganizedBytes(),
                    summary.duplicatesRemoved(),
                    summary.spaceFreedBytes(),
                    summary.batchesCount(),
                    lastActivity,
                    filesByRule,
                    topRules,
                    filesPerPeriod,
                    monthly,
                    recentBatches
            );
        }
    }

    private record SummaryRow(
            long filesOrganized,
            long spaceOrganizedBytes,
            long duplicatesRemoved,
            long spaceFreedBytes,
            long batchesCount
    ) {
    }

    private SummaryRow querySummary(Connection conn, String startIso) throws SQLException {
        String sql = """
                SELECT
                    COALESCE(SUM(CASE WHEN action_type = 'MOVE' AND status = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS files_organized,
                    COALESCE(SUM(CASE WHEN action_type = 'MOVE' AND status = 'SUCCESS' THEN size_bytes ELSE 0 END), 0) AS space_organized,
                    COALESCE(SUM(CASE WHEN status = 'TRASHED' THEN 1 ELSE 0 END), 0) AS duplicates_removed,
                    COALESCE(SUM(CASE WHEN status = 'TRASHED' THEN size_bytes ELSE 0 END), 0) AS space_freed,
                    COUNT(DISTINCT CASE WHEN status IN ('SUCCESS', 'TRASHED') THEN batch_id END) AS batches_count
                FROM operations
                WHERE (? IS NULL OR julianday(created_at) >= julianday(?));
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, startIso);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new SummaryRow(
                            rs.getLong("files_organized"),
                            rs.getLong("space_organized"),
                            rs.getLong("duplicates_removed"),
                            rs.getLong("space_freed"),
                            rs.getLong("batches_count")
                    );
                }
            }
        }
        return new SummaryRow(0L, 0L, 0L, 0L, 0L);
    }

    private Instant queryLastActivity(Connection conn, String startIso) throws SQLException {
        String sql = """
                SELECT b.finished_at
                FROM batches b
                WHERE b.finished_at IS NOT NULL
                  AND (? IS NULL OR julianday(b.finished_at) >= julianday(?))
                  AND EXISTS (
                      SELECT 1 FROM operations o
                      WHERE o.batch_id = b.id
                        AND o.status IN ('SUCCESS', 'TRASHED')
                  )
                ORDER BY julianday(b.finished_at) DESC, b.id DESC
                LIMIT 1;
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, startIso);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String raw = rs.getString("finished_at");
                    if (raw != null && !raw.isBlank()) {
                        return Instant.parse(raw);
                    }
                }
            }
        }
        return null;
    }

    public List<RuleCount> queryFilesByRule(Instant startInclusive) throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            return queryFilesByRule(conn, startInclusive != null ? startInclusive.toString() : null);
        }
    }

    private List<RuleCount> queryFilesByRule(Connection conn, String startIso) throws SQLException {
        String sql = """
                SELECT rule_name, COUNT(*) AS cnt
                FROM operations
                WHERE action_type = 'MOVE'
                  AND status = 'SUCCESS'
                  AND (? IS NULL OR julianday(created_at) >= julianday(?))
                GROUP BY rule_name
                ORDER BY cnt DESC, rule_name ASC;
                """;
        List<RuleCount> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, startIso);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new RuleCount(rs.getString("rule_name"), rs.getLong("cnt")));
                }
            }
        }
        return List.copyOf(results);
    }

    /**
     * Aggregates organized files by UTC minute in SQL and converts each bucket into the injectable ZoneId
     * so local calendar dates near midnight are exact. Fills zero-count gaps via DailyBucketer.
     */
    private List<TimeBucketCount> queryFilesPerPeriod(
            Connection conn,
            Instant startInclusive,
            String startIso
    ) throws SQLException {
        String sql = """
                SELECT strftime('%Y-%m-%dT%H:%M:%SZ', created_at) AS utc_bucket, COUNT(*) AS cnt
                FROM operations
                WHERE action_type = 'MOVE'
                  AND status = 'SUCCESS'
                  AND (? IS NULL OR julianday(created_at) >= julianday(?))
                GROUP BY utc_bucket
                ORDER BY utc_bucket ASC;
                """;

        if (startInclusive == null) {
            Map<YearMonth, Long> byMonth = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, null);
                ps.setString(2, null);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String utcBucket = rs.getString("utc_bucket");
                        long cnt = rs.getLong("cnt");
                        if (utcBucket != null) {
                            YearMonth ym = YearMonth.from(Instant.parse(utcBucket).atZone(zoneId));
                            byMonth.merge(ym, cnt, Long::sum);
                        }
                    }
                }
            }
            if (byMonth.isEmpty()) {
                return List.of();
            }
            YearMonth currentMonth = YearMonth.from(clock.instant().atZone(zoneId));
            return DailyBucketer.fillMonthlyGaps(byMonth, currentMonth).stream()
                    .map(mb -> new TimeBucketCount(mb.month().toString(), mb.count()))
                    .toList();
        }

        Map<LocalDate, Long> byDate = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, startIso);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String utcBucket = rs.getString("utc_bucket");
                    long cnt = rs.getLong("cnt");
                    if (utcBucket != null) {
                        LocalDate localDate = Instant.parse(utcBucket).atZone(zoneId).toLocalDate();
                        byDate.merge(localDate, cnt, Long::sum);
                    }
                }
            }
        }

        LocalDate startDate = startInclusive.atZone(zoneId).toLocalDate();
        LocalDate endDate = clock.instant().atZone(zoneId).toLocalDate();
        for (LocalDate d : byDate.keySet()) {
            if (d.isAfter(endDate)) {
                endDate = d;
            }
            if (d.isBefore(startDate)) {
                startDate = d;
            }
        }

        return DailyBucketer.fillDailyGaps(startDate, endDate, byDate).stream()
                .map(db -> new TimeBucketCount(db.date().toString(), db.count()))
                .toList();
    }

    public List<BatchRecord> queryRecentBatches(Instant startInclusive, int limit) throws SQLException {
        try (Connection conn = dbManager.openConnection()) {
            return queryRecentBatches(conn, startInclusive != null ? startInclusive.toString() : null, limit);
        }
    }

    private List<BatchRecord> queryRecentBatches(Connection conn, String startIso, int limit) throws SQLException {
        String sql = """
                SELECT id, kind, started_at, finished_at, files_count, bytes_moved
                FROM batches b
                WHERE (? IS NULL OR julianday(COALESCE(b.finished_at, b.started_at)) >= julianday(?))
                  AND EXISTS (
                      SELECT 1 FROM operations o
                      WHERE o.batch_id = b.id
                        AND o.status IN ('SUCCESS', 'TRASHED')
                  )
                ORDER BY julianday(COALESCE(b.finished_at, b.started_at)) DESC, b.id DESC
                LIMIT ?;
                """;
        List<BatchRecord> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, startIso);
            ps.setString(2, startIso);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String finishedRaw = rs.getString("finished_at");
                    results.add(new BatchRecord(
                            rs.getLong("id"),
                            rs.getString("kind"),
                            Instant.parse(rs.getString("started_at")),
                            finishedRaw != null ? Instant.parse(finishedRaw) : null,
                            rs.getInt("files_count"),
                            rs.getLong("bytes_moved")
                    ));
                }
            }
        }
        return List.copyOf(results);
    }
}
