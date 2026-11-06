package com.example.codemem.adapter.out.parser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.port.out.CodeParser;

/**
 * Parses non-Java languages by delegating to the external chunking service over the
 * standard chunk contract. Keeps the Java app pure (no native parsers): the service uses
 * tree-sitter/LSP per language. If the service is unavailable, degrades to a single
 * module-level symbol so indexing never fails.
 */
public class RemoteChunkingParser implements CodeParser {

    private static final Logger log = LoggerFactory.getLogger(RemoteChunkingParser.class);

    private final RestClient http;
    private final LanguageDetector detector;
    private final ScopeResolver scope;
    private final Set<String> languages;

    public RemoteChunkingParser(RestClient http, LanguageDetector detector, ScopeResolver scope,
                                Set<String> languages) {
        this.http = http;
        this.detector = detector;
        this.scope = scope;
        this.languages = Set.copyOf(languages);
    }

    @Override
    public boolean supports(String filePath) {
        return detector.detect(filePath).filter(languages::contains).isPresent();
    }

    @Override
    public ParsedFile parse(String repo, String filePath, byte[] source) {
        String language = detector.detect(filePath).orElse("unknown");
        List<String> acl = scope.resolve(filePath);
        String code = new String(source, StandardCharsets.UTF_8);
        List<CodeSymbol> symbols = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        symbols.add(new CodeSymbol(repo + ":" + filePath + ":<module>", repo, filePath, language,
                "module", "<module>", baseName(filePath), 1, Math.max(1, (int) code.lines().count()),
                "", Map.of(), acl));
        try {
            ChunkResponse resp = http.post().uri("/chunk")
                    .body(new ChunkRequest(repo, filePath, language, code))
                    .retrieve().body(ChunkResponse.class);
            if (resp != null && resp.symbols() != null) {
                for (ChunkSymbol s : resp.symbols()) {
                    symbols.add(new CodeSymbol(repo + ":" + filePath + ":" + s.symbolPath(), repo,
                            filePath, language, s.kind(), s.symbolPath(), s.signature(),
                            s.startLine(), s.endLine(), s.content(), Map.of(), acl));
                }
            }
            if (resp != null && resp.edges() != null) {
                for (ChunkEdge e : resp.edges()) {
                    edges.add(new CodeEdge(repo + ":" + filePath + ":" + e.src(), e.kind(), null, e.dstName()));
                }
            }
        }
        catch (Exception ex) {
            log.warn("chunking service failed for {} ({}): {}", filePath, language, ex.getMessage());
        }
        return new ParsedFile(symbols, edges);
    }

    private static String baseName(String path) {
        String p = path.replace('\\', '/');
        return p.contains("/") ? p.substring(p.lastIndexOf('/') + 1) : p;
    }

    // --- standard chunk contract (wire shape shared with the chunking service) ---
    public record ChunkRequest(String repo, String path, String language, String content) {
    }

    public record ChunkResponse(List<ChunkSymbol> symbols, List<ChunkEdge> edges) {
    }

    public record ChunkSymbol(String symbolPath, String kind, String signature,
                              int startLine, int endLine, String content) {
    }

    public record ChunkEdge(String src, String kind, String dstName) {
    }
}
