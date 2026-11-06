-- Git-sync state for the self-healing read-gate (local-oriented).
-- repo_state: last-synced commit per repo (the cursor the read-gate diffs against).
CREATE TABLE IF NOT EXISTS repo_state (
    repo           TEXT PRIMARY KEY,
    root           TEXT NOT NULL,
    cursor_sha     TEXT,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- code_files: per-file content hash so re-indexing skips unchanged files (GAP B).
CREATE TABLE IF NOT EXISTS code_files (
    repo         TEXT NOT NULL,
    file_path    TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    PRIMARY KEY (repo, file_path)
);
