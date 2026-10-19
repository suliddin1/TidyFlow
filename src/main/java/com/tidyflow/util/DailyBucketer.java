package com.tidyflow.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure helper that fills missing days or months with zero counts so dashboard bar charts have no gaps.
 */
public final class DailyBucketer {

    public record DateBucket(LocalDate date, long count) {
        public DateBucket {
            Objects.requireNonNull(date, "date must not be null");
        }
    }

    public record MonthBucket(YearMonth month, long count) {
        public MonthBucket {
            Objects.requireNonNull(month, "month must not be null");
        }
    }

    private DailyBucketer() {
    }

    /**
     * Produces a contiguous list of daily buckets from {@code startDate} to {@code endDate} (inclusive),
     * filling any missing dates with a count of 0.
     */
    public static List<DateBucket> fillDailyGaps(
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, Long> countsByDate
    ) {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        Objects.requireNonNull(countsByDate, "countsByDate must not be null");

        if (endDate.isBefore(startDate)) {
            return List.of();
        }

        List<DateBucket> result = new ArrayList<>();
        LocalDate cursor = startDate;
        while (!cursor.isAfter(endDate)) {
            long count = Math.max(0L, countsByDate.getOrDefault(cursor, 0L));
            result.add(new DateBucket(cursor, count));
            cursor = cursor.plusDays(1);
        }
        return List.copyOf(result);
    }

    /**
     * Produces a contiguous list of monthly buckets covering the min-to-max YearMonth in {@code countsByMonth}
     * (or ending at {@code currentMonth}), filling any missing months with a count of 0.
     */
    public static List<MonthBucket> fillMonthlyGaps(
            Map<YearMonth, Long> countsByMonth,
            YearMonth currentMonth
    ) {
        Objects.requireNonNull(countsByMonth, "countsByMonth must not be null");
        Objects.requireNonNull(currentMonth, "currentMonth must not be null");

        if (countsByMonth.isEmpty()) {
            return List.of();
        }

        YearMonth minMonth = Collections.min(countsByMonth.keySet());
        YearMonth maxMonth = Collections.max(countsByMonth.keySet());
        if (currentMonth.isAfter(maxMonth)) {
            maxMonth = currentMonth;
        }

        List<MonthBucket> result = new ArrayList<>();
        YearMonth cursor = minMonth;
        while (!cursor.isAfter(maxMonth)) {
            long count = Math.max(0L, countsByMonth.getOrDefault(cursor, 0L));
            result.add(new MonthBucket(cursor, count));
            cursor = cursor.plusMonths(1);
        }
        return List.copyOf(result);
    }
}
