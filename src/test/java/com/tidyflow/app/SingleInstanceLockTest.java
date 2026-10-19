package com.tidyflow.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceLockTest {

    @Test
    void secondLockAttemptFailsWhileFirstIsHeldAndSucceedsAfterRelease(@TempDir Path tempDir) {
        Path lockFile = tempDir.resolve("app.lock");

        try (SingleInstanceLock firstLock = new SingleInstanceLock(lockFile);
             SingleInstanceLock secondLock = new SingleInstanceLock(lockFile)) {

            assertTrue(firstLock.tryAcquire(), "First instance should acquire lock");
            assertFalse(secondLock.tryAcquire(), "Second instance should fail while first lock is held");

            firstLock.release();

            assertTrue(secondLock.tryAcquire(), "Second instance should acquire lock after first releases it");
        }
    }
}
