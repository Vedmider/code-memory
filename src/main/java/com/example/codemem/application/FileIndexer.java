package com.example.codemem.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.example.codemem.domain.port.out.CodeParser;
import com.example.codemem.domain.port.out.SymbolStore;
import com.example.codemem.domain.port.out.SyncStore;

/**
 * Indexes one file content-hash-aware: parse + upsert only when the bytes changed, recording
 * the hash so repeat/full indexing skips unchanged files. Shared by full, incremental and
 * sync flows; callers run {@code resolvePendingEdges} once after a batch.
 */
@Component
public class FileIndexer {

    private final CodeParser parser;
    private final SymbolStore store;
    private final SyncStore sync;

    public FileIndexer(CodeParser parser, SymbolStore store, SyncStore sync) {
        this.parser = parser;
        this.store = store;
        this.sync = sync;
    }

    /** @return symbols indexed, or 0 if unsupported, unreadable, or unchanged (skipped). */
    public int index(String repo, String root, String relPath) {
        if (!parser.supports(relPath)) {
            return 0;
        }
        Path abs = Path.of(root).resolve(relPath);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(abs);
        }
        catch (IOException e) {
            return 0;
        }
        String hash = sha256(bytes);
        if (sync.fileHash(repo, relPath).filter(hash::equals).isPresent()) {
            return 0;  // unchanged
        }
        int n = store.upsertFile(repo, relPath, parser.parse(repo, relPath, bytes));
        sync.saveFileHash(repo, relPath, hash);
        return n;
    }

    public int remove(String repo, String relPath) {
        int deleted = store.deleteFile(repo, relPath);
        sync.clearFile(repo, relPath);
        return deleted;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
