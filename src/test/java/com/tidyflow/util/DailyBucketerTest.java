package com.tidyflow.util;

import com.tidyflow.util.DailyBucketer.DateBucket;
import com.tidyflow.util.DailyBucketer.MonthBucket;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyBucketerTest {

    @Test
    void fillDailyGapsFillsMissingDaysWithZeros() {
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 5);
        Map<LocalDate, Long> raw = Map.of(
                LocalDate.of(2026, 4, 1), 3L,
                LocalDate.of(2026, 4, 4), 7L
        );

        List<DateBucket> buckets = DailyBucketer.fillDailyGaps(start, end, raw);
        assertEquals(5, buckets.size());
        assertEquals(new DateBucket(LocalDate.of(2026, 4, 1), 3L), buckets.get(0));
        assertEquals(new DateBucket(LocalDate.of(2026, 4, 2), 0L), buckets.get(1));
        assertEquals(new DateBucket(LocalDate.of(2026, 4, 3), 0L), buckets.get(2));
        assertEquals(new DateBucket(LocalDate.of(2026, 4, 4), 7L), buckets.get(3));
        assertEquals(new DateBucket(LocalDate.of(2026, 4, 5), 0L), buckets.get(4));
    }

    @Test
    void fillMonthlyGapsSpansEarliestToCurrentMonthWithZeros() {
        YearMonth endMonth = YearMonth.of(2026, 4);
        Map<YearMonth, Long> raw = Map.of(
                YearMonth.of(2026, 1), 10L,
                YearMonth.of(2026, 3), 4L
        );

        List<MonthBucket> buckets = DailyBucketer.fillMonthlyGaps(raw, endMonth);
        assertEquals(4, buckets.size());
        assertEquals(new MonthBucket(YearMonth.of(2026, 1), 10L), buckets.get(0));
        assertEquals(new MonthBucket(YearMonth.of(2026, 2), 0L), buckets.get(1));
        assertEquals(new MonthBucket(YearMonth.of(2026, 3), 4L), buckets.get(2));
        assertEquals(new MonthBucket(YearMonth.of(2026, 4), 0L), buckets.get(3));
    }

    @Test
    void fillDailyGapsReturnsEmptyWhenEndBeforeStart() {
        List<DateBucket> buckets = DailyBucketer.fillDailyGaps(
                LocalDate.of(2026, 4, 10),
                LocalDate.of(2026, 4, 5),
                Map.of()
        );
        assertTrue(buckets.isEmpty());
    }
}
