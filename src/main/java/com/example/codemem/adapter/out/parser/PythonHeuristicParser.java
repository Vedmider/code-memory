package com.example.codemem.adapter.out.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.codemem.config.ScopeResolver;

public class PythonHeuristicParser extends AbstractLineHeuristicParser {

    private static final Pattern SYMBOL = Pattern.compile("^(\\s*)(def|class)\\s+([A-Za-z_]\\w*)");
    private static final Pattern IMPORT = Pattern.compile("^\\s*(?:import|from)\\s+([A-Za-z_][\\w.]*)");
    private static final Set<String> KEYWORDS = Set.of(
            "def", "class", "return", "if", "elif", "else", "for", "while", "with", "try",
            "except", "finally", "import", "from", "as", "print", "self", "lambda", "yield",
            "assert", "raise", "await", "async", "and", "or", "not", "in", "is");

    public PythonHeuristicParser(ScopeResolver scope) {
        super(scope);
    }

    @Override
    public boolean supports(String filePath) {
        return filePath.endsWith(".py");
    }

    @Override
    protected String language() {
        return "python";
    }

    @Override
    protected List<SymbolHit> findSymbols(String[] lines) {
        List<SymbolHit> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = SYMBOL.matcher(lines[i]);
            if (m.find()) {
                String kind = m.group(2).equals("class") ? "class" : "function";
                hits.add(new SymbolHit(i, m.group(3), kind));
            }
        }
        return hits;
    }

    @Override
    protected String importName(String line) {
        Matcher m = IMPORT.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    @Override
    protected Set<String> keywords() {
        return KEYWORDS;
    }
}
