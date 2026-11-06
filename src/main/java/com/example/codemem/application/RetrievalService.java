package com.example.codemem.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.codemem.config.SyncProperties;
import com.example.codemem.domain.model.AgentContext;
import com.example.codemem.domain.model.RetrievedSymbol;
import com.example.codemem.domain.port.in.RetrieveUseCase;
import com.example.codemem.domain.port.out.SymbolStore;

/** Retrieval: scoped hybrid search enriched with dependency-graph context (#3, #4). A
 *  self-healing git read-gate refreshes tracked repos before serving (when enabled). */
@Service
public class RetrievalService implements RetrieveUseCase {

    private final SymbolStore store;
    private final SyncService sync;
    private final SyncProperties props;

    public RetrievalService(SymbolStore store, SyncService sync, SyncProperties props) {
        this.store = store;
        this.sync = sync;
        this.props = props;
    }

    @Override
    public List<RetrievedSymbol> search(String query, String agent, int topK) {
        ensureFresh();
        return store.search(query, agent, topK);
    }

    @Override
    public AgentContext context(String task, String agent, int topK) {
        ensureFresh();
        List<RetrievedSymbol> results = store.search(task, agent, topK);
        Map<String, List<String>> callers = new LinkedHashMap<>();
        Map<String, List<String>> tests = new LinkedHashMap<>();
        for (RetrievedSymbol r : results) {
            callers.put(r.symbol().id(), store.callersOf(r.symbol().id()));
            tests.put(r.symbol().id(), store.testsFor(r.symbol().id()));
        }
        return new AgentContext(results, callers, tests);
    }

    private void ensureFresh() {
        if (props.isOnRead()) {
            sync.ensureAllFresh();
        }
    }
}
