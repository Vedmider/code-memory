package com.example.codemem.adapter.out.embed;

import com.example.codemem.domain.port.out.EmbeddingProvider;

/**
 * Deterministic bag-of-tokens hashing into a fixed-dim L2-normalised vector. No model
 * download, reproducible — good for dev/tests/offline. Swap for a code-tuned embedding
 * model in production by providing a different {@link EmbeddingProvider} bean.
 * {@code String.hashCode} is a specified algorithm, so the mapping is stable across JVMs.
 */
public class HashEmbeddingProvider implements EmbeddingProvider {

    private final int dim;

    public HashEmbeddingProvider(int dim) {
        this.dim = dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        if (text != null) {
            for (String token : text.split("\\s+")) {
                if (!token.isEmpty()) {
                    v[Math.floorMod(token.hashCode(), dim)] += 1f;
                }
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    @Override
    public int dimensions() {
        return dim;
    }
}
