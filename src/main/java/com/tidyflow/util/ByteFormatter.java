package com.tidyflow.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure helper for formatting byte sizes using a 1024 base and one decimal place (B, KB, MB, GB, TB).
 */
public final class ByteFormatter {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private ByteFormatter() {
    }

    public static String format(long bytes) {
        return format(bytes, Locale.US);
    }

    public static String format(long bytes, Locale locale) {
        Objects.requireNonNull(locale, "locale must not be null");
        long clamped = Math.max(0L, bytes);
        if (clamped < 1024L) {
            return clamped + " B";
        }

        double value = clamped;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < UNITS.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }

        DecimalFormat df = new DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale));
        return df.format(value) + " " + UNITS[unitIndex];
    }
}
