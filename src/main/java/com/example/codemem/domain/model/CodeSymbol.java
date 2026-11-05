package com.example.codemem.domain.model;

import java.util.List;
import java.util.Map;

/** An AST-level unit (function / method / class / interface), not an arbitrary chunk. */
public record CodeSymbol(String id, String repo, String filePath, String language, String kind,
                         String symbolPath, String signature, int startLine, int endLine,
                         String content, Map<String, Object> metadata, List<String> allowedAgents) {

    public static final String ALL = "all";

    public CodeSymbol {
        metadata = metadata == null ? Map.of() : metadata;
        allowedAgents = (allowedAgents == null || allowedAgents.isEmpty()) ? List.of(ALL) : allowedAgents;
    }
}
