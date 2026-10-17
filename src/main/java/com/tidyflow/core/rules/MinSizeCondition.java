package com.tidyflow.core.rules;

import com.tidyflow.core.scanner.FileItem;

/**
 * Matches files with a size strictly greater than the specified threshold in bytes.
 */
public final class MinSizeCondition implements Condition {

    private final long minBytes;

    public MinSizeCondition(long minBytes) {
        this.minBytes = minBytes;
    }

    public long minBytes() {
        return minBytes;
    }

    @Override
    public boolean matches(FileItem file) {
        if (file == null || minBytes < 0) {
            return false;
        }
        return file.size() > minBytes;
    }
}
