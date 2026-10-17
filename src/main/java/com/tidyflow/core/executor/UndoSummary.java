package com.tidyflow.core.executor;

import java.util.List;
import java.util.Objects;

/**
 * Summary of an undo execution for a single operation or an entire batch.
 */
public record UndoSummary(
        int restored,
        int restoredWithNewName,
        int failed,
        int skipped,
        List<String> messages
) {
    public UndoSummary {
        Objects.requireNonNull(messages, "messages must not be null");
        messages = List.copyOf(messages);
    }

    public int totalRestored() {
        return restored + restoredWithNewName;
    }

    public String formatSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                "%d restored, %d restored with new name, %d failed",
                restored, restoredWithNewName, failed
        ));
        if (skipped > 0) {
            sb.append(String.format(" (%d skipped)", skipped));
        }
        return sb.toString();
    }
}
