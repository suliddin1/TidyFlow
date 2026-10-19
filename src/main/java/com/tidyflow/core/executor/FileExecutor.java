package com.tidyflow.core.executor;

import com.tidyflow.core.rules.PlannedOperation;
import com.tidyflow.data.BatchRepository;
import com.tidyflow.data.DatabaseManager;
import com.tidyflow.data.OperationRecord;
import com.tidyflow.data.OperationRepository;
import com.tidyflow.data.OperationStatus;
import com.tidyflow.util.FileNameUtils;
import com.tidyflow.util.PathSafety;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes planned file moves safely, creating target folders as needed,
 * resolving filename collisions without overwriting, refusing protected paths,
 * and recording each batch in SQLite (with a fallback log file if DB persistence fails).
 */
public class FileExecutor {

    private static final Duration SELF_MOVE_RETENTION = Duration.ofSeconds(10);

    private final BatchRepository batchRepository;
    private final OperationRepository operationRepository;
    private final Path fallbackLogPath;
    private final Map<String, Instant> selfMoveExpirations = new ConcurrentHashMap<>();

    public FileExecutor() {
        this(null, null, DatabaseManager.defaultFallbackLogPath());
    }

    public FileExecutor(BatchRepository batchRepository, OperationRepository operationRepository) {
        this(batchRepository, operationRepository, DatabaseManager.defaultFallbackLogPath());
    }

    public FileExecutor(
            BatchRepository batchRepository,
            OperationRepository operationRepository,
            Path fallbackLogPath
    ) {
        this.batchRepository = batchRepository;
        this.operationRepository = operationRepository;
        this.fallbackLogPath = Objects.requireNonNull(fallbackLogPath, "fallbackLogPath must not be null");
    }

    public void recordSelfMove(Path path) {
        if (path != null) {
            selfMoveExpirations.put(normalizeKey(path), Instant.now().plus(SELF_MOVE_RETENTION));
        }
    }

    public boolean isRecentSelfMove(Path path) {
        if (path == null) {
            return false;
        }
        Instant now = Instant.now();
        selfMoveExpirations.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        Instant expiry = selfMoveExpirations.get(normalizeKey(path));
        return expiry != null && !expiry.isBefore(now);
    }

    private static String normalizeKey(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    public List<OperationResult> execute(List<PlannedOperation> operations) {
        return executeBatch(operations, "MANUAL").results();
    }

    public BatchExecutionResult executeBatch(List<PlannedOperation> operations) {
        return executeBatch(operations, "MANUAL");
    }

    public BatchExecutionResult executeBatch(List<PlannedOperation> operations, String kind) {
        Objects.requireNonNull(operations, "operations must not be null");
        Objects.requireNonNull(kind, "kind must not be null");

        Instant startedAt = Instant.now();
        List<OperationResult> results = new ArrayList<>(operations.size());
        for (PlannedOperation operation : operations) {
            results.add(executeSingle(operation));
        }
        Instant finishedAt = Instant.now();

        if (batchRepository == null || operationRepository == null) {
            return new BatchExecutionResult(results, null, true, null, null);
        }

        int movedFilesCount = 0;
        long totalBytesMoved = 0L;
        List<OperationRecord> records = new ArrayList<>(results.size());

        for (OperationResult result : results) {
            if (result.success()) {
                movedFilesCount++;
                totalBytesMoved += result.sizeBytes();
            }
            records.add(OperationRecord.unsaved(
                    result.operation().ruleName(),
                    result.operation().source(),
                    result.actualDestination(),
                    result.sizeBytes(),
                    result.success() ? OperationStatus.SUCCESS : OperationStatus.FAILED,
                    result.success() ? null : result.reason(),
                    result.completedAt()
            ));
        }

        try {
            long batchId = batchRepository.createBatchWithOperations(
                    kind,
                    startedAt,
                    finishedAt,
                    movedFilesCount,
                    totalBytesMoved,
                    records,
                    operationRepository
            );
            return new BatchExecutionResult(results, batchId, true, null, null);
        } catch (Exception dbEx) {
            writeFallbackLog(results, dbEx);
            return new BatchExecutionResult(results, null, false, dbEx.getMessage(), fallbackLogPath);
        }
    }

    private OperationResult executeSingle(PlannedOperation operation) {
        Path source = operation.source();
        Path destination = operation.destination();

        if (PathSafety.isProtectedPath(source) || PathSafety.isProtectedPath(destination)) {
            return OperationResult.failure(operation, "Refused to touch protected system directory");
        }

        if (!Files.exists(source)) {
            return OperationResult.failure(operation, "Source file does not exist: " + source);
        }

        long sizeBytes = 0L;
        try {
            sizeBytes = Files.size(source);
            Path targetDir = destination.getParent();
            if (targetDir != null) {
                Files.createDirectories(targetDir);
            }

            Path finalDestination = resolveNonConflictingPath(destination);
            recordSelfMove(source);
            recordSelfMove(finalDestination);
            Files.move(source, finalDestination);
            recordSelfMove(source);
            recordSelfMove(finalDestination);
            return OperationResult.success(operation, finalDestination, sizeBytes);
        } catch (Exception e) {
            return OperationResult.failure(operation, e.getMessage(), sizeBytes);
        }
    }

    private void writeFallbackLog(List<OperationResult> results, Exception dbException) {
        try {
            Path parent = fallbackLogPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            List<String> lines = new ArrayList<>();
            lines.add("=== Fallback Move Log (" + Instant.now() + ") | DB Error: " + dbException.getMessage() + " ===");
            for (OperationResult r : results) {
                if (r.success()) {
                    lines.add(String.format("%s | MOVED | rule=%s | from=%s | to=%s | bytes=%d",
                            r.completedAt(),
                            r.operation().ruleName(),
                            r.operation().source(),
                            r.actualDestination(),
                            r.sizeBytes()));
                }
            }
            Files.write(
                    fallbackLogPath,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Best-effort fallback logging when DB fails
        }
    }

    static Path resolveNonConflictingPath(Path destination) {
        if (!Files.exists(destination)) {
            return destination;
        }

        Path parent = destination.getParent();
        String fileName = destination.getFileName().toString();
        String baseName = FileNameUtils.getBaseName(fileName);
        String extWithDot = FileNameUtils.getRawExtensionWithDot(fileName);

        int counter = 1;
        while (true) {
            String candidateName = baseName + " (" + counter + ")" + extWithDot;
            Path candidate = parent != null ? parent.resolve(candidateName) : Path.of(candidateName);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            counter++;
        }
    }
}
