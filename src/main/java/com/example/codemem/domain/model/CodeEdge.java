package com.example.codemem.domain.model;

/** A directed relationship between symbols (or to an unresolved name) — the graph layer. */
public record CodeEdge(String srcId, String kind, String dstId, String dstName) {

    public static final String CALLS = "calls";
    public static final String IMPORTS = "imports";

    public static CodeEdge call(String srcId, String calleeName) {
        return new CodeEdge(srcId, CALLS, null, calleeName);
    }

    public static CodeEdge imports(String srcId, String moduleName) {
        return new CodeEdge(srcId, IMPORTS, null, moduleName);
    }
}
