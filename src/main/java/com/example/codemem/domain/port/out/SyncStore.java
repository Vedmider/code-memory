package com.example.codemem.domain.port.out;

import java.util.List;
import java.util.Optional;

/** Persists sync bookkeeping: per-repo git cursor and per-file content hashes. */
public interface SyncStore {

    /** (repo, root) pair for a tracked repository. */
    record RepoRef(String repo, String root) {
    }

    Optional<String> cursor(String repo);

    void saveCursor(String repo, String root, String sha);

    List<RepoRef> repos();

    Optional<String> fileHash(String repo, String filePath);

    void saveFileHash(String repo, String filePath, String hash);

    void clearFile(String repo, String filePath);

    /** Forget all file hashes + cursor for a repo (forces a full reindex). */
    void clearRepo(String repo);
}
