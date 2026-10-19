package com.tidyflow.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsRepositoryTest {

    @TempDir
    Path tempDir;

    private DatabaseManager createDb() throws Exception {
        DatabaseManager db = new DatabaseManager(tempDir.resolve("stats-test.db"));
        db.migrate();
        return db;
    }

    @Test
    void emptyDatabaseReturnsZerosAndEmptyLists() throws Exception {
        DatabaseManager db = createDb();
        Instant fixedNow = Instant.parse("2026-05-15T12:00:00Z");
        ZoneId utc = ZoneId.of("UTC");
        StatsRepository statsRepo = new StatsRepository(db, utc, Clock.fixed(fixedNow, utc));

        DashboardStats stats = statsRepo.loadStats(Instant.parse("2026-05-09T00:00:00Z"));

        assertEquals(0L, stats.filesOrganized());
        assertEquals(0L, stats.spaceOrganizedBytes());
        assertEquals(0L, stats.duplicatesRemoved());
        assertEquals(0L, stats.spaceFreedBytes());
        assertEquals(0L, stats.batchesCount());
        assertNull(stats.lastActivity());
        assertTrue(stats.isEmpty());
        assertTrue(stats.filesByRule().isEmpty());
        assertTrue(stats.recentBatches().isEmpty());
        assertEquals(7, stats.filesPerPeriod().size());
        assertTrue(stats.filesPerPeriod().stream().allMatch(b -> b.count() == 0L));
    }

    @Test
    void excludesUndoneOperationsAndSeparatesTrashedFromOrganized() throws Exception {
        DatabaseManager db = createDb();
        BatchRepository batchRepo = new BatchRepository(db);
        OperationRepository opRepo = new OperationRepository(db);
        ZoneId utc = ZoneId.of("UTC");
        StatsRepository statsRepo = new StatsRepository(
                db, utc, Clock.fixed(Instant.parse("2026-05-15T12:00:00Z"), utc)
        );

        Instant t1 = Instant.parse("2026-05-10T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-11T14:00:00Z");

        // Batch 1: 2 MOVE SUCCESS, 1 MOVE UNDONE, 1 MOVE FAILED
        List<OperationRecord> ops1 = List.of(
                OperationRecord.unsaved("Documents", Path.of("C:/a.pdf"), Path.of("C:/Docs/a.pdf"), 1000, OperationStatus.SUCCESS, null, t1),
                OperationRecord.unsaved("Documents", Path.of("C:/b.pdf"), Path.of("C:/Docs/b.pdf"), 2000, OperationStatus.SUCCESS, null, t1),
                new OperationRecord(0, 0, OperationActionType.MOVE, "Images", Path.of("C:/c.png"), Path.of("C:/Img/c.png"), 500, OperationStatus.UNDONE, null, t1, t1.plusSeconds(60)),
                OperationRecord.unsaved("Images", Path.of("C:/d.png"), Path.of("C:/Img/d.png"), 800, OperationStatus.FAILED, "err", t1)
        );
        batchRepo.createBatchWithOperations("MANUAL", t1, t1.plusSeconds(5), 3, 3500L, ops1, opRepo);

        // Batch 2: 2 TRASHED operations
        List<OperationRecord> ops2 = List.of(
                OperationRecord.unsavedTrash(Path.of("C:/dup1.zip"), 4000, OperationStatus.TRASHED, null, t2),
                OperationRecord.unsavedTrash(Path.of("C:/dup2.zip"), 5000, OperationStatus.TRASHED, null, t2)
        );
        batchRepo.createBatchWithOperations("DEDUPE", t2, t2.plusSeconds(10), 2, 9000L, ops2, opRepo);

        // Batch 3: only UNDONE operations (should NOT count towards active batchesCount or lastActivity)
        Instant t3 = Instant.parse("2026-05-12T08:00:00Z");
        batchRepo.createBatchWithOperations(
                "MANUAL", t3, t3.plusSeconds(2), 1, 100L,
                List.of(new OperationRecord(0, 0, OperationActionType.MOVE, "Audio", Path.of("C:/x.mp3"), Path.of("C:/Audio/x.mp3"), 100, OperationStatus.UNDONE, null, t3, t3.plusSeconds(30))),
                opRepo
        );

        DashboardStats stats = statsRepo.loadStats(null);

        assertEquals(2L, stats.filesOrganized());
        assertEquals(3000L, stats.spaceOrganizedBytes());
        assertEquals(2L, stats.duplicatesRemoved());
        assertEquals(9000L, stats.spaceFreedBytes());
        assertEquals(2L, stats.batchesCount()); // Only batch1 and batch2 have SUCCESS or TRASHED
        assertEquals(t2.plusSeconds(10), stats.lastActivity());
    }

    @Test
    void respectsTimeRangeBoundariesFor7Days30DaysAndAllTime() throws Exception {
        DatabaseManager db = createDb();
        BatchRepository batchRepo = new BatchRepository(db);
        OperationRepository opRepo = new OperationRepository(db);
        Instant now = Instant.parse("2026-05-30T12:00:00Z");
        ZoneId utc = ZoneId.of("UTC");
        StatsRepository statsRepo = new StatsRepository(db, utc, Clock.fixed(now, utc));

        Instant twoDaysAgo = Instant.parse("2026-05-28T10:00:00Z");
        Instant fifteenDaysAgo = Instant.parse("2026-05-15T10:00:00Z");
        Instant sixtyDaysAgo = Instant.parse("2026-03-31T10:00:00Z");

        insertSingleMoveBatch(batchRepo, opRepo, "RulesA", 100, sixtyDaysAgo);
        insertSingleMoveBatch(batchRepo, opRepo, "RulesB", 200, fifteenDaysAgo);
        insertSingleMoveBatch(batchRepo, opRepo, "RulesC", 300, twoDaysAgo);

        // Last 7 days: start = 2026-05-24T00:00:00Z
        DashboardStats stats7d = statsRepo.loadStats(Instant.parse("2026-05-24T00:00:00Z"));
        assertEquals(1L, stats7d.filesOrganized());
        assertEquals(300L, stats7d.spaceOrganizedBytes());
        assertEquals(1L, stats7d.batchesCount());

        // Last 30 days: start = 2026-05-01T00:00:00Z
        DashboardStats stats30d = statsRepo.loadStats(Instant.parse("2026-05-01T00:00:00Z"));
        assertEquals(2L, stats30d.filesOrganized());
        assertEquals(500L, stats30d.spaceOrganizedBytes());
        assertEquals(2L, stats30d.batchesCount());

        // All time: start = null
        DashboardStats statsAll = statsRepo.loadStats(null);
        assertEquals(3L, statsAll.filesOrganized());
        assertEquals(600L, statsAll.spaceOrganizedBytes());
        assertEquals(3L, statsAll.batchesCount());
    }

    @Test
    void ordersFilesByRuleDescendingAndAggregatesTop8PlusOther() throws Exception {
        DatabaseManager db = createDb();
        BatchRepository batchRepo = new BatchRepository(db);
        OperationRepository opRepo = new OperationRepository(db);
        ZoneId utc = ZoneId.of("UTC");
        Instant ts = Instant.parse("2026-05-10T10:00:00Z");
        StatsRepository statsRepo = new StatsRepository(db, utc, Clock.fixed(ts, utc));

        List<OperationRecord> ops = new ArrayList<>();
        // Create 10 distinct rules with counts 1..10
        for (int r = 1; r <= 10; r++) {
            String ruleName = String.format("Rule-%02d", r);
            for (int c = 0; c < r; c++) {
                ops.add(OperationRecord.unsaved(
                        ruleName,
                        Path.of("C:/f_" + r + "_" + c),
                        Path.of("C:/dest/f_" + r + "_" + c),
                        100L,
                        OperationStatus.SUCCESS,
                        null,
                        ts
                ));
            }
        }
        batchRepo.createBatchWithOperations("MANUAL", ts, ts, ops.size(), 1000L, ops, opRepo);

        DashboardStats stats = statsRepo.loadStats(null);
        List<RuleCount> byRule = stats.filesByRule();
        assertEquals(10, byRule.size());
        assertEquals(new RuleCount("Rule-10", 10L), byRule.get(0));
        assertEquals(new RuleCount("Rule-01", 1L), byRule.get(9));

        List<RuleCount> top8WithOther = stats.topRulesWithOther();
        assertEquals(9, top8WithOther.size());
        assertEquals(new RuleCount("Rule-10", 10L), top8WithOther.get(0));
        assertEquals(new RuleCount("Rule-03", 3L), top8WithOther.get(7));
        // Rule-02 (2) + Rule-01 (1) = 3 in "Other"
        assertEquals(new RuleCount("Other", 3L), top8WithOther.get(8));
    }

    @Test
    void bucketsPerDayAcrossMidnightInNonUtcZoneAndFillsMissingDaysWithZero() throws Exception {
        DatabaseManager db = createDb();
        BatchRepository batchRepo = new BatchRepository(db);
        OperationRepository opRepo = new OperationRepository(db);
        ZoneId bakuZone = ZoneId.of("Asia/Baku"); // UTC+4
        Instant now = Instant.parse("2026-05-13T08:00:00Z"); // 2026-05-13 12:00 Baku
        StatsRepository statsRepo = new StatsRepository(db, bakuZone, Clock.fixed(now, bakuZone));

        // 2026-05-10T21:30:00Z -> 2026-05-11 01:30 in Asia/Baku
        Instant lateUtcMay10 = Instant.parse("2026-05-10T21:30:00Z");
        // 2026-05-10T18:30:00Z -> 2026-05-10 22:30 in Asia/Baku
        Instant eveningUtcMay10 = Instant.parse("2026-05-10T18:30:00Z");

        insertSingleMoveBatch(batchRepo, opRepo, "Docs", 100, eveningUtcMay10);
        insertSingleMoveBatch(batchRepo, opRepo, "Docs", 100, lateUtcMay10);
        insertSingleMoveBatch(batchRepo, opRepo, "Docs", 100, lateUtcMay10.plusSeconds(60));

        // Range start: 2026-05-10 00:00 Baku (2026-05-09T20:00:00Z)
        Instant start = Instant.parse("2026-05-09T20:00:00Z");

        DashboardStats stats = statsRepo.loadStats(start);
        List<TimeBucketCount> buckets = stats.filesPerPeriod();
        assertEquals(4, buckets.size());
        assertEquals(new TimeBucketCount("2026-05-10", 1L), buckets.get(0));
        assertEquals(new TimeBucketCount("2026-05-11", 2L), buckets.get(1));
        assertEquals(new TimeBucketCount("2026-05-12", 0L), buckets.get(2));
        assertEquals(new TimeBucketCount("2026-05-13", 0L), buckets.get(3));
    }

    @Test
    void recentBatchesRespectsLimitAndNewestOrder() throws Exception {
        DatabaseManager db = createDb();
        BatchRepository batchRepo = new BatchRepository(db);
        OperationRepository opRepo = new OperationRepository(db);
        ZoneId utc = ZoneId.of("UTC");
        Instant base = Instant.parse("2026-05-01T00:00:00Z");
        StatsRepository statsRepo = new StatsRepository(db, utc, Clock.fixed(base.plusSeconds(50000), utc));

        for (int i = 1; i <= 12; i++) {
            insertSingleMoveBatch(batchRepo, opRepo, "Rule", i * 10L, base.plusSeconds(i * 3600L));
        }

        List<BatchRecord> recent = statsRepo.queryRecentBatches(null, 10);
        assertEquals(10, recent.size());
        assertEquals(120L, recent.get(0).bytesMoved());
        assertEquals(30L, recent.get(9).bytesMoved());
    }

    private void insertSingleMoveBatch(
            BatchRepository batchRepo,
            OperationRepository opRepo,
            String ruleName,
            long bytes,
            Instant timestamp
    ) throws Exception {
        OperationRecord op = OperationRecord.unsaved(
                ruleName,
                Path.of("C:/src/file.txt"),
                Path.of("C:/dst/file.txt"),
                bytes,
                OperationStatus.SUCCESS,
                null,
                timestamp
        );
        batchRepo.createBatchWithOperations("MANUAL", timestamp, timestamp, 1, bytes, List.of(op), opRepo);
    }
}
