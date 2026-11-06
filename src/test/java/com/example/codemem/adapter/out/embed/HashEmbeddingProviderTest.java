package com.example.codemem.adapter.out.embed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.assertj.core.data.Offset;

class HashEmbeddingProviderTest {

    private final HashEmbeddingProvider emb = new HashEmbeddingProvider(32);

    private static double norm(float[] v) {
        double n = 0;
        for (float x : v) {
            n += (double) x * x;
        }
        return Math.sqrt(n);
    }

    @Test
    void isDeterministic() {
        assertThat(emb.embed("public int add(int a, int b)"))
                .containsExactly(emb.embed("public int add(int a, int b)"));
    }

    @Test
    void hasConfiguredDimension() {
        assertThat(emb.embed("x y z")).hasSize(32);
        assertThat(emb.dimensions()).isEqualTo(32);
    }

    @Test
    void isUnitNormForNonEmptyText() {
        assertThat(norm(emb.embed("return service.call()"))).isCloseTo(1.0, Offset.offset(1e-6));
    }

    @Test
    void emptyTextYieldsZeroVectorWithoutNaN() {
        float[] v = emb.embed("");
        assertThat(v).hasSize(32);
        for (float x : v) {
            assertThat(Float.isNaN(x)).isFalse();
            assertThat(x).isEqualTo(0f);
        }
    }

    @Test
    void nullTextYieldsZeroVectorWithoutNaN() {
        float[] v = emb.embed(null);
        assertThat(v).hasSize(32);
        assertThat(norm(v)).isEqualTo(0.0);
    }

    @Test
    void blankWhitespaceTextYieldsZeroVector() {
        assertThat(norm(emb.embed("   \t  \n "))).isEqualTo(0.0);
    }
}
