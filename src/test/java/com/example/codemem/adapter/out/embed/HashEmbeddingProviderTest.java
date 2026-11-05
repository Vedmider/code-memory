package com.example.codemem.adapter.out.embed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashEmbeddingProviderTest {

    private final HashEmbeddingProvider emb = new HashEmbeddingProvider(32);

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
        double norm = 0;
        for (float v : emb.embed("return service.call()")) {
            norm += (double) v * v;
        }
        assertThat(Math.sqrt(norm)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    }
}
