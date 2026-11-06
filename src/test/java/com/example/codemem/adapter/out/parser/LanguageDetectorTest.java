package com.example.codemem.adapter.out.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector();

    @Test
    void detectsByExtension() {
        assertThat(detector.detect("src/App.java")).contains("java");
        assertThat(detector.detect("pkg/mod.py")).contains("python");
        assertThat(detector.detect("cmd/main.go")).contains("go");
    }

    @Test
    void extensionMatchIsCaseInsensitive() {
        assertThat(detector.detect("src/App.JAVA")).contains("java");
        assertThat(detector.detect("pkg/MOD.Py")).contains("python");
    }

    @Test
    void resolvesOnLastExtensionForMultiDotNames() {
        assertThat(detector.detect("a.b.test.java")).contains("java");
        assertThat(detector.detect("service.integration.py")).contains("python");
    }

    @Test
    void unknownOrExtensionlessIsEmpty() {
        assertThat(detector.detect("README.md")).isEmpty();
        assertThat(detector.detect("Makefile")).isEmpty();
        assertThat(detector.detect("script.sh")).isEmpty();
    }
}
