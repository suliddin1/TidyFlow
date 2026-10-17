package com.tidyflow.core.dedupe;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Abstraction for moving a file to the operating system Recycle Bin.
 */
@FunctionalInterface
public interface TrashService {
    void moveToTrash(Path path) throws IOException;
}
