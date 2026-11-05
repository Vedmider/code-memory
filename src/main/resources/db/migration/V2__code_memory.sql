-- AST-level symbols (not arbitrary chunks). English full-text config (code is English).
CREATE TABLE IF NOT EXISTS code_symbols (
    id             TEXT PRIMARY KEY,
    repo           TEXT NOT NULL,
    file_path      TEXT NOT NULL,
    language       TEXT NOT NULL,
    kind           TEXT NOT NULL,
    symbol_path    TEXT NOT NULL,
    signature      TEXT NOT NULL DEFAULT '',
    start_line     INT NOT NULL,
    end_line       INT NOT NULL,
    content        TEXT NOT NULL,
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
    allowed_agents TEXT[] NOT NULL DEFAULT ARRAY['all'],
    embedding      vector(${embeddingDimensions}),
    content_tsv    tsvector GENERATED ALWAYS AS
                       (to_tsvector('english', symbol_path || ' ' || content)) STORED
);
CREATE INDEX IF NOT EXISTS code_symbols_tsv_idx    ON code_symbols USING GIN  (content_tsv);
CREATE INDEX IF NOT EXISTS code_symbols_agents_idx ON code_symbols USING GIN  (allowed_agents);
CREATE INDEX IF NOT EXISTS code_symbols_file_idx   ON code_symbols (repo, file_path);
CREATE INDEX IF NOT EXISTS code_symbols_emb_idx    ON code_symbols USING hnsw (embedding vector_cosine_ops);

-- Dependency graph (who calls what / imports).
CREATE TABLE IF NOT EXISTS code_edges (
    src_id   TEXT NOT NULL,
    kind     TEXT NOT NULL,          -- calls | imports
    dst_id   TEXT,
    dst_name TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS code_edges_uq ON code_edges
    (src_id, kind, dst_id, dst_name) NULLS NOT DISTINCT;
CREATE INDEX IF NOT EXISTS code_edges_dst_idx     ON code_edges (dst_id);
CREATE INDEX IF NOT EXISTS code_edges_dstname_idx ON code_edges (dst_name);
