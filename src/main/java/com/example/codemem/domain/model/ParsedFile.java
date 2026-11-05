package com.example.codemem.domain.model;

import java.util.List;

/** Output of parsing one file: its symbols and the edges originating in it. */
public record ParsedFile(List<CodeSymbol> symbols, List<CodeEdge> edges) {
}
