package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.example.codemem.config.ScopeResolver;
import com.example.codemem.domain.model.CodeEdge;
import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;

class JavaAstParserTest {

    private final JavaAstParser parser = new JavaAstParser(new ScopeResolver());

    private ParsedFile parse(String src) {
        return parser.parse("repo", "x/Foo.java", src.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void extractsClassMethodsAndCallEdge() {
        ParsedFile pf = parse("""
                package x;
                class Foo {
                    int a() { return b(); }
                    int b() { return 1; }
                }
                """);

        assertThat(pf.symbols()).extracting("symbolPath")
                .contains("<module>", "Foo", "Foo.a", "Foo.b");
        assertThat(pf.symbols()).filteredOn(s -> s.symbolPath().equals("Foo"))
                .allMatch(s -> s.kind().equals("class"));
        assertThat(pf.edges()).anyMatch(e -> e.kind().equals(CodeEdge.CALLS) && "b".equals(e.dstName()));
    }

    @Test
    void nestedTypesProduceDottedSymbolPath() {
        ParsedFile pf = parse("""
                package x;
                class Outer {
                    class Inner {
                        void m() {}
                    }
                }
                """);
        assertThat(pf.symbols()).extracting("symbolPath")
                .contains("Outer", "Outer.Inner", "Outer.Inner.m");
    }

    @Test
    void importBecomesImportEdge() {
        ParsedFile pf = parse("""
                package x;
                import java.util.List;
                class Foo {}
                """);
        assertThat(pf.edges())
                .anyMatch(e -> e.kind().equals(CodeEdge.IMPORTS) && "List".equals(e.dstName()));
    }

    @Test
    void lineNumbersReflectSourcePositions() {
        ParsedFile pf = parse("""
                package x;
                class Foo {
                    int a() {
                        return 1;
                    }
                }
                """);
        CodeSymbol a = pf.symbols().stream().filter(s -> s.symbolPath().equals("Foo.a"))
                .findFirst().orElseThrow();
        assertThat(a.startLine()).isEqualTo(3);
        assertThat(a.endLine()).isEqualTo(5);
    }

    @Test
    void classifiesInterfaceEnumAndRecordKinds() {
        assertThat(parse("interface I { void m(); }").symbols())
                .filteredOn(s -> s.symbolPath().equals("I")).allMatch(s -> s.kind().equals("interface"));
        assertThat(parse("enum E { A, B }").symbols())
                .filteredOn(s -> s.symbolPath().equals("E")).allMatch(s -> s.kind().equals("enum"));
        assertThat(parse("record R(int x) {}").symbols())
                .filteredOn(s -> s.symbolPath().equals("R")).allMatch(s -> s.kind().equals("record"));
    }

    @Test
    void emptySourceYieldsOnlyModuleSymbol() {
        assertThat(parse("").symbols()).extracting("symbolPath").containsExactly("<module>");
    }

    @Test
    void commentsOnlySourceYieldsOnlyModuleSymbol() {
        assertThat(parse("// just a comment\n/* block */\n").symbols())
                .extracting("symbolPath").containsExactly("<module>");
    }

    @Test
    void malformedSourceDegradesToModuleWithoutThrowing() {
        ParsedFile pf = parse("class Broken { int a( { oops");
        assertThat(pf.symbols()).extracting("symbolPath").contains("<module>");
    }

    @Test
    void unicodeIdentifiersAreExtracted() {
        ParsedFile pf = parse("""
                class Café {
                    int número() { return 1; }
                }
                """);
        assertThat(pf.symbols()).extracting("symbolPath").contains("Café", "Café.número");
    }

    @Test
    void anonymousClassDoesNotProduceEmptyNamedSymbol() {
        ParsedFile pf = parse("""
                class Foo {
                    Runnable r = new Runnable() { public void run() {} };
                }
                """);
        assertThat(pf.symbols()).noneMatch(s -> s.symbolPath().isEmpty() || s.symbolPath().endsWith("."));
    }
}
