package com.example.codemem.domain.port.in;

import com.example.codemem.domain.model.AgentContext;
import com.example.codemem.domain.model.RetrievedSymbol;

import java.util.List;

/** Inbound port: scoped hybrid search and task-context assembly for an agent. */
public interface RetrieveUseCase {

    List<RetrievedSymbol> search(String query, String agent, int topK);

    AgentContext context(String task, String agent, int topK);
}
