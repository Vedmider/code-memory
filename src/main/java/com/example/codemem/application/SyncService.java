package com.example.codemem.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.codemem.domain.model.FileChange;
import com.example.codemem.domain.port.in.SyncUseCase;
import com.example.codemem.domain.port.out.CodeParser;
import com.example.codemem.domain.port.out.SymbolStore;
import com.example.codemem.domain.port.out.SyncStore;
import com.example.codemem.domain.port.out.VcsClient;

/**
 * Keeps the locator index fresh. The read-gate ({@link #ensureAllFresh}) runs before every
 * retrieval: for each tracked git repo it diffs cursor..HEAD ∪ working tree and re-indexes
 * only the delta, so correctness never depends on the agent remembering to sync. Explicit
 * {@link #sync} serves hooks/agents. A per-repo lock serializes concurrent syncs.
 */
@Service
public class SyncService implements SyncUseCase {

    private static final Logger log = LoggerFactory.getLogger(SyncService.class);

    private final VcsClient vcs;
    private final SyncStore sync;
    private final FileIndexer indexer;
    private final SymbolStore store;
    private final CodeParser parser;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public SyncService(VcsClient vcs, SyncStore sync, FileIndexer indexer, SymbolStore store,
                       CodeParser parser) {
        this.vcs = vcs;
        this.sync = sync;
        this.indexer = indexer;
        this.store = store;
        this.parser = parser;
    }

    /** Read-gate: best-effort refresh of every tracked repo; never throws into retrieval. */
    public void ensureAllFresh() {
        for (SyncStore.RepoRef ref : sync.repos()) {
            try {
                gitSync(ref.repo(), ref.root());
            }
            catch (Exception e) {
                log.warn("read-gate sync failed for {} ({}): {}", ref.repo(), ref.root(), e.getMessage());
            }
        }
    }

    @Override
    public Map<String, Object> sync(String repo, String root, List<String> paths) {
        ReentrantLock lock = locks.computeIfAbsent(repo, r -> new ReentrantLock());
        lock.lock();
        try {
            return (paths == null || paths.isEmpty())
                    ? gitSync(repo, root)
                    : pathSync(repo, root, paths);
        }
        finally {
            lock.unlock();
        }
    }

    private Map<String, Object> gitSync(String repo, String root) {
        ReentrantLock lock = locks.computeIfAbsent(repo, r -> new ReentrantLock());
        lock.lock();
        try {
            if (!vcs.isRepo(root)) {
                return counts(0, 0);
            }
            String head = vcs.head(root).orElse(null);
            String cursor = sync.cursor(repo).orElse(null);
            List<FileChange> changes = vcs.changesSince(root, cursor).stream()
                    .filter(c -> parser.supports(c.path())).toList();
            Map<String, Object> result = apply(repo, root, changes);
            if (head != null && (!head.equals(cursor) || !changes.isEmpty())) {
                sync.saveCursor(repo, root, head);
            }
            return result;
        }
        finally {
            lock.unlock();
        }
    }

    private Map<String, Object> pathSync(String repo, String root, List<String> paths) {
        List<FileChange> changes = paths.stream().filter(parser::supports)
                .map(p -> Files.exists(Path.of(root).resolve(p))
                        ? FileChange.upsert(p) : FileChange.delete(p))
                .toList();
        Map<String, Object> result = apply(repo, root, changes);
        if (vcs.isRepo(root)) {
            vcs.head(root).ifPresent(sha -> sync.saveCursor(repo, root, sha));
        }
        return result;
    }

    private Map<String, Object> apply(String repo, String root, List<FileChange> changes) {
        int indexed = 0;
        int removed = 0;
        for (FileChange c : changes) {
            if (c.type() == FileChange.Type.DELETE) {
                removed += indexer.remove(repo, c.path()) > 0 ? 1 : 0;
            }
            else if (indexer.index(repo, root, c.path()) > 0) {
                indexed++;
            }
        }
        if (!changes.isEmpty()) {
            store.resolvePendingEdges(repo);
        }
        return counts(indexed, removed);
    }

    private static Map<String, Object> counts(int indexed, int removed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexed", indexed);
        m.put("removed", removed);
        return m;
    }
}
