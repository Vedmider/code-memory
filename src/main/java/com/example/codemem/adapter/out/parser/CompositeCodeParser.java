package com.example.codemem.adapter.out.parser;

import java.util.List;

import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.port.out.CodeParser;

/** Language switch: routes a file to the first sub-parser that supports it (Java/Go/Python). */
public class CompositeCodeParser implements CodeParser {

    private final List<CodeParser> parsers;

    public CompositeCodeParser(List<CodeParser> parsers) {
        this.parsers = parsers;
    }

    @Override
    public boolean supports(String filePath) {
        return parsers.stream().anyMatch(p -> p.supports(filePath));
    }

    @Override
    public ParsedFile parse(String repo, String filePath, byte[] source) {
        for (CodeParser p : parsers) {
            if (p.supports(filePath)) {
                return p.parse(repo, filePath, source);
            }
        }
        return new ParsedFile(List.of(), List.of());
    }
}
