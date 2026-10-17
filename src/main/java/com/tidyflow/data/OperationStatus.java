package com.tidyflow.data;

/**
 * Allowed lifecycle states for a recorded file operation in the database.
 */
public enum OperationStatus {
    SUCCESS,
    FAILED,
    UNDONE,
    UNDO_FAILED,
    TRASHED
}
