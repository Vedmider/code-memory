package com.example.codemem.domain.model;

import java.util.List;
import java.util.Map;

/** What an agent gets for a task: ranked symbols enriched with graph relations. */
public record AgentContext(List<RetrievedSymbol> results,
                           Map<String, List<String>> callers,
                           Map<String, List<String>> tests) {
}
