package com.example.codemem.domain.port.out;

import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.model.RetrievedSymbol;

import java.util.List;

/** Persists symbols + edges and answers scoped hybrid + graph queries. */
public interface SymbolStore {

    int upsertFile(String repo, String filePath, ParsedFile parsed);

    int deleteFile(String repo, String filePath);

    /** Remove all symbols + edges for a repo (forces a full reindex). */
    void clearRepo(String repo);

    List<RetrievedSymbol> search(String repo, String query, String agent, int topK);

    List<String> callersOf(String symbolId);

    List<String> testsFor(String symbolId);

    int resolvePendingEdges(String repo);

    /** Recompute PageRank over the resolved graph and persist it (call after edge resolution). */
    void computeGraphRank(String repo);
}
