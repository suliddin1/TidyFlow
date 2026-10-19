CREATE INDEX IF NOT EXISTS idx_operations_status_created_at ON operations(status, created_at);
CREATE INDEX IF NOT EXISTS idx_operations_action_status_created_at ON operations(action_type, status, created_at);
CREATE INDEX IF NOT EXISTS idx_batches_finished_at ON batches(finished_at);
