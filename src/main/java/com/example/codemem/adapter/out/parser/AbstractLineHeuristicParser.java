package com.example.codemem.adapter.out.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.port.out.CodeParser;

/**
 * Lightweight, dependency-free line/regex parser for languages without an in-process AST
 * here (Python, Go). Extracts top-level symbols, imports, and call references. Heuristic
 * (spans approximated between symbol declarations) — swap in a tree-sitter/LSP adapter for
 * precision. Good enough to give the index multi-language coverage.
 */
public abstract class AbstractLineHeuristicParser implements CodeParser {

    private static final Pattern CALL = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    protected final ScopeResolver scope;

    protected AbstractLineHeuristicParser(ScopeResolver scope) {
        this.scope = scope;
    }

    protected abstract String language();

    protected abstract List<SymbolHit> findSymbols(String[] lines);

    protected abstract String importName(String line);

    protected abstract Set<String> keywords();

    protected record SymbolHit(int lineIndex, String name, String kind) {
    }

    @Override
    public ParsedFile parse(String repo, String filePath, byte[] source) {
        String[] lines = new String(source, StandardCharsets.UTF_8).split("\n", -1);
        List<CodeSymbol> symbols = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        List<String> acl = scope.resolve(filePath);
        String moduleId = repo + ":" + filePath + ":<module>";
        symbols.add(new CodeSymbol(moduleId, repo, filePath, language(), "module", "<module>",
                baseName(filePath), 1, Math.max(1, lines.length), "", Map.of(), acl));

        for (String line : lines) {
            String imp = importName(line);
            if (imp != null && !imp.isBlank()) {
                edges.add(CodeEdge.imports(moduleId, imp));
            }
        }

        List<SymbolHit> hits = findSymbols(lines);
        for (int i = 0; i < hits.size(); i++) {
            SymbolHit h = hits.get(i);
            int start = h.lineIndex();
            int end = (i + 1 < hits.size()) ? hits.get(i + 1).lineIndex() - 1 : lines.length - 1;
            if (end < start) {
                end = start;
            }
            StringBuilder sb = new StringBuilder();
            for (int l = start; l <= end && l < lines.length; l++) {
                sb.append(lines[l]).append('\n');
            }
            String content = sb.toString();
            String id = repo + ":" + filePath + ":" + h.name();
            String signature = lines[start].trim();
            if (signature.length() > 500) {
                signature = signature.substring(0, 500);
            }
            symbols.add(new CodeSymbol(id, repo, filePath, language(), h.kind(), h.name(),
                    signature, start + 1, end + 1, content, Map.of(), acl));
            for (String callee : extractCalls(content, h.name())) {
                edges.add(CodeEdge.call(id, callee));
            }
        }
        return new ParsedFile(symbols, edges);
    }

    private Set<String> extractCalls(String content, String selfName) {
        Set<String> calls = new LinkedHashSet<>();
        Matcher m = CALL.matcher(content);
        Set<String> kw = keywords();
        while (m.find()) {
            String name = m.group(1);
            if (!kw.contains(name) && !name.equals(selfName)) {
                calls.add(name);
            }
        }
        return calls;
    }

    protected static String baseName(String path) {
        String p = path.replace('\\', '/');
        return p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
    }
}
