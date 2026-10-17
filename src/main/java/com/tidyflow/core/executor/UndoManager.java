package com.tidyflow.core.executor;

import com.tidyflow.data.OperationActionType;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.util.PathSafety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Safely reverses recorded file operations for an entire batch or a single file.
 */
public class UndoManager {

    private final OperationRepository operationRepository;

    public UndoManager(OperationRepository operationRepository) {
        this.operationRepository = Objects.requireNonNull(operationRepository, "operationRepository must not be null");
    }

    public UndoSummary undoBatch(long batchId) throws SQLException {
        List<OperationRecord> operations = operationRepository.findByBatchId(batchId);
        return undoOperations(operations);
    }

    public UndoSummary undoOperation(long operationId) throws SQLException {
        Optional<OperationRecord> operationOpt = operationRepository.findById(operationId);
        if (operationOpt.isEmpty()) {
            return new UndoSummary(0, 0, 1, 0, List.of("Operation not found: " + operationId));
        }
        return undoOperations(List.of(operationOpt.get()));
    }

    private UndoSummary undoOperations(List<OperationRecord> operations) throws SQLException {
        int restored = 0;
        int restoredWithNewName = 0;
        int failed = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();

        for (OperationRecord op : operations) {
            if (op.actionType() == OperationActionType.TRASH || op.status() == OperationStatus.TRASHED) {
                skipped++;
                messages.add("Skipped trashed file (restore from the Windows Recycle Bin): " + op.sourcePath().getFileName());
                continue;
            }

            if (op.status() != OperationStatus.SUCCESS) {
                skipped++;
                continue;
            }

            Path currentLocation = op.destPath();
            Path originalLocation = op.sourcePath();

            if (PathSafety.isProtectedPath(currentLocation) || PathSafety.isProtectedPath(originalLocation)) {
                String reason = "Refused to touch protected system directory";
                operationRepository.updateStatus(op.id(), OperationStatus.UNDO_FAILED, reason, Instant.now());
                failed++;
                messages.add(reason);
                continue;
            }

            if (!Files.exists(currentLocation)) {
                String reason = "Destination file no longer exists: " + currentLocation;
                operationRepository.updateStatus(op.id(), OperationStatus.UNDO_FAILED, reason, Instant.now());
                failed++;
                messages.add(reason);
                continue;
            }

            try {
                Path sourceParent = originalLocation.getParent();
                if (sourceParent != null && !Files.exists(sourceParent)) {
                    Files.createDirectories(sourceParent);
                }

                boolean sourceOccupied = Files.exists(originalLocation);
                Path restoreTarget = FileExecutor.resolveNonConflictingPath(originalLocation);
                Files.move(currentLocation, restoreTarget);

                if (sourceOccupied) {
                    String note = "Restored as " + restoreTarget.getFileName();
                    operationRepository.updateStatus(op.id(), OperationStatus.UNDONE, note, Instant.now());
                    restoredWithNewName++;
                    messages.add(originalLocation.getFileName() + " -> " + restoreTarget.getFileName());
                } else {
                    operationRepository.updateStatus(op.id(), OperationStatus.UNDONE, null, Instant.now());
                    restored++;
                }
            } catch (Exception e) {
                String reason = e.getMessage() != null ? e.getMessage() : "Failed to move file back";
                operationRepository.updateStatus(op.id(), OperationStatus.UNDO_FAILED, reason, Instant.now());
                failed++;
                messages.add(reason);
            }
        }

        return new UndoSummary(restored, restoredWithNewName, failed, skipped, messages);
    }
}
