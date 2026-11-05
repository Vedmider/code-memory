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
import com.example.codemem.domain.port.out.CodeParser;
import com.example.codemem.domain.port.out.SymbolStore;

/** Indexing: full walk + incremental per-file (Transformations #1 and #2). */
@Service
public class IndexingService implements IndexUseCase {

    private static final Set<String> SKIP = Set.of(".git", ".hg", "node_modules", "venv",
            ".venv", "__pycache__", "target", "build", "dist", ".idea");

    private final CodeParser parser;
    private final SymbolStore store;

    public IndexingService(CodeParser parser, SymbolStore store) {
        this.parser = parser;
        this.store = store;
    }

    @Override
    public Map<String, Object> indexPath(String repo, String root) {
        Path base = Path.of(root);
        int[] counts = {0, 0};  // files, symbols
        try (Stream<Path> paths = Files.walk(base)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> !skipped(base.relativize(p)))
                    .filter(p -> parser.supports(p.toString()))
                    .forEach(p -> {
                        String rel = base.relativize(p).toString();
                        try {
                            counts[1] += store.upsertFile(repo, rel, parser.parse(repo, rel, Files.readAllBytes(p)));
                            counts[0]++;
                        }
                        catch (IOException e) {
                            // unreadable file: skip
                        }
                    });
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int resolved = store.resolvePendingEdges(repo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("repo", repo);
        result.put("files", counts[0]);
        result.put("symbols", counts[1]);
        result.put("edgesResolved", resolved);
        return result;
    }

    @Override
    public int indexFile(String repo, String root, String filePath) {
        if (!parser.supports(filePath)) {
            return 0;
        }
        Path base = Path.of(root);
        Path abs = Path.of(filePath).isAbsolute() ? Path.of(filePath) : base.resolve(filePath);
        String rel = base.relativize(abs).toString();
        try {
            int n = store.upsertFile(repo, rel, parser.parse(repo, rel, Files.readAllBytes(abs)));
            store.resolvePendingEdges(repo);
            return n;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public int removeFile(String repo, String root, String filePath) {
        Path base = Path.of(root);
        Path abs = Path.of(filePath).isAbsolute() ? Path.of(filePath) : base.resolve(filePath);
        return store.deleteFile(repo, base.relativize(abs).toString());
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
