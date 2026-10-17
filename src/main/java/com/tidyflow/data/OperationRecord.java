package com.tidyflow.data;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a row in the operations table.
 */
public record OperationRecord(
        long id,
        long batchId,
        OperationActionType actionType,
        String ruleName,
        Path sourcePath,
        Path destPath,
        long sizeBytes,
        OperationStatus status,
        String error,
        Instant createdAt,
        Instant undoneAt
) {
    public OperationRecord {
        Objects.requireNonNull(actionType, "actionType must not be null");
        Objects.requireNonNull(ruleName, "ruleName must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        Objects.requireNonNull(destPath, "destPath must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public OperationRecord(
            long id,
            long batchId,
            String ruleName,
            Path sourcePath,
            Path destPath,
            long sizeBytes,
            OperationStatus status,
            String error,
            Instant createdAt,
            Instant undoneAt
    ) {
        this(id, batchId, OperationActionType.MOVE, ruleName, sourcePath, destPath, sizeBytes, status, error, createdAt, undoneAt);
    }

    public static OperationRecord unsaved(
            String ruleName,
            Path sourcePath,
            Path destPath,
            long sizeBytes,
            OperationStatus status,
            String error,
            Instant createdAt
    ) {
        return new OperationRecord(
                0L,
                0L,
                OperationActionType.MOVE,
                ruleName,
                sourcePath,
                destPath,
                sizeBytes,
                status,
                error,
                createdAt,
                null
        );
    }

    public static OperationRecord unsavedTrash(
            Path sourcePath,
            long sizeBytes,
            OperationStatus status,
            String error,
            Instant createdAt
    ) {
        return new OperationRecord(
                0L,
                0L,
                OperationActionType.TRASH,
                "Duplicate Removal",
                sourcePath,
                Path.of("Recycle Bin"),
                sizeBytes,
                status,
                error,
                createdAt,
                null
        );
    }
}
