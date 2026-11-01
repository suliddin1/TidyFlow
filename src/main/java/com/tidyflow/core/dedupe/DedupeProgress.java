package com.tidyflow.core.dedupe;

import com.tidyflow.util.ByteFormatter;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Rich progress snapshot emitted during a duplicate scan.
 */
public record DedupeProgress(
        String stageName,
        int processedCount,
        int totalCount,
        long processedBytes,
        long totalBytes,
        String currentFolder,
        long elapsedMillis,
        double throughputMbPerSec,
        long etaSeconds,
        int skippedUnreadableCount,
        int skippedExcludedCount,
        int walkScannedFiles,
        long walkScannedBytes,
        int candidateFiles,
        long candidateBytes
) {
    public DedupeProgress {
        Objects.requireNonNull(stageName, "stageName must not be null");
        if (currentFolder == null) {
            currentFolder = "";
        }
    }

    public DedupeProgress(String stageName, int processedCount, int totalCount) {
        this(
                stageName,
                processedCount,
                totalCount,
                0L,
                0L,
                "",
                0L,
                0.0,
                -1L,
                0,
                0,
                processedCount,
                0L,
                0,
                0L
        );
    }

    public boolean isIndeterminate() {
        return totalCount <= 0 || "Scanning files".equals(stageName);
    }

    public double progressFraction() {
        if (isIndeterminate()) {
            return -1.0;
        }
        if ("Full SHA-256 hashing".equals(stageName) && totalBytes > 0L) {
            return Math.min(1.0, Math.max(0.0, (double) processedBytes / totalBytes));
        }
        return Math.min(1.0, Math.max(0.0, (double) processedCount / totalCount));
    }

    public String formatWalkSummary() {
        if (walkScannedFiles <= 0 && candidateFiles <= 0) {
            return "";
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        return String.format(
                "Found %s files (%s). Hashing candidates: %s files (%s)",
                nf.format(walkScannedFiles),
                ByteFormatter.format(walkScannedBytes),
                nf.format(candidateFiles),
                ByteFormatter.format(candidateBytes)
        );
    }

    public String formatStatusLine() {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        String elapsed = SmoothedEtaCalculator.formatDurationSeconds(elapsedMillis / 1000L);
        StringBuilder sb = new StringBuilder();

        if ("Scanning files".equals(stageName)) {
            sb.append(nf.format(processedCount)).append(" files scanned");
            if (walkScannedBytes > 0L) {
                sb.append(" (").append(ByteFormatter.format(walkScannedBytes)).append(")");
            }
        } else if ("Quick hashing".equals(stageName)) {
            sb.append("Quick hashing: ")
                    .append(nf.format(processedCount))
                    .append(" / ")
                    .append(nf.format(totalCount))
                    .append(" files");
        } else if ("Full SHA-256 hashing".equals(stageName)) {
            sb.append("Full hashing: ")
                    .append(ByteFormatter.format(processedBytes))
                    .append(" / ")
                    .append(ByteFormatter.format(totalBytes))
                    .append(" (")
                    .append(nf.format(processedCount))
                    .append("/")
                    .append(nf.format(totalCount))
                    .append(")");
            if (throughputMbPerSec > 0.05) {
                sb.append(String.format(Locale.US, " | %.0f MB/s", throughputMbPerSec));
            }
            if (etaSeconds >= 0L) {
                sb.append(" | ETA ").append(SmoothedEtaCalculator.formatDurationSeconds(etaSeconds));
            }
        } else {
            sb.append(stageName);
            if (totalCount > 0) {
                sb.append(" (").append(nf.format(processedCount)).append(" / ").append(nf.format(totalCount)).append(")");
            }
        }

        sb.append(" | Elapsed ").append(elapsed);
        if (skippedExcludedCount > 0 || skippedUnreadableCount > 0) {
            sb.append(String.format(" | Skipped: %d excluded, %d unreadable",
                    skippedExcludedCount, skippedUnreadableCount));
        }
        return sb.toString();
    }
}
