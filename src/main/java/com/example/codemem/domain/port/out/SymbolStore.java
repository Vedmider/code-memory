package com.example.codemem.domain.port.out;

import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.model.RetrievedSymbol;

import java.util.List;

/** Persists symbols + edges and answers scoped hybrid + graph queries. */
public interface SymbolStore {

    int upsertFile(String repo, String filePath, ParsedFile parsed);

    int deleteFile(String repo, String filePath);

    List<RetrievedSymbol> search(String query, String agent, int topK);

    List<String> callersOf(String symbolId);

    List<String> testsFor(String symbolId);

    int resolvePendingEdges(String repo);
}
