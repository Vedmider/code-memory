package com.example.codemem.application;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.example.codemem.domain.port.in.IndexUseCase;
import com.example.codemem.domain.port.out.SymbolStore;
import com.example.codemem.domain.port.out.SyncStore;
import com.example.codemem.domain.port.out.VcsClient;

/** Indexing: full walk + incremental per-file (Transformations #1 and #2), hash-aware. */
@Service
public class IndexingService implements IndexUseCase {

    private static final Set<String> SKIP = Set.of(".git", ".hg", "node_modules", "venv",
            ".venv", "__pycache__", "target", "build", "dist", ".idea");

    private final FileIndexer indexer;
    private final SymbolStore store;
    private final SyncStore sync;
    private final VcsClient vcs;

    public IndexingService(FileIndexer indexer, SymbolStore store, SyncStore sync, VcsClient vcs) {
        this.indexer = indexer;
        this.store = store;
        this.sync = sync;
        this.vcs = vcs;
    }

    @Override
    public Map<String, Object> indexPath(String repo, String root) {
        Path base = Path.of(root);
        int[] counts = {0, 0};  // files, symbols
        try (Stream<Path> paths = Files.walk(base)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !skipped(base.relativize(p)))
                    .forEach(p -> {
                        String rel = base.relativize(p).toString();
                        int n = indexer.index(repo, root, rel);
                        if (n > 0) {
                            counts[0]++;
                            counts[1] += n;
                        }
                    });
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int resolved = store.resolvePendingEdges(repo);
        recordCursor(repo, root);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", repo);
        result.put("files", counts[0]);
        result.put("symbols", counts[1]);
        result.put("edgesResolved", resolved);
        return result;
    }

    @Override
    public int indexFile(String repo, String root, String filePath) {
        int n = indexer.index(repo, root, relativize(root, filePath));
        store.resolvePendingEdges(repo);
        return n;
    }

    @Override
    public int removeFile(String repo, String root, String filePath) {
        return indexer.remove(repo, relativize(root, filePath));
    }

    private void recordCursor(String repo, String root) {
        if (vcs.isRepo(root)) {
            vcs.head(root).ifPresent(sha -> sync.saveCursor(repo, root, sha));
        }
    }

    private static String relativize(String root, String filePath) {
        Path base = Path.of(root);
        Path abs = Path.of(filePath).isAbsolute() ? Path.of(filePath) : base.resolve(filePath);
        return base.relativize(abs).toString();
    }

    private static boolean skipped(Path relative) {
        for (Path seg : relative) {
            if (SKIP.contains(seg.toString())) {
                return true;
            }
        }
        return false;
    }
}
