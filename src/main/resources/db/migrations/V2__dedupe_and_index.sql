CREATE TABLE IF NOT EXISTS file_index (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    path TEXT NOT NULL UNIQUE,
    size_bytes INTEGER NOT NULL,
    mtime TEXT NOT NULL,
    quick_hash TEXT,
    sha256 TEXT,
    last_seen_at TEXT NOT NULL
);

CREATE TABLE operations_v2 (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    batch_id INTEGER NOT NULL,
    action_type TEXT NOT NULL DEFAULT 'MOVE' CHECK (action_type IN ('MOVE', 'TRASH')),
    rule_name TEXT NOT NULL,
    source_path TEXT NOT NULL,
    dest_path TEXT NOT NULL,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('SUCCESS', 'FAILED', 'UNDONE', 'UNDO_FAILED', 'TRASHED')),
    error TEXT,
    created_at TEXT NOT NULL,
    undone_at TEXT,
    FOREIGN KEY (batch_id) REFERENCES batches(id)
);

INSERT INTO operations_v2 (id, batch_id, action_type, rule_name, source_path, dest_path, size_bytes, status, error, created_at, undone_at)
SELECT id, batch_id, 'MOVE', rule_name, source_path, dest_path, size_bytes, status, error, created_at, undone_at
FROM operations;

DROP TABLE operations;

ALTER TABLE operations_v2 RENAME TO operations;

CREATE INDEX IF NOT EXISTS idx_operations_batch_id ON operations(batch_id);
