package com.example.codemem.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.codemem.domain.model.AgentContext;
import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.in.RetrieveUseCase;
import com.example.codemem.domain.port.out.SymbolStore;

/** Retrieval: scoped hybrid search enriched with dependency-graph context (#3, #4). */
@Service
public class RetrievalService implements RetrieveUseCase {

    private final SymbolStore store;

    public RetrievalService(SymbolStore store) {
        this.store = store;
    }

    @Override
    public List<RetrievedSymbol> search(String query, String agent, int topK) {
        return store.search(query, agent, topK);
    }

    @Override
    public AgentContext context(String task, String agent, int topK) {
        List<RetrievedSymbol> results = store.search(task, agent, topK);
        Map<String, List<String>> callers = new LinkedHashMap<>();
        Map<String, List<String>> tests = new LinkedHashMap<>();
        for (RetrievedSymbol r : results) {
            callers.put(r.symbol().id(), store.callersOf(r.symbol().id()));
            tests.put(r.symbol().id(), store.testsFor(r.symbol().id()));
        }
        return new AgentContext(results, callers, tests);
    }
}
