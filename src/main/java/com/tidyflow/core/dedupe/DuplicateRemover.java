package com.tidyflow.core.dedupe;

import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.util.PathSafety;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Safely moves user-selected duplicate files to the Windows Recycle Bin after
 * re-verifying file metadata and enforcing that at least one intact keeper remains per group.
 */
public class DuplicateRemover {

    private final TrashService trashService;
    private final BatchRepository batchRepository;
    private final OperationRepository operationRepository;

    public DuplicateRemover(
            TrashService trashService,
            BatchRepository batchRepository,
            OperationRepository operationRepository
    ) {
        this.trashService = Objects.requireNonNull(trashService, "trashService must not be null");
        this.batchRepository = batchRepository;
        this.operationRepository = operationRepository;
    }

    public DedupeRemovalSummary removeSelected(List<DuplicateGroupSelection> selections) throws SQLException {
        Objects.requireNonNull(selections, "selections must not be null");

        Instant startedAt = Instant.now();
        int removed = 0;
        int skipped = 0;
        int failed = 0;
        long bytesFreed = 0L;
        List<String> details = new ArrayList<>();
        List<OperationRecord> operationsToRecord = new ArrayList<>();

        for (DuplicateGroupSelection selection : selections) {
            DuplicateGroup group = selection.group();
            Set<Path> selectedPaths = selection.selectedPaths();
            if (selectedPaths.isEmpty()) {
                continue;
            }

            List<FileEntry> selectedEntries = new ArrayList<>();
            List<FileEntry> keeperEntries = new ArrayList<>();

            for (FileEntry entry : group.files()) {
                Path normalized = entry.path().toAbsolutePath().normalize();
                if (selectedPaths.contains(normalized)) {
                    selectedEntries.add(entry);
                } else {
                    keeperEntries.add(entry);
                }
            }

            if (selectedEntries.isEmpty()) {
                continue;
            }

            // Hard safety rule: never allow removing all files of a duplicate group
            if (keeperEntries.isEmpty()) {
                skipped += selectedEntries.size();
                details.add("Refused to remove all files in group (at least one copy must be kept)");
                continue;
            }

            for (FileEntry target : selectedEntries) {
                Path targetPath = target.path().toAbsolutePath().normalize();

                if (PathSafety.isProtectedPath(targetPath)) {
                    skipped++;
                    details.add("Skipped protected path: " + targetPath);
                    continue;
                }

                if (!hasIntactKeeperOnDisk(keeperEntries, group.sizeBytes())) {
                    skipped++;
                    details.add("Skipped " + target.fileName() + ": no intact keeper file remains on disk");
                    continue;
                }

                String verificationError = verifyUnchangedSinceScan(target);
                if (verificationError != null) {
                    skipped++;
                    details.add("Skipped " + target.fileName() + ": " + verificationError);
                    continue;
                }

                try {
                    trashService.moveToTrash(targetPath);
                    removed++;
                    bytesFreed += target.sizeBytes();
                    operationsToRecord.add(OperationRecord.unsavedTrash(
                            targetPath,
                            target.sizeBytes(),
                            OperationStatus.TRASHED,
                            null,
                            Instant.now()
                    ));
                } catch (Exception e) {
                    failed++;
                    String reason = e.getMessage() != null ? e.getMessage() : "Failed to move to Recycle Bin";
                    details.add("Failed " + target.fileName() + ": " + reason);
                    operationsToRecord.add(OperationRecord.unsavedTrash(
                            targetPath,
                            target.sizeBytes(),
                            OperationStatus.FAILED,
                            reason,
                            Instant.now()
                    ));
                }
            }
        }

        Instant finishedAt = Instant.now();
        Long batchId = null;
        if (!operationsToRecord.isEmpty() && batchRepository != null && operationRepository != null) {
            batchId = batchRepository.createBatchWithOperations(
                    "DEDUPE",
                    startedAt,
                    finishedAt,
                    removed,
                    bytesFreed,
                    operationsToRecord,
                    operationRepository
            );
        }

        return new DedupeRemovalSummary(removed, skipped, failed, bytesFreed, batchId, details);
    }

    private boolean hasIntactKeeperOnDisk(List<FileEntry> keepers, long expectedSize) {
        for (FileEntry keeper : keepers) {
            Path path = keeper.path();
            try {
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == expectedSize) {
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private String verifyUnchangedSinceScan(FileEntry entry) {
        Path path = entry.path();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return "file no longer exists";
        }
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.size() != entry.sizeBytes()) {
                return "file size changed after scan";
            }
            if (!attrs.lastModifiedTime().toInstant().equals(entry.lastModified())) {
                return "file modified time changed after scan";
            }
            return null;
        } catch (IOException e) {
            return "cannot read file attributes: " + e.getMessage();
        }
    }
}
