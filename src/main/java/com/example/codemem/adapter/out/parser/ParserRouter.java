package com.example.codemem.adapter.out.parser;

import java.util.List;
import java.util.Map;

import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.port.out.CodeParser;

/**
 * Routes each file to the parser for its detected language. The enabled set (which
 * languages map to a parser) is decided by {@link com.example.codemem.config.ParsingProperties}
 * at wiring time, so the same router serves java-only, single-language, and multi modes.
 */
public class ParserRouter implements CodeParser {

    private final LanguageDetector detector;
    private final Map<String, CodeParser> byLanguage;

    public ParserRouter(LanguageDetector detector, Map<String, CodeParser> byLanguage) {
        this.detector = detector;
        this.byLanguage = Map.copyOf(byLanguage);
    }

    @Override
    public boolean supports(String filePath) {
        return detector.detect(filePath).map(byLanguage::containsKey).orElse(false);
    }

    @Override
    public ParsedFile parse(String repo, String filePath, byte[] source) {
        CodeParser parser = detector.detect(filePath).map(byLanguage::get).orElse(null);
        if (parser == null) {
            return new ParsedFile(List.of(), List.of());
        }
        return parser.parse(repo, filePath, source);
    }
}
