package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.codemem.domain.model.CodeSymbol;
import com.example.codemem.domain.model.ParsedFile;
import com.example.codemem.domain.port.out.CodeParser;

class ParserRouterTest {

    private static CodeParser tagging(String tag) {
        return new CodeParser() {
            @Override
            public boolean supports(String filePath) {
                return true;
            }

            @Override
            public ParsedFile parse(String repo, String filePath, byte[] source) {
                return new ParsedFile(List.of(new CodeSymbol("id", repo, filePath, "x", "module",
                        tag, "", 1, 1, "", Map.of(), List.of("all"))), List.of());
            }
        };
    }

    @Test
    void routesByDetectedLanguageAndRespectsEnabledSet() {
        ParserRouter router = new ParserRouter(new LanguageDetector(),
                Map.of("java", tagging("JAVA"), "python", tagging("PY")));

        assertThat(router.supports("A.java")).isTrue();
        assertThat(router.supports("a.py")).isTrue();
        assertThat(router.supports("a.go")).isFalse();   // go not enabled in this map

        assertThat(router.parse("r", "A.java", new byte[0]).symbols().get(0).symbolPath()).isEqualTo("JAVA");
        assertThat(router.parse("r", "a.py", new byte[0]).symbols().get(0).symbolPath()).isEqualTo("PY");
    }

    @Test
    void unknownLanguageYieldsEmpty() {
        ParserRouter router = new ParserRouter(new LanguageDetector(), Map.of("java", tagging("JAVA")));
        assertThat(router.parse("r", "notes.md", new byte[0]).symbols()).isEmpty();
    }
}
