package com.tidyflow.app;

import com.tidyflow.data.DatabaseManager;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Ensures only a single instance of TidyFlow runs at a time using an OS file lock
 * on %APPDATA%\TidyFlow\app.lock.
 */
public class SingleInstanceLock implements AutoCloseable {

    private final Path lockFilePath;
    private FileChannel channel;
    private FileLock lock;

    public SingleInstanceLock() {
        this(defaultLockFilePath());
    }

    public SingleInstanceLock(Path lockFilePath) {
        this.lockFilePath = Objects.requireNonNull(lockFilePath, "lockFilePath must not be null")
                .toAbsolutePath().normalize();
    }

    public static Path defaultLockFilePath() {
        return DatabaseManager.defaultAppDataDirectory().resolve("app.lock");
    }

    public Path lockFilePath() {
        return lockFilePath;
    }

    public synchronized boolean tryAcquire() {
        if (lock != null && lock.isValid()) {
            return true;
        }
        try {
            Path parent = lockFilePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            channel = FileChannel.open(
                    lockFilePath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
            );
            lock = channel.tryLock();
            if (lock != null) {
                return true;
            }
        } catch (OverlappingFileLockException | IOException ignored) {
            // Another instance or lock holder in the same JVM already holds the lock
        }
        closeChannelQuietly();
        return false;
    }

    public synchronized void release() {
        if (lock != null) {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } catch (IOException ignored) {
            } finally {
                lock = null;
            }
        }
        closeChannelQuietly();
    }

    private void closeChannelQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            } finally {
                channel = null;
            }
        }
    }

    @Override
    public void close() {
        release();
    }
}
