-- code-memory baseline schema (single fresh migration — pre-release, no step history kept).
-- Requires the pgvector/pgvector image. ${embeddingDimensions} is a Flyway placeholder.
CREATE EXTENSION IF NOT EXISTS vector;

-- AST-level symbols (not arbitrary chunks). English full-text (code is English); PageRank
-- centrality in `rank`; dense vector in `embedding`; per-agent visibility in `allowed_agents`.
CREATE TABLE code_symbols (
    id             TEXT PRIMARY KEY,
    repo           TEXT NOT NULL,
    file_path      TEXT NOT NULL,
    language       TEXT NOT NULL,
    kind           TEXT NOT NULL,
    symbol_path    TEXT NOT NULL,
    signature      TEXT NOT NULL DEFAULT '',
    start_line     INT  NOT NULL,
    end_line       INT  NOT NULL,
    content        TEXT NOT NULL,
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
    allowed_agents TEXT[] NOT NULL DEFAULT ARRAY['all'],
    rank           DOUBLE PRECISION NOT NULL DEFAULT 0,
    embedding      vector(${embeddingDimensions}),
    content_tsv    tsvector GENERATED ALWAYS AS
                       (to_tsvector('english', symbol_path || ' ' || content)) STORED
);
CREATE INDEX code_symbols_tsv_idx    ON code_symbols USING GIN  (content_tsv);
CREATE INDEX code_symbols_agents_idx ON code_symbols USING GIN  (allowed_agents);
CREATE INDEX code_symbols_file_idx   ON code_symbols (repo, file_path);
CREATE INDEX code_symbols_rank_idx   ON code_symbols (repo, rank DESC);
CREATE INDEX code_symbols_emb_idx    ON code_symbols USING hnsw (embedding vector_cosine_ops);

-- Dependency graph: who calls / imports what (dst_id resolved lazily, dst_name is the raw target).
CREATE TABLE code_edges (
    src_id   TEXT NOT NULL,
    kind     TEXT NOT NULL,          -- calls | imports
    dst_id   TEXT,
    dst_name TEXT
);
CREATE UNIQUE INDEX code_edges_uq ON code_edges (src_id, kind, dst_id, dst_name) NULLS NOT DISTINCT;
CREATE INDEX code_edges_dst_idx     ON code_edges (dst_id);
CREATE INDEX code_edges_dstname_idx ON code_edges (dst_name);

-- Git-sync bookkeeping: per-repo cursor (read-gate) + per-file content hash (skip-unchanged).
CREATE TABLE repo_state (
    repo           TEXT PRIMARY KEY,
    root           TEXT NOT NULL,
    cursor_sha     TEXT,
    last_synced_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE code_files (
    repo         TEXT NOT NULL,
    file_path    TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    PRIMARY KEY (repo, file_path)
);
