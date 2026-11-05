package com.example.codemem.adapter.out.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.codemem.config.ScopeResolver;

public class GoHeuristicParser extends AbstractLineHeuristicParser {

    private static final Pattern FUNC =
            Pattern.compile("^func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)");
    private static final Pattern TYPE =
            Pattern.compile("^type\\s+([A-Za-z_]\\w*)\\s+(struct|interface)");
    private static final Pattern IMPORT_SINGLE = Pattern.compile("^\\s*import\\s+\"([^\"]+)\"");
    private static final Pattern IMPORT_BLOCK = Pattern.compile("^\\s*\"([^\"]+)\"\\s*$");
    private static final Set<String> KEYWORDS = Set.of(
            "func", "type", "struct", "interface", "if", "for", "range", "return", "var",
            "const", "package", "import", "switch", "case", "default", "go", "defer", "chan",
            "map", "select", "else", "make", "new", "len", "cap", "append");

    public GoHeuristicParser(ScopeResolver scope) {
        super(scope);
    }

    @Override
    public boolean supports(String filePath) {
        return filePath.endsWith(".go");
    }

    @Override
    protected String language() {
        return "go";
    }

    @Override
    protected List<SymbolHit> findSymbols(String[] lines) {
        List<SymbolHit> hits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher f = FUNC.matcher(lines[i]);
            if (f.find()) {
                hits.add(new SymbolHit(i, f.group(1), "func"));
                continue;
            }
            Matcher t = TYPE.matcher(lines[i]);
            if (t.find()) {
                hits.add(new SymbolHit(i, t.group(1), t.group(2)));
            }
        }
        return hits;
    }

    @Override
    protected String importName(String line) {
        Matcher s = IMPORT_SINGLE.matcher(line);
        if (s.find()) {
            return lastSegment(s.group(1));
        }
        Matcher b = IMPORT_BLOCK.matcher(line);
        return b.find() ? lastSegment(b.group(1)) : null;
    }

    private static String lastSegment(String path) {
        return path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
    }

    @Override
    protected Set<String> keywords() {
        return KEYWORDS;
    }
}
