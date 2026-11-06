package com.example.codemem.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.example.codemem.adapter.out.parser.LanguageDetector;
import com.example.codemem.config.ParsingProperties.Mode;
import com.example.codemem.domain.port.out.CodeParser;

class AdapterConfigTest {

    private final AdapterConfig config = new AdapterConfig();
    private final LanguageDetector detector = new LanguageDetector();
    private final ScopeResolver scope = new ScopeResolver();

    private CodeParser parser(Mode mode, String language) {
        ParsingProperties p = new ParsingProperties();
        p.setMode(mode);
        p.setLanguage(language);
        return config.codeParser(p, detector, scope);
    }

    @Test
    void javaModeSupportsOnlyJava() {
        CodeParser p = parser(Mode.java, "");
        assertThat(p.supports("A.java")).isTrue();
        assertThat(p.supports("a.py")).isFalse();
        assertThat(p.supports("a.go")).isFalse();
    }

    @Test
    void multiModeSupportsAllKnownLanguages() {
        CodeParser p = parser(Mode.multi, "");
        assertThat(p.supports("A.java")).isTrue();
        assertThat(p.supports("a.py")).isTrue();
        assertThat(p.supports("a.go")).isTrue();
        assertThat(p.supports("notes.md")).isFalse();
    }

    @Test
    void singleModeSupportsOnlyTheChosenLanguage() {
        CodeParser python = parser(Mode.single, "python");
        assertThat(python.supports("a.py")).isTrue();
        assertThat(python.supports("A.java")).isFalse();

        CodeParser java = parser(Mode.single, "java");
        assertThat(java.supports("A.java")).isTrue();
        assertThat(java.supports("a.py")).isFalse();
    }

    @Test
    void singleModeWithUnsupportedLanguageFailsFast() {
        assertThatThrownBy(() -> parser(Mode.single, "cobol"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cobol");
    }
}
