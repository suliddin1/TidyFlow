CREATE TABLE IF NOT EXISTS batches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    kind TEXT NOT NULL,
    started_at TEXT NOT NULL,
    finished_at TEXT,
    files_count INTEGER NOT NULL DEFAULT 0,
    bytes_moved INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS operations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id INTEGER NOT NULL,
    rule_name TEXT NOT NULL,
    source_path TEXT NOT NULL,
    dest_path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'UNDONE', 'UNDO_FAILED')),
    error TEXT,
    created_at TEXT NOT NULL,
    undone_at TEXT,
    FOREIGN KEY (batch_id) REFERENCES batches(id)
);

CREATE INDEX IF NOT EXISTS idx_operations_batch_id ON operations(batch_id);
CREATE INDEX IF NOT EXISTS idx_batches_started_at ON batches(started_at);
