package com.example.codemem.adapter.out.store;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.codemem.domain.port.out.SyncStore;

/** pgvector/Postgres-backed sync bookkeeping (repo_state cursor + code_files hashes). */
public class PgSyncStore implements SyncStore {

    private final JdbcTemplate jdbc;

    public PgSyncStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> cursor(String repo) {
        try {
            return Optional.ofNullable(
                    jdbc.queryForObject("SELECT cursor_sha FROM repo_state WHERE repo=?", String.class, repo));
        }
        catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveCursor(String repo, String root, String sha) {
        jdbc.update("""
                INSERT INTO repo_state (repo, root, cursor_sha, last_synced_at)
                VALUES (?,?,?, now())
                ON CONFLICT (repo) DO UPDATE SET root=excluded.root,
                    cursor_sha=excluded.cursor_sha, last_synced_at=now()""",
                repo, root, sha);
    }

    @Override
    public List<RepoRef> repos() {
        return jdbc.query("SELECT repo, root FROM repo_state",
                (rs, i) -> new RepoRef(rs.getString("repo"), rs.getString("root")));
    }

    @Override
    public Optional<String> fileHash(String repo, String filePath) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT content_hash FROM code_files WHERE repo=? AND file_path=?",
                    String.class, repo, filePath));
        }
        catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveFileHash(String repo, String filePath, String hash) {
        jdbc.update("""
                INSERT INTO code_files (repo, file_path, content_hash) VALUES (?,?,?)
                ON CONFLICT (repo, file_path) DO UPDATE SET content_hash=excluded.content_hash""",
                repo, filePath, hash);
    }

    @Override
    public void clearFile(String repo, String filePath) {
        jdbc.update("DELETE FROM code_files WHERE repo=? AND file_path=?", repo, filePath);
    }
}
