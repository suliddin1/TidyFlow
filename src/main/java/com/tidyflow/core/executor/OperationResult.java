package com.tidyflow.core.executor;

import com.tidyflow.core.rules.PlannedOperation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Outcome of executing a single PlannedOperation.
 */
public record OperationResult(
        PlannedOperation operation,
        Path actualDestination,
        boolean success,
        String reason,
        long sizeBytes,
        Instant completedAt
) {
    public OperationResult {
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(actualDestination, "actualDestination must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
    }

    public OperationResult(
            PlannedOperation operation,
            Path actualDestination,
            boolean success,
            String reason
    ) {
        this(operation, actualDestination, success, reason, 0L, Instant.now());
    }

    public static OperationResult success(PlannedOperation operation, Path actualDestination) {
        return success(operation, actualDestination, 0L);
    }

    public static OperationResult success(PlannedOperation operation, Path actualDestination, long sizeBytes) {
        return new OperationResult(operation, actualDestination, true, "Moved successfully", sizeBytes, Instant.now());
    }

    public static OperationResult failure(PlannedOperation operation, String reason) {
        return failure(operation, reason, 0L);
    }

    public static OperationResult failure(PlannedOperation operation, String reason, long sizeBytes) {
        return new OperationResult(
                operation,
                operation.destination(),
                false,
                reason != null ? reason : "Unknown error",
                sizeBytes,
                Instant.now()
        );
    }
}
