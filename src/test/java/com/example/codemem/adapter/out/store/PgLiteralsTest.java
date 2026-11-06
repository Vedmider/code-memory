package com.example.codemem.adapter.out.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PgLiteralsTest {

    @Test
    void vectorRendersBracketedCommaSeparated() {
        assertThat(PgLiterals.vector(new float[] {1f, 2.5f, -0.25f})).isEqualTo("[1.0,2.5,-0.25]");
    }

    @Test
    void vectorOfEmptyArrayIsEmptyBrackets() {
        assertThat(PgLiterals.vector(new float[0])).isEqualTo("[]");
    }

    @Test
    void textArrayQuotesEachElement() {
        assertThat(PgLiterals.textArray(List.of("all", "qa"))).isEqualTo("{\"all\",\"qa\"}");
    }

    @Test
    void textArrayEscapesQuotesAndBackslashesToPreventBreakout() {
        assertThat(PgLiterals.textArray(List.of("a\"b", "c\\d")))
                .isEqualTo("{\"a\\\"b\",\"c\\\\d\"}");
    }

    @Test
    void textArrayNeutralisesArrayLiteralInjection() {
        String injected = PgLiterals.textArray(List.of("x\",\"admin"));
        assertThat(injected).isEqualTo("{\"x\\\",\\\"admin\"}");
        assertThat(injected).startsWith("{\"").endsWith("\"}");
        assertThat(injected.substring(2, injected.length() - 2)).doesNotContain("\",\"");
    }
}
