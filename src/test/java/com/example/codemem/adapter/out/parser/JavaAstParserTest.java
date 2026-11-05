package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.ParsedFile;

class JavaAstParserTest {

    private final JavaAstParser parser = new JavaAstParser(new ScopeResolver());

    @Test
    void extractsClassMethodsAndCallEdge() {
        String src = """
                package x;
                class Foo {
                    int a() { return b(); }
                    int b() { return 1; }
                }
                """;
        ParsedFile pf = parser.parse("repo", "x/Foo.java", src.getBytes(StandardCharsets.UTF_8));

        assertThat(pf.symbols()).extracting("symbolPath")
                .contains("<module>", "Foo", "Foo.a", "Foo.b");
        assertThat(pf.symbols()).filteredOn(s -> s.symbolPath().equals("Foo"))
                .allMatch(s -> s.kind().equals("class"));
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals(CodeEdge.CALLS) && "b".equals(e.dstName()));
    }
}
