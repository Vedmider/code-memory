package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.ParsedFile;

class HeuristicParsersTest {

    private final ScopeResolver scope = new ScopeResolver();

    @Test
    void pythonSymbolsImportsAndCalls() {
        String src = """
                import os

                def foo():
                    return bar()

                class C:
                    def m(self):
                        pass
                """;
        ParsedFile pf = new PythonHeuristicParser(scope).parse("r", "mod.py", src.getBytes(StandardCharsets.UTF_8));

        assertThat(pf.symbols()).extracting("symbolPath").contains("foo", "C", "m");
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals(CodeEdge.IMPORTS) && "os".equals(e.dstName()));
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals(CodeEdge.CALLS) && "bar".equals(e.dstName()));
    }

    @Test
    void goFuncsTypesAndImports() {
        String src = """
                package main
                import "fmt"
                func Foo() { Bar() }
                func Bar() {}
                type T struct {}
                """;
        ParsedFile pf = new GoHeuristicParser(scope).parse("r", "main.go", src.getBytes(StandardCharsets.UTF_8));

        assertThat(pf.symbols()).extracting("symbolPath").contains("Foo", "Bar", "T");
        assertThat(pf.symbols()).filteredOn(s -> s.symbolPath().equals("T"))
                .allMatch(s -> s.kind().equals("struct"));
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals(CodeEdge.IMPORTS) && "fmt".equals(e.dstName()));
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals(CodeEdge.CALLS) && "Bar".equals(e.dstName()));
    }
}
